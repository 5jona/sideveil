import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '../../src/styles.css';
import { VelloScopeApp } from '../../src/plugins/vello-scope/VelloScopeApp.tsx';

const rootEl = document.getElementById('root');
if (!rootEl) {
  throw new Error('Root element #root not found');
}

createRoot(rootEl).render(
  <StrictMode>
    <VelloScopeApp />
  </StrictMode>,
);
