/**
 * This file can be edited to customize webpack configuration.
 * To reset delete this file and rerun theia build again.
 */
// @ts-check
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

module.exports = [
    ...configs,
    nodeWebpackConfig
];
