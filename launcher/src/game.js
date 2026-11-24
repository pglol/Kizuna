// Getting a server's game running: Java, Fabric, the pack, then Minecraft itself straight into the server.
const fs = require('fs');
const path = require('path');
const { Client } = require('minecraft-launcher-core');
const net = require('./net');
const java = require('./java');
const pack = require('./pack');

async function fabric(root, mc, loader) {
  const name = `fabric-loader-${loader}-${mc}`;
  const file = path.join(root, 'versions', name, `${name}.json`);
  if (!fs.existsSync(file)) {
    const j = await net.text(`https://meta.fabricmc.net/v2/versions/loader/${mc}/${loader}/profile/json`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, j);
  }
  return name;
}

/**
 * server: an entry from launcher.json. session: { name, uuid, access, xuid }.
 * on: { status(text, fraction), started(), exited(code, log) }.
 */
async function play(dataDir, server, session, settings, on) {
  const root = path.join(dataDir, 'minecraft');
  const gameDir = path.join(dataDir, 'instances', server.id);
  // The pack first: it says which Minecraft and Fabric it wants.
  const p = await pack.sync(server.pack, gameDir, (t, f) => on.status(t, 0.05 + f * 0.25));
  const mc = p.minecraft || server.minecraft;
  const loader = p.fabric || server.fabric;
  on.status('Preparing Java', 0.3);
  const javaPath = await java.ensure(root, mc, (d, t) => on.status(`Installing Java (${d}/${t})`, 0.3 + (d / t) * 0.2));
  on.status('Preparing Fabric', 0.5);
  const custom = await fabric(root, mc, loader);

  const launcher = new Client();
  const log = [];
  let started = false;
  launcher.on('progress', (e) => {
    if (!e || !e.total) return;
    const what = { assets: 'Downloading sounds and textures', natives: 'Downloading natives', 'classes-custom': 'Downloading Fabric', classes: 'Downloading libraries' }[e.type] || 'Downloading Minecraft';
    on.status(`${what} (${e.task}/${e.total})`, 0.5 + (e.task / e.total) * 0.45);
  });
  launcher.on('debug', (d) => log.push(String(d)));
  launcher.on('data', (d) => {
    const s = String(d);
    log.push(s);
    if (log.length > 400) log.splice(0, log.length - 400);
    if (!started && /Setting user|LWJGL|Backend library|Loading Minecraft/.test(s)) {
      started = true;
      on.started();
    }
  });
  launcher.on('close', (code) => {
    try {
      fs.writeFileSync(path.join(gameDir, 'last-launch.log'), log.join('\n'));
    } catch {}
    on.exited(code, log.slice(-120).join('\n'));
  });

  const addr = (server.address || '').trim();
  const opts = {
    root,
    javaPath,
    version: { number: mc, type: 'release', custom },
    memory: { max: `${settings.ram}G`, min: '1G' },
    authorization: {
      access_token: session.access,
      client_token: session.uuid,
      uuid: session.uuid,
      name: session.name,
      user_properties: '{}',
      meta: { type: session.offline ? 'legacy' : 'msa', demo: false, xuid: session.xuid },
    },
    overrides: { gameDirectory: gameDir, detached: true, maxSockets: 8 },
    customArgs: ['-XX:+UseG1GC', '-XX:MaxGCPauseMillis=50'],
  };
  // Offline test profiles can't join an online-mode server, so they stop at the title screen.
  if (addr && !addr.includes('CHANGE_ME') && !session.offline) opts.quickPlay = { type: 'multiplayer', identifier: addr };
  on.status('Starting the game', 0.95);
  const child = await launcher.launch(opts);
  if (!child) throw new Error('Minecraft could not start: ' + (log.slice(-5).join(' ') || 'unknown error'));
  return { gameDir, pack: p };
}

module.exports = { play };
