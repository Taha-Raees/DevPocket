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
    try {
        if (info && info.shell && info.shell.includes('com.termux')) {
            info.shell = process.env.SHELL || '/system/bin/sh';
        }
        if (info && info.homedir && info.homedir.includes('com.termux')) {
            info.homedir = process.env.HOME || os.homedir();
        }
    } catch(e) {}
    return info;
};

console.error('[android-lite][polyfill] shell diagnostics', {
    envShell: process.env.SHELL,
    envTheiaShell: process.env.THEIA_SHELL,
    npmConfigShell: process.env.npm_config_shell,
    npmConfigScriptShell: process.env.npm_config_script_shell
});

const originalTmpdir = os.tmpdir;
os.tmpdir = function() {
    const tmp = originalTmpdir.call(this);
    if (tmp && tmp.includes('com.termux')) {
        return process.env.TMPDIR || '/data/local/tmp';
    }
    return tmp;
};
