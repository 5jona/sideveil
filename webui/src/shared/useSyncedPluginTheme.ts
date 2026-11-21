import { useSyncedPluginTheme as useSdkSyncedPluginTheme } from '@pochita/sideveil-sdk';
import {
  type ColorMode,
  isValidThemeId,
  type ResolvedColorScheme,
  type ThemeId,
  useTheme,
} from '@pochita/ui';
import {
  type ConfigUpdatedEventPayload,
  normalizeSideVeilConfig,
  type SideVeilConfig,
} from './types.ts';

export interface UseSyncedPluginThemeOptions {
  onConfigLoaded?: (config: SideVeilConfig) => void;
  onConfigUpdated?: (config: SideVeilConfig, payload: ConfigUpdatedEventPayload) => void;
  onError?: (message: string) => void;
}

export interface UseSyncedPluginThemeResult {
  hydrated: boolean;
  isNativeHost: boolean;
  themeId: ThemeId;
  colorMode: ColorMode;
  resolvedScheme: ResolvedColorScheme;
  isDark: boolean;
}

/**
 * Thin SideVeil-typed wrapper around `@pochita/sideveil-sdk`'s generic `useSyncedPluginTheme`,
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
        eventPayload as unknown as ConfigUpdatedEventPayload,
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
