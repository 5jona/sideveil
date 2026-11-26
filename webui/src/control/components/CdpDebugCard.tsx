import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  SettingRow,
  Switch,
} from '@gojona/ui';
import { AlertTriangle, Bug, Check, Copy } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import type { DaemonStatus, DebugCdpStatusResult, SideVeilConfig } from '../../shared/types.ts';

export interface CdpDebugCardProps {
  config: SideVeilConfig;
  status: DaemonStatus | null;
  cdpStatus: DebugCdpStatusResult | null;
  busy: boolean;
  onSetWebViewDebugging: (enabled: boolean) => void;
}

async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // Fall back to execCommand when clipboard API is unavailable.
  }
  try {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.setAttribute('readonly', '');
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(textarea);
    return ok;
  } catch {
    return false;
  }
}

export function CdpDebugCard({
  config,
  status,
  cdpStatus,
  busy,
  onSetWebViewDebugging,
}: CdpDebugCardProps) {
  const [copied, setCopied] = useState(false);
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current);
      }
    };
  }, []);

  const webviewDebuggingEnabled = Boolean(
    cdpStatus?.webviewDebuggingEnabled ?? config.debug?.webviewDebuggingEnabled,
  );
  const devServerActive = Boolean(cdpStatus?.devServerActive);
  const effectiveEnabled = Boolean(
    cdpStatus?.effectiveEnabled ?? (webviewDebuggingEnabled || devServerActive),
  );
  const hostPid = cdpStatus?.hostPid ?? status?.hostPid ?? null;
  const activeCdpSocket = effectiveEnabled
    ? (cdpStatus?.cdpSocket ?? (hostPid !== null ? `webview_devtools_remote_${hostPid}` : null))
    : null;
  const cdpCommand =
    hostPid !== null
      ? `strata-debug cdp forward --port 9222 --pid ${hostPid}`
      : (activeCdpSocket ?? '');

  const handleCopyCommand = async () => {
    if (!cdpCommand) return;
    const ok = await copyText(cdpCommand);
    if (!ok) return;
    setCopied(true);
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
    }
    timerRef.current = window.setTimeout(() => {
      setCopied(false);
    }, 1500);
  };

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <div className="flex flex-col gap-0.5">
          <CardTitle className="flex items-center gap-1.5">
            <Bug className="size-4 text-[var(--accent)]" />
            WebView CDP 远程调试
          </CardTitle>
          <CardDescription>开启后可通过 Chrome DevTools 附加到 Host WebView 进程</CardDescription>
        </div>
        <div className="flex flex-wrap items-center gap-1.5">
          <Badge variant={effectiveEnabled ? 'success' : 'neutral'} dot>
            {effectiveEnabled ? 'CDP 已生效' : '未开启'}
          </Badge>
          {devServerActive && !webviewDebuggingEnabled ? (
            <Badge variant="accent">DevServer 自动开启</Badge>
          ) : null}
        </div>
      </CardHeader>

      <CardContent className="flex flex-col gap-3">
        <SettingRow
          variant="plain"
          title="启用 WebView 调试 (CDP)"
          description="仅建议在开发排障时开启，关闭后可按需重启 Host 释放调试会话"
          control={
            <Switch
              checked={webviewDebuggingEnabled}
              disabled={busy}
              onCheckedChange={onSetWebViewDebugging}
              aria-label="启用 WebView CDP 远程调试"
            />
          }
        />

        {activeCdpSocket ? (
          <div className="flex flex-col gap-2 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-3 py-2 text-xs">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="text-[var(--text-secondary)]">活跃 CDP Socket</span>
              <code className="font-mono text-[var(--text-primary)]">{activeCdpSocket}</code>
            </div>
            {cdpCommand ? (
              <div className="flex flex-wrap items-center justify-between gap-2 border-t border-[var(--border-hairline)] pt-2">
                <code className="truncate font-mono text-[11px] text-[var(--text-secondary)]">
                  {cdpCommand}
                </code>
                <Button
                  size="sm"
                  variant="secondary"
                  aria-label="复制 CDP 连接命令"
                  onClick={() => void handleCopyCommand()}
                >
                  {copied ? (
                    <>
                      <Check className="size-3.5 text-[var(--success)]" />
                      已复制
                    </>
                  ) : (
                    <>
                      <Copy className="size-3.5" />
                      复制命令
                    </>
                  )}
                </Button>
              </div>
            ) : null}
          </div>
        ) : null}

        {cdpStatus?.restartRecommended ? (
          <div className="flex items-center gap-2 rounded-[var(--radius-control)] border border-[color-mix(in_oklab,var(--warning)_35%,transparent)] bg-[color-mix(in_oklab,var(--warning)_12%,transparent)] px-3 py-2 text-xs text-[var(--text-primary)]">
            <AlertTriangle className="size-4 shrink-0 text-[var(--warning)]" />
            <span>
              已关闭 WebView 调试，建议点击上方「重启 Host 服务」以彻底断开已存在的 DevTools
              套接字。
            </span>
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}
