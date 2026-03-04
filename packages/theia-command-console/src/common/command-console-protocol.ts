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

import { RpcServer } from '@theia/core';

export const commandConsolePath = '/services/command-console';

export const CommandConsoleServer = Symbol('CommandConsoleServer');
export const CommandConsoleClient = Symbol('CommandConsoleClient');

export interface CommandConsoleExecuteRequest {
    command: string;
    cwd?: string;
    timeoutMs?: number;
}

export interface CommandConsoleExecutionSummary {
    request: CommandConsoleExecuteRequest;
    startedAt: number;
    finishedAt: number;
    durationMs: number;
    exitCode: number | null;
    signal: NodeJS.Signals | null;
}

export interface CommandConsoleHistoryEntry {
    id: string;
    command: string;
    cwd: string;
    createdAt: number;
    exitCode: number | null;
    durationMs: number;
}

export interface CommandConsoleClient {
    onDidStart(id: string, request: CommandConsoleExecuteRequest, startedAt: number): void;
    onDidStdout(id: string, chunk: string): void;
    onDidStderr(id: string, chunk: string): void;
    onDidExit(id: string, summary: CommandConsoleExecutionSummary): void;
    onDidError(id: string, message: string): void;
}

export interface CommandConsoleServer extends RpcServer<CommandConsoleClient> {
    execute(request: CommandConsoleExecuteRequest): Promise<string>;
    cancel(id: string): Promise<boolean>;
    getHistory(): Promise<CommandConsoleHistoryEntry[]>;
    clearHistory(): Promise<void>;
    listWorkspaceDirectories(): Promise<string[]>;
}
