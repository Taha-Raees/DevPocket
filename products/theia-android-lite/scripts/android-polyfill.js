const cp = require('child_process');
const os = require('os');
const originalSpawn = cp.spawn;
const originalSpawnSync = cp.spawnSync;

function patchOptions(options) {
    if (!options) {
        options = {};
    }
    if (options.shell === true) {
        options.shell = process.env.SHELL || '/system/bin/sh';
    }
    if (!options.env) {
        options.env = Object.assign({}, process.env);
    }
    if (!options.env.SHELL) {
        options.env.SHELL = process.env.SHELL || '/system/bin/sh';
    }
    if (!options.env.THEIA_SHELL) {
        options.env.THEIA_SHELL = process.env.SHELL || '/system/bin/sh';
    }
    return options;
}

cp.spawn = function(command, args, options) {
    if (!Array.isArray(args) && args !== undefined) {
        options = args;
        args = [];
    }
    options = patchOptions(options);
    return originalSpawn.call(this, command, args, options);
};

cp.spawnSync = function(command, args, options) {
    if (!Array.isArray(args) && args !== undefined) {
        options = args;
        args = [];
    }
    options = patchOptions(options);
    return originalSpawnSync.call(this, command, args, options);
};

const originalUserInfo = os.userInfo;
os.userInfo = function(options) {
    const info = originalUserInfo.call(this, options);
    if (info && info.shell === '/data/data/com.termux/files/usr/bin/bash' || info.shell === '/data/data/com.termux/files/usr/bin/sh') {
        info.shell = process.env.SHELL || '/system/bin/sh';
    }
    return info;
};

console.error('[android-lite][polyfill] shell diagnostics', {
    envShell: process.env.SHELL,
    envTheiaShell: process.env.THEIA_SHELL,
    npmConfigShell: process.env.npm_config_shell,
    npmConfigScriptShell: process.env.npm_config_script_shell
});
