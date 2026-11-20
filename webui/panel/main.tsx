import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '../src/styles.css';
import { PanelApp } from '../src/panel/PanelApp.tsx';

const rootEl = document.getElementById('root');
if (!rootEl) {
  throw new Error('Root element #root not found');
}

createRoot(rootEl).render(
  <StrictMode>
    <PanelApp />
  </StrictMode>,
);
