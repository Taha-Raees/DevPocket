#!/usr/bin/env node

import { mkdirSync, cpSync, existsSync, rmSync, writeFileSync, readdirSync, statSync, unlinkSync, readlinkSync, lstatSync, copyFileSync, chmodSync, createWriteStream } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import https from 'node:https';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const productRoot = path.resolve(__dirname, '..');
const repoRoot = path.resolve(productRoot, '..', '..');

// Allow arch override via env: ANDROID_ARCH=x86_64 or ANDROID_ARCH=aarch64
// Default to aarch64 since most tablets shipped since ~2019 are 64-bit ARM.
const arch = process.env.ANDROID_ARCH || 'aarch64';
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

async function downloadExecutable(url, destination) {
    await new Promise((resolve, reject) => {
        const file = createWriteStream(destination, { mode: 0o755 });
        https.get(url, response => {
            if (response.statusCode !== 200) {
                reject(new Error(`Failed downloading ${url}: HTTP ${response.statusCode}`));
                response.resume();
                return;
            }

            response.pipe(file);
            file.on('finish', () => {
                file.close(resolve);
            });
        }).on('error', error => {
            reject(error);
        });
    });

    chmodSync(destination, 0o755);
}

async function ensureProotBinary() {
    const packagedProotPath = path.resolve(termuxBinDir, 'proot');
    const envProotPath = process.env.ANDROID_PROOT_BIN ? path.resolve(process.env.ANDROID_PROOT_BIN) : '';
    const defaultProotUrls = {
        aarch64: 'https://skirsten.github.io/proot-portable-android-binaries/aarch64/proot',
        x86_64: 'https://skirsten.github.io/proot-portable-android-binaries/x86_64/proot'
    };
    const envProotUrl = process.env.ANDROID_PROOT_URL || defaultProotUrls[arch];
    const outputPath = path.resolve(binOut, 'proot');

    if (existsSync(packagedProotPath)) {
        copyFileSync(packagedProotPath, outputPath);
        chmodSync(outputPath, 0o755);
        console.log(`Copied packaged proot from ${packagedProotPath}`);
        return;
    }

    if (envProotPath && existsSync(envProotPath)) {
        copyFileSync(envProotPath, outputPath);
        chmodSync(outputPath, 0o755);
        console.log(`Copied proot from ANDROID_PROOT_BIN=${envProotPath}`);
        return;
    }

    if (envProotUrl) {
        console.log(`Downloading proot from ANDROID_PROOT_URL=${envProotUrl}`);
        await downloadExecutable(envProotUrl, outputPath);
        return;
    }

    throw new Error(
        `Missing Android proot binary for arch ${arch}. ` +
        `Provide runtime/bin/android-${arch}/proot, set ANDROID_PROOT_BIN, or set ANDROID_PROOT_URL.`
    );
}

function resolveSymlinks(dir) {
    const entries = readdirSync(dir, { withFileTypes: true });
    for (const entry of entries) {
        const entryPath = path.resolve(dir, entry.name);
        if (entry.isDirectory()) {
            resolveSymlinks(entryPath);
            continue;
        }
        try {
            const stats = lstatSync(entryPath);
            if (stats.isSymbolicLink()) {
                const target = readlinkSync(entryPath);
                const resolvedTarget = path.resolve(dir, target);
                if (existsSync(resolvedTarget)) {
                    unlinkSync(entryPath);
                    copyFileSync(resolvedTarget, entryPath);
                } else {
                    console.warn(`Removing broken symlink: ${entryPath} -> ${target}`);
                    unlinkSync(entryPath);
                }
            }
        } catch (e) {
            console.warn(`Failed to resolve symlink ${entryPath}: ${e.message}`);
        }
    }
}

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

// Inject rg into node_modules so Kilocode extension can find it on Android
if (existsSync(termuxRgPath)) {
    const vscodeRgBinOut = path.resolve(runtimeProjectRoot, 'node_modules', '@vscode', 'ripgrep', 'bin');
    mkdirSync(vscodeRgBinOut, { recursive: true });

    // Copy the real binary as rg.bin
    cpSync(termuxRgPath, path.resolve(vscodeRgBinOut, 'rg.bin'));

    // Create a shell wrapper for rg that sets LD_LIBRARY_PATH
    // This is required because Kilocode extension spawns rg without passing the Android library paths,
    // causing libpcre2-8.so to be unfound and crashing the node process.
    // NOTE: Android's dynamic linker strictly rejects relative .. segments in LD_LIBRARY_PATH,
    // so we evaluate the exact absolute path of the lib folder using cd and pwd.
    const rgWrapperContent = `#!/system/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
LIB_DIR="$(cd "$DIR/../../../../../lib" && pwd)"
export LD_LIBRARY_PATH="$LIB_DIR:$LD_LIBRARY_PATH"
exec "$DIR/rg.bin" "$@"
`;
    writeFileSync(path.resolve(vscodeRgBinOut, 'rg'), rgWrapperContent, { mode: 0o755 });

    console.log(`Copied Termux rg and generated absolute wrapper in node_modules for Kilocode`);
}

cpSync(ovsxRouterSrc, path.resolve(configOut, 'ovsx-router-config.json'));

// Copy Termux node binary and npm
if (existsSync(termuxBinDir)) {
    console.log(`Copying Termux binaries from ${termuxBinDir}`);
    cpSync(termuxBinDir, binOut, { recursive: true });
    await ensureProotBinary();

    // Resolve symlinks in git-core: Android AssetManager can't handle symlinks.
    // Most git subcommands (git-checkout, git-diff, etc.) are symlinks to the main git binary.
    const gitCoreDir = path.resolve(binOut, 'libexec', 'git-core');
    if (existsSync(gitCoreDir)) {
        resolveSymlinks(gitCoreDir);
        console.log('Resolved symlinks in libexec/git-core for Android compatibility');
    }

    // Generate node-wrapper to circumvent Android 10+ stripping LD_LIBRARY_PATH
    // for binaries executed from the data directory.
    const nodeWrapperContent = `#!/system/bin/sh
export LD_LIBRARY_PATH=$(dirname $0)/../lib:$LD_LIBRARY_PATH
exec $(dirname $0)/node "$@"
`;
    writeFileSync(path.resolve(binOut, 'node-wrapper'), nodeWrapperContent, { mode: 0o755 });
    console.log(`Generated bin/node-wrapper script`);

    // Create ps-wrapper (tree-kill uses ps -ax which Toybox doesn't understand, causing backend uncaught exceptions)
    const psWrapperContent = `#!/system/bin/sh
args=""
for arg in "$@"; do
  if [ "$arg" = "ax" ] || [ "$arg" = "-ax" ]; then
    args="$args -A"
  else
    args="$args $arg"
  fi
done
exec /system/bin/ps $args
`;
    writeFileSync(path.resolve(binOut, 'ps'), psWrapperContent, { mode: 0o755 });
    console.log(`Generated bin/ps script to polyfill Toybox limitations`);

    // Create sh wrapper to redirect Kilocode execa raw calls to Termux bash
    const shWrapperContent = `#!/system/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ "$1" = "-c" ]; then
    shift
    exec "$DIR/bash" -c "$@"
else
    exec "$DIR/bash" "$@"
fi
`;
    writeFileSync(path.resolve(binOut, 'sh'), shWrapperContent, { mode: 0o755 });
    console.log(`Generated bin/sh script to redirect execa to bash`);

    // Generate improved bash wrapper with proper initialization
    const bashWrapperContent = `#!/system/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
LIB_DIR="$(cd "$DIR/../lib" && pwd)"
export PATH="$DIR:$PATH"
export LD_LIBRARY_PATH="$LIB_DIR\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
export PS1='\\[\\033[01;32m\\]devpocket\\[\\033[00m\\]:\\[\\033[01;34m\\]\\w\\[\\033[00m\\]\\$ '
export HISTFILE="\$HOME/.bash_history"
export HISTSIZE=1000
export HISTFILESIZE=2000

# Set up git exec path so git subcommands work
export GIT_EXEC_PATH="$DIR/libexec/git-core"

# npm: disable bin-links to avoid EACCES on symlink creation
export npm_config_bin_links=false

exec "$DIR/bash.real" --noprofile --norc "$@"
`;
    writeFileSync(path.resolve(binOut, 'bash'), bashWrapperContent, { mode: 0o755 });
    console.log('Generated improved bin/bash wrapper');

    // Create a no-op spawn-helper for node-pty compatibility.
    // On Linux/Android, forkpty() is used directly and spawn-helper is NOT called.
    // This file exists only to prevent any file-not-found errors during module init.
    const backendBuildDir = path.resolve(backendOut, 'build', 'Release');
    mkdirSync(backendBuildDir, { recursive: true });
    writeFileSync(path.resolve(backendBuildDir, 'spawn-helper'), '#!/system/bin/sh\nexec "$@"\n', { mode: 0o755 });
    console.log('Created no-op spawn-helper for node-pty compatibility');
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

// Bundle CA certificates for SSL/TLS (git clone, npm install, curl, etc.)
const caCertOut = path.resolve(runtimeOut, 'etc', 'ca-certificates');
mkdirSync(caCertOut, { recursive: true });
const caCertSrc = path.resolve(repoRoot, 'runtime', 'etc', 'cacert.pem');
if (existsSync(caCertSrc)) {
    cpSync(caCertSrc, path.resolve(caCertOut, 'cacert.pem'));
    console.log('Copied CA certificate bundle to runtime/etc/ca-certificates/');
} else {
    // Try system cert bundle as fallback
    const systemCert = '/etc/ssl/certs/ca-certificates.crt';
    if (existsSync(systemCert)) {
        cpSync(systemCert, path.resolve(caCertOut, 'cacert.pem'));
        console.log('Copied system CA certificates to runtime/etc/ca-certificates/');
    } else {
        console.warn('WARNING: No CA certificate bundle found. SSL operations may fail.');
        console.warn('         Place a cacert.pem at runtime/etc/cacert.pem');
        console.warn('         Download from: https://curl.se/ca/cacert.pem');
    }
}

// Bundle pre-installed extensions (VSIX files) so they are available on first launch
const extensionsOut = path.resolve(runtimeOut, 'extensions');
const extensionsSrc = path.resolve(repoRoot, 'assets');
mkdirSync(extensionsOut, { recursive: true });
if (existsSync(extensionsSrc)) {
    for (const entry of readdirSync(extensionsSrc)) {
        if (entry.endsWith('.vsix')) {
            cpSync(path.resolve(extensionsSrc, entry), path.resolve(extensionsOut, entry));
            console.log(`Bundled extension: ${entry}`);
        }
    }
}

console.log(`Runtime assets prepared at ${runtimeOut}`);
