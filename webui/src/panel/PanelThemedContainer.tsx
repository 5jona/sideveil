import { useContainerThemeSync } from '@gojona/bridge';
import { ErrorBoundary, type LogEntry, ThemeProvider, ToastProvider } from '@gojona/ui';
import { type ReactNode, useCallback, useState } from 'react';

export interface PanelThemedContainerProps {
  onHydratedReady?: () => void;
  children: (onHydrated: () => void) => ReactNode;
}

/**
 * Provides theme synchronization, toast viewport, and error boundary around the drawer workbench.
 */
export function PanelThemedContainer({
  children,
}: {
  children: (onHydrated: () => void) => ReactNode;
}) {
  const [hydrated, setHydrated] = useState(false);
  const markHydrated = useCallback(() => setHydrated(true), []);
  const handleThemeSync = useContainerThemeSync({ includeAppearanceHints: false });

  return (
    <ThemeProvider
      defaultTheme="coloros-glass"
      defaultColorMode="system"
      defaultSurfaceMode="native-glass"
      syncEnabled={hydrated}
      onThemeSync={handleThemeSync}
    >
      <ToastProvider>
        <ErrorBoundary title="诊断面板渲染异常">
          {children(markHydrated) as ReactNode}
        </ErrorBoundary>
      </ToastProvider>
    </ThemeProvider>
  );
}
export type { LogEntry };
