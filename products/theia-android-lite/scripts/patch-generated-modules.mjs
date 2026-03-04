#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const productRoot = path.resolve(__dirname, '..');

const frontendIndexPath = path.resolve(productRoot, 'src-gen', 'frontend', 'index.js');
const backendServerPath = path.resolve(productRoot, 'src-gen', 'backend', 'server.js');
const backendWebpackPath = path.resolve(productRoot, 'gen-webpack.node.config.js');

// ─── IMPORTANT ──────────────────────────────────────────────
// Do NOT block modules that plugin-ext depends on!
// plugin-ext requires: @theia/notebook, @theia/debug, @theia/scm
// Blocking those breaks extension loading with "No matching bindings" errors.
// Only block modules that are truly standalone and unused.
// ─────────────────────────────────────────────────────────────

const blockedFrontendModules = [
    // ONLY block modules with ZERO downstream DI dependencies.
    // plugin-ext depends on: terminal, debug, notebook, scm, callhierarchy, typehierarchy, etc.
    '@theia/ai-core/lib/browser/ai-core-frontend-module',
    '@theia/ai-mcp/lib/browser/mcp-frontend-module',
];

const blockedBackendModules = [
    '@theia/ai-core/lib/node/ai-core-backend-module',
    '@theia/ai-mcp/lib/node/mcp-backend-module',
    // NOTE: Do NOT block terminal backend — needed for TerminalService bindings
];

// These MUST remain in the backend
const preservedBackendModules = [
    '@theia/process/lib/common/process-common-module',
    '@theia/process/lib/node/process-backend-module',
    '@theia/theia-command-console/lib/node/theia-command-console-backend-module'
];

// These MUST be present in the frontend
const preservedFrontendModules = [
    '@theia/theia-command-console/lib/browser/theia-command-console-frontend-module'
];

function readText(filePath) {
    return fs.existsSync(filePath) ? fs.readFileSync(filePath, 'utf8') : '';
}

function writeIfChanged(filePath, before, after) {
    if (before !== after) {
        fs.writeFileSync(filePath, after);
        return true;
    }
    return false;
}

function stripFrontendModules(source) {
    let output = source;
    let removedCount = 0;
    for (const modulePath of blockedFrontendModules) {
        const escaped = modulePath.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        // Match both require() and import() patterns
        const requirePattern = new RegExp(`^\\s*await load\\(container, require\\('${escaped}'\\)\\);\\n`, 'gm');
        const importPattern = new RegExp(`^\\s*await load\\(container, import\\('${escaped}'\\)\\);\\n`, 'gm');
        const before = output;
        output = output.replace(requirePattern, '');
        output = output.replace(importPattern, '');
        if (before !== output) {
            removedCount += 1;
        }
    }
    console.log(`patch-generated-modules: frontend strip removed=${removedCount}/${blockedFrontendModules.length}`);
    return output;
}

function preserveFrontendModules(source) {
    let output = source;
    let addedCount = 0;
    for (const modulePath of preservedFrontendModules) {
        const escaped = modulePath.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const requireCheck = new RegExp(`require\\('${escaped}'\\)`);
        const importCheck = new RegExp(`import\\('${escaped}'\\)`);
        if (!requireCheck.test(output) && !importCheck.test(output)) {
            const insertBefore = "await load(container, require('@theia/mobile-shell/lib/browser/mobile-shell-frontend-module'));";
            const statement = `        await load(container, require('${modulePath}'));\n        `;
            if (output.includes(insertBefore)) {
                output = output.replace(insertBefore, `${statement}${insertBefore}`);
                addedCount += 1;
            } else {
                const monacoInit = 'MonacoInit.init(container);';
                if (output.includes(monacoInit)) {
                    output = output.replace(monacoInit, `${statement.trim()}\n\n        ${monacoInit}`);
                    addedCount += 1;
                }
            }
        }
    }
    console.log(`patch-generated-modules: frontend preserve added=${addedCount}/${preservedFrontendModules.length}`);
    return output;
}

function stripBackendModules(source) {
    let output = source;
    let removedCount = 0;
    for (const modulePath of blockedBackendModules) {
        const escaped = modulePath.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const pattern = new RegExp(`^\\s*await load\\(require\\('${escaped}'\\)\\);\\n`, 'gm');
        const before = output;
        output = output.replace(pattern, '');
        if (before !== output) {
            removedCount += 1;
        }
    }
    console.log(`patch-generated-modules: backend strip removed=${removedCount}/${blockedBackendModules.length}`);
    return output;
}

function preserveBackendModules(source) {
    let output = source;
    for (const modulePath of preservedBackendModules) {
        const escaped = modulePath.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const pattern = new RegExp(`require\\('${escaped}'\\)`);
        if (!pattern.test(output)) {
            const statement = `        await load(require('${modulePath}'));\n`;
            output = output.replace(
                "        await load(require('@theia/vsx-registry/lib/node/vsx-registry-backend-module'));\n",
                `${statement}        await load(require('@theia/vsx-registry/lib/node/vsx-registry-backend-module'));\n`
            );
        }
    }
    return output;
}

function disablePtyInWebpack(source) {
    let output = source;
    output = output.replace('pty: true,', 'pty: false,');
    output = output.replace(
        /^\s*'worker\/conoutSocketWorker': require\.resolve\('node-pty\/lib\/worker\/conoutSocketWorker'\),\s*\n/gm,
        ''
    );
    output = output.replace(
        /\/\/ Ensure that node-pty is correctly hoisted[\s\S]*?process\.exit\(1\);\n\s*}\n\n/m,
        ''
    );
    output = output.replace(
        /\s*\/\/ Make sure the node-pty thread worker can be executed:\s*\n/gm,
        ''
    );
    output = output.replace(
        /\}, \{\n\s*module: \/node-pty\//m,
        '}, {'
    );
    return output;
}

const frontendBefore = readText(frontendIndexPath);
const backendBefore = readText(backendServerPath);
const webpackBefore = readText(backendWebpackPath);

if (!frontendBefore || !backendBefore || !webpackBefore) {
    console.log('patch-generated-modules: skipped (generated files are missing, run theiaext build first).');
    process.exit(0);
}

const frontendAfter = preserveFrontendModules(stripFrontendModules(frontendBefore));
const backendAfter = preserveBackendModules(stripBackendModules(backendBefore));
const webpackAfter = webpackBefore;

const hasTerminalFrontendModule = /@theia\/terminal\/lib\/browser\/terminal-frontend-module/.test(frontendAfter);
const hasCommandConsoleFrontendModule = /@theia\/theia-command-console\/lib\/browser\/theia-command-console-frontend-module/.test(frontendAfter);
console.log('patch-generated-modules: frontend diagnostics', {
    hasTerminalFrontendModule,
    hasCommandConsoleFrontendModule
});

const frontendChanged = writeIfChanged(frontendIndexPath, frontendBefore, frontendAfter);
const backendChanged = writeIfChanged(backendServerPath, backendBefore, backendAfter);
const webpackChanged = writeIfChanged(backendWebpackPath, webpackBefore, webpackAfter);

console.log(`patch-generated-modules: frontend=${frontendChanged ? 'updated' : 'unchanged'} backend=${backendChanged ? 'updated' : 'unchanged'} webpack=${webpackChanged ? 'updated' : 'unchanged'}`);
