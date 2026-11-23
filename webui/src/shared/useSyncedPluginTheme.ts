import { useSyncedPluginTheme as useSdkSyncedPluginTheme } from '@gojona/sideveil-sdk';
import {
  type ColorMode,
  isValidThemeId,
  type ResolvedColorScheme,
  type ThemeId,
  useTheme,
} from '@gojona/ui';
import {
  type ConfigUpdatedEventPayload,
  normalizeSideVeilConfig,
  type SideVeilConfig,
} from './types.ts';

/** Callback options for synchronizing SideVeil daemon appearance and config snapshots. */
export interface UseSyncedPluginThemeOptions {
  onConfigLoaded?: (config: SideVeilConfig) => void;
  onConfigUpdated?: (config: SideVeilConfig, payload: ConfigUpdatedEventPayload) => void;
  onError?: (message: string) => void;
}

/** Reactive theme state synchronized with the authoritative SideVeil host configuration. */
export interface UseSyncedPluginThemeResult {
  hydrated: boolean;
  isNativeHost: boolean;
  themeId: ThemeId;
  colorMode: ColorMode;
  resolvedScheme: ResolvedColorScheme;
  isDark: boolean;
}

function toConfigUpdatedPayload(
  rawConfig: Partial<SideVeilConfig> | null,
  eventPayload: Record<string, unknown>,
): ConfigUpdatedEventPayload {
  return {
    config: rawConfig ?? undefined,
    foregroundPackage:
      typeof eventPayload.foregroundPackage === 'string'
        ? eventPayload.foregroundPackage
        : undefined,
    scopeActive:
      typeof eventPayload.scopeActive === 'boolean' ? eventPayload.scopeActive : undefined,
  };
}

/**
 * Thin SideVeil-typed wrapper around `@gojona/sideveil-sdk`'s generic `useSyncedPluginTheme`,
 * normalizing full `SideVeilConfig` payloads for first-party SideVeil panels and bundled plugins.
 */
export function useSyncedPluginTheme(
  options: UseSyncedPluginThemeOptions = {},
): UseSyncedPluginThemeResult {
  const theme = useTheme();
  const synced = useSdkSyncedPluginTheme<ThemeId, Partial<SideVeilConfig> | null>({
    theme,
    isValidThemeId,
    onConfigLoaded: (raw) => {
      options.onConfigLoaded?.(normalizeSideVeilConfig(raw));
    },
    onConfigUpdated: (raw, _appearance, eventPayload) => {
      options.onConfigUpdated?.(
        normalizeSideVeilConfig(raw),
        toConfigUpdatedPayload(raw, eventPayload),
      );
    },
    onError: options.onError,
  });

  return {
    hydrated: synced.hydrated,
    isNativeHost: synced.isNativeHost,
    themeId: theme.themeId,
    colorMode: theme.colorMode,
    resolvedScheme: theme.resolvedScheme,
    isDark: synced.isDark,
  };
}
