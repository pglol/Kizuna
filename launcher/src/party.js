// The launcher's line to the party service: one WebSocket that says who you are and what you're doing
// (in the launcher, or playing which game where), keeps your friends list there, and passes party
// actions through. Everything the service sends comes back to the window as 'party' events.
// While a game is running, the party is also written to <game>/config/kizuna/party.json so the game
// can mirror it (the Paradis mod puts the same people in your in-game party).
const fs = require('fs');
const path = require('path');

const ACTIONS = new Set(['watch', 'invite', 'accept', 'decline', 'leave', 'kick', 'promote']);

let url = '';
let ws = null;
let identity = async () => null; // -> { name, token? } or null when signed out
let onEvent = () => {};
let onFatal = async () => false; // a chance to refresh the sign-in; true = try again
let status = { state: 'launcher' };
let watch = [];
let gameDir = null;
let last = null;
let retry = 0;
let timer = null;
let pinger = null;
let gen = 0;

function configure(opts) {
  if (opts.identity) identity = opts.identity;
  if (opts.onEvent) onEvent = opts.onEvent;
  if (opts.onFatal) onFatal = opts.onFatal;
  if (opts.url !== undefined && opts.url !== url) {
    url = opts.url || '';
    restart();
  }
}

function raw(m) {
  if (ws && ws.readyState === 1) ws.send(JSON.stringify(m));
}

/** Reconnect from scratch (signed in or out, the service moved, ...). */
function restart() {
  const my = ++gen;
  clearTimeout(timer);
  clearInterval(pinger);
  if (ws) {
    try {
      ws.close();
    } catch {}
    ws = null;
  }
  last = null;
  onEvent({ t: 'connection', up: false });
  if (!url) return;
  connect(my);
}

async function connect(my) {
  const who = await identity();
  if (my !== gen) return;
  if (!who) return; // not signed in: nothing to say
  let sock;
  try {
    sock = new WebSocket(url);
  } catch {
    return later(my);
  }
  ws = sock;
  let fatal = false;
  sock.onopen = () => {
    retry = 0;
    raw({ t: 'hello', name: who.name, token: who.token || undefined });
    raw({ t: 'status', ...status });
    raw({ t: 'watch', names: watch });
    onEvent({ t: 'connection', up: true });
    clearInterval(pinger);
    pinger = setInterval(() => raw({ t: 'ping' }), 45000);
  };
  sock.onmessage = async (e) => {
    let m;
    try {
      m = JSON.parse(e.data);
    } catch {
      return;
    }
    if (m.t === 'pong' || m.t === 'welcome') return;
    if (m.t === 'state') {
      last = m;
      writeGameFile();
    }
    if (m.t === 'error' && m.fatal) {
      fatal = true;
      if (await onFatal(m)) return restart();
    }
    onEvent(m);
  };
  sock.onclose = () => {
    clearInterval(pinger);
    if (my !== gen) return;
    ws = null;
    onEvent({ t: 'connection', up: false });
    if (!fatal) later(my);
  };
  sock.onerror = () => {};
}

function later(my) {
  clearTimeout(timer);
  const wait = Math.min(30000, 2000 * 2 ** Math.min(4, retry++));
  timer = setTimeout(() => my === gen && connect(my), wait);
}

function setStatus(s) {
  status = s && s.state === 'playing' ? s : { state: 'launcher' };
  raw({ t: 'status', ...status });
}

function setWatch(names) {
  watch = (names || []).slice(0, 300);
  raw({ t: 'watch', names: watch });
}

function act(m) {
  if (!m || !ACTIONS.has(m.t)) return false;
  if (m.t === 'watch') return setWatch(m.names), true;
  raw(m);
  return !!(ws && ws.readyState === 1);
}

/** The running game's folder (null when it closes): the party is mirrored there. */
function setGameDir(dir) {
  gameDir = dir;
  writeGameFile();
}

function writeGameFile() {
  if (!gameDir) return;
  const p = last && last.party;
  const data = p
    ? { you: last.you, leader: p.leader, members: p.members.map((x) => ({ name: x.name, online: !!x.online, state: x.state || 'offline', game: x.gameName || '' })) }
    : { you: last ? last.you : '', leader: '', members: [] };
  try {
    const dir = path.join(gameDir, 'config', 'kizuna');
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'party.json.tmp'), JSON.stringify(data));
    fs.renameSync(path.join(dir, 'party.json.tmp'), path.join(dir, 'party.json'));
  } catch {}
}

module.exports = { configure, restart, setStatus, setWatch, act, setGameDir, state: () => last };
