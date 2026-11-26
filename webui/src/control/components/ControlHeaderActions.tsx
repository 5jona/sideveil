import { Badge, Button } from '@gojona/ui';
import { PanelRightClose, PanelRightOpen } from 'lucide-react';
import type { CtlCommand, DaemonStatus } from '../../shared/types.ts';
import { resolvePanelToggle } from '../hooks/panelToggle.ts';

export interface ControlHeaderActionsProps {
  status: DaemonStatus | null;
  daemonOfflineError: string | null;
  saving: boolean;
  envLabel: string;
  busyAction: string | null;
  onRunCtl: (action: CtlCommand, label: string) => void;
}

/**
 * Top-bar status badges and sidebar open/close toggle button derived from a single
 * `resolvePanelToggle` descriptor so the label, command, and toast text stay aligned.
 */
export function ControlHeaderActions({
  status,
  daemonOfflineError,
  saving,
  envLabel,
  busyAction,
  onRunCtl,
}: ControlHeaderActionsProps) {
  const panelToggle = resolvePanelToggle(status?.panelState);

  return (
    <>
      <Badge
        variant={status?.hostConnected ? 'success' : daemonOfflineError ? 'danger' : 'warning'}
        dot
        pulse={status?.hostConnected}
        className="shrink-0 whitespace-nowrap"
      >
        {status?.hostConnected ? 'Host 已连接' : daemonOfflineError ? 'Daemon 离线' : '等待 Host'}
      </Badge>
      <Badge variant="neutral" className="shrink-0 whitespace-nowrap">
        {saving ? '正在写入...' : envLabel}
      </Badge>
      <Button
        size="sm"
        variant={panelToggle.isOpen ? 'secondary' : 'primary'}
        className="shrink-0 whitespace-nowrap"
        disabled={busyAction !== null}
        aria-label={panelToggle.label}
        onClick={() => onRunCtl(panelToggle.action, panelToggle.label)}
      >
        {panelToggle.isOpen ? (
          <PanelRightClose className="size-3.5" />
        ) : (
          <PanelRightOpen className="size-3.5" />
        )}
        {panelToggle.label}
      </Button>
    </>
  );
}
