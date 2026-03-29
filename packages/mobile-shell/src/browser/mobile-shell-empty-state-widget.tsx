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

import { codicon, ReactWidget } from '@theia/core/lib/browser';
import * as React from '@theia/core/shared/react';

export interface MobileShellEmptyStateAction {
    id: string;
    label: string;
    description: string;
    iconClass?: string;
    hidden?: boolean;
    disabled?: boolean;
}

export interface MobileShellEmptyStateWidgetOptions {
    onDidRequestAction: (action: MobileShellEmptyStateAction) => void;
}

export class MobileShellEmptyStateWidget extends ReactWidget {
    protected readonly options: MobileShellEmptyStateWidgetOptions;
    protected actions: MobileShellEmptyStateAction[] = [];
    protected visible = false;

    constructor(options: MobileShellEmptyStateWidgetOptions) {
        super();
        this.options = options;
        this.id = 'theia-mobile-shell-empty-state';
        this.addClass('theia-mobile-empty-state-widget');
        this.node.style.display = 'none';
    }

    setVisible(visible: boolean): void {
        if (this.visible !== visible) {
            this.visible = visible;
            // Toggle the outer widget container's display so the fixed-position
            // overlay is fully removed from rendering when the empty state is
            // not active.  Without this the container covers the bottom panel
            // (terminal) even though React renders null children.
            this.node.style.display = visible ? '' : 'none';
            this.update();
        }
    }

    setActions(actions: Iterable<MobileShellEmptyStateAction>): void {
        this.actions = Array.from(actions).filter(action => !action.hidden);
        this.update();
    }

    protected readonly activateAction = (action: MobileShellEmptyStateAction): void => {
        if (!action.disabled) {
            this.options.onDidRequestAction(action);
        }
    };

    protected renderAction(action: MobileShellEmptyStateAction): React.ReactNode {
        const iconClass = action.iconClass?.trim() || codicon('arrow-right');
        return <button
            key={action.id}
            className='theia-mobile-empty-state-action'
            onClick={() => this.activateAction(action)}
            disabled={action.disabled}
            title={action.label}
            aria-label={action.label}>
            <span className='theia-mobile-empty-state-action-icon'>
                <i className={iconClass}></i>
            </span>
            <span className='theia-mobile-empty-state-action-content'>
                <span className='theia-mobile-empty-state-action-label'>{action.label}</span>
                <span className='theia-mobile-empty-state-action-description'>{action.description}</span>
            </span>
        </button>;
    }

    protected override render(): React.ReactNode {
        if (!this.visible) {
            return null;
        }

        return <div className='theia-mobile-empty-state'>
            <div className='theia-mobile-empty-state-brand'>
                <div className='theia-mobile-empty-state-logo'></div>
                <div className='theia-mobile-empty-state-title'>DevPocket</div>
                <div className='theia-mobile-empty-state-subtitle'>Open your tools and jump back into work.</div>
            </div>
            <div className='theia-mobile-empty-state-actions'>
                {this.actions.map(action => this.renderAction(action))}
            </div>
        </div>;
    }
}
