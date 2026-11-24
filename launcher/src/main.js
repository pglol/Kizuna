// Kizuna Launcher: sign in once with Microsoft, pick one of our servers, press Play.
const { app, BrowserWindow, ipcMain, shell } = require('electron');
const path = require('path');
const crypto = require('crypto');
const fs = require('fs');
const auth = require('./auth');
const game = require('./game');
const net = require('./net');
const store = require('./store');

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

app.whenReady().then(() => {
  create();
  if (app.isPackaged) {
    try {
      const { autoUpdater } = require('electron-updater');
      autoUpdater.on('update-available', (i) => send('update', { state: 'downloading', version: i.version }));
      autoUpdater.on('update-downloaded', (i) => send('update', { state: 'ready', version: i.version }));
      autoUpdater.on('error', () => {});
      autoUpdater.checkForUpdates().catch(() => {});
      ipcMain.handle('update-install', () => autoUpdater.quitAndInstall());
    } catch {}
  }
});
app.on('window-all-closed', () => {
  if (!playing) app.quit();
});

const pub = (a) => (a ? { name: a.name, uuid: a.uuid } : null);

ipcMain.handle('open-logs', () => shell.openPath(store.dir()));

ipcMain.handle('init', () => ({ testMode: !!config.testMode, brand: config.brand || 'KIZUNA', name: config.name || 'Kizuna', version: app.getVersion(), account: pub(store.account.get()), settings: store.settings.get(), platform: process.platform }));

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
  return hub;
});

/**
 * A server taken off the list: delete its downloaded game folder (mods, configs, its own saves).
 * Only after a fresh, successful read of the list, never while offline, and never while that game
 * is running. Servers paused as "soon" or hidden as "private" are still on the list, so they stay.
 */
function removeDropped(hub) {
  const listed = new Set((hub.servers || []).map((s) => s.id));
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

ipcMain.handle('unlock', async (_e, code) => {
  const h = crypto.createHash('sha256').update(String(code || '').trim().toUpperCase()).digest('hex');
  const hub = store.read('hub-cache.json', { servers: [] });
  const hit = (hub.servers || []).find((s) => s.codeHash === h);
  if (!hit) return { ok: false };
  const s = store.settings.get();
  store.settings.set({ unlocked: [...new Set([...(s.unlocked || []), h])], selected: hit.id });
  return { ok: true, id: hit.id, name: hit.name };
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

ipcMain.handle('open-folder', (_e, id) => {
  const dir = path.join(store.dir(), 'instances', String(id).replace(/[^a-z0-9_-]/gi, ''));
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

ipcMain.handle('play', async (_e, id, test) => {
  if (playing) return { ok: false, error: 'A game is already running.' };
  const hub = store.read('hub-cache.json', { servers: [] });
  const server = (hub.servers || []).find((s) => s.id === id);
  if (!server) return { ok: false, error: 'That server is not available right now.' };
  if (server.status === 'soon') return { ok: false, error: `${server.name} isn't open yet.` };
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
