// Brings a server's game folder in line with its packwiz pack: downloads what's new or changed,
// checks every hash, and removes files an earlier version of the pack installed but this one dropped.
// Files the player added themselves (screenshots, options, extra resource packs) are never touched.
const fs = require('fs');
const path = require('path');
const TOML = require('smol-toml');
const net = require('./net');

const ALGO = { sha1: 'sha1', sha256: 'sha256', sha512: 'sha512', md5: 'md5' };

function inside(root, rel) {
  const p = path.resolve(root, rel);
  if (p !== root && !p.startsWith(root + path.sep)) throw new Error(`Unsafe path in pack: ${rel}`);
  return p;
}

const join = (base, rel) => new URL(rel.split('/').map(encodeURIComponent).join('/'), base.endsWith('/') ? base : base + '/').toString();

/** progress(text, fraction) */
async function sync(packUrl, gameDir, progress) {
  progress && progress('Checking for updates', 0);
  const packBase = packUrl.slice(0, packUrl.lastIndexOf('/') + 1);
  const pack = TOML.parse(await net.text(packUrl));
  const indexUrl = join(packBase, pack.index.file);
  const indexBase = indexUrl.slice(0, indexUrl.lastIndexOf('/') + 1);
  const index = TOML.parse(await net.text(indexUrl));
  const files = index.files || [];

  // Work out every file the pack wants, reading the metafiles for mods.
  const want = [];
  await net.pool(files, 8, async (f) => {
    if (f.metafile) {
      const meta = TOML.parse(await net.text(join(indexBase, f.file)));
      if (meta.side === 'server') return;
      const dir = path.posix.dirname(f.file);
      const d = meta.download || {};
      if (!d.url) throw new Error(`${meta.name || f.file} has no direct download`);
      want.push({ rel: (dir === '.' ? '' : dir + '/') + meta.filename, url: d.url, hash: d.hash, algo: ALGO[d['hash-format']] });
    } else {
      want.push({ rel: f.file, url: join(indexBase, f.file), hash: f.hash, algo: ALGO[f['hash-format'] || index['hash-format']] });
    }
  });

  fs.mkdirSync(gameDir, { recursive: true });
  const ledger = path.join(gameDir, '.kizuna-pack.json');
  let before = [];
  try {
    before = JSON.parse(fs.readFileSync(ledger, 'utf8')).files || [];
  } catch {}
  const now = new Set(want.map((w) => w.rel));
  for (const rel of before) {
    if (!now.has(rel)) {
      try {
        fs.rmSync(inside(gameDir, rel), { force: true });
      } catch {}
    }
  }

  let done = 0, fetched = 0;
  await net.pool(want, 6, async (w) => {
    const dest = inside(gameDir, w.rel);
    if (await net.file(w.url, dest, w.hash, w.algo || 'sha256')) fetched++;
    done++;
    progress && progress(`Updating ${pack.name || 'pack'} (${done}/${want.length})`, done / Math.max(1, want.length));
  });
  fs.writeFileSync(ledger, JSON.stringify({ version: pack.version || '', files: [...now] }, null, 2));
  return { name: pack.name, version: pack.version, files: want.length, fetched, minecraft: pack.versions && pack.versions.minecraft, fabric: pack.versions && pack.versions.fabric };
}

module.exports = { sync };
