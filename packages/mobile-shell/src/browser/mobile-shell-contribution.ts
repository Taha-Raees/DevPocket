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
import { Disposable, DisposableCollection } from '@theia/core/lib/common/disposable';
import { MessageLoop } from '@lumino/messaging';
import { Widget } from '@lumino/widgets';
import { MobileShellEmptyStateAction, MobileShellEmptyStateWidget } from './mobile-shell-empty-state-widget';
import { MobileShellFabItem, MobileShellFabWidget } from './mobile-shell-fab-widget';

const TERMINAL_TOGGLE_COMMAND_ID = 'workbench.action.terminal.toggleTerminal';
const OPEN_FOLDER_COMMAND_ID = 'workspace:open';
const OPEN_RECENT_COMMAND_IDS = ['workbench.action.openRecent', 'workspace:openRecent'];
const SHOW_EXPLORER_COMMAND_ID = 'workbench.view.explorer';

export namespace MobileShellCommands {
    export const SHOW_FILES: Command = {
        id: 'mobile.showFiles',
        label: nls.localizeByDefault('Mobile: Files')
    };
    export const SHOW_TERMINAL: Command = {
        id: 'mobile.showTerminal',
        label: nls.localizeByDefault('Mobile: Terminal')
    };
    export const OPEN_SETTINGS_MENU: Command = {
        id: 'mobile.openSettingsMenu',
        label: nls.localizeByDefault('Mobile: Settings Menu')
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
    protected fabWidget: MobileShellFabWidget | undefined;
    protected emptyStateWidget: MobileShellEmptyStateWidget | undefined;
    protected readonly fabItems = new Map<string, MobileShellFabItem>();

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

        this.mountFloatingFab();
        this.mountEmptyState();
        this.refreshFloatingFabItems();
        this.refreshEmptyState();
        this.watchFloatingFabSource();
        this.collapseLeftPanelWhenMainWidgetIsActive();
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
            filesIcon.className = 'codicon codicon-folder-library';
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

    protected mountFloatingFab(): void {
        if (this.fabWidget) {
            return;
        }

        const widget = new MobileShellFabWidget({
            onDidRequestItemActivation: item => {
                if (this.commandRegistry?.getCommand(item.id)) {
                    void this.commandRegistry.executeCommand(item.id);
                    return;
                }
                if (item.active) {
                    void this.shell.collapsePanel('left');
                    return;
                }
                void this.shell.activateWidget(item.id);
            }
        });

        this.fabWidget = widget;
        Widget.attach(widget, document.body);

        this.toDispose.push(Disposable.create(() => {
            if (widget.isAttached) {
                Widget.detach(widget);
            }
            widget.dispose();
            this.fabWidget = undefined;
        }));
    }

    protected mountEmptyState(): void {
        if (this.emptyStateWidget) {
            return;
        }

        const widget = new MobileShellEmptyStateWidget({
            onDidRequestAction: action => {
                void this.handleEmptyStateAction(action);
            }
        });

        this.emptyStateWidget = widget;
        Widget.attach(widget, document.body);

        this.toDispose.push(Disposable.create(() => {
            if (widget.isAttached) {
                Widget.detach(widget);
            }
            widget.dispose();
            this.emptyStateWidget = undefined;
        }));
    }

    protected watchFloatingFabSource(): void {
        const leftPanelHandler = this.shell.leftPanelHandler;
        if (!leftPanelHandler) {
            return;
        }

        const refresh = () => {
            this.refreshFloatingFabItems();
            this.refreshEmptyState();
        };

        leftPanelHandler.tabBar.tabAdded.connect(refresh, this);
        leftPanelHandler.tabBar.currentChanged.connect(refresh, this);
        leftPanelHandler.dockPanel.widgetAdded.connect(refresh, this);
        leftPanelHandler.dockPanel.widgetRemoved.connect(refresh, this);

        const refreshInterval = window.setInterval(refresh, 2000);

        this.toDispose.push(Disposable.create(() => {
            leftPanelHandler.tabBar.tabAdded.disconnect(refresh, this);
            leftPanelHandler.tabBar.currentChanged.disconnect(refresh, this);
            leftPanelHandler.dockPanel.widgetAdded.disconnect(refresh, this);
            leftPanelHandler.dockPanel.widgetRemoved.disconnect(refresh, this);
            window.clearInterval(refreshInterval);
        }));
    }

    protected collapseLeftPanelWhenMainWidgetIsActive(): void {
        const collapseIfNeeded = (): void => {
            const activeWidget = this.shell.activeWidget;
            if (!activeWidget) {
                return;
            }
            if (this.shell.getAreaFor(activeWidget) === 'main' && this.shell.isExpanded('left')) {
                void this.shell.collapsePanel('left').then(() => this.scheduleViewportRelayout());
                return;
            }
            if (this.shell.getAreaFor(activeWidget) === 'main') {
                this.scheduleViewportRelayout();
            }
        };

        this.shell.onDidChangeActiveWidget(() => {
            collapseIfNeeded();
            this.refreshFloatingFabItems();
            this.refreshEmptyState();
        });

        this.shell.mainPanel.widgetAdded.connect(() => {
            this.scheduleViewportRelayout();
            this.refreshEmptyState();
        }, this);
        this.shell.mainPanel.widgetRemoved.connect(() => {
            this.scheduleViewportRelayout();
            this.refreshEmptyState();
        }, this);
        this.shell.mainPanel.widgetActivated.connect(() => {
            this.scheduleViewportRelayout();
            this.refreshEmptyState();
        }, this);
        this.shell.bottomPanel.widgetAdded.connect(() => this.refreshEmptyState(), this);
        this.shell.bottomPanel.widgetRemoved.connect(() => this.refreshEmptyState(), this);

        this.toDispose.push(Disposable.create(() => {
            this.shell.mainPanel.widgetAdded.disconnect(() => this.scheduleViewportRelayout(), this);
            this.shell.mainPanel.widgetRemoved.disconnect(() => this.refreshEmptyState(), this);
            this.shell.mainPanel.widgetActivated.disconnect(() => this.scheduleViewportRelayout(), this);
            this.shell.bottomPanel.widgetAdded.disconnect(() => this.refreshEmptyState(), this);
            this.shell.bottomPanel.widgetRemoved.disconnect(() => this.refreshEmptyState(), this);
        }));
    }

    protected scheduleViewportRelayout(): void {
        const dispatch = () => window.dispatchEvent(new Event('resize'));
        const refreshEditors = () => this.refreshVisibleEditors();
        dispatch();
        refreshEditors();
        requestAnimationFrame(() => {
            dispatch();
            refreshEditors();
        });
        setTimeout(() => {
            dispatch();
            refreshEditors();
        }, 50);
        setTimeout(() => {
            dispatch();
            refreshEditors();
        }, 150);
        setTimeout(() => {
            dispatch();
            refreshEditors();
        }, 300);
    }

    protected applyMobileEditorOptions(editor: {
        getControl?: () => { updateOptions?: (options: object) => void };
        updateOptions?: (options: object) => void;
    }): void {
        const mobileOptions = {
            wordWrap: 'on',
            wordWrapOverride2: 'on',
            wrappingStrategy: 'advanced',
            wrappingIndent: 'same',
            lineNumbers: 'on',
            lineNumbersMinChars: 2,
            glyphMargin: false,
            lineDecorationsWidth: 0,
            overviewRulerLanes: 0,
            minimap: {
                enabled: false,
            },
            scrollBeyondLastColumn: 0,
            scrollbar: {
                horizontal: 'hidden',
                horizontalScrollbarSize: 0,
                useShadows: false,
            },
        };

        editor.updateOptions?.(mobileOptions);
        editor.getControl?.().updateOptions?.(mobileOptions);
    }

    protected refreshVisibleEditors(): void {
        const refreshFrom = (widgets: Iterable<unknown>) => {
            for (const candidate of widgets) {
                const widget = candidate as {
                    isVisible?: boolean;
                    node?: HTMLElement;
                    editor?: {
                        refresh?: () => void;
                        resizeToFit?: () => void;
                        getControl?: () => {
                            updateOptions?: (options: object) => void;
                            layout?: (dimension?: { width: number; height: number }) => void;
                        };
                        updateOptions?: (options: object) => void;
                    };
                };
                if (widget.node?.classList.contains('theia-editor')) {
                    if (widget.editor) {
                        this.applyMobileEditorOptions(widget.editor);
                        const control = widget.editor.getControl?.();
                        const width = Math.max(widget.node.clientWidth, Math.round(widget.node.getBoundingClientRect().width));
                        const height = Math.max(widget.node.clientHeight, Math.round(widget.node.getBoundingClientRect().height));
                        if (width > 0 && height > 0) {
                            control?.layout?.({ width, height });
                        }
                    }
                    widget.editor?.resizeToFit?.();
                    widget.editor?.refresh?.();
                    MessageLoop.sendMessage(widget as unknown as Widget, Widget.ResizeMessage.UnknownSize);
                }
            }
        };

        refreshFrom(this.shell.mainPanel.widgets());
        refreshFrom(this.shell.bottomPanel.widgets());
    }

    protected refreshFloatingFabItems(): void {
        const tabBar = this.shell.leftPanelHandler?.tabBar;
        if (!tabBar || !this.fabWidget) {
            return;
        }

        const currentWidgetId = tabBar.currentTitle?.owner.id;
        this.fabItems.clear();

        tabBar.titles.forEach((title, order) => {
            const widget = title.owner;
            if (!widget.id) {
                return;
            }

            const label = (title.caption || title.label || widget.id).trim();
            const iconClass = title.iconClass.trim() || 'codicon codicon-circle-large-filled';

            this.fabItems.set(widget.id, {
                id: widget.id,
                label,
                iconClass,
                order,
                active: widget.id === currentWidgetId,
            });
        });

        const bottomFabItems: MobileShellFabItem[] = [
            {
                id: MobileShellCommands.SHOW_FILES.id,
                label: 'File Actions',
                iconClass: 'codicon codicon-folder-library',
                order: 100,
            },
            {
                id: MobileShellCommands.SHOW_TERMINAL.id,
                label: 'Terminal',
                iconClass: 'codicon codicon-terminal',
                order: 101,
            },
            {
                id: MobileShellCommands.OPEN_SETTINGS_MENU.id,
                label: 'Manage',
                iconClass: 'codicon codicon-settings-gear',
                order: 102,
            }
        ];

        bottomFabItems.forEach(item => this.fabItems.set(item.id, item));

        this.fabWidget.setItems(this.fabItems.values());
    }

    protected hasVisibleWorkbenchContent(): boolean {
        const hasMainWidgets = Array.from(this.shell.mainPanel.widgets()).length > 0;
        const hasVisibleBottomWidgets = Array.from(this.shell.bottomPanel.widgets()).some(widget => widget.isVisible);
        const hasOpenSidebar = this.shell.isExpanded('left');
        return hasMainWidgets || hasVisibleBottomWidgets || hasOpenSidebar;
    }

    protected createEmptyStateActions(): MobileShellEmptyStateAction[] {
        const commands = this.commandRegistry;
        const kiloItem = Array.from(this.fabItems.values()).find(item => item.label.toLowerCase().includes('kilo'));

        return [
            {
                id: 'mobile.empty.kilo',
                label: 'Open Kilo Code',
                description: 'Jump straight into the AI assistant panel.',
                iconClass: kiloItem?.iconClass || 'codicon codicon-sparkle',
                disabled: !kiloItem,
            },
            {
                id: 'mobile.empty.folder',
                label: 'Open Folder',
                description: 'Pick a workspace folder to start working.',
                iconClass: 'codicon codicon-folder-opened',
                disabled: !commands?.getCommand(OPEN_FOLDER_COMMAND_ID),
            },
            {
                id: 'mobile.empty.explorer',
                label: 'Show Explorer',
                description: 'Browse files from the mobile file tree.',
                iconClass: 'codicon codicon-files',
                disabled: !commands?.getCommand(SHOW_EXPLORER_COMMAND_ID),
            },
            {
                id: 'mobile.empty.terminal',
                label: 'Open Terminal',
                description: 'Drop into the built-in Android terminal.',
                iconClass: 'codicon codicon-terminal',
                disabled: !commands?.getCommand(TERMINAL_TOGGLE_COMMAND_ID),
            },
            {
                id: 'mobile.empty.recent',
                label: 'Recents',
                description: 'Reopen a recent workspace or file entry.',
                iconClass: 'codicon codicon-history',
                disabled: !OPEN_RECENT_COMMAND_IDS.some(id => !!commands?.getCommand(id)),
            },
        ];
    }

    protected refreshEmptyState(): void {
        if (!this.emptyStateWidget) {
            return;
        }
        this.emptyStateWidget.setActions(this.createEmptyStateActions());
        this.emptyStateWidget.setVisible(!this.hasVisibleWorkbenchContent());
    }

    protected async handleEmptyStateAction(action: MobileShellEmptyStateAction): Promise<void> {
        const commands = this.commandRegistry;
        if (!commands) {
            return;
        }

        switch (action.id) {
            case 'mobile.empty.kilo': {
                const kiloItem = Array.from(this.fabItems.values()).find(item => item.label.toLowerCase().includes('kilo'));
                if (kiloItem) {
                    await this.shell.activateWidget(kiloItem.id);
                }
                break;
            }
            case 'mobile.empty.folder':
                await this.executeIfAvailable(commands, OPEN_FOLDER_COMMAND_ID);
                break;
            case 'mobile.empty.explorer':
                await this.executeIfAvailable(commands, SHOW_EXPLORER_COMMAND_ID);
                break;
            case 'mobile.empty.terminal':
                await this.executeIfAvailable(commands, TERMINAL_TOGGLE_COMMAND_ID);
                break;
            case 'mobile.empty.recent':
                for (const commandId of OPEN_RECENT_COMMAND_IDS) {
                    if (await this.executeIfAvailable(commands, commandId)) {
                        break;
                    }
                }
                break;
            default:
                break;
        }

        this.refreshEmptyState();
    }

    registerCommands(commands: CommandRegistry): void {
        this.commandRegistry = commands;
        this.refreshEmptyState();

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

        commands.registerCommand(MobileShellCommands.OPEN_SETTINGS_MENU, {
            execute: async () => {
                const settingsItem = document.querySelector('#theia-left-content-panel .theia-sidebar-menu .codicon-settings-gear')
                    ?.closest('.theia-sidebar-menu-item') as HTMLElement | null;
                if (settingsItem) {
                    settingsItem.click();
                    return;
                }
                await this.executeIfAvailable(commands, 'settings.open');
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
