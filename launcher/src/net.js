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

// GitHub's raw file host keeps a branch's files cached for a few minutes, so right after a new build
// is pushed it can still hand out the old one. Before playing we look up the branch's newest commit
// and read everything from that exact commit instead (those links never go stale).
let pinned = null;

async function pinLatest(hubUrl) {
  const m = /^https:\/\/raw\.githubusercontent\.com\/([^/]+)\/([^/]+)\/(.+)\/dist\/[^/]+$/.exec(hubUrl || '');
  if (!m) return null;
  const [, owner, repo, branch] = m;
  try {
    const r = await fetch(`https://api.github.com/repos/${owner}/${repo}/commits/${branch}`, {
      headers: { Accept: 'application/vnd.github.sha', 'User-Agent': 'kizuna-launcher' },
      cache: 'no-store',
      signal: AbortSignal.timeout(8000),
    });
    const sha = r.ok ? (await r.text()).trim() : '';
    if (!/^[0-9a-f]{40}$/.test(sha)) throw new Error(`commit lookup ${r.status}`);
    pinned = { from: `https://raw.githubusercontent.com/${owner}/${repo}/${branch}/`, to: `https://raw.githubusercontent.com/${owner}/${repo}/${sha}/` };
    return sha;
  } catch {
    pinned = null;
    return null;
  }
}

/** The same link at the pinned commit, when it points into the repo's branch. */
function fresh(url) {
  return pinned && url.startsWith(pinned.from) ? pinned.to + url.slice(pinned.from.length) : url;
}

async function text(url) {
  url = fresh(url);
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
  url = fresh(url);
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

module.exports = { text, json, file, hashFile, pool, retry, pinLatest, fresh };
