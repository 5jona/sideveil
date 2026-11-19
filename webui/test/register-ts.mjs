import { existsSync } from 'node:fs';
import { registerHooks } from 'node:module';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (
      (specifier.startsWith('./') || specifier.startsWith('../')) &&
      !path.extname(specifier) &&
      context.parentURL
    ) {
      const parentDir = path.dirname(fileURLToPath(context.parentURL));
      const candidate = path.resolve(parentDir, `${specifier}.ts`);
      if (existsSync(candidate)) {
        return nextResolve(pathToFileURL(candidate).href, context);
      }
    }
    return nextResolve(specifier, context);
  },
});
