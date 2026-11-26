import type { PanelState } from '../../shared/types.ts';

export interface PanelToggleDescriptor {
  isOpen: boolean;
  action: 'open' | 'close';
  label: '弹出侧边栏' | '收起侧边栏';
  targetState: 'open' | 'closed';
}

export interface PendingPanelTransition {
  targetState: 'open' | 'closed';
  expiresAtMs: number;
}

export const PANEL_TRANSITION_GRACE_MS = 1000;

/**
 * Single source of truth for the header sidebar toggle button label,
 * dispatched `sideveil-ctl` command, toast copy, and target panel state.
 */
export function resolvePanelToggle(
  panelState: PanelState | null | undefined,
): PanelToggleDescriptor {
  if (panelState === 'open') {
    return {
      isOpen: true,
      action: 'close',
      label: '收起侧边栏',
      targetState: 'closed',
    };
  }
  return {
    isOpen: false,
    action: 'open',
    label: '弹出侧边栏',
    targetState: 'open',
  };
}

/**
 * Reconciles a polled `panelState` against a recent optimistic `open`/`close`
 * transition so an immediate status read cannot revert the button before Host
 * delivers `panel.stateChanged`.
 */
export function reconcilePolledPanelState(
  polledState: PanelState,
  pending: PendingPanelTransition | null,
  nowMs: number,
): { panelState: PanelState; nextPending: PendingPanelTransition | null } {
  if (!pending || nowMs >= pending.expiresAtMs) {
    return { panelState: polledState, nextPending: null };
  }
  if (polledState === pending.targetState) {
    return { panelState: polledState, nextPending: null };
  }
  return { panelState: pending.targetState, nextPending: pending };
}
