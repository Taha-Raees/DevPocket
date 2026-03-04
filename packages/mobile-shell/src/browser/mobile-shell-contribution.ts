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

import { inject, injectable } from 'inversify';
import {
    ApplicationShell,
    FrontendApplication,
    FrontendApplicationContribution,
    StatusBar,
    QuickInputService,
} from '@theia/core/lib/browser';
import { Command, CommandContribution, CommandRegistry, nls } from '@theia/core';
import { DisposableCollection } from '@theia/core/lib/common/disposable';

const TERMINAL_TOGGLE_COMMAND_ID = 'workbench.action.terminal.toggleTerminal';

export namespace MobileShellCommands {
    export const SHOW_FILES: Command = {
        id: 'mobile.showFiles',
        label: nls.localizeByDefault('Mobile: Files')
    };
    export const SHOW_TERMINAL: Command = {
        id: 'mobile.showTerminal',
        label: nls.localizeByDefault('Mobile: Terminal')
    };
}

@injectable()
export class MobileShellContribution implements FrontendApplicationContribution, CommandContribution {
    @inject(ApplicationShell)
    protected readonly shell: ApplicationShell;

    @inject(StatusBar)
    protected readonly statusBar: StatusBar;

    @inject(QuickInputService)
    protected readonly quickInputService: QuickInputService;

    protected readonly toDispose = new DisposableCollection();
    protected commandRegistry: CommandRegistry | undefined;

    onStart(_app: FrontendApplication): void {
        this.shell.addClass('theia-mobile-shell');
        document.body.classList.add('theia-mobile-shell');

        this.toDispose.push({
            dispose: () => {
                document.body.classList.remove('theia-mobile-shell');
            }
        });

        this.hideTopPanel();
        this.hideRightPanel();
        this.removeOldStatusBarItems();
        this.addActivityBarButtons();
    }

    protected hideTopPanel(): void {
        const tryHide = () => {
            try {
                if (this.shell.topPanel) {
                    this.shell.topPanel.hide();
                    const n = this.shell.topPanel.node;
                    n.style.minHeight = '0';
                    n.style.maxHeight = '0';
                    n.style.height = '0';
                    n.style.overflow = 'hidden';
                }
            } catch { /* ignore */ }
        };
        tryHide();
        setTimeout(tryHide, 100);
        setTimeout(tryHide, 500);
        setTimeout(tryHide, 2000);
    }

    protected hideRightPanel(): void {
        const tryHide = () => {
            try {
                this.shell.collapsePanel('right');
                const container = (this.shell as any).rightPanelHandler?.container;
                if (container) {
                    container.hide();
                    if (container.node) {
                        container.node.style.display = 'none';
                        container.node.style.minWidth = '0';
                        container.node.style.width = '0';
                    }
                }
            } catch { /* ignore */ }
        };
        tryHide();
        setTimeout(tryHide, 100);
        setTimeout(tryHide, 500);
        setTimeout(tryHide, 2000);
    }

    /**
     * Aggressively remove old status bar nav items that were persisted in localStorage.
     * Also remove them by directly targeting DOM elements as a fallback.
     */
    protected removeOldStatusBarItems(): void {
        const oldIds = [
            'mobile-nav-files', 'mobile-nav-search', 'mobile-nav-git',
            'mobile-nav-terminal', 'mobile-nav-ai',
            'mobile-action-newfile', 'mobile-action-newfolder', 'mobile-action-openfolder'
        ];

        const tryRemove = () => {
            // Method 1: Use StatusBar API
            for (const id of oldIds) {
                try { this.statusBar.removeElement(id); } catch { /* ignore */ }
            }

            // Method 2: Direct DOM removal as fallback
            for (const id of oldIds) {
                const domId = `status-bar-${id}`;
                const el = document.getElementById(domId);
                if (el) {
                    el.remove();
                }
            }
        };

        // Try immediately and at multiple delayed intervals to catch late restorations
        tryRemove();
        setTimeout(tryRemove, 200);
        setTimeout(tryRemove, 1000);
        setTimeout(tryRemove, 3000);
        setTimeout(tryRemove, 6000);
        setTimeout(tryRemove, 10000);
    }

    /**
     * Add Files and Terminal buttons to the left activity bar's bottom menu area.
     * Uses MutationObserver + polling to reliably detect the gear icon.
     */
    protected addActivityBarButtons(): void {
        const tryAdd = (): boolean => {
            if (document.getElementById('mobile-files-btn')) {
                return true; // Already added
            }

            // The gear is at: .theia-app-sidebar-container .theia-sidebar-menu .theia-sidebar-menu-item > I.codicon-settings-gear
            const gearIcon = document.querySelector('#theia-left-content-panel .theia-sidebar-menu .codicon-settings-gear')
                || document.querySelector('.theia-app-sidebar-container .theia-sidebar-menu .codicon-settings-gear')
                || document.querySelector('.theia-sidebar-menu .codicon-settings-gear');
            if (!gearIcon) {
                return false;
            }
            const gearItem = gearIcon.closest('.theia-sidebar-menu-item');
            const menuContainer = gearItem?.parentElement;
            if (!menuContainer || !gearItem) {
                return false;
            }

            // Create Files button
            const filesBtn = document.createElement('div');
            filesBtn.id = 'mobile-files-btn';
            filesBtn.className = 'theia-sidebar-menu-item mobile-activity-btn';
            filesBtn.title = 'File Actions';
            const filesIcon = document.createElement('i');
            filesIcon.className = 'codicon codicon-files';
            filesBtn.appendChild(filesIcon);
            filesBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                if (this.commandRegistry) {
                    this.commandRegistry.executeCommand(MobileShellCommands.SHOW_FILES.id);
                }
            });

            // Create Terminal button
            const termBtn = document.createElement('div');
            termBtn.id = 'mobile-terminal-btn';
            termBtn.className = 'theia-sidebar-menu-item mobile-activity-btn';
            termBtn.title = 'Terminal';
            const termIcon = document.createElement('i');
            termIcon.className = 'codicon codicon-terminal';
            termBtn.appendChild(termIcon);
            termBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                if (this.commandRegistry) {
                    this.commandRegistry.executeCommand(MobileShellCommands.SHOW_TERMINAL.id);
                }
            });

            // Insert before the settings gear item
            menuContainer.insertBefore(filesBtn, gearItem);
            menuContainer.insertBefore(termBtn, gearItem);
            return true;
        };

        // Strategy 1: Try immediately
        if (tryAdd()) {
            return;
        }

        // Strategy 2: Use MutationObserver to detect when gear icon is added to DOM
        const observer = new MutationObserver(() => {
            if (tryAdd()) {
                observer.disconnect();
            }
        });
        observer.observe(document.body, { childList: true, subtree: true });

        // Strategy 3: Polling fallback with longer intervals
        const poll = (remaining: number) => {
            if (remaining <= 0) {
                observer.disconnect();
                return;
            }
            setTimeout(() => {
                if (!tryAdd()) {
                    poll(remaining - 1);
                } else {
                    observer.disconnect();
                }
            }, 2000);
        };
        poll(15); // Try for 30 seconds
    }

    registerCommands(commands: CommandRegistry): void {
        this.commandRegistry = commands;

        commands.registerCommand(MobileShellCommands.SHOW_FILES, {
            execute: async () => {
                const items = [
                    { label: '$(folder-opened)  Open Folder', commandId: 'workspace:open' },
                    { label: '$(new-file)  New File', commandId: 'file.newFile' },
                    { label: '$(new-folder)  New Folder', commandId: 'file.newFolder' },
                    { label: '$(files)  Show Explorer', commandId: 'workbench.view.explorer' },
                    { label: '$(save)  Save File', commandId: 'core.save' },
                    { label: '$(save-all)  Save All', commandId: 'core.saveAll' },
                    { label: '$(close)  Close Folder', commandId: 'workspace:close' },
                ];
                const availableItems = items.filter(item => commands.getCommand(item.commandId));
                const selected = await this.quickInputService.showQuickPick(
                    availableItems.map(item => ({ label: item.label, value: item.commandId })),
                    { placeholder: 'File Actions...' }
                );
                if (selected) {
                    await this.executeIfAvailable(commands, selected.value!);
                }
            }
        });

        commands.registerCommand(MobileShellCommands.SHOW_TERMINAL, {
            execute: async () => {
                await this.executeIfAvailable(commands, TERMINAL_TOGGLE_COMMAND_ID);
                this.shell.expandPanel('bottom');
            }
        });
    }

    protected async executeIfAvailable(commands: CommandRegistry, commandId: string, ...args: unknown[]): Promise<boolean> {
        if (!commands.getCommand(commandId)) {
            return false;
        }
        try {
            await commands.executeCommand(commandId, ...args);
            return true;
        } catch {
            return false;
        }
    }
}
