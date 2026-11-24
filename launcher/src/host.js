// Hosting a server from the launcher (for the team): a folder per server and build, Java, Fabric's
// server launcher, the pack's server-side files brought up to date, then the server itself with its
// console in the launcher. When the AoT mod asks for a restart to install a new build, the server is
// brought up to date again and started back up by itself.
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const AdmZip = require('adm-zip');
const net = require('./net');
const java = require('./java');
const pack = require('./pack');

const running = new Map(); // key -> { proc, log: [], state }

function key(id, channel) {
  return `${id}-${channel || 'public'}`;
}

function info(k) {
  const r = running.get(k);
  return { state: r ? r.state : 'stopped', log: r ? r.log.slice(-400) : [] };
}

/** The mod ids inside a jar (its own and any it bundles), from fabric.mod.json. */
function modIds(file) {
  try {
    const e = new AdmZip(file).getEntry('fabric.mod.json');
    if (!e) return [];
    const j = JSON.parse(e.getData().toString('utf8').replace(/^﻿/, ''));
    return j.id ? [String(j.id)] : [];
  } catch {
    return [];
  }
}

/**
 * Jars in mods/ that aren't the pack's but are the same mod as one of the pack's (an older copy
 * someone put there by hand) would make the server refuse to start: move them to mods-disabled/.
 */
function setAsideDuplicates(folder, packFiles, log) {
  const mods = path.join(folder, 'mods');
  if (!fs.existsSync(mods)) return;
  const ours = new Set(packFiles.filter((f) => f.startsWith('mods/')).map((f) => path.basename(f)));
  const ids = new Set();
  for (const f of ours) for (const id of modIds(path.join(mods, f))) ids.add(id);
  for (const f of fs.readdirSync(mods)) {
    if (!f.endsWith('.jar') || ours.has(f)) continue;
    if (modIds(path.join(mods, f)).some((id) => ids.has(id))) {
      fs.mkdirSync(path.join(folder, 'mods-disabled'), { recursive: true });
      fs.renameSync(path.join(mods, f), path.join(folder, 'mods-disabled', f));
      log(`Moved ${f} to mods-disabled (the pack has its own copy of that mod)`);
    }
  }
}

async function serverJar(folder, mc, loader) {
  const meta = path.join(folder, '.kizuna-server.json');
  let m = {};
  try {
    m = JSON.parse(fs.readFileSync(meta, 'utf8'));
  } catch {}
  if (m.mc === mc && m.loader === loader && m.jar && fs.existsSync(path.join(folder, m.jar))) return m.jar;
  const inst = JSON.parse(await net.text('https://meta.fabricmc.net/v2/versions/installer'));
  const v = (inst.find((x) => x.stable) || inst[0]).version;
  const jar = `fabric-server-mc.${mc}-loader.${loader}-launcher.${v}.jar`;
  await net.file(`https://meta.fabricmc.net/v2/versions/loader/${mc}/${loader}/${v}/server/jar`, path.join(folder, jar));
  fs.writeFileSync(meta, JSON.stringify({ mc, loader, jar }, null, 2));
  return jar;
}

/**
 * Brings the folder up to date and starts the server. on: { line(text), state(s) }.
 * opts: { packUrl, folder, root (launcher data for Java), ram, eula }
 */
async function start(k, opts, on) {
  if (running.get(k) && running.get(k).state !== 'stopped') return;
  const r = { proc: null, log: [], state: 'updating' };
  running.set(k, r);
  const line = (s) => {
    for (const l of String(s).split(/\r?\n/)) {
      if (!l) continue;
      r.log.push(l);
      if (r.log.length > 2000) r.log.splice(0, r.log.length - 2000);
      on.line(l);
    }
  };
  const state = (s) => {
    r.state = s;
    on.state(s);
  };
  state('updating');
  try {
    const folder = opts.folder;
    fs.mkdirSync(folder, { recursive: true });
    line('Checking for the newest build…');
    const sha = await net.pinLatest(opts.hubUrl);
    if (sha) line(`Newest build: ${sha.slice(0, 8)}`);
    const p = await pack.sync(opts.packUrl, folder, (t) => line(t), { side: 'server' });
    line(`Server files up to date (${p.files} files, ${p.fetched} updated)`);
    let ledger = [];
    try {
      ledger = JSON.parse(fs.readFileSync(path.join(folder, '.kizuna-pack.json'), 'utf8')).files || [];
    } catch {}
    setAsideDuplicates(folder, ledger, line);
    const javaPath = await java.ensure(opts.root, p.minecraft, (d, t) => line(`Installing Java (${d}/${t})`));
    const jar = await serverJar(folder, p.minecraft, p.fabric);
    if (!opts.eula) throw new Error('Accept the Minecraft EULA first (tick the box).');
    fs.writeFileSync(path.join(folder, 'eula.txt'), '# Accepted in the Kizuna launcher: https://aka.ms/MinecraftEULA\neula=true\n');
    try {
      fs.rmSync(path.join(folder, 'update-restart.flag'), { force: true });
    } catch {}
    const args = ['-Xms2G', `-Xmx${opts.ram || 6}G`, '-XX:+UseG1GC', '-XX:+ParallelRefProcEnabled', '-Daotrpg.autorestart=true', '-jar', jar, 'nogui'];
    line(`Starting: java ${args.join(' ')}`);
    const proc = spawn(javaPath, args, { cwd: folder, windowsHide: true });
    r.proc = proc;
    state('starting');
    const read = (d) => {
      const s = String(d);
      line(s);
      if (r.state === 'starting' && /Done \(\d/.test(s)) state('running');
    };
    proc.stdout.on('data', read);
    proc.stderr.on('data', read);
    proc.on('error', (e) => {
      line('Could not start: ' + e.message);
      state('stopped');
    });
    proc.on('close', (code) => {
      r.proc = null;
      line(`Server stopped (exit code ${code})`);
      // The AoT mod asked for a restart to install a new build: update and go again.
      if (fs.existsSync(path.join(folder, 'update-restart.flag'))) {
        line('Restarting to install the new build…');
        state('stopped');
        running.delete(k);
        setTimeout(() => start(k, opts, on), 2000);
      } else state('stopped');
    });
  } catch (e) {
    line('Error: ' + (e.message || e));
    state('stopped');
  }
}

function command(k, text) {
  const r = running.get(k);
  if (!r || !r.proc) return false;
  r.proc.stdin.write(String(text).trim() + '\n');
  return true;
}

function stop(k) {
  return command(k, 'stop');
}

function anyRunning() {
  return [...running.values()].some((r) => r.proc);
}

/** Stops every server (when the launcher quits). */
function stopAll() {
  for (const r of running.values()) if (r.proc) r.proc.stdin.write('stop\n');
}

module.exports = { key, info, start, command, stop, stopAll, anyRunning };
