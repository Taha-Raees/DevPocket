#!/usr/bin/env node

import { mkdirSync, cpSync, existsSync, rmSync, writeFileSync, readdirSync, statSync, unlinkSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const productRoot = path.resolve(__dirname, '..');
const repoRoot = path.resolve(productRoot, '..', '..');

// Allow arch override via env: ANDROID_ARCH=x86_64 or ANDROID_ARCH=aarch64
const arch = process.env.ANDROID_ARCH || 'x86_64';
console.log(`Preparing runtime assets for arch: ${arch}`);

const runtimeOut = path.resolve(repoRoot, 'android-app', 'android', 'app', 'src', 'main', 'assets', 'runtime');
const frontendOut = path.resolve(runtimeOut, 'theia-android-lite', 'lib', 'frontend');
const backendOut = path.resolve(runtimeOut, 'theia-android-lite', 'lib', 'backend');
const webviewOut = path.resolve(runtimeOut, 'theia-android-lite', 'lib', 'webview');
const configOut = path.resolve(runtimeOut, 'config');
const binOut = path.resolve(runtimeOut, 'bin');
const libOut = path.resolve(runtimeOut, 'lib');

const frontendSrc = path.resolve(productRoot, 'lib', 'frontend');
const backendSrc = path.resolve(productRoot, 'lib', 'backend');
const webviewSrc = path.resolve(productRoot, 'lib', 'webview');
const ovsxRouterSrc = path.resolve(productRoot, 'configs', 'ovsx-router-config.json');

// Termux-compatible binary paths
const termuxBinDir = path.resolve(repoRoot, 'runtime', 'bin', `android-${arch}`);
const termuxLibDir = path.resolve(repoRoot, 'runtime', 'lib', `android-${arch}`);

function cleanIncompatibleAssets(rootDir) {
    if (!existsSync(rootDir)) return;
    const entries = readdirSync(rootDir, { withFileTypes: true });
    for (const entry of entries) {
        const entryPath = path.resolve(rootDir, entry.name);
        if (entry.isDirectory()) {
            cleanIncompatibleAssets(entryPath);
            continue;
        }
        if (entry.isFile()) {
            // Delete compressed files since Android asset merger rejects duplicates
            if (entry.name.endsWith('.gz')) {
                unlinkSync(entryPath);
            }
            // Delete native node Addons (glibc compiled, incompatible with Android Bionic)
            if (entry.name.endsWith('.node')) {
                console.log(`Removing incompatible native addon: ${entryPath}`);
                unlinkSync(entryPath);
            }
            // Temporarily delete 'rg' if it's the linux version, we'll supply Termux one later
            if (entry.name === 'rg') {
                console.log(`Removing incompatible ripgrep binary: ${entryPath}`);
                unlinkSync(entryPath);
            }
        }
    }
}

if (!existsSync(frontendSrc)) {
    throw new Error(`Missing frontend build output at ${frontendSrc}. Run npm run build:production in products/theia-android-lite first.`);
}

if (!existsSync(backendSrc)) {
    throw new Error(`Missing backend build output at ${backendSrc}. Run npm run build:production in products/theia-android-lite first.`);
}

rmSync(runtimeOut, { recursive: true, force: true });
mkdirSync(frontendOut, { recursive: true });
mkdirSync(backendOut, { recursive: true });
mkdirSync(webviewOut, { recursive: true });
mkdirSync(configOut, { recursive: true });
mkdirSync(binOut, { recursive: true });
mkdirSync(libOut, { recursive: true });

cpSync(frontendSrc, frontendOut, { recursive: true });
cpSync(backendSrc, backendOut, { recursive: true });
if (existsSync(webviewSrc)) {
    cpSync(webviewSrc, webviewOut, { recursive: true });
}

// Copy index.html — webpack puts JS bundles into lib/frontend/ but NOT index.html.
// It's generated separately in src-gen/frontend/ by Theia's frontend-generator.
// Express.static needs it to serve GET / requests.
const indexHtmlSrc = path.resolve(productRoot, 'src-gen', 'frontend', 'index.html');
if (existsSync(indexHtmlSrc)) {
    cpSync(indexHtmlSrc, path.resolve(frontendOut, 'index.html'));
    console.log('Copied index.html to frontend output');
} else {
    console.warn('WARNING: index.html not found at ' + indexHtmlSrc);
}

// Android's asset merger treats some pre-compressed and uncompressed files as
// duplicate paths. Keep the uncompressed files for predictable APK packaging.
// Also strip out `.node` binary addons compiled for glibc!
cleanIncompatibleAssets(runtimeOut);

// Copy Termux 'rg' binary to replace the incompatible glibc one we just deleted
const termuxRgPath = path.resolve(termuxBinDir, 'rg');
const backendNativeDir = path.resolve(backendOut, 'native');
if (existsSync(termuxRgPath)) {
    mkdirSync(backendNativeDir, { recursive: true });
    cpSync(termuxRgPath, path.resolve(backendNativeDir, 'rg'));
    console.log(`Copied Termux rg to ${backendNativeDir}/rg`);
}

// Copy Termux pty.node to replace the incompatible glibc one we just deleted
const termuxPtyPath = path.resolve(termuxLibDir, 'pty.node');
if (existsSync(termuxPtyPath)) {
    mkdirSync(backendNativeDir, { recursive: true });
    cpSync(termuxPtyPath, path.resolve(backendNativeDir, 'pty.node'));
    console.log(`Copied Termux pty.node to ${backendNativeDir}/pty.node`);
} else {
    console.warn(`WARNING: Termux pty.node not found at ${termuxPtyPath}`);
}

// Copy package.json so Theia backend can find its configuration
// (applicationName, preferences, frontend.config, etc.)
const productPkgJson = path.resolve(productRoot, 'package.json');
const runtimeProjectRoot = path.resolve(runtimeOut, 'theia-android-lite');
cpSync(productPkgJson, path.resolve(runtimeProjectRoot, 'package.json'));

cpSync(ovsxRouterSrc, path.resolve(configOut, 'ovsx-router-config.json'));

// Copy Termux node binary and npm
if (existsSync(termuxBinDir)) {
    console.log(`Copying Termux binaries from ${termuxBinDir}`);
    cpSync(termuxBinDir, binOut, { recursive: true });

    // Generate node-wrapper to circumvent Android 10+ stripping LD_LIBRARY_PATH
    // for binaries executed from the data directory.
    const wrapperContent = `#!/system/bin/sh
export LD_LIBRARY_PATH=$(dirname $0)/../lib:$LD_LIBRARY_PATH
exec $(dirname $0)/node "$@"
`;
    writeFileSync(path.resolve(binOut, 'node-wrapper'), wrapperContent, { mode: 0o755 });
    console.log(`Generated bin/node-wrapper script`);
} else {
    console.warn(`WARNING: Termux binary dir not found at ${termuxBinDir}`);
    console.warn('         Run: ./scripts/download-node-android.sh ' + arch);
}

// Copy Termux shared libraries
if (existsSync(termuxLibDir)) {
    console.log(`Copying Termux shared libs from ${termuxLibDir}`);
    cpSync(termuxLibDir, libOut, { recursive: true });
} else {
    console.warn(`WARNING: Termux lib dir not found at ${termuxLibDir}`);
}

console.log(`Runtime assets prepared at ${runtimeOut}`);
