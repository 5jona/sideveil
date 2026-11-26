import assert from 'node:assert/strict';
import test from 'node:test';
import {
  PANEL_TRANSITION_GRACE_MS,
  reconcilePolledPanelState,
  resolvePanelToggle,
} from '../src/control/hooks/panelToggle.ts';
import { handleMockControlExec } from '../src/control/mockControlBridge.ts';

test('resolvePanelToggle keeps button label, command, and targetState aligned', () => {
  assert.deepEqual(resolvePanelToggle('closed'), {
    isOpen: false,
    action: 'open',
    label: '弹出侧边栏',
    targetState: 'open',
  });
  assert.deepEqual(resolvePanelToggle(undefined), {
    isOpen: false,
    action: 'open',
    label: '弹出侧边栏',
    targetState: 'open',
  });
  assert.deepEqual(resolvePanelToggle('open'), {
    isOpen: true,
    action: 'close',
    label: '收起侧边栏',
    targetState: 'closed',
  });
});

test('reconcilePolledPanelState preserves optimistic target during grace window until daemon catches up', () => {
  const t0 = 10_000;
  const pendingOpen = { targetState: 'open', expiresAtMs: t0 + PANEL_TRANSITION_GRACE_MS };

  // Immediate poll still sees 'closed' before Host emits panel.stateChanged
  const stalePoll = reconcilePolledPanelState('closed', pendingOpen, t0 + 120);
  assert.equal(stalePoll.panelState, 'open');
  assert.deepEqual(stalePoll.nextPending, pendingOpen);

  // Daemon catches up and reports 'open' -> clears pending transition
  const caughtUp = reconcilePolledPanelState('open', pendingOpen, t0 + 350);
  assert.equal(caughtUp.panelState, 'open');
  assert.equal(caughtUp.nextPending, null);

  // After grace window expires, daemon status is authoritative again
  const expired = reconcilePolledPanelState('closed', pendingOpen, t0 + PANEL_TRANSITION_GRACE_MS);
  assert.equal(expired.panelState, 'closed');
  assert.equal(expired.nextPending, null);
});

test('mockControlBridge transitions panelState cleanly across open and close commands', async () => {
  const openRes = await handleMockControlExec('sideveil-ctl open');
  assert.equal(openRes.errno, 0);
  const afterOpen = JSON.parse((await handleMockControlExec('sideveil-ctl status')).stdout);
  assert.equal(afterOpen.panelState, 'open');
  assert.equal(resolvePanelToggle(afterOpen.panelState).label, '收起侧边栏');

  const closeRes = await handleMockControlExec('sideveil-ctl close');
  assert.equal(closeRes.errno, 0);
  const afterClose = JSON.parse((await handleMockControlExec('sideveil-ctl status')).stdout);
  assert.equal(afterClose.panelState, 'closed');
  assert.equal(resolvePanelToggle(afterClose.panelState).label, '弹出侧边栏');
});
