import { useDynamicThemeTokens, useSideVeilPlugin } from '@pochita/sideveil-sdk';
import { type ColorMode, type ResolvedColorScheme, type ThemeId, useTheme } from '@pochita/ui';
import { useEffect, useRef, useState } from 'react';
import {
  type ConfigUpdatedEventPayload,
  normalizeSideVeilConfig,
  SIDEVEIL_EVENTS,
  SIDEVEIL_METHODS,
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
 * Synchronizes a SideVeil plugin WebView with authoritative daemon appearance config
 * (`config.get` + `config.updated`) and host-synthesized Material You `Oklch` palette tokens
 * (`theme.paletteUpdated`).
 */
export function useSyncedPluginTheme(
  options: UseSyncedPluginThemeOptions = {},
): UseSyncedPluginThemeResult {
  const { bridge } = useSideVeilPlugin();
  const { themeId, colorMode, resolvedScheme, setThemeId, setColorMode } = useTheme();
  const [hydrated, setHydrated] = useState(false);

  const isNativeHost = bridge.env === 'host';
  useDynamicThemeTokens(isNativeHost);

  const callbacksRef = useRef(options);
  callbacksRef.current = options;

  useEffect(() => {
    let active = true;

    const hydrate = async () => {
      try {
        const raw = await bridge.invoke<unknown>(SIDEVEIL_METHODS.CONFIG_GET);
        if (!active) return;
        const cfg = normalizeSideVeilConfig(raw as Partial<SideVeilConfig> | null);
        setThemeId(cfg.panel.themeId);
        setColorMode(cfg.panel.colorMode);
        callbacksRef.current.onConfigLoaded?.(cfg);
      } catch (err) {
        callbacksRef.current.onError?.(
          `config.get failed: ${err instanceof Error ? err.message : String(err)}`,
        );
      } finally {
        if (active) setHydrated(true);
      }
    };
    void hydrate();

    const unsubConfig = bridge.onHostEvent(SIDEVEIL_EVENTS.CONFIG_UPDATED, (raw) => {
      const payload = raw as ConfigUpdatedEventPayload | undefined;
      if (!payload?.config) return;
      const cfg = normalizeSideVeilConfig(payload.config);
      setThemeId(cfg.panel.themeId);
      setColorMode(cfg.panel.colorMode);
      setHydrated(true);
      callbacksRef.current.onConfigUpdated?.(cfg, payload);
    });

    return () => {
      active = false;
      unsubConfig();
    };
  }, [bridge, setColorMode, setThemeId]);

  return {
    hydrated,
    isNativeHost,
    themeId,
    colorMode,
    resolvedScheme,
    isDark: resolvedScheme === 'dark',
  };
}
