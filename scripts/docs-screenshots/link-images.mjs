// Points the in-app help at the screenshots capture.mjs made. For every <img> in
// booklore-ui/public/docs/**/*.html whose file name (minus the hash the old docs site added, e.g.
// "overview-9a8ae1789a1648cbc4496f142f9290f3.jpg" -> "overview") has a capture in
// images/<page>/<name>.jpg, the src is replaced with a relative link to it. Images with no capture yet
// (third-party screens such as an identity provider's admin pages) are left as they are.
//
//   node scripts/docs-screenshots/link-images.mjs          # rewrite pages
//   node scripts/docs-screenshots/link-images.mjs --check  # list what would change and what's still remote

import {existsSync, readFileSync, readdirSync, statSync, writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const DOCS = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../booklore-ui/public/docs');
const IMAGES = path.join(DOCS, 'images');
const check = process.argv.includes('--check');

function* pages(dir) {
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (full !== IMAGES) yield* pages(full);
    } else if (entry.endsWith('.html')) {
      yield full;
    }
  }
}

let linked = 0;
const remote = [];
for (const file of pages(DOCS)) {
  const slug = path.relative(DOCS, file).replace(/\.html$/, '');
  const html = readFileSync(file, 'utf8');
  const updated = html.replace(/(<img\b[^>]*\bsrc=")([^"]+)(")/g, (match, before, src, after) => {
    const base = src.split('/').pop().replace(/-[0-9a-f]{32}(?=\.\w+$)/, '').replace(/\.\w+$/, '');
    const capture = path.join(IMAGES, slug, `${base}.jpg`);
    if (!existsSync(capture)) {
      if (/^https?:/.test(src)) remote.push(`${slug}: ${base}`);
      return match;
    }
    const relative = path.relative(path.dirname(file), capture).split(path.sep).join('/');
    if (relative === src) return match;
    linked++;
    if (check) console.log(`${slug}: ${base} -> ${relative}`);
    return `${before}${relative}${after}`;
  });
  if (!check && updated !== html) writeFileSync(file, updated);
}
console.log(`${check ? 'Would link' : 'Linked'} ${linked} image(s); ${remote.length} still load from the old docs site.`);
if (check) remote.forEach(r => console.log(`  remote  ${r}`));
