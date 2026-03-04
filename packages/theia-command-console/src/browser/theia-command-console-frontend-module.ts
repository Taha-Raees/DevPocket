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

import '../../src/styles/theia-command-console.css';

import { ContainerModule } from '@theia/core/shared/inversify';
import {
    WidgetFactory,
    bindViewContribution,
    FrontendApplicationContribution,
    WebSocketConnectionProvider
} from '@theia/core/lib/browser';
import { CommandConsoleServer, commandConsolePath } from '../common/command-console-protocol';
import { TheiaCommandConsoleWidget } from './theia-command-console-widget';
import { TheiaCommandConsoleContribution } from './theia-command-console-contribution';

export default new ContainerModule(bind => {
    // NOTE: Do NOT pass widget as client here — it causes circular DI
    // (widget needs server → server needs widget → ∞).
    // The backend RpcConnectionHandler handles the client callback separately.
    bind(CommandConsoleServer).toDynamicValue(ctx => {
        const connection = ctx.container.get(WebSocketConnectionProvider);
        return connection.createProxy<CommandConsoleServer>(commandConsolePath);
    }).inSingletonScope();

    bind(TheiaCommandConsoleWidget).toSelf();
    bind(WidgetFactory).toDynamicValue(ctx => ({
        id: TheiaCommandConsoleWidget.ID,
        createWidget: () => {
            const widget = ctx.container.get(TheiaCommandConsoleWidget);
            // Connect widget as RPC client after creation to receive
            // onDidStart/onDidStdout/onDidStderr/onDidExit/onDidError callbacks
            const server = ctx.container.get(CommandConsoleServer) as any;
            if (typeof server.setClient === 'function') {
                server.setClient(widget);
            }
            return widget;
        }
    })).inSingletonScope();

    bindViewContribution(bind, TheiaCommandConsoleContribution);
    bind(FrontendApplicationContribution).toService(TheiaCommandConsoleContribution);
});
