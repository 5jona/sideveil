import { Button, Input } from '@pochita/ui';
import { Plus, X } from 'lucide-react';
import { type ReactNode, useState } from 'react';

export interface PackageScopeListProps {
  title: string;
  description: string;
  emptyText?: string;
  packages: readonly string[];
  inputId: string;
  inputName: string;
  inputPlaceholder: string;
  inputAriaLabel: string;
  removeAriaPrefix: string;
  submitLabel?: string;
  headerAction?: ReactNode;
  onAddPackage: (pkg: string) => void;
  onRemovePackage: (pkg: string) => void;
}

export function PackageScopeList({
  title,
  description,
  emptyText,
  packages,
  inputId,
  inputName,
  inputPlaceholder,
  inputAriaLabel,
  removeAriaPrefix,
  submitLabel = '添加',
  headerAction,
  onAddPackage,
  onRemovePackage,
}: PackageScopeListProps) {
  const [inputValue, setInputValue] = useState('');

  const handleSubmit = (pkgRaw: string) => {
    const trimmed = pkgRaw.trim();
    if (!trimmed) return;
    onAddPackage(trimmed);
    setInputValue('');
  };

  return (
    <div className="pochita-card-elevated space-y-2.5 p-3.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-xs font-medium text-[var(--text-primary)]">
            {title}（{packages.length}）
          </div>
          <p className="text-[11px] text-[var(--text-secondary)]">{description}</p>
        </div>
        {headerAction}
      </div>

      <div className="flex flex-wrap gap-1.5">
        {packages.length === 0 && emptyText ? (
          <span className="text-xs text-[var(--text-secondary)]">{emptyText}</span>
        ) : (
          packages.map((pkg) => (
            <span
              key={pkg}
              className="inline-flex items-center gap-1 rounded-[var(--radius-pill)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1 text-xs text-[var(--text-primary)]"
            >
              <span className="font-mono text-[11px]">{pkg}</span>
              <button
                type="button"
                onClick={() => onRemovePackage(pkg)}
                aria-label={`${removeAriaPrefix} ${pkg}`}
                className="cursor-pointer rounded-full p-0.5 text-[var(--text-secondary)] hover:bg-[var(--surface-elevated)] hover:text-[var(--danger)]"
              >
                <X className="size-3" />
              </button>
            </span>
          ))
        )}
      </div>

      <form
        className="flex items-center gap-2 pt-1"
        onSubmit={(ev) => {
          ev.preventDefault();
          handleSubmit(inputValue);
        }}
      >
        <Input
          id={inputId}
          name={inputName}
          size="sm"
          className="flex-1"
          value={inputValue}
          onChange={(ev) => setInputValue(ev.target.value)}
          placeholder={inputPlaceholder}
          aria-label={inputAriaLabel}
        />
        <Button size="sm" type="submit" variant="secondary" disabled={!inputValue.trim()}>
          <Plus className="size-3.5" />
          {submitLabel}
        </Button>
      </form>
    </div>
  );
}
