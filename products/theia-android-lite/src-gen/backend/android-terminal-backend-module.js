/**
 * Android-specific terminal backend module.
 * This overrides the default terminal-backend-module to force pseudo-terminal mode
 * for all terminal processes, since node-pty's native bindings don't work on Android.
 */

const { ContainerModule, bind } = require('inversify');
const { ShellProcess, ShellProcessFactory, ShellProcessOptions } = require('@theia/terminal/lib/node/shell-process');
const { ProcessManager, MultiRingBuffer, ILogger } = require('@theia/process/lib/node');
const { EnvironmentUtils } = require('@theia/core/lib/node/environment-utils');

/**
 * AndroidShellProcess - forces isPseudo to true
 */
class AndroidShellProcess extends ShellProcess {
    constructor(options, processManager, ringBuffer, logger, environmentUtils) {
        // Force pseudo-terminal mode for Android
        const androidOptions = {
            ...options,
            isPseudo: true
        };
        super(androidOptions, processManager, ringBuffer, logger, environmentUtils);
    }
}

/**
 * Create Android terminal backend module
 */
function createAndroidTerminalBackendModule() {
    return new ContainerModule((bind) => {
        // Override ShellProcess to always use pseudo-terminal mode
        bind(ShellProcess).toClass(AndroidShellProcess);
        
        // Override the factory to always pass isPseudo: true
        bind(ShellProcessFactory).toFactory((ctx) => {
            return (options) => {
                const processManager = ctx.container.get(ProcessManager);
                const ringBuffer = ctx.container.get(MultiRingBuffer);
                const logger = ctx.container.get(ILogger);
                const environmentUtils = ctx.container.get(EnvironmentUtils);
                
                // Always force pseudo-terminal mode
                const androidOptions = { ...options, isPseudo: true };
                return new AndroidShellProcess(androidOptions, processManager, ringBuffer, logger, environmentUtils);
            };
        });
    });
}

module.exports = { androidTerminalBackendModule: createAndroidTerminalBackendModule() };
