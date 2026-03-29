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

import '../../src/browser/style/mobile-shell-fab.css';

export interface MobileShellFabItem {
    id: string;
    label: string;
    iconClass: string;
    order: number;
    active?: boolean;
    hidden?: boolean;
}

export interface MobileShellFabWidgetOptions {
    onDidRequestItemActivation: (item: MobileShellFabItem) => void;
}

export class MobileShellFabWidget extends ReactWidget {
    protected readonly options: MobileShellFabWidgetOptions;
    protected readonly itemMap = new Map<string, MobileShellFabItem>();
    protected expanded = false;

    protected dragState: {
        isDragging: boolean;
        startX: number;
        startY: number;
        startLeft: number;
        startTop: number;
        dragTimer: ReturnType<typeof setTimeout> | null;
    } | null = null;

    protected userPosition: { left: number; top: number } | null = null;

    constructor(options: MobileShellFabWidgetOptions) {
        super();
        this.options = options;
        this.id = 'theia-mobile-shell-fab';
        this.addClass('theia-mobile-shell-fab-widget');
        this.restoreSavedPosition();
    }

    protected restoreSavedPosition(): void {
        try {
            const saved = localStorage.getItem('devpocket.fab.position');
            if (saved) {
                this.userPosition = JSON.parse(saved);
            }
        } catch { /* ignore */ }
    }

    getUserPosition(): { left: number; top: number } | null {
        return this.userPosition;
    }

    setPosition(left: number, top: number): void {
        this.userPosition = { left, top };
        this.node.style.left = `${left}px`;
        this.node.style.top = `${top}px`;
        this.node.style.right = 'auto';
        this.node.style.bottom = 'auto';
    }

    protected clampToViewport(left: number, top: number): { left: number; top: number } {
        const vv = window.visualViewport;
        const vw = vv?.width ?? window.innerWidth;
        const vh = vv?.height ?? window.innerHeight;
        const ox = vv?.offsetLeft ?? 0;
        const oy = vv?.offsetTop ?? 0;
        const w = 64;
        const h = 64;
        return {
            left: Math.max(ox, Math.min(ox + vw - w, left)),
            top: Math.max(oy, Math.min(oy + vh - h, top)),
        };
    }

    protected readonly onFabTouchStart = (e: React.TouchEvent): void => {
        if (e.touches.length !== 1) {
            return;
        }
        const touch = e.touches[0];
        const node = this.node;
        this.dragState = {
            isDragging: false,
            startX: touch.clientX,
            startY: touch.clientY,
            startLeft: node.offsetLeft,
            startTop: node.offsetTop,
            dragTimer: null,
        };

        const onTouchMove = (ev: TouchEvent) => {
            if (!this.dragState || ev.touches.length !== 1) {
                return;
            }
            const t = ev.touches[0];
            const dx = t.clientX - this.dragState.startX;
            const dy = t.clientY - this.dragState.startY;
            if (!this.dragState.isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                this.dragState.isDragging = true;
                this.addClass('dragging');
            }
            if (this.dragState.isDragging) {
                ev.preventDefault();
                const newLeft = this.dragState.startLeft + dx;
                const newTop = this.dragState.startTop + dy;
                const clamped = this.clampToViewport(newLeft, newTop);
                node.style.left = `${clamped.left}px`;
                node.style.top = `${clamped.top}px`;
                node.style.right = 'auto';
                node.style.bottom = 'auto';
            }
        };

        const onTouchEnd = () => {
            document.removeEventListener('touchmove', onTouchMove);
            document.removeEventListener('touchend', onTouchEnd);
            this.removeClass('dragging');
            if (this.dragState?.isDragging) {
                const left = node.offsetLeft;
                const top = node.offsetTop;
                this.userPosition = { left, top };
                try {
                    localStorage.setItem('devpocket.fab.position', JSON.stringify(this.userPosition));
                } catch { /* ignore */ }
            } else {
                this.toggleExpanded();
            }
            this.dragState = null;
        };

        document.addEventListener('touchmove', onTouchMove, { passive: false });
        document.addEventListener('touchend', onTouchEnd);
    };

    setItems(items: Iterable<MobileShellFabItem>): void {
        this.itemMap.clear();
        for (const item of items) {
            this.itemMap.set(item.id, item);
        }
        this.update();
    }

    upsertItem(item: MobileShellFabItem): void {
        this.itemMap.set(item.id, item);
        this.update();
    }

    removeItem(id: string): void {
        if (this.itemMap.delete(id)) {
            this.update();
        }
    }

    clearItems(): void {
        if (this.itemMap.size > 0) {
            this.itemMap.clear();
            this.update();
        }
    }

    protected readonly toggleExpanded = (): void => {
        this.expanded = !this.expanded;
        this.update();
    };

    protected readonly activateItem = (item: MobileShellFabItem): void => {
        this.options.onDidRequestItemActivation(item);
        this.expanded = false;
        this.update();
    };

    protected computeQuarterCirclePosition(index: number, count: number, radius: number): { x: number; y: number } {
        const startAngle = Math.PI;
        const endAngle = Math.PI * 1.5;
        const angle = count <= 1
            ? (startAngle + endAngle) / 2
            : startAngle + (index / (count - 1)) * (endAngle - startAngle);
        return {
            x: Math.cos(angle) * radius,
            y: Math.sin(angle) * radius
        };
    }

    protected renderLayer(items: MobileShellFabItem[], radius: number, layerClass: 'inner' | 'outer'): React.ReactNode {
        return items.map((item, index) => {
            const position = this.computeQuarterCirclePosition(index, items.length, radius);
            const style: React.CSSProperties = {
                transform: this.expanded
                    ? `translate(${position.x}px, ${position.y}px)`
                    : 'translate(0px, 0px)',
                opacity: this.expanded ? 1 : 0,
                pointerEvents: this.expanded ? 'auto' : 'none'
            };

            const iconClass = item.iconClass.trim() || codicon('circle-large-filled');
            const className = [
                'theia-mobile-fab-item',
                `theia-mobile-fab-item-${layerClass}`,
                item.active ? 'theia-mobile-fab-item-active' : ''
            ].filter(Boolean).join(' ');

            return <button
                key={item.id}
                className={className}
                style={style}
                title={item.label}
                aria-label={item.label}
                onClick={() => this.activateItem(item)}>
                <i className={iconClass}></i>
            </button>;
        });
    }

    protected override render(): React.ReactNode {
        const items = Array.from(this.itemMap.values())
            .filter(item => !item.hidden)
            .sort((a, b) => a.order - b.order);

        const innerLayerItems = items.slice(0, 6);
        const outerLayerItems = items.slice(6);

        return <div className={`theia-mobile-fab-root ${this.expanded ? 'theia-open' : 'theia-closed'}`}>
            {this.renderLayer(innerLayerItems, 108, 'inner')}
            {this.renderLayer(outerLayerItems, 168, 'outer')}

            <button
                className='theia-mobile-fab-main'
                aria-label='Toggle floating activity menu'
                title='Floating Activity Menu'
                onTouchStart={this.onFabTouchStart}
                onClick={this.toggleExpanded}>
                <span className='theia-mobile-fab-main-icon'></span>
            </button>
        </div>;
    }
}

