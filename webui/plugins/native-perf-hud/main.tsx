import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '../../src/styles.css';
import { NativePerfHudApp } from '../../src/plugins/native-perf-hud/NativePerfHudApp.tsx';

const rootEl = document.getElementById('root');
if (!rootEl) {
  throw new Error('Root element #root not found');
}

createRoot(rootEl).render(
  <StrictMode>
    <NativePerfHudApp />
  </StrictMode>,
);
