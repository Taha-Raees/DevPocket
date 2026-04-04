// *****************************************************************************
// Copyright (C) 2017 Ericsson and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// http://www.eclipse.org/legal/epl-2.0.
//
// This Source Code may also be made available under the following Secondary
// Licenses when the conditions for such availability set forth in the Eclipse
// Public License v. 2.0 are satisfied: GNU General Public License, version 2
// with the GNU Classpath Exception which is available at
// https://www.gnu.org/software/classpath/license.html.
//
// SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0
// *****************************************************************************

import { injectable, inject, named } from '@theia/core/shared/inversify';
import * as os from 'os';
import { ILogger } from '@theia/core/lib/common/logger';
import { TerminalProcess, TerminalProcessOptions, ProcessManager, MultiRingBuffer } from '@theia/process/lib/node';
import { isWindows, isOSX } from '@theia/core/lib/common';
import URI from '@theia/core/lib/common/uri';
import { FileUri } from '@theia/core/lib/common/file-uri';
import { EnvironmentUtils } from '@theia/core/lib/node/environment-utils';
import { parseArgs } from '@theia/process/lib/node/utils';

export const ShellProcessFactory = Symbol('ShellProcessFactory');
export type ShellProcessFactory = (options: ShellProcessOptions) => ShellProcess;

export const ShellProcessOptions = Symbol('ShellProcessOptions');
export interface ShellProcessOptions {
    shell?: string,
    args?: string[] | string,
    rootURI?: string,
    cols?: number,
    rows?: number,
    env?: { [key: string]: string | null },
    strictEnv?: boolean,
    isPseudo?: boolean,
}

export function getRootPath(rootURI?: string): string {
    if (rootURI) {
        const uri = new URI(rootURI);
        return FileUri.fsPath(uri);
    } else {
        return os.homedir();
    }
}

@injectable()
export class ShellProcess extends TerminalProcess {

    protected static defaultCols = 80;
    protected static defaultRows = 24;

    constructor( // eslint-disable-next-line @typescript-eslint/indent
        @inject(ShellProcessOptions) options: ShellProcessOptions,
        @inject(ProcessManager) processManager: ProcessManager,
        @inject(MultiRingBuffer) ringBuffer: MultiRingBuffer,
        @inject(ILogger) @named('terminal') logger: ILogger,
        @inject(EnvironmentUtils) environmentUtils: EnvironmentUtils,
    ) {
        const env = { 'COLORTERM': 'truecolor' };
        // When proot is active the node-pty cwd must be a real Android-side path that
        // exists before exec. Use the Debian home dir (which lives on Android's filesystem
        // under debianRoot/home/<user>) so node-pty can always chdir successfully.
        // proot's own -w flag then sets the working directory *inside* the guest.
        const cwd = (() => {
            const debianRoot = process.env.DEVPOCKET_DEBIAN_ROOT;
            const debianUser = process.env.DEVPOCKET_USER;
            if (debianRoot && debianUser) {
                const path = require('path') as typeof import('path');
                const fs = require('fs') as typeof import('fs');
                const debianHome = path.join(debianRoot, 'home', debianUser);
                if (fs.existsSync(debianHome)) {
                    return debianHome;
                }
                // Fallback: debianRoot itself always exists if onboarding completed
                if (fs.existsSync(debianRoot)) {
                    return debianRoot;
                }
            }
            return getRootPath(options.rootURI);
        })();
        super(<TerminalProcessOptions>{
            command: options.shell || ShellProcess.getShellExecutablePath(),
            args: options.args || ShellProcess.getShellExecutableArgs(),
            options: {
                name: 'xterm-256color',
                cols: options.cols || ShellProcess.defaultCols,
                rows: options.rows || ShellProcess.defaultRows,
                cwd,
                env: options.strictEnv !== true ? Object.assign(env, environmentUtils.mergeProcessEnv(options.env)) : Object.assign(env, options.env),
            },
            isPseudo: options.isPseudo,
        }, processManager, ringBuffer, logger);
    }

    public static getShellExecutablePath(): string {
        const shell = process.env.THEIA_SHELL;
        if (shell) {
            return shell;
        }

        // Android/Debian Detection: Use wrapper script if Debian runtime is available
        if (process.env.DEVPOCKET_DEBIAN_ROOT) {
            const path = require('path');
            const debianBin = path.join(process.env.DEVPOCKET_DEBIAN_ROOT, 'bin', 'devpocket-shell');
            try {
                // Check if wrapper exists and is executable
                const fs = require('fs');
                const stats = fs.statSync(debianBin);
                if (stats.isFile() && (stats.mode & 0o100)) {
                    return debianBin;
                }
            } catch (e) {
                // Fallback if wrapper not found
            }
        }

        if (isWindows) {
            return 'cmd.exe';
        } else {
            return process.env.SHELL!;
        }
    }

    public static getShellExecutableArgs(): string[] {
        const args = process.env.THEIA_SHELL_ARGS;
        if (args) {
            return parseArgs(args);
        }

        // Android/Debian: No special args needed for wrapper script
        if (process.env.DEVPOCKET_DEBIAN_ROOT) {
            return [];
        }

        if (isOSX) {
            return ['-l'];
        } else {
            return [];
        }
    }
}
