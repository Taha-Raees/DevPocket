/**
 * Phase 9: Package Management UX
 * Quick install buttons for common developer tools
 * Frontend component that bridges UI to apt package management
 */

import { inject, injectable } from '@theia/core/shared/inversify';
import { Command, CommandContribution, CommandRegistry, MessageService, MAIN_MENU_BAR } from '@theia/core';
import { MenuContribution, MenuModelRegistry } from '@theia/core/lib/common/menu';
import { TerminalService } from '@theia/terminal/lib/browser/base/terminal-service';
import { TerminalWidget } from '@theia/terminal/lib/browser/base/terminal-widget';

export namespace PackageManagerCommands {
    export const QUICK_INSTALL_GIT: Command = {
        id: 'devpocket.quickInstall.git',
        label: 'Install: git',
        category: 'DevPocket',
    };

    export const QUICK_INSTALL_PYTHON: Command = {
        id: 'devpocket.quickInstall.python',
        label: 'Install: Python 3',
        category: 'DevPocket',
    };

    export const QUICK_INSTALL_NODE: Command = {
        id: 'devpocket.quickInstall.node',
        label: 'Install: Node.js',
        category: 'DevPocket',
    };

    export const QUICK_INSTALL_RUST: Command = {
        id: 'devpocket.quickInstall.rust',
        label: 'Install: Rust',
        category: 'DevPocket',
    };

    export const OPEN_PACKAGE_MANAGER: Command = {
        id: 'devpocket.packageManager.open',
        label: 'Package Manager',
        category: 'DevPocket',
    };
}

const PACKAGE_INSTALL_COMMANDS: Record<string, string> = {
    git: 'sudo apt update && sudo apt install -y git',
    python3: 'sudo apt update && sudo apt install -y python3 python3-pip python3-venv',
    nodejs: 'sudo apt update && sudo apt install -y nodejs npm',
    rust: 'sudo apt update && sudo apt install -y rustc cargo'
};

const TOOLS_MENU = [...MAIN_MENU_BAR, '8_devpocket-tools'];

@injectable()
export class PackageManagerContribution implements CommandContribution, MenuContribution {
    @inject(TerminalService) protected readonly terminalService: TerminalService;
    @inject(MessageService) protected readonly messageService: MessageService;

    registerCommands(registry: CommandRegistry): void {
        registry.registerCommand(PackageManagerCommands.QUICK_INSTALL_GIT, {
            execute: () => this.quickInstall('git'),
        });

        registry.registerCommand(PackageManagerCommands.QUICK_INSTALL_PYTHON, {
            execute: () => this.quickInstall('python3'),
        });

        registry.registerCommand(PackageManagerCommands.QUICK_INSTALL_NODE, {
            execute: () => this.quickInstall('nodejs'),
        });

        registry.registerCommand(PackageManagerCommands.QUICK_INSTALL_RUST, {
            execute: () => this.quickInstall('rust'),
        });

        registry.registerCommand(PackageManagerCommands.OPEN_PACKAGE_MANAGER, {
            execute: () => this.openPackageManager(),
        });
    }

    registerMenus(menus: MenuModelRegistry): void {
        menus.registerSubmenu(TOOLS_MENU, 'DevPocket Tools');

        menus.registerMenuAction(TOOLS_MENU, {
            commandId: PackageManagerCommands.OPEN_PACKAGE_MANAGER.id,
            order: '1',
        });

        menus.registerMenuAction(TOOLS_MENU, {
            commandId: PackageManagerCommands.QUICK_INSTALL_GIT.id,
            order: '2',
        });

        menus.registerMenuAction(TOOLS_MENU, {
            commandId: PackageManagerCommands.QUICK_INSTALL_PYTHON.id,
            order: '3',
        });

        menus.registerMenuAction(TOOLS_MENU, {
            commandId: PackageManagerCommands.QUICK_INSTALL_NODE.id,
            order: '4',
        });

        menus.registerMenuAction(TOOLS_MENU, {
            commandId: PackageManagerCommands.QUICK_INSTALL_RUST.id,
            order: '5',
        });
    }

    private async quickInstall(packageName: string): Promise<void> {
        try {
            const terminal = await this.getOrCreateTerminal();
            const installCommand = PACKAGE_INSTALL_COMMANDS[packageName] ?? `sudo apt update && sudo apt install -y ${packageName}`;
            terminal.sendText(`${installCommand}\n`);
            this.messageService.info(`Started terminal install for ${packageName}.`);
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            this.messageService.error(`Failed to start package installation for ${packageName}: ${message}`);
        }
    }

    private async openPackageManager(): Promise<void> {
        const terminal = await this.getOrCreateTerminal();
        terminal.sendText('echo "Use apt to install tools, for example: sudo apt install -y git python3 nodejs npm rustc cargo"\n');
        this.messageService.info('Opened terminal with Debian package manager help.');
    }

    protected async getOrCreateTerminal(): Promise<TerminalWidget> {
        const existing = this.terminalService.currentTerminal ?? this.terminalService.lastUsedTerminal;
        if (existing) {
            this.terminalService.open(existing);
            return existing;
        }

        const terminal = await this.terminalService.newTerminal({ title: 'Debian Package Manager' });
        await terminal.start();
        this.terminalService.open(terminal);
        return terminal;
    }
}
