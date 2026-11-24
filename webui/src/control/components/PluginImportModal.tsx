import { Button, Input, StatusBanner } from '@gojona/ui';
import { FileArchive, FolderUp, ShieldCheck, Upload, X } from 'lucide-react';
import { type ChangeEvent, useEffect, useId, useState } from 'react';
import type { PluginArchiveInspection, PluginCapability } from '../../shared/types.ts';
import type { PluginInspectArchiveInput, PluginInstallInput } from '../hooks/usePluginsControl.ts';
import { deriveInitialImportGrants } from '../pluginMeta.ts';
import { PluginImportReview } from './PluginImportReview.tsx';

export interface PluginImportModalProps {
  isBusy: boolean;
  onInspect: (input: PluginInspectArchiveInput) => Promise<PluginArchiveInspection>;
  onInstall: (input: PluginInstallInput) => Promise<boolean>;
  onClose: () => void;
}

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('无法读取本地插件文件'));
    reader.onload = () => {
      const result = typeof reader.result === 'string' ? reader.result : '';
      const comma = result.indexOf(',');
      resolve(comma >= 0 ? result.slice(comma + 1) : result);
    };
    reader.readAsDataURL(file);
  });
}

export function PluginImportModal({
  isBusy,
  onInspect,
  onInstall,
  onClose,
}: PluginImportModalProps) {
  const titleId = useId();
  const [devicePath, setDevicePath] = useState('');
  const [selectedFile, setSelectedFile] = useState<{ name: string; base64: string } | null>(null);
  const [inspection, setInspection] = useState<PluginArchiveInspection | null>(null);
  const [grantedCaps, setGrantedCaps] = useState<PluginCapability[]>([]);
  const [approveDaemon, setApproveDaemon] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const handleFileChange = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    setErrorMsg(null);
    try {
      const base64 = await readFileAsBase64(file);
      setSelectedFile({ name: file.name, base64 });
      setInspection(null);
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err));
    }
  };

  const handleInspect = async () => {
    setErrorMsg(null);
    const input: PluginInspectArchiveInput = selectedFile
      ? { archiveBase64: selectedFile.base64, filename: selectedFile.name }
      : { path: devicePath.trim() };
    if (!input.archiveBase64 && !input.path) {
      setErrorMsg('请选择 .svp/.zip 插件包或填写设备路径');
      return;
    }
    try {
      const result = await onInspect(input);
      setInspection(result);
      const initial = deriveInitialImportGrants(result);
      setGrantedCaps(initial.grantedCapabilities);
      setApproveDaemon(initial.approveDaemon);
    } catch (err) {
      setErrorMsg(err instanceof Error ? err.message : String(err));
    }
  };

  const handleInstall = async () => {
    if (!inspection) return;
    setErrorMsg(null);
    const input: PluginInstallInput = selectedFile
      ? {
          archiveBase64: selectedFile.base64,
          filename: selectedFile.name,
          grantedCapabilities: grantedCaps,
          approveDaemon,
        }
      : {
          sourcePath: devicePath.trim(),
          grantedCapabilities: grantedCaps,
          approveDaemon,
        };
    const ok = await onInstall(input);
    if (ok) onClose();
  };

  const toggleCap = (cap: PluginCapability, checked: boolean) => {
    setGrantedCaps((prev) =>
      checked ? Array.from(new Set([...prev, cap])) : prev.filter((item) => item !== cap),
    );
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-[color-mix(in_oklab,black_45%,transparent)] p-3 sm:items-center"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
    >
      <div className="flex max-h-[88dvh] w-full max-w-xl flex-col overflow-hidden rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--surface-card)] shadow-xl">
        <div className="flex items-center justify-between gap-2 border-b border-[var(--border-hairline)] px-4 py-3.5">
          <div className="flex items-center gap-2">
            <FolderUp className="size-4 text-[var(--accent)]" />
            <h2 id={titleId} className="text-sm font-semibold text-[var(--text-primary)]">
              导入第三方插件 (.svp / 目录)
            </h2>
          </div>
          <Button size="icon-sm" variant="ghost" aria-label="关闭导入窗口" onClick={onClose}>
            <X className="size-4" />
          </Button>
        </div>

        <div className="flex-1 space-y-3 overflow-y-auto p-4">
          {errorMsg ? (
            <StatusBanner tone="danger" title="解析或安装失败" description={errorMsg} />
          ) : null}

          <div className="flex flex-col gap-2.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3">
            <label className="flex cursor-pointer items-center justify-between gap-2 text-xs">
              <span className="inline-flex items-center gap-1.5 font-medium text-[var(--text-primary)]">
                <FileArchive className="size-3.5 text-[var(--accent)]" />
                {selectedFile ? `已选择：${selectedFile.name}` : '从本地选择 .svp / .zip 归档'}
              </span>
              <input
                type="file"
                accept=".svp,.zip"
                aria-label="选择本地插件压缩包"
                className="max-w-44 text-[11px] text-[var(--text-secondary)]"
                onChange={(e) => void handleFileChange(e)}
              />
            </label>

            <div className="flex items-center gap-2">
              <Input
                size="sm"
                className="flex-1 font-mono"
                value={devicePath}
                onChange={(e) => {
                  setDevicePath(e.target.value);
                  if (e.target.value.trim()) setSelectedFile(null);
                  setInspection(null);
                }}
                placeholder="或输入设备路径，如 /sdcard/Download/plugin.svp"
                aria-label="输入设备插件路径"
              />
              <Button
                size="sm"
                variant="secondary"
                aria-label="解析插件清单"
                disabled={isBusy || (!selectedFile && !devicePath.trim())}
                onClick={() => void handleInspect()}
              >
                <Upload className="size-3.5" />
                解析清单
              </Button>
            </div>
          </div>

          {inspection ? (
            <PluginImportReview
              inspection={inspection}
              grantedCaps={grantedCaps}
              approveDaemon={approveDaemon}
              onToggleCap={toggleCap}
              onToggleDaemon={setApproveDaemon}
            />
          ) : null}
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-4 py-3">
          <Button size="sm" variant="ghost" aria-label="取消导入插件" onClick={onClose}>
            取消
          </Button>
          <Button
            size="sm"
            variant="primary"
            aria-label="确认安装插件"
            disabled={isBusy || !inspection}
            onClick={() => void handleInstall()}
          >
            <ShieldCheck className="size-3.5" />
            {isBusy ? '安装中...' : '确认安装'}
          </Button>
        </div>
      </div>
    </div>
  );
}
