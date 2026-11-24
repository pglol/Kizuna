// Asks a Minecraft server how many people are on it (the same "server list ping" the game uses).
const net = require('net');

function varint(n) {
  const out = [];
  do {
    let b = n & 0x7f;
    n >>>= 7;
    if (n) b |= 0x80;
    out.push(b);
  } while (n);
  return Buffer.from(out);
}

function packet(id, ...parts) {
  const body = Buffer.concat([varint(id), ...parts]);
  return Buffer.concat([varint(body.length), body]);
}

function readVarint(buf, at) {
  let n = 0, shift = 0, b;
  do {
    if (at >= buf.length) return null;
    b = buf[at++];
    n |= (b & 0x7f) << shift;
    shift += 7;
  } while (b & 0x80 && shift < 35);
  return [n, at];
}

/** address "host:port" -> { online, max, ms } or null when it doesn't answer. */
function ping(address, timeout = 3500) {
  return new Promise((resolve) => {
    const [host, p] = String(address).trim().split(':');
    const port = Number(p) || 25565;
    if (!host) return resolve(null);
    const started = Date.now();
    const sock = net.connect({ host, port });
    let buf = Buffer.alloc(0);
    const done = (v) => {
      sock.destroy();
      resolve(v);
    };
    sock.setTimeout(timeout, () => done(null));
    sock.on('error', () => done(null));
    sock.on('connect', () => {
      const h = Buffer.from(host, 'utf8');
      const portBuf = Buffer.alloc(2);
      portBuf.writeUInt16BE(port);
      sock.write(packet(0, varint(767), varint(h.length), h, portBuf, varint(1)));
      sock.write(packet(0));
    });
    sock.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      const len = readVarint(buf, 0);
      if (!len || buf.length < len[1] + len[0]) return;
      try {
        let at = len[1];
        const id = readVarint(buf, at);
        at = id[1];
        const sl = readVarint(buf, at);
        const json = JSON.parse(buf.slice(sl[1], sl[1] + sl[0]).toString('utf8'));
        done({ online: json.players ? json.players.online : 0, max: json.players ? json.players.max : 0, ms: Date.now() - started });
      } catch {
        done(null);
      }
    });
  });
}

module.exports = { ping };
