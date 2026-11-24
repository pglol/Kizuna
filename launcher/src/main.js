// Kizuna Launcher: sign in once with Microsoft, pick one of our servers, press Play.
const { app, BrowserWindow, ipcMain, shell, Notification } = require('electron');
const path = require('path');
const crypto = require('crypto');
const fs = require('fs');
const auth = require('./auth');
const game = require('./game');
const net = require('./net');
const store = require('./store');
const status = require('./status');

const config = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'config.json'), 'utf8'));
let win = null;
let playing = null;

if (!app.requestSingleInstanceLock()) app.quit();
app.on('second-instance', () => {
  if (win) {
    if (win.isMinimized()) win.restore();
    win.show();
    win.focus();
  }
});

/** A desktop notification; clicking it brings the launcher to the front. */
function notify(title, body) {
  try {
    if (!Notification.isSupported()) return;
    const n = new Notification({ title, body, icon: path.join(__dirname, '..', 'build', 'icon.png') });
    n.on('click', () => {
      if (win) {
        if (win.isMinimized()) win.restore();
        win.show();
        win.focus();
      }
    });
    n.show();
  } catch {}
}

ipcMain.handle('notify', (_e, title, body) => notify(String(title).slice(0, 80), String(body).slice(0, 200)));

function send(ch, data) {
  if (win && !win.isDestroyed()) win.webContents.send(ch, data);
}

function create() {
  win = new BrowserWindow({
    width: 1180,
    height: 720,
    minWidth: 960,
    minHeight: 600,
    frame: false,
    titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : undefined,
    backgroundColor: '#070908',
    show: false,
    icon: path.join(__dirname, '..', 'build', 'icon.png'),
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false },
  });
  win.removeMenu();
  win.loadFile(path.join(__dirname, 'ui', 'index.html'));
  win.once('ready-to-show', () => win.show());
  // Links open in the real browser, never inside the launcher.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https:\/\//.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e) => e.preventDefault());
}

// Play asks this first: is there a newer launcher? (At most a few seconds; offline just says no.)
let updater = null;
const newer = (a, b) => {
  const x = String(a).split(/[.-]/).map(Number), y = String(b).split(/[.-]/).map(Number);
  for (let i = 0; i < 3; i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0);
  return false;
};
ipcMain.handle('update-check', async () => {
  if (!updater) return null;
  try {
    const r = await Promise.race([updater.checkForUpdates(), new Promise((res) => setTimeout(() => res(null), 6000))]);
    const v = r && r.updateInfo && r.updateInfo.version;
    return v && newer(v, app.getVersion()) ? { version: v } : null;
  } catch (e) {
    logLine('update check before play failed:', e && e.message);
    return null;
  }
});

app.whenReady().then(() => {
  if (process.platform === 'win32') app.setAppUserModelId('net.pglol.kizuna');
  create();
  if (app.isPackaged) {
    // Launcher updates: check now and every 30 minutes; ask the player, then download and restart.
    try {
      const { autoUpdater } = require('electron-updater');
      autoUpdater.autoDownload = false;
      autoUpdater.autoInstallOnAppQuit = true;
      let told = '';
      autoUpdater.on('update-available', (i) => {
        send('update', { state: 'available', version: i.version });
        // A desktop notification too, so people hear about it even with the launcher minimised.
        if (told !== i.version) {
          told = i.version;
          notify('Kizuna update', `Version ${i.version} is ready. Click to update.`);
        }
      });
      autoUpdater.on('download-progress', (p) => send('update', { state: 'downloading', percent: Math.round(p.percent || 0) }));
      autoUpdater.on('update-downloaded', (i) => send('update', { state: 'ready', version: i.version }));
      autoUpdater.on('error', (e) => logLine('update error:', e && e.message));
      const check = () => autoUpdater.checkForUpdates().catch(() => {});
      check();
      setInterval(check, 10 * 60 * 1000);
      let last = Date.now();
      win.on('focus', () => {
        if (Date.now() - last > 60 * 1000) {
          last = Date.now();
          check();
        }
      });
      updater = autoUpdater;
      ipcMain.handle('update-download', () => autoUpdater.downloadUpdate().catch((e) => logLine('update download failed:', e && e.message)));
      ipcMain.handle('update-install', () => {
        if (playing) return false;
        autoUpdater.quitAndInstall(false, true);
        return true;
      });
    } catch {}
  }
});
app.on('window-all-closed', () => {
  if (!playing) app.quit();
});

const pub = (a) => (a ? { name: a.name, uuid: a.uuid } : null);

ipcMain.handle('ping', (_e, address) => (address && !String(address).includes('CHANGE_ME') ? status.ping(address) : null));

/** Friends made inside our games: each game saves its list to config/kizuna/friends.json in its folder. */
function gameFriends() {
  const out = new Map();
  const dir = path.join(store.dir(), 'instances');
  let names = [];
  try {
    names = fs.readdirSync(dir);
  } catch {}
  for (const n of names) {
    try {
      const j = JSON.parse(fs.readFileSync(path.join(dir, n, 'config', 'kizuna', 'friends.json'), 'utf8'));
      for (const f of j.friends || []) if (/^[A-Za-z0-9_]{3,16}$/.test(f) && !out.has(f.toLowerCase())) out.set(f.toLowerCase(), { name: f, from: j.server || n });
    } catch {}
  }
  return out;
}

ipcMain.handle('friends', (_e, list) => {
  if (Array.isArray(list)) store.settings.set({ friends: list.filter((n) => /^[A-Za-z0-9_]{3,16}$/.test(n)).slice(0, 200) });
  const mine = store.settings.get().friends || [];
  const game = gameFriends();
  const rows = mine.map((name) => ({ name, from: '' }));
  for (const [k, f] of game) if (!mine.some((m) => m.toLowerCase() === k)) rows.push(f);
  return rows;
});

ipcMain.handle('open-logs', () => shell.openPath(store.dir()));

ipcMain.handle('init', () => ({ mark: config.mark || '', testMode: !!config.testMode, brand: config.brand || 'KIZUNA', name: config.name || 'Kizuna', version: app.getVersion(), account: pub(store.account.get()), settings: store.settings.get(), platform: process.platform }));

ipcMain.handle('hub', async () => {
  let hub;
  try {
    hub = await net.json(config.hubUrl + (config.hubUrl.includes('?') ? '&' : '?') + 't=' + Date.now());
    store.write('hub-cache.json', hub);
    removeDropped(hub);
  } catch (e) {
    hub = store.read('hub-cache.json', null);
    if (!hub) return { servers: [], offline: true, error: String(e.message || e) };
    hub.offline = true;
  }
  const unlocked = new Set(store.settings.get().unlocked || []);
  hub.servers = (hub.servers || []).filter((s) => s.status !== 'private' || unlocked.has(s.codeHash));
  // Build channels: a coded one stays locked until its code has been entered on this computer.
  for (const s of hub.servers) {
    s.channels = (s.channels && s.channels.length ? s.channels : [{ id: 'public', name: 'Public', pack: s.pack, address: s.address }]).map((c) => ({
      ...c,
      locked: !!c.codeHash && !unlocked.has(c.codeHash),
    }));
  }
  return hub;
});

/**
 * A server taken off the list: delete its downloaded game folder (mods, configs, its own saves).
 * Only after a fresh, successful read of the list, never while offline, and never while that game
 * is running. Servers paused as "soon" or hidden as "private" are still on the list, so they stay.
 */
function removeDropped(hub) {
  const listed = new Set();
  for (const s of hub.servers || []) for (const c of s.channels && s.channels.length ? s.channels : [{ id: 'public' }]) listed.add(dirName(s.id, c.id));
  if (!listed.size) return;
  const dir = path.join(store.dir(), 'instances');
  let names = [];
  try {
    names = fs.readdirSync(dir);
  } catch {
    return;
  }
  for (const n of names) {
    if (listed.has(n) || n === playing) continue;
    try {
      fs.rmSync(path.join(dir, n), { recursive: true, force: true });
    } catch {}
  }
  const s = store.settings.get();
  if (s.selected && !listed.has(s.selected)) store.settings.set({ selected: null });
}

/** Where a server's channel keeps its game files: instances/aot, instances/aot-dev, ... */
function dirName(id, channel) {
  const safe = (x) => String(x).replace(/[^a-z0-9_-]/gi, '');
  return !channel || channel === 'public' ? safe(id) : `${safe(id)}-${safe(channel)}`;
}

ipcMain.handle('unlock', async (_e, code) => {
  const h = crypto.createHash('sha256').update(String(code || '').trim().toUpperCase()).digest('hex');
  const hub = store.read('hub-cache.json', { servers: [] });
  const s = store.settings.get();
  const save = (patch) => store.settings.set({ unlocked: [...new Set([...(s.unlocked || []), h])], ...patch });
  const server = (hub.servers || []).find((x) => x.codeHash === h);
  if (server) {
    save({ selected: server.id });
    return { ok: true, id: server.id, name: server.name };
  }
  for (const x of hub.servers || []) {
    const c = (x.channels || []).find((ch) => ch.codeHash === h);
    if (c) {
      save({ selected: x.id, channels: { ...(s.channels || {}), [x.id]: c.id } });
      return { ok: true, id: x.id, channel: c.id, name: `${x.name}: ${c.name}` };
    }
  }
  return { ok: false };
});

ipcMain.handle('login', async () => {
  try {
    const r = await auth.login(win, config.msClientId);
    store.account.set(r.account);
    return { ok: true, account: pub(r.account) };
  } catch (e) {
    return { ok: false, error: e.message, code: e.code };
  }
});

ipcMain.handle('logout', () => {
  store.account.set(null);
  return true;
});

ipcMain.handle('settings', (_e, patch) => store.settings.set(patch || {}));

ipcMain.handle('open-folder', (_e, id, channel) => {
  const dir = path.join(store.dir(), 'instances', dirName(id, channel));
  fs.mkdirSync(dir, { recursive: true });
  shell.openPath(dir);
});

ipcMain.handle('win', (_e, what) => {
  if (!win) return;
  if (what === 'min') win.minimize();
  else if (what === 'max') win.isMaximized() ? win.unmaximize() : win.maximize();
  else if (what === 'close') win.close();
});

/** Test builds only: an offline profile, so the whole install-and-start path can be tried before Microsoft approves the app. */
function testSession() {
  const name = 'Tester';
  const h = crypto.createHash('md5').update('OfflinePlayer:' + name).digest();
  h[6] = (h[6] & 0x0f) | 0x30;
  h[8] = (h[8] & 0x3f) | 0x80;
  return { name, uuid: h.toString('hex'), access: '0', xuid: '', offline: true };
}

/** Everything the launcher did, for testers to send back. */
function logLine(...a) {
  try {
    const f = path.join(store.dir(), 'launcher.log');
    fs.mkdirSync(store.dir(), { recursive: true });
    fs.appendFileSync(f, `[${new Date().toISOString()}] ${a.join(' ')}\n`);
  } catch {}
}

ipcMain.handle('play', async (_e, id, test, channelId, hostLabel) => {
  if (playing) return { ok: false, error: 'A game is already running.' };
  // Always the newest build: pin to the latest commit and re-read the server list before anything else.
  send('status', { text: 'Checking for updates', fraction: 0.01 });
  const sha = await net.pinLatest(config.hubUrl);
  try {
    const latest = await net.json(config.hubUrl + (sha ? '' : (config.hubUrl.includes('?') ? '&' : '?') + 't=' + Date.now()));
    store.write('hub-cache.json', latest);
  } catch (e) {
    logLine('could not refresh the server list before playing:', e.message || e);
  }
  logLine('build check:', sha ? 'latest commit ' + sha.slice(0, 8) : 'commit lookup failed, using branch links');
  const hub = store.read('hub-cache.json', { servers: [] });
  const found = (hub.servers || []).find((s) => s.id === id);
  if (!found) return { ok: false, error: 'That server is not available right now.' };
  if (found.status === 'soon') return { ok: false, error: `${found.name} isn't open yet.` };
  const chans = found.channels && found.channels.length ? found.channels : [{ id: 'public', pack: found.pack, address: found.address }];
  const ch = chans.find((c) => c.id === channelId) || chans[0];
  if (ch.codeHash && !(store.settings.get().unlocked || []).includes(ch.codeHash)) return { ok: false, error: `${ch.name} needs an access code.`, code: 'locked' };
  const host = (ch.addresses || []).find((a) => a.label === hostLabel);
  const server = { ...found, pack: ch.pack || found.pack, address: (host && host.address) || ch.address || found.address, dir: dirName(found.id, ch.id), channel: ch.id };
  const acct = store.account.get();
  const testing = !!(test && config.testMode);
  if (!acct && !testing) return { ok: false, error: 'Sign in first.', code: 'signin' };
  playing = id;
  logLine('play', id, testing ? '(test mode)' : '');
  try {
    send('status', { text: 'Signing in', fraction: 0.02 });
    let r;
    if (testing) r = { account: acct, session: testSession() };
    else try {
      r = await auth.refresh(acct, config.msClientId);
    } catch (e) {
      if (e.code === 'invalid_grant' || e.status === 400) {
        store.account.set(null);
        throw Object.assign(new Error('Your sign-in expired. Sign in again.'), { code: 'signin' });
      }
      throw e;
    }
    if (r.account) store.account.set(r.account);
    const settings = store.settings.get();
    await game.play(store.dir(), server, r.session, settings, {
      status: (text, fraction) => {
        logLine(text);
        send('status', { text, fraction });
      },
      started: () => {
        send('status', { text: 'Playing', fraction: 1, playing: true });
        if (settings.closeOnPlay && win) win.hide();
      },
      exited: (code, log) => {
        playing = null;
        logLine('game exited with code', code);
        if (code) logLine(log);
        if (win) {
          win.show();
          win.focus();
        }
        send('exited', { code, log: code ? log : '' });
      },
    });
    return { ok: true };
  } catch (e) {
    playing = null;
    logLine('error:', e.stack || e.message || String(e));
    return { ok: false, error: e.message || String(e), code: e.code };
  }
});
