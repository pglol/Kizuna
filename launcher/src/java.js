// The right Java for the Minecraft version, from Mojang's own runtime downloads (the same ones the
// official launcher uses), kept in the app's data folder. Players never install Java themselves.
const fs = require('fs');
const path = require('path');
const net = require('./net');

const ALL = 'https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json';
const MANIFEST = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';

function platform() {
  const a = process.arch;
  if (process.platform === 'win32') return a === 'arm64' ? 'windows-arm64' : a === 'ia32' ? 'windows-x86' : 'windows-x64';
  if (process.platform === 'darwin') return a === 'arm64' ? 'mac-os-arm64' : 'mac-os';
  return a === 'ia32' ? 'linux-i386' : 'linux';
}

function exe(home) {
  if (process.platform === 'win32') return path.join(home, 'bin', 'javaw.exe');
  if (process.platform === 'darwin') return path.join(home, 'jre.bundle', 'Contents', 'Home', 'bin', 'java');
  return path.join(home, 'bin', 'java');
}

/** Which runtime a Minecraft version wants (e.g. java-runtime-delta for 1.21). */
async function component(mcVersion) {
  try {
    const m = await net.json(MANIFEST);
    const v = m.versions.find((x) => x.id === mcVersion);
    if (v) {
      const j = await net.json(v.url);
      if (j.javaVersion && j.javaVersion.component) return j.javaVersion.component;
    }
  } catch {}
  return 'java-runtime-delta';
}

/** Makes sure the runtime is installed; returns the java executable. progress(done, total). */
async function ensure(root, mcVersion, progress) {
  const comp = await component(mcVersion);
  const home = path.join(root, 'runtimes', comp);
  const stamp = path.join(home, '.installed');
  const all = await net.json(ALL);
  const list = (all[platform()] || {})[comp];
  if (!list || !list.length) throw new Error(`No Java ${comp} for this computer (${platform()}).`);
  const want = list[0].manifest.sha1;
  if (fs.existsSync(stamp) && fs.readFileSync(stamp, 'utf8') === want && fs.existsSync(exe(home))) return exe(home);
  const man = await net.json(list[0].manifest.url);
  const files = Object.entries(man.files);
  const todo = files.filter(([, f]) => f.type === 'file');
  for (const [p, f] of files) if (f.type === 'directory') fs.mkdirSync(path.join(home, p), { recursive: true });
  let done = 0;
  await net.pool(todo, 8, async ([p, f]) => {
    const dest = path.join(home, p);
    await net.file(f.downloads.raw.url, dest, f.downloads.raw.sha1, 'sha1');
    if (f.executable && process.platform !== 'win32') fs.chmodSync(dest, 0o755);
    progress && progress(++done, todo.length);
  });
  for (const [p, f] of files) {
    if (f.type !== 'link' || process.platform === 'win32') continue;
    const dest = path.join(home, p);
    try {
      fs.rmSync(dest, { force: true });
      fs.symlinkSync(f.target, dest);
    } catch {}
  }
  fs.writeFileSync(stamp, want);
  return exe(home);
}

module.exports = { ensure, platform };
