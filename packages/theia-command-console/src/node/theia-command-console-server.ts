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

import { inject, injectable } from '@theia/core/shared/inversify';
import { randomUUID } from 'crypto';
import { ChildProcessWithoutNullStreams, spawn } from 'child_process';
import * as path from 'path';
import * as os from 'os';
import * as fs from '@theia/core/shared/fs-extra';
import { FileUri } from '@theia/core/lib/node';
import { EnvVariablesServer } from '@theia/core/lib/common/env-variables';
import { WorkspaceServer } from '@theia/workspace/lib/common';
import {
    CommandConsoleClient,
    CommandConsoleExecuteRequest,
    CommandConsoleExecutionSummary,
    CommandConsoleHistoryEntry,
    CommandConsoleServer
} from '../common/command-console-protocol';

interface RunningCommand {
    id: string;
    startedAt: number;
    request: CommandConsoleExecuteRequest;
    process: ChildProcessWithoutNullStreams;
    timeout: NodeJS.Timeout | undefined;
}

@injectable()
export class TheiaCommandConsoleServer implements CommandConsoleServer {

    protected client: CommandConsoleClient | undefined;
    protected readonly running = new Map<string, RunningCommand>();
    protected readonly history: CommandConsoleHistoryEntry[] = [];
    protected readonly maxHistorySize = 100;
    protected loaded = false;

    @inject(WorkspaceServer)
    protected readonly workspaceServer: WorkspaceServer;

    @inject(EnvVariablesServer)
    protected readonly envServer: EnvVariablesServer;

    setClient(client: CommandConsoleClient | undefined): void {
        this.client = client;
    }

    dispose(): void {
        for (const id of [...this.running.keys()]) {
            this.killRunning(id);
        }
    }

    async execute(request: CommandConsoleExecuteRequest): Promise<string> {
        await this.loadHistory();
        const id = randomUUID();
        const startedAt = Date.now();
        const cwd = await this.resolveCwd(request.cwd);
        const env = await this.resolveEnv();

        const shell = await this.resolveShell();
        console.info('[theia-command-console] execute diagnostics', {
            command: request.command,
            cwd,
            shell,
            envShell: env.SHELL,
            envTheiaShell: env.THEIA_SHELL,
            envTheiaShellArgs: env.THEIA_SHELL_ARGS,
            pathHead: (env.PATH || '').split(path.delimiter).slice(0, 5)
        });
        const child = spawn(shell, ['-lc', request.command], {
            cwd,
            env,
            stdio: ['pipe', 'pipe', 'pipe']
        });
        child.stdin.end();

        const running: RunningCommand = {
            id,
            startedAt,
            request: { ...request, cwd },
            process: child,
            timeout: undefined
        };
        this.running.set(id, running);
        this.client?.onDidStart(id, running.request, startedAt);

        child.stdout.on('data', data => {
            this.client?.onDidStdout(id, data.toString());
        });
        child.stderr.on('data', data => {
            this.client?.onDidStderr(id, data.toString());
        });
        child.on('error', error => {
            this.client?.onDidError(id, error.message);
        });
        child.on('exit', (exitCode, signal) => {
            const finishedAt = Date.now();
            this.running.delete(id);
            if (running.timeout) {
                clearTimeout(running.timeout);
            }

            const summary: CommandConsoleExecutionSummary = {
                request: running.request,
                startedAt,
                finishedAt,
                durationMs: finishedAt - startedAt,
                exitCode: exitCode ?? null,
                signal
            };
            this.recordHistory({
                id,
                command: running.request.command,
                cwd,
                createdAt: startedAt,
                exitCode: summary.exitCode,
                durationMs: summary.durationMs
            });
            this.client?.onDidExit(id, summary);
        });

        if (request.timeoutMs && request.timeoutMs > 0) {
            running.timeout = setTimeout(() => {
                this.killRunning(id);
            }, request.timeoutMs);
        }

        return id;
    }

    async cancel(id: string): Promise<boolean> {
        return this.killRunning(id);
    }

    async getHistory(): Promise<CommandConsoleHistoryEntry[]> {
        await this.loadHistory();
        return [...this.history];
    }

    async clearHistory(): Promise<void> {
        this.history.length = 0;
        await this.saveHistory();
    }

    async listWorkspaceDirectories(): Promise<string[]> {
        const recents = await this.workspaceServer.getRecentWorkspaces();
        const fsPaths = recents
            .filter(uri => uri.startsWith('file://'))
            .map(uri => FileUri.fsPath(uri));

        if (fsPaths.length) {
            return [...new Set(fsPaths)];
        }

        const configUri = await this.envServer.getConfigDirUri();
        return [path.dirname(FileUri.fsPath(configUri))];
    }

    protected async resolveCwd(cwd?: string): Promise<string> {
        if (cwd && await fs.pathExists(cwd)) {
            return cwd;
        }
        const directories = await this.listWorkspaceDirectories();
        return directories[0];
    }

    protected async resolveEnv(): Promise<NodeJS.ProcessEnv> {
        const envs = await this.envServer.getVariables();
        const env: NodeJS.ProcessEnv = {};
        for (const variable of envs) {
            env[variable.name] = variable.value;
        }

        const runtimeBin = process.env.THEIA_ANDROID_RUNTIME_BIN;
        if (runtimeBin) {
            env.PATH = `${runtimeBin}${path.delimiter}${env.PATH ?? ''}`;
        }
        return env;
    }

    protected async resolveShell(): Promise<string> {
        const shellCandidates = process.platform === 'android'
            ? [process.env.THEIA_SHELL, process.env.SHELL, '/system/bin/sh', '/bin/sh']
            : [process.env.THEIA_SHELL, process.env.SHELL, '/bin/bash', '/bin/sh'];

        const diagnostics: Array<{ candidate: string; exists: boolean }> = [];
        for (const candidate of shellCandidates) {
            if (!candidate) {
                continue;
            }
            const exists = await fs.pathExists(candidate);
            diagnostics.push({ candidate, exists });
            if (exists) {
                console.info('[theia-command-console] resolveShell diagnostics', {
                    platform: process.platform,
                    envShell: process.env.SHELL,
                    theiaShell: process.env.THEIA_SHELL,
                    theiaShellArgs: process.env.THEIA_SHELL_ARGS,
                    selected: candidate,
                    candidates: diagnostics
                });
                return candidate;
            }
        }

        if (os.platform() === 'win32') {
            const fallback = process.env.ComSpec ?? 'cmd.exe';
            console.info('[theia-command-console] resolveShell diagnostics', {
                platform: process.platform,
                envShell: process.env.SHELL,
                theiaShell: process.env.THEIA_SHELL,
                theiaShellArgs: process.env.THEIA_SHELL_ARGS,
                selected: fallback,
                candidates: diagnostics
            });
            return fallback;
        }
        console.info('[theia-command-console] resolveShell diagnostics', {
            platform: process.platform,
            envShell: process.env.SHELL,
            theiaShell: process.env.THEIA_SHELL,
            theiaShellArgs: process.env.THEIA_SHELL_ARGS,
            selected: '/bin/sh',
            candidates: diagnostics
        });
        return '/bin/sh';
    }

    protected killRunning(id: string): boolean {
        const running = this.running.get(id);
        if (!running) {
            return false;
        }
        if (running.timeout) {
            clearTimeout(running.timeout);
            running.timeout = undefined;
        }
        try {
            running.process.kill('SIGTERM');
            return true;
        } catch {
            return false;
        }
    }

    protected recordHistory(entry: CommandConsoleHistoryEntry): void {
        this.history.push(entry);
        while (this.history.length > this.maxHistorySize) {
            this.history.shift();
        }
        void this.saveHistory();
    }

    protected async loadHistory(): Promise<void> {
        if (this.loaded) {
            return;
        }
        this.loaded = true;

        const historyPath = await this.getHistoryPath();
        if (!await fs.pathExists(historyPath)) {
            return;
        }
        try {
            const raw = await fs.readJSON(historyPath);
            if (Array.isArray(raw)) {
                this.history.splice(0, this.history.length, ...raw as CommandConsoleHistoryEntry[]);
            }
        } catch {
            // ignore malformed history
        }
    }

    protected async saveHistory(): Promise<void> {
        const historyPath = await this.getHistoryPath();
        await fs.ensureDir(path.dirname(historyPath));
        await fs.writeJSON(historyPath, this.history, { spaces: 2 });
    }

    protected async getHistoryPath(): Promise<string> {
        const configUri = await this.envServer.getConfigDirUri();
        return path.resolve(FileUri.fsPath(configUri), 'command-console', 'history.json');
    }
}
