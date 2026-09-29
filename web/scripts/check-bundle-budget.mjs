import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { gzipSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const assetsDir = fileURLToPath(new URL('../dist/assets/', import.meta.url));
const limits = { '.js': 200 * 1024, '.css': 50 * 1024 };
const failures = [];

for (const name of readdirSync(assetsDir)) {
  const extension = name.endsWith('.js') ? '.js' : name.endsWith('.css') ? '.css' : null;
  if (!extension) continue;
  const bytes = gzipSync(readFileSync(join(assetsDir, name))).byteLength;
  if (bytes > limits[extension]) failures.push(`${name}: ${(bytes / 1024).toFixed(1)} KiB gzip`);
}

if (failures.length) {
  console.error(`Bundle budget exceeded:\n${failures.join('\n')}`);
  process.exit(1);
}
console.log('Bundle budget OK (JS <= 200 KiB gzip; CSS <= 50 KiB gzip per asset).');
