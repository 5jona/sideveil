import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createPochitaViteConfig } from '@gojona/config';
import { mergeConfig } from 'vite';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const kitRoot = process.env.POCHITA_KIT_ROOT
  ? path.resolve(process.env.POCHITA_KIT_ROOT)
  : undefined;

const baseConfig = createPochitaViteConfig({
  outDir: path.resolve(__dirname, '../webroot'),
  port: 5174,
  ...(kitRoot ? { kitRoot } : {}),
  surface: 'standalone',
  server: {
    fs: {
      allow: [__dirname],
    },
  },
});

export default mergeConfig(baseConfig, {
  root: path.resolve(__dirname, 'control'),
  build: {
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            {
              name: 'vendor',
              test: /node_modules[\\/](react|react-dom|scheduler|lucide-react)[\\/]/,
            },
          ],
        },
      },
    },
  },
});
