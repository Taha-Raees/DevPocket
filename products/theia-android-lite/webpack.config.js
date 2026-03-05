/**
 * This file can be edited to customize webpack configuration.
 * To reset delete this file and rerun theia build again.
 */
// @ts-check
const path = require('path');
const NativeWebpackPlugin = require('@theia/native-webpack-plugin');
const configs = require('./gen-webpack.config.js');
const nodeConfig = require('./gen-webpack.node.config.js');

configs[0].module.rules.push({
    test: /\.js$/,
    loader: require.resolve('@theia/application-manager/lib/expose-loader')
});

const nodeWebpackConfig = nodeConfig.config;

if (nodeWebpackConfig.entry && typeof nodeWebpackConfig.entry === 'object') {
    delete nodeWebpackConfig.entry['worker/conoutSocketWorker'];
}

nodeWebpackConfig.resolve = nodeWebpackConfig.resolve || {};
nodeWebpackConfig.resolve.alias = {
    ...(nodeWebpackConfig.resolve.alias || {}),
    'keytar': false,
    'nsfw': false,
    '@parcel/watcher': false,
    'spdlog': false,
    'native-watchdog': false,
    'native-keymap': false,
    'vscode-sqlite3': false
};

// Android: Remove NativeWebpackPlugin entirely.
// All native .node addons are compiled for glibc and will crash on Android/Bionic.
// Ripgrep is handled separately by bundling Termux's pre-compiled `rg` binary.
const nativePlugin = nodeConfig.nativePlugin;
if (Array.isArray(nodeWebpackConfig.plugins) && nativePlugin) {
    nodeWebpackConfig.plugins = nodeWebpackConfig.plugins.filter(plugin => plugin !== nativePlugin);
}

// Android PTY fix: node-pty tries to require('../build/Release/pty.node') at runtime.
// When webpack bundles unixTerminal.js, __dirname becomes the output dir (lib/backend/).
// We replace the pty.node require with a runtime load from native/pty.node next to the bundle.
// The node-loader rule (test: /\.node$/) would try to inline it as glibc binary — exclude it.
nodeWebpackConfig.module = nodeWebpackConfig.module || { rules: [] };
// Exclude pty.node from node-loader so we handle it manually via the alias stub below
nodeWebpackConfig.module.rules = (nodeWebpackConfig.module.rules || []).map(rule => {
    if (rule && rule.test && rule.test.toString() === '/\\.node$/') {
        return { ...rule, exclude: /pty\.node$/ };
    }
    return rule;
});

// Inject a virtual module alias: redirect node-pty's pty.node load to our runtime stub
const ptyStubPath = path.resolve(__dirname, 'scripts', 'android-pty-stub.js');
const fs = require('fs');
fs.writeFileSync(ptyStubPath, [
    '// Android PTY stub: load Termux pty.node from native/ dir at runtime.',
    'const path = require("path");',
    'module.exports = __non_webpack_require__(path.join(__dirname, "native", "pty.node"));',
].join('\n'));

nodeWebpackConfig.resolve.alias = {
    ...nodeWebpackConfig.resolve.alias,
    // node-pty/lib/unixTerminal.js does require('../build/Release/pty.node')
    // After webpack resolves __dirname of unixTerminal.js, that becomes
    // <outputPath>/build/Release/pty.node — alias that to our stub.
};

// Use a NormalModuleReplacementPlugin to redirect pty.node requires
const webpack = require('webpack');
nodeWebpackConfig.plugins = nodeWebpackConfig.plugins || [];
nodeWebpackConfig.plugins.push(
    new webpack.NormalModuleReplacementPlugin(
        /[/\\]build[/\\](Release|Debug)[/\\]pty\.node$/,
        ptyStubPath
    )
);

module.exports = [
    ...configs,
    nodeWebpackConfig
];
