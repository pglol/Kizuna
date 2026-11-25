// Kizuna party service.
//
// Every launcher keeps one WebSocket open here. Through it a player says who they are, what they're
// doing (in the launcher, or playing which game on which server) and who their friends are; and they
// form parties: invite, accept, leave, kick, hand over the lead. In return each launcher is sent its
// own picture of the world whenever something changes: its friends and what they're doing, who has
// added it, its party and any invites.
//
// Friends see each other once both have added each other (one-way adds show up as requests). Party
// members always see each other. Names are checked against Minecraft when the launcher signs in with
// Microsoft; test logins are marked as such and can't take a name a signed-in player is using.
//
// One Durable Object holds everything (fine for a community this size). Sockets use the hibernation
// API, so an idle hub costs nothing; parties are kept in its storage, sessions in the sockets.
import { DurableObject } from 'cloudflare:workers';

const NAME = /^[A-Za-z0-9_]{3,16}$/;
const MAX_PARTY = 5;
const INVITE_MS = 2 * 60 * 1000;
const MAX_WATCH = 300;
const STATES = new Set(['launcher', 'playing']);

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === '/health') return new Response('ok');
    if (url.pathname === '/ws') {
      if (req.headers.get('Upgrade') !== 'websocket') return new Response('Expected a WebSocket', { status: 426 });
      return env.HUB.get(env.HUB.idFromName('main')).fetch(req);
    }
    return new Response('Kizuna party service', { headers: { 'content-type': 'text/plain' } });
  },
};

const clip = (v, n = 64) => (typeof v === 'string' ? v.slice(0, n) : '');
const key = (name) => String(name || '').toLowerCase();

export class Hub extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.parties = null; // id -> { id, leader, members: [key], names: {key: name}, invites: {key: expiry} }
  }

  async load() {
    if (this.parties) return;
    this.parties = new Map();
    for (const [, p] of await this.ctx.storage.list({ prefix: 'party:' })) this.parties.set(p.id, p);
  }

  async save(p) {
    if (p.members.length) await this.ctx.storage.put('party:' + p.id, p);
    else {
      this.parties.delete(p.id);
      await this.ctx.storage.delete('party:' + p.id);
    }
  }

  async fetch() {
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({ name: null });
    return new Response(null, { status: 101, webSocket: client });
  }

  // ---- sessions (one per launcher, kept on the socket itself) ----

  sessions() {
    const out = [];
    for (const ws of this.ctx.getWebSockets()) {
      const a = ws.deserializeAttachment();
      if (a && a.name) out.push({ ws, a });
    }
    return out;
  }

  find(k) {
    return this.sessions().find((s) => s.a.key === k) || null;
  }

  partyOf(k) {
    for (const p of this.parties.values()) if (p.members.includes(k)) return p;
    return null;
  }

  // ---- messages ----

  async webSocketMessage(ws, data) {
    if (typeof data !== 'string' || data.length > 8192) return;
    let m;
    try {
      m = JSON.parse(data);
    } catch {
      return;
    }
    await this.load();
    const me = ws.deserializeAttachment() || {};
    if (m.t === 'hello') return this.hello(ws, m);
    if (!me.name) return send(ws, { t: 'error', msg: 'Say hello first.' });
    if (m.t === 'ping') return send(ws, { t: 'pong' });
    const handler = {
      status: () => this.status(ws, me, m),
      watch: () => this.watch(ws, me, m),
      invite: () => this.invite(me, m),
      accept: () => this.accept(me, m),
      decline: () => this.decline(me, m),
      leave: () => this.leave(me.key),
      kick: () => this.kick(me, m),
      promote: () => this.promote(me, m),
    }[m.t];
    if (!handler) return;
    const err = await handler();
    if (err) send(ws, { t: 'error', msg: err });
    this.broadcast();
  }

  async hello(ws, m) {
    const name = clip(m.name, 16);
    if (!NAME.test(name)) return send(ws, { t: 'error', msg: 'That isn’t a Minecraft name.', fatal: true });
    let verified = false, shown = name;
    if (m.token) {
      try {
        const r = await fetch('https://api.minecraftservices.com/minecraft/profile', { headers: { Authorization: 'Bearer ' + m.token } });
        const p = r.ok ? await r.json() : null;
        if (p && key(p.name) === key(name)) {
          verified = true;
          shown = p.name;
        }
      } catch {}
      if (!verified) return send(ws, { t: 'error', msg: 'Couldn’t check your Minecraft account. Try signing in again.', fatal: true });
    }
    const k = key(shown);
    const other = this.find(k);
    if (other && other.ws !== ws) {
      // A signed-in player always wins their own name; otherwise the newest launcher takes over.
      if (other.a.verified && !verified) return send(ws, { t: 'error', msg: `${shown} is signed in already.`, fatal: true });
      send(other.ws, { t: 'error', msg: 'Signed in on another computer.', fatal: true });
      try {
        other.ws.close(4000, 'replaced');
      } catch {}
    }
    const a = { name: shown, key: k, verified, status: { state: 'launcher' }, watch: [] };
    ws.serializeAttachment(a);
    send(ws, { t: 'welcome', you: shown, verified });
    this.broadcast();
  }

  status(ws, me, m) {
    const state = STATES.has(m.state) ? m.state : 'launcher';
    me.status = state === 'playing'
      ? { state, game: clip(m.game, 32), gameName: clip(m.gameName, 48), channel: clip(m.channel, 32), address: clip(m.address, 96) }
      : { state };
    ws.serializeAttachment(me);
  }

  watch(ws, me, m) {
    const names = Array.isArray(m.names) ? m.names : [];
    me.watch = [...new Set(names.map((n) => clip(n, 16)).filter((n) => NAME.test(n)).map(key))].slice(0, MAX_WATCH);
    ws.serializeAttachment(me);
  }

  mutual(a, b) {
    const sa = this.find(a), sb = this.find(b);
    return !!(sa && sb && sa.a.watch.includes(b) && sb.a.watch.includes(a));
  }

  async invite(me, m) {
    const to = key(m.name);
    if (to === me.key) return 'You can’t invite yourself.';
    const them = this.find(to);
    if (!them) return `${clip(m.name, 16)} isn’t online.`;
    if (!this.mutual(me.key, to)) return `Add ${them.a.name} as a friend first (and they need to add you).`;
    let p = this.partyOf(me.key);
    if (!p) {
      p = { id: crypto.randomUUID(), leader: me.key, members: [me.key], names: { [me.key]: me.name }, invites: {} };
      this.parties.set(p.id, p);
    }
    if (p.members.includes(to)) return `${them.a.name} is already in your party.`;
    if (p.members.length >= MAX_PARTY) return 'Your party is full.';
    p.invites[to] = Date.now() + INVITE_MS;
    p.names[to] = them.a.name;
    await this.save(p);
    send(them.ws, { t: 'invited', from: me.name, size: p.members.length });
  }

  async accept(me, m) {
    const from = key(m.from);
    const p = this.partyOf(from);
    if (!p || !(p.invites[me.key] > Date.now())) return 'That invite has run out.';
    if (p.members.length >= MAX_PARTY) return 'That party is full.';
    const old = this.partyOf(me.key);
    if (old && old.id !== p.id) await this.leave(me.key);
    delete p.invites[me.key];
    p.members.push(me.key);
    p.names[me.key] = me.name;
    await this.save(p);
  }

  async decline(me, m) {
    const p = this.partyOf(key(m.from));
    if (!p || !p.invites[me.key]) return;
    delete p.invites[me.key];
    await this.save(p);
  }

  async leave(k) {
    const p = this.partyOf(k);
    if (!p) return;
    p.members = p.members.filter((x) => x !== k);
    if (p.leader === k) p.leader = p.members[0] || null;
    // A party of one with nobody invited is just a person.
    if (p.members.length === 1 && !Object.values(p.invites).some((t) => t > Date.now())) p.members = [];
    await this.save(p);
  }

  async kick(me, m) {
    const p = this.partyOf(me.key);
    if (!p || p.leader !== me.key) return 'Only the party leader can do that.';
    const k = key(m.name);
    if (k === me.key || !p.members.includes(k)) return;
    await this.leave(k);
    const them = this.find(k);
    if (them) send(them.ws, { t: 'kicked', by: me.name });
  }

  async promote(me, m) {
    const p = this.partyOf(me.key);
    if (!p || p.leader !== me.key) return 'Only the party leader can do that.';
    const k = key(m.name);
    if (!p.members.includes(k)) return;
    p.leader = k;
    await this.save(p);
  }

  // ---- what each launcher is told ----

  view(s, now) {
    const online = new Map(this.sessions().map((x) => [x.a.key, x.a]));
    const look = (k) => {
      const a = online.get(k);
      return a ? { online: true, ...a.status } : { online: false };
    };
    const friends = s.a.watch.map((k) => {
      const a = online.get(k);
      const back = a && a.watch.includes(s.a.key);
      if (!a) return { name: k, online: false };
      return back ? { name: a.name, verified: a.verified, ...look(k) } : { name: a.name, pending: true, online: false };
    });
    const requests = [];
    for (const [k, a] of online) if (k !== s.a.key && a.watch.includes(s.a.key) && !s.a.watch.includes(k)) requests.push(a.name);
    const p = this.partyOf(s.a.key);
    const party = p && {
      leader: p.names[p.leader] || p.leader,
      members: p.members.map((k) => ({ name: p.names[k] || k, leader: k === p.leader, you: k === s.a.key, ...look(k) })),
      invited: Object.entries(p.invites).filter(([, t]) => t > now).map(([k]) => p.names[k] || k),
    };
    const invites = [];
    for (const q of this.parties.values()) {
      if (q.invites[s.a.key] > now && (!p || q.id !== p.id)) invites.push({ from: q.names[q.leader] || q.leader, size: q.members.length });
    }
    return { t: 'state', you: s.a.name, verified: s.a.verified, friends, requests, party, invites };
  }

  broadcast() {
    const now = Date.now();
    for (const s of this.sessions()) send(s.ws, this.view(s, now));
  }

  async webSocketClose(ws) {
    await this.gone(ws);
  }

  async webSocketError(ws) {
    await this.gone(ws);
  }

  async gone(ws) {
    await this.load();
    const a = ws.deserializeAttachment();
    try {
      ws.close(1000, 'bye');
    } catch {}
    if (a && a.key) {
      // Parties outlive a closed launcher (it's back in a moment), but not everyone leaving.
      const p = this.partyOf(a.key);
      const online = new Set(this.sessions().filter((s) => s.ws !== ws).map((s) => s.a.key));
      if (p && !p.members.some((k) => online.has(k))) {
        p.members = [];
        await this.save(p);
      }
    }
    ws.serializeAttachment({ name: null });
    this.broadcast();
  }
}

function send(ws, m) {
  try {
    ws.send(JSON.stringify(m));
  } catch {}
}
