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

import { ChildProcessWithoutNullStreams, spawn as spawnProcess } from 'child_process';
import { injectable, inject, named } from '@theia/core/shared/inversify';
import { Disposable, DisposableCollection, Emitter, Event, isWindows } from '@theia/core';
import { ILogger } from '@theia/core/lib/common';
import { Process, ProcessType, ProcessOptions, /* ProcessErrorEvent */ } from './process';
import { ProcessManager } from './process-manager';
import type { IPty } from 'node-pty';
import { MultiRingBuffer, MultiRingBufferReadableStream } from './multi-ring-buffer';
import { DevNullStream } from './dev-null-stream';
import { signame } from './utils';
import { PseudoPty } from './pseudo-pty';
import { Writable } from 'stream';
import * as fs from 'fs';
import * as path from 'path';

export const TerminalProcessOptions = Symbol('TerminalProcessOptions');
export interface TerminalProcessOptions extends ProcessOptions {
    /**
     * Windows only. Allow passing complex command lines already escaped for CommandLineToArgvW.
     */
    commandLine?: string;
    isPseudo?: boolean;
}

export const TerminalProcessFactory = Symbol('TerminalProcessFactory');
export interface TerminalProcessFactory {
    (options: TerminalProcessOptions): TerminalProcess;
}

export enum NodePtyErrors {
    EACCES = 'Permission denied',
    ENOENT = 'No such file or directory'
}

class StreamTerminal implements IPty {

    readonly pid: number;

    readonly process: string;

    handleFlowControl = false;

    readonly onData: Event<string>;

    readonly onExit: Event<{ exitCode: number, signal?: number }>;

    protected readonly onDataEmitter = new Emitter<string>();

    protected readonly onExitEmitter = new Emitter<{ exitCode: number, signal?: number }>();

    protected _cols: number;

    protected _rows: number;

    constructor(
        protected readonly child: ChildProcessWithoutNullStreams,
        cols: number,
        rows: number,
        process: string,
        protected readonly logger?: ILogger,
    ) {
        this.pid = child.pid ?? -1;
        this.process = process;
        this._cols = cols;
        this._rows = rows;
        this.onData = this.onDataEmitter.event;
        this.onExit = this.onExitEmitter.event;

        child.stdout.on('data', data => {
            let text = data.toString();
            // Ensure bare \n gets \r\n for proper xterm rendering
            text = text.replace(/\r?\n/g, '\r\n');
            this.logger?.info(`[android-terminal][stdout] ${JSON.stringify(text.slice(0, 200))}`);
            this.onDataEmitter.fire(text);
        });
        child.stderr.on('data', data => {
            let text = data.toString();
            // Suppress noisy bash warnings that are expected with pipe-based terminals
            // (no real PTY, so tcsetpgrp / job control fail harmlessly).
            text = text.replace(/bash[^:]*: cannot set terminal process group \(\d+\): [^\n]*\n?/g, '');
            text = text.replace(/bash[^:]*: no job control in this shell\n?/g, '');
            if (text.length === 0) {
                return;
            }
            this.logger?.info(`[android-terminal][stderr] ${JSON.stringify(text.slice(0, 200))}`);
            this.onDataEmitter.fire(text);
        });
        child.on('exit', (exitCode, signal) => this.onExitEmitter.fire({
            exitCode: exitCode ?? 0,
            signal: typeof signal === 'number' ? signal : undefined,
        }));
    }

    get cols(): number {
        return this._cols;
    }

    get rows(): number {
        return this._rows;
    }

    on(event: string, listener: (data: string) => void): void;

    on(event: string, listener: (exitCode: number, signal?: number) => void): void;

    on(event: string, listener: (error?: string) => void): void;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    on(event: string, listener: (...args: any[]) => void): void {
        switch (event) {
            case 'data':
                this.onData(data => listener(data));
                break;
            case 'exit':
                this.onExit(({ exitCode, signal }) => listener(exitCode, signal));
                break;
            default:
                break;
        }
    }

    resize(columns: number, rows: number): void {
        this._cols = columns;
        this._rows = rows;
        // Forward resize to the shell via stty so tools can detect the new size
        try {
            this.child.stdin.write(`stty cols ${columns} rows ${rows} 2>/dev/null\n`);
        } catch { /* ignore if stdin is closed */ }
    }

    write(data: string): void {
        // Only convert standalone \r (not \r\n) to \n for proper line ending handling
        const normalized = data.replace(/\r(?!\n)/g, '\n');
        this.logger?.info(`[android-terminal][stdin] ${JSON.stringify(normalized.slice(0, 200))}`);
        this.child.stdin.write(normalized);
    }

    kill(signal?: string): void {
        this.child.kill(signal as NodeJS.Signals | number | undefined);
    }

    pause(): void {
        this.child.stdout.pause();
        this.child.stderr.pause();
    }

    resume(): void {
        this.child.stdout.resume();
        this.child.stderr.resume();
    }

    clear(): void { }
}

/**
 * Run arbitrary processes inside pseudo-terminals (PTY).
 *
 * Note: a PTY is not a shell process (bash/pwsh/cmd...)
 */
@injectable()
export class TerminalProcess extends Process {

    protected readonly terminal: IPty | undefined;
    private _delayedResizer: DelayedResizer | undefined;
    private _exitCode: number | undefined;

    readonly outputStream = this.createOutputStream();
    readonly errorStream = new DevNullStream({ autoDestroy: true });
    readonly inputStream: Writable;

    constructor( // eslint-disable-next-line @typescript-eslint/indent
        @inject(TerminalProcessOptions) protected override readonly options: TerminalProcessOptions,
        @inject(ProcessManager) processManager: ProcessManager,
        @inject(MultiRingBuffer) protected readonly ringBuffer: MultiRingBuffer,
        @inject(ILogger) @named('process') logger: ILogger
    ) {
        super(processManager, logger, ProcessType.Terminal, options);

        if (options.isPseudo) {
            // do not need to spawn a process, new a pseudo pty instead
            this.terminal = new PseudoPty();
            this.inputStream = new DevNullStream({ autoDestroy: true });
            return;
        }

        if (this.isForkOptions(this.options)) {
            throw new Error('terminal processes cannot be forked as of today');
        }
        this.logger.debug('Starting terminal process', JSON.stringify(options, undefined, 2));

        // Delay resizes to avoid conpty not respecting very early resize calls
        // see https://github.com/microsoft/vscode/blob/a1c783c/src/vs/platform/terminal/node/terminalProcess.ts#L177
        if (isWindows) {
            this._delayedResizer = new DelayedResizer();
            this._delayedResizer.onTrigger(dimensions => {
                this._delayedResizer?.dispose();
                this._delayedResizer = undefined;
                if (dimensions.cols && dimensions.rows) {
                    this.resize(dimensions.cols, dimensions.rows);
                }
            });
        }

        const startTerminal = (command: string): { terminal: IPty | undefined, inputStream: Writable } => {
            if (process.env.THEIA_ANDROID_LITE === '1' || (process.platform as string) === 'android') {
                // Try native PTY helper first for full terminal capabilities
                const ptyHelperPath = path.join(process.env.THEIA_ANDROID_RUNTIME_BIN || '', 'pty-helper');
                if (fs.existsSync(ptyHelperPath)) {
                    this.logger.info('Using native PTY helper on Android');
                    return this.createNativePtyTerminal(command, options, ringBuffer, ptyHelperPath);
                }
                this.logger.warn('pty-helper not found, using pipe-based shell terminal on Android.');
                return this.createFallbackTerminal(command, options, ringBuffer);
            }
            try {
                return this.createPseudoTerminal(command, options, ringBuffer);
            } catch (error) {
                // Normalize the error to make it as close as possible as what
                // node's child_process.spawn would generate in the same
                // situation.
                const message: string = error.message;

                if (message.startsWith('File not found: ') || message.endsWith(NodePtyErrors.ENOENT)) {
                    if (isWindows && command && !command.toLowerCase().endsWith('.exe')) {
                        const commandExe = command + '.exe';
                        this.logger.debug(`Trying terminal command '${commandExe}' because '${command}' was not found.`);
                        return startTerminal(commandExe);
                    }

                    // Proceed with failure, reporting the original command because it was
                    // the intended command and it was not found
                    error.errno = 'ENOENT';
                    error.code = 'ENOENT';
                    error.path = options.command;
                } else if (message.endsWith(NodePtyErrors.EACCES)) {
                    // The shell program exists but was not accessible, so just fail
                    error.errno = 'EACCES';
                    error.code = 'EACCES';
                    error.path = options.command;
                }

                if ((process.env.THEIA_ANDROID_LITE === '1' || (process.platform as string) === 'android')
                    && error instanceof Error && /node-pty native fork function is unavailable/i.test(error.message)) {
                    this.logger.warn('node-pty native fork is unavailable on Android, falling back to pipe-based shell terminal.');
                    return this.createFallbackTerminal(command, options, ringBuffer);
                }

                // node-pty throws exceptions on Windows.
                // Call the client error handler, but first give them a chance to register it.
                this.emitOnErrorAsync(error);

                return { terminal: undefined, inputStream: new DevNullStream({ autoDestroy: true }) };
            }
        };

        const { terminal, inputStream } = startTerminal(options.command);
        this.terminal = terminal;
        this.inputStream = inputStream;
    }

    /**
     * Helper for the constructor to attempt to create the pseudo-terminal encapsulating the shell process.
     *
     * @param command the shell command to launch
     * @param options options for the shell process
     * @param ringBuffer a ring buffer in which to collect terminal output
     * @returns the terminal PTY and a stream by which it may be sent input
     */
    private createPseudoTerminal(command: string, options: TerminalProcessOptions, ringBuffer: MultiRingBuffer): { terminal: IPty | undefined, inputStream: Writable } {
        const { spawn } = require('node-pty') as typeof import('node-pty');
        const terminal = spawn(
            command,
            (isWindows && options.commandLine) || options.args || [],
            options.options || {}
        );

        process.nextTick(() => this.emitOnStarted());

        // node-pty actually wait for the underlying streams to be closed before emitting exit.
        // We should emulate the `exit` and `close` sequence.
        terminal.onExit(({ exitCode, signal }) => {
            // see https://github.com/microsoft/node-pty/issues/751
            if (exitCode === undefined) {
                exitCode = 0;
            }
            // Make sure to only pass either code or signal as !undefined, not
            // both.
            //
            // node-pty quirk: On Linux/macOS, if the process exited through the
            // exit syscall (with an exit code), signal will be 0 (an invalid
            // signal value).  If it was terminated because of a signal, the
            // signal parameter will hold the signal number and code should
            // be ignored.
            this._exitCode = exitCode;
            if (signal === undefined || signal === 0) {
                this.onTerminalExit(exitCode, undefined);
            } else {
                this.onTerminalExit(undefined, signame(signal));
            }
            process.nextTick(() => {
                if (signal === undefined || signal === 0) {
                    this.emitOnClose(exitCode, undefined);
                } else {
                    this.emitOnClose(undefined, signame(signal));
                }
            });
        });

        terminal.onData((data: string) => {
            ringBuffer.enq(data);
        });

        const inputStream = new Writable({
            write: (chunk: string) => {
                this.write(chunk);
            },
        });

        return { terminal, inputStream };
    }

    private createFallbackTerminal(command: string, options: TerminalProcessOptions, ringBuffer: MultiRingBuffer): { terminal: IPty, inputStream: Writable } {
        const spawnOptions = options.options || {};
        const args = [...((isWindows && options.commandLine) || options.args || [])];
        if (!args.includes('-i')) {
            args.push('-i');
        }

        // Set terminal environment variables so tools detect color/size support
        // even without a real PTY backing the process.
        const env: Record<string, string> = { ...spawnOptions.env } as Record<string, string>;
        if (!env.TERM) {
            env.TERM = (spawnOptions as { name?: string }).name || 'xterm-256color';
        }
        env.COLORTERM = 'truecolor';
        env.COLUMNS = String(spawnOptions.cols || 80);
        env.LINES = String(spawnOptions.rows || 24);
        env.FORCE_COLOR = '1';
        env.CLICOLOR_FORCE = '1';

        const child = spawnProcess(command, args, {
            cwd: spawnOptions.cwd,
            env,
            stdio: 'pipe',
        });
        this.logger.info(`[android-terminal][spawn] pid=${child.pid} command=${command} args=${JSON.stringify(args)} cwd=${spawnOptions.cwd} TERM=${env.TERM}`);

        child.on('spawn', () => {
            this.logger.info(`[android-terminal][spawn-ok] pid=${child.pid} — shell process started successfully`);
        });
        child.on('error', err => {
            this.logger.error(`[android-terminal][spawn-error] ${err.message}`);
        });
        child.on('exit', (code, signal) => {
            this.logger.info(`[android-terminal][exit] pid=${child.pid} code=${code} signal=${signal}`);
        });
        const terminal = new StreamTerminal(
            child,
            spawnOptions.cols || 80,
            spawnOptions.rows || 24,
            command,
            this.logger,
        );

        process.nextTick(() => this.emitOnStarted());

        terminal.onData((data: string) => {
            ringBuffer.enq(data);
        });

        terminal.onExit(({ exitCode, signal }) => {
            this._exitCode = exitCode;
            if (signal === undefined || signal === 0) {
                this.onTerminalExit(exitCode, undefined);
            } else {
                this.onTerminalExit(undefined, signame(signal));
            }
            process.nextTick(() => {
                if (signal === undefined || signal === 0) {
                    this.emitOnClose(exitCode, undefined);
                } else {
                    this.emitOnClose(undefined, signame(signal));
                }
            });
        });

        child.on('error', error => {
            this.emitOnErrorAsync(Object.assign(error, {
                code: (error as NodeJS.ErrnoException).code ?? 'UNKNOWN',
            }));
        });

        return { terminal, inputStream: child.stdin };
    }

    /**
     * Create a terminal using a native PTY helper binary on Android.
     * The helper binary calls forkpty() and relays data via stdin/stdout,
     * providing real PTY semantics (isatty, job control, SIGWINCH).
     */
    private createNativePtyTerminal(
        command: string,
        options: TerminalProcessOptions,
        ringBuffer: MultiRingBuffer,
        ptyHelperPath: string
    ): { terminal: IPty, inputStream: Writable } {
        const spawnOptions = options.options || {};
        const args = [...((isWindows && options.commandLine) || options.args || [])];
        const cols = spawnOptions.cols || 80;
        const rows = spawnOptions.rows || 24;

        const env: Record<string, string> = { ...spawnOptions.env } as Record<string, string>;
        if (!env.TERM) {
            env.TERM = (spawnOptions as { name?: string }).name || 'xterm-256color';
        }
        env.COLORTERM = 'truecolor';

        // Launch pty-helper: pty-helper <cols> <rows> <command> [args...]
        const helperArgs = [String(cols), String(rows), command, ...args];
        const child = spawnProcess(ptyHelperPath, helperArgs, {
            cwd: spawnOptions.cwd,
            env,
            stdio: 'pipe',
        });
        this.logger.info(`[android-pty-helper][spawn] pid=${child.pid} command=${command} cols=${cols} rows=${rows}`);

        const terminal = new StreamTerminal(child, cols, rows, command, this.logger);

        // Override resize to send SIGWINCH to the helper which forwards it to the PTY
        const originalResize = terminal.resize.bind(terminal);
        terminal.resize = (columns: number, newRows: number) => {
            originalResize(columns, newRows);
            try {
                // Signal the helper to resize - it handles SIGWINCH forwarding
                child.kill('SIGWINCH');
                // Also write resize command for helpers that support control protocol
                child.stdin.write(`\x1b[8;${newRows};${columns}t`);
            } catch { /* ignore if process is gone */ }
        };

        process.nextTick(() => this.emitOnStarted());

        terminal.onData((data: string) => {
            ringBuffer.enq(data);
        });

        terminal.onExit(({ exitCode, signal }) => {
            this._exitCode = exitCode;
            if (signal === undefined || signal === 0) {
                this.onTerminalExit(exitCode, undefined);
            } else {
                this.onTerminalExit(undefined, signame(signal));
            }
            process.nextTick(() => {
                if (signal === undefined || signal === 0) {
                    this.emitOnClose(exitCode, undefined);
                } else {
                    this.emitOnClose(undefined, signame(signal));
                }
            });
        });

        child.on('error', error => {
            this.emitOnErrorAsync(Object.assign(error, {
                code: (error as NodeJS.ErrnoException).code ?? 'UNKNOWN',
            }));
        });

        return { terminal, inputStream: child.stdin };
    }

    createOutputStream(): MultiRingBufferReadableStream {
        return this.ringBuffer.getStream();
    }

    get pid(): number {
        this.checkTerminal();
        return this.terminal!.pid;
    }

    get executable(): string {
        return (this.options as ProcessOptions).command;
    }

    get arguments(): string[] {
        return this.options.args || [];
    }

    protected onTerminalExit(code: number | undefined, signal: string | undefined): void {
        this.emitOnExit(code, signal);
        this.unregisterProcess();
    }

    unregisterProcess(): void {
        this.processManager.unregister(this);
    }

    kill(signal?: string): void {
        if (this.terminal && this.killed === false) {
            this.terminal.kill(signal);
        }
    }

    resize(cols: number, rows: number): void {
        if (typeof cols !== 'number' || typeof rows !== 'number' || isNaN(cols) || isNaN(rows)) {
            return;
        }
        this.checkTerminal();
        try {
            // Ensure that cols and rows are always >= 1, this prevents a native exception in winpty.
            cols = Math.max(cols, 1);
            rows = Math.max(rows, 1);

            // Delay resize if needed
            if (this._delayedResizer) {
                this._delayedResizer.cols = cols;
                this._delayedResizer.rows = rows;
                return;
            }

            this.terminal!.resize(cols, rows);
        } catch (error) {
            // swallow error if the pty has already exited
            // see also https://github.com/microsoft/vscode/blob/a1c783c/src/vs/platform/terminal/node/terminalProcess.ts#L549
            if (this._exitCode !== undefined &&
                error.message !== 'ioctl(2) failed, EBADF' &&
                error.message !== 'Cannot resize a pty that has already exited') {
                throw error;
            }
        }
    }

    write(data: string): void {
        this.checkTerminal();
        this.terminal!.write(data);
    }

    protected checkTerminal(): void | never {
        if (!this.terminal) {
            throw new Error('pty process did not start correctly');
        }
    }

}

/**
 * Tracks the latest resize event to be trigger at a later point.
 */
class DelayedResizer extends DisposableCollection {
    rows: number | undefined;
    cols: number | undefined;
    private _timeout: NodeJS.Timeout;

    private readonly _onTrigger = new Emitter<{ rows?: number; cols?: number }>();
    get onTrigger(): Event<{ rows?: number; cols?: number }> { return this._onTrigger.event; }

    constructor() {
        super();
        this.push(this._onTrigger);
        this._timeout = setTimeout(() => this._onTrigger.fire({ rows: this.rows, cols: this.cols }), 1000);
        this.push(Disposable.create(() => clearTimeout(this._timeout)));
    }

    override dispose(): void {
        super.dispose();
        clearTimeout(this._timeout);
    }
}
