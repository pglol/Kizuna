// Runs against a local `wrangler dev` (PARTY_URL, default ws://127.0.0.1:8787/ws): three launchers
// add each other, form a party, follow the leader's status, and leave.
const URL = process.env.PARTY_URL || 'ws://127.0.0.1:8787/ws';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function client(name) {
  const ws = new WebSocket(URL);
  const c = { ws, last: null, msgs: [], send: (m) => ws.send(JSON.stringify(m)) };
  ws.onmessage = (e) => { const m = JSON.parse(e.data); c.msgs.push(m); if (m.t === 'state') c.last = m; };
  c.ready = new Promise((r) => (ws.onopen = () => { c.send({ t: 'hello', name }); r(); }));
  return c;
}
let fails = 0;
const ok = (cond, what) => { console.log((cond ? 'ok   ' : 'FAIL ') + what); if (!cond) fails++; };
const a = client('Alpha'), b = client('Bravo'), c = client('Charlie');
await Promise.all([a.ready, b.ready, c.ready]);
await sleep(300);
a.send({ t: 'watch', names: ['Bravo', 'Charlie'] });
await sleep(300);
ok(b.last.requests.includes('Alpha'), 'Bravo sees Alpha’s friend request');
ok(a.last.friends.find((f) => f.name === 'Bravo').pending, 'Alpha sees Bravo as not added back yet');
b.send({ t: 'watch', names: ['Alpha'] });
c.send({ t: 'watch', names: ['Alpha'] });
await sleep(300);
ok(a.last.friends.every((f) => f.online && !f.pending), 'friends are mutual and online');
a.send({ t: 'invite', name: 'Bravo' });
a.send({ t: 'invite', name: 'Charlie' });
await sleep(300);
ok(b.last.invites.some((i) => i.from === 'Alpha'), 'Bravo has an invite from Alpha');
b.send({ t: 'accept', from: 'Alpha' });
c.send({ t: 'decline', from: 'Alpha' });
await sleep(300);
ok(b.last.party && b.last.party.leader === 'Alpha' && b.last.party.members.length === 2, 'Bravo joined Alpha’s party');
ok(!c.last.party && !c.last.invites.length, 'Charlie declined');
a.send({ t: 'status', state: 'playing', game: 'aot', gameName: 'Paradis', channel: 'public', address: 'play.example:25565' });
await sleep(300);
const lead = b.last.party.members.find((m) => m.leader);
ok(lead.state === 'playing' && lead.game === 'aot' && lead.address === 'play.example:25565', 'Bravo sees where the leader is playing');
a.send({ t: 'promote', name: 'Bravo' });
await sleep(300);
ok(a.last.party.leader === 'Bravo', 'lead handed to Bravo');
b.send({ t: 'kick', name: 'Alpha' });
await sleep(300);
ok(!a.last.party, 'Alpha kicked, party of one dissolves');
const d = client('Alpha');
await d.ready;
await sleep(300);
ok(a.msgs.some((m) => m.fatal), 'a second launcher with the same name replaces the first');
for (const x of [a, b, c, d]) try { x.ws.close(); } catch {}
console.log(fails ? `${fails} failed` : 'all good');
process.exit(fails ? 1 : 0);
