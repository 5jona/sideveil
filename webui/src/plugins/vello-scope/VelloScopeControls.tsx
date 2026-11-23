import type { ContainerBackdrop } from '@gojona/bridge';
import { Button, Card, cn, SettingRow, Switch } from '@gojona/ui';
import { Check, Layers, PanelRight, Pin, Sparkles } from 'lucide-react';

const BACKDROP_OPTIONS: ReadonlyArray<{
  value: ContainerBackdrop;
  label: string;
  icon: typeof Sparkles;
}> = [
  { value: 'native-glass', label: '原生毛玻璃', icon: Sparkles },
  { value: 'transparent', label: '透明穿透', icon: Layers },
];

export interface VelloScopeControlsProps {
  isPinned: boolean;
  backdrop: ContainerBackdrop;
  clickThrough: boolean;
  onTogglePin: () => void;
  onSelectBackdrop: (next: ContainerBackdrop) => void;
  onToggleClickThrough: (enabled: boolean) => void;
}

export function VelloScopeControls({
  isPinned,
  backdrop,
  clickThrough,
  onTogglePin,
  onSelectBackdrop,
  onToggleClickThrough,
}: VelloScopeControlsProps) {
  return (
    <Card className="flex flex-col gap-2 p-2.5">
      <Button
        size="sm"
        variant={isPinned ? 'secondary' : 'primary'}
        className="w-full"
        aria-label={isPinned ? '返回侧栏' : '钉选悬浮'}
        onClick={onTogglePin}
      >
        {isPinned ? <PanelRight className="size-3.5" /> : <Pin className="size-3.5" />}
        {isPinned ? '返回侧栏' : '钉选悬浮'}
      </Button>

      <fieldset className="m-0 grid grid-cols-2 gap-1.5 border-0 p-0" aria-label="窗口底板材质">
        {BACKDROP_OPTIONS.map((opt) => {
          const Icon = opt.icon;
          const active = backdrop === opt.value;
          return (
            <button
              key={opt.value}
              type="button"
              aria-pressed={active}
              onClick={() => onSelectBackdrop(opt.value)}
              className={cn(
                'pochita-switcher-pill sideveil-backdrop-pill flex cursor-pointer items-center justify-between gap-1 rounded-[var(--radius-control)] px-2.5 py-1.5 text-left text-xs font-medium transition-all duration-150 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--accent)]',
                active
                  ? 'pochita-switcher-pill-active bg-[var(--accent-soft)] text-[var(--text-primary)] ring-1 ring-inset ring-[color-mix(in_oklab,var(--accent)_45%,transparent)]'
                  : 'bg-[var(--surface-elevated)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]',
              )}
            >
              <span className="flex min-w-0 items-center gap-1">
                <Icon className="size-3.5 shrink-0" />
                <span className="sideveil-backdrop-label truncate">{opt.label}</span>
              </span>
              {active ? (
                <span className="inline-flex size-3.5 shrink-0 items-center justify-center rounded-full bg-[var(--accent)] text-[var(--accent-fg)]">
                  <Check className="size-2" />
                </span>
              ) : null}
            </button>
          );
        })}
      </fieldset>

      {isPinned ? (
        <>
          <div className="pochita-divider" />
          <SettingRow
            variant="plain"
            title="点击穿透"
            description="开启后由底部“解除穿透”胶囊恢复"
            control={
              <Switch
                checked={clickThrough}
                onCheckedChange={onToggleClickThrough}
                aria-label="点击穿透"
              />
            }
          />
        </>
      ) : null}
    </Card>
  );
}
