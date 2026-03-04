// *****************************************************************************
// Copyright (C) 2026 EclipseSource GmbH and others.
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

import * as React from '@theia/core/shared/react';
import { inject, injectable, postConstruct } from '@theia/core/shared/inversify';
import { codicon, ReactWidget, QuickInputService } from '@theia/core/lib/browser';
import { ClipboardService } from '@theia/core/lib/browser/clipboard-service';
import { nls } from '@theia/core';
import {
    CommandConsoleClient,
    CommandConsoleExecutionSummary,
    CommandConsoleHistoryEntry,
    CommandConsoleServer,
    CommandConsoleExecuteRequest
} from '../common/command-console-protocol';

interface CommandConsoleLine {
    stream: 'stdout' | 'stderr' | 'system';
    text: string;
}

@injectable()
export class TheiaCommandConsoleWidget extends ReactWidget implements CommandConsoleClient {

    static readonly ID = 'theia-command-console-widget';
    static readonly LABEL = nls.localizeByDefault('Command Console');

    @inject(CommandConsoleServer)
    protected readonly server: CommandConsoleServer;

    @inject(ClipboardService)
    protected readonly clipboardService: ClipboardService;

    @inject(QuickInputService)
    protected readonly quickInputService: QuickInputService;

    protected commandInput = '';
    protected timeoutMsInput = '';
    protected cwd = '';
    protected directories: string[] = [];
    protected output: CommandConsoleLine[] = [];
    protected history: CommandConsoleHistoryEntry[] = [];
    protected runningId: string | undefined;
    protected startedAt = 0;
    protected summary: CommandConsoleExecutionSummary | undefined;
    protected errorText = '';
    protected loading = false;

    @postConstruct()
    protected init(): void {
        this.id = TheiaCommandConsoleWidget.ID;
        this.title.label = TheiaCommandConsoleWidget.LABEL;
        this.title.caption = TheiaCommandConsoleWidget.LABEL;
        this.title.closable = true;
        this.title.iconClass = codicon('terminal');
        this.node.tabIndex = 0;
        this.addClass('theia-command-console-widget');
        void this.refreshMetadata();
    }

    protected async refreshMetadata(): Promise<void> {
        const [directories, history] = await Promise.all([
            this.server.listWorkspaceDirectories(),
            this.server.getHistory()
        ]);
        this.directories = directories;
        if (!this.cwd && directories.length) {
            this.cwd = directories[0];
        }
        this.history = history;
        this.update();
    }

    async executeCurrentCommand(): Promise<void> {
        const command = this.commandInput.trim();
        if (!command || this.runningId || this.loading) {
            return;
        }
        this.loading = true;
        this.output = [];
        this.errorText = '';
        this.summary = undefined;
        this.update();

        try {
            const request: CommandConsoleExecuteRequest = {
                command,
                cwd: this.cwd || undefined,
                timeoutMs: this.timeoutMsInput ? Number(this.timeoutMsInput) : undefined
            };
            await this.server.execute(request);
        } catch (error) {
            this.errorText = String(error);
        } finally {
            this.loading = false;
            this.update();
        }
    }

    protected async chooseDirectory(): Promise<void> {
        if (!this.directories.length) {
            return;
        }
        const selected = await this.quickInputService.showQuickPick(
            this.directories.map(directory => ({
                label: directory,
                description: directory === this.cwd ? nls.localizeByDefault('Current') : undefined
            })),
            { placeholder: nls.localizeByDefault('Select command working directory') }
        );
        if (selected?.label) {
            this.cwd = selected.label;
            this.update();
        }
    }

    async cancelCurrentCommand(): Promise<void> {
        if (this.runningId) {
            await this.server.cancel(this.runningId);
        }
    }

    async copyOutput(): Promise<void> {
        const text = this.output.map(line => line.text).join('\n');
        await this.clipboardService.writeText(text);
    }

    async clearHistory(): Promise<void> {
        await this.server.clearHistory();
        this.history = [];
        this.update();
    }

    onDidStart(id: string, request: CommandConsoleExecuteRequest, startedAt: number): void {
        this.runningId = id;
        this.startedAt = startedAt;
        this.output.push({ stream: 'system', text: `$ ${request.command}` });
        this.update();
    }

    onDidStdout(_id: string, chunk: string): void {
        this.output.push({ stream: 'stdout', text: chunk });
        this.update();
    }

    onDidStderr(_id: string, chunk: string): void {
        this.output.push({ stream: 'stderr', text: chunk });
        this.update();
    }

    onDidExit(id: string, summary: CommandConsoleExecutionSummary): void {
        if (id === this.runningId) {
            this.runningId = undefined;
        }
        this.summary = summary;
        this.output.push({
            stream: 'system',
            text: `\n[exit=${summary.exitCode ?? 'null'} duration=${summary.durationMs}ms signal=${summary.signal ?? 'none'}]`
        });
        void this.refreshMetadata();
        this.update();
    }

    onDidError(id: string, message: string): void {
        if (id === this.runningId) {
            this.runningId = undefined;
        }
        this.errorText = message;
        this.update();
    }

    protected render(): React.ReactNode {
        return <div className='theia-command-console-root'>
            <div className='theia-command-console-toolbar'>
                <button className='theia-button secondary' onClick={() => this.chooseDirectory()}>
                    {this.cwd || nls.localizeByDefault('Select directory')}
                </button>
                <input
                    className='theia-command-console-timeout'
                    type='number'
                    min={0}
                    placeholder={nls.localizeByDefault('Timeout (ms, optional)')}
                    value={this.timeoutMsInput}
                    onChange={event => {
                        this.timeoutMsInput = event.currentTarget.value;
                        this.update();
                    }}
                />
                <button className='theia-button secondary'
                    onClick={() => this.copyOutput()}>{nls.localizeByDefault('Copy Output')}</button>
                <button className='theia-button secondary'
                    onClick={() => this.clearHistory()}>{nls.localizeByDefault('Clear History')}</button>
            </div>
            <div className='theia-command-console-input-row'>
                <input
                    className='theia-command-console-command'
                    placeholder={nls.localizeByDefault('Type command (e.g. npm install)')}
                    value={this.commandInput}
                    onChange={event => {
                        this.commandInput = event.currentTarget.value;
                        this.update();
                    }}
                    onKeyDown={event => {
                        if (event.key === 'Enter') {
                            void this.executeCurrentCommand();
                        }
                    }}
                />
                <button className='theia-button'
                    disabled={!this.commandInput.trim() || !!this.runningId || this.loading}
                    onClick={() => this.executeCurrentCommand()}>{nls.localizeByDefault('Run')}</button>
                <button className='theia-button secondary'
                    disabled={!this.runningId}
                    onClick={() => this.cancelCurrentCommand()}>{nls.localizeByDefault('Stop')}</button>
            </div>
            {this.errorText ? <div className='theia-command-console-error'>{this.errorText}</div> : undefined}
            <pre className='theia-command-console-output'>
                {this.output.map((line, index) => <div key={`${index}-${line.stream}`} className={`stream-${line.stream}`}>{line.text}</div>)}
            </pre>
            <div className='theia-command-console-footer'>
                <div>{this.summary ? `Last exit: ${this.summary.exitCode ?? 'null'} (${this.summary.durationMs} ms)` : ''}</div>
                <div>{this.runningId ? nls.localizeByDefault('Running...') : ''}</div>
            </div>
            <div className='theia-command-console-history'>
                {this.history.slice().reverse().map(entry =>
                    <button
                        key={entry.id}
                        className='theia-button secondary history-entry'
                        onClick={() => {
                            this.commandInput = entry.command;
                            this.cwd = entry.cwd;
                            this.update();
                        }}
                    >
                        {entry.command}
                    </button>
                )}
            </div>
        </div>;
    }
}
