import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createWebUiViteConfig } from '@pochita/config';
import { mergeConfig } from 'vite';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const kitRoot = path.resolve(__dirname, '../../../../pochita-webui-kit');

const baseConfig = createWebUiViteConfig({
  outDir: path.resolve(__dirname, '../android/host-app/src/main/assets/panel'),
  port: 5175,
  server: {
    fs: {
      allow: [__dirname, kitRoot],
    },
  },
});

export default mergeConfig(baseConfig, {
  root: path.resolve(__dirname, 'panel'),
});
