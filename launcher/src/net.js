// Small download helpers: JSON/text fetches with retries, and files with a hash check.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

async function retry(fn, tries = 4) {
  let last;
  for (let i = 0; i < tries; i++) {
    try {
      return await fn();
    } catch (e) {
      last = e;
      if (e && e.fatal) break;
      await new Promise((r) => setTimeout(r, 400 * (i + 1) * (i + 1)));
    }
  }
  throw last;
}

async function text(url) {
  return retry(async () => {
    const r = await fetch(url, { cache: 'no-store' });
    if (!r.ok) {
      const e = new Error(`${r.status} fetching ${url}`);
      if (r.status === 404 || r.status === 403) e.fatal = true;
      throw e;
    }
    return r.text();
  });
}

async function json(url) {
  return JSON.parse(await text(url));
}

function hashFile(file, algo) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash(algo);
    fs.createReadStream(file).on('data', (d) => h.update(d)).on('end', () => resolve(h.digest('hex'))).on('error', reject);
  });
}

/** Downloads url to dest (atomically), checking the hash when one is given. Skips it if dest already matches. */
async function file(url, dest, hash, algo = 'sha1') {
  if (hash && fs.existsSync(dest)) {
    try {
      if ((await hashFile(dest, algo)) === hash.toLowerCase()) return false;
    } catch {}
  }
  await fs.promises.mkdir(path.dirname(dest), { recursive: true });
  await retry(async () => {
    const r = await fetch(url);
    if (!r.ok) {
      const e = new Error(`${r.status} downloading ${url}`);
      if (r.status === 404) e.fatal = true;
      throw e;
    }
    const buf = Buffer.from(await r.arrayBuffer());
    if (hash) {
      const got = crypto.createHash(algo).update(buf).digest('hex');
      if (got !== hash.toLowerCase()) throw new Error(`Corrupt download (${path.basename(dest)})`);
    }
    const tmp = dest + '.part';
    await fs.promises.writeFile(tmp, buf);
    await fs.promises.rename(tmp, dest);
  });
  return true;
}

/** Runs tasks with at most n at once. */
async function pool(items, n, fn) {
  let i = 0;
  const workers = Array.from({ length: Math.min(n, items.length) }, async () => {
    while (i < items.length) {
      const k = i++;
      await fn(items[k], k);
    }
  });
  await Promise.all(workers);
}

module.exports = { text, json, file, hashFile, pool, retry };
