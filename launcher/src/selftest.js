// A quick check of the pack sync against a pack served from a local web server:
// first install, nothing re-downloaded on the second run, and dropped files removed.
const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const pack = require('./pack');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'wf-'));
const site = path.join(tmp, 'site'), game = path.join(tmp, 'game');
const sha = (b, a = 'sha256') => crypto.createHash(a).update(b).digest('hex');
fs.mkdirSync(path.join(site, 'mods'), { recursive: true });
fs.mkdirSync(path.join(site, 'config'), { recursive: true });
fs.mkdirSync(path.join(site, 'jars'), { recursive: true });
const jar = Buffer.from('fake jar ' + Date.now());
fs.writeFileSync(path.join(site, 'jars', 'cool-mod.jar'), jar);
const cfg = Buffer.from('setting=1\n');
fs.writeFileSync(path.join(site, 'config', 'cool.properties'), cfg);
fs.mkdirSync(path.join(site, 'defaults'), { recursive: true });
const opts = Buffer.from('key_key.jump:key.keyboard.space\n');
fs.writeFileSync(path.join(site, 'defaults', 'options.txt'), opts);

const srv = http.createServer((q, r) => {
  const f = path.join(site, decodeURIComponent(q.url.split('?')[0]));
  if (!f.startsWith(site) || !fs.existsSync(f)) return r.writeHead(404).end();
  r.end(fs.readFileSync(f));
});
srv.listen(0, async () => {
  const base = `http://127.0.0.1:${srv.address().port}/`;
  const meta = `name = "Cool"\nfilename = "cool-mod.jar"\nside = "client"\n\n[download]\nurl = "${base}jars/cool-mod.jar"\nhash-format = "sha512"\nhash = "${sha(jar, 'sha512')}"\n`;
  const srvOnly = `name = "Srv"\nfilename = "srv.jar"\nside = "server"\n\n[download]\nurl = "${base}nope.jar"\nhash-format = "sha1"\nhash = "00"\n`;
  fs.writeFileSync(path.join(site, 'mods', 'cool.pw.toml'), meta);
  fs.writeFileSync(path.join(site, 'mods', 'srv.pw.toml'), srvOnly);
  const index = (withCfg) => `hash-format = "sha256"\n\n[[files]]\nfile = "mods/cool.pw.toml"\nhash = "${sha(meta)}"\nmetafile = true\n\n[[files]]\nfile = "mods/srv.pw.toml"\nhash = "${sha(srvOnly)}"\nmetafile = true\n` + (withCfg ? `\n[[files]]\nfile = "config/cool.properties"\nhash = "${sha(cfg)}"\n` : '') + `\n[[files]]\nfile = "defaults/options.txt"\nhash = "${sha(opts)}"\n`;
  const write = (withCfg) => {
    fs.writeFileSync(path.join(site, 'index.toml'), index(withCfg));
    fs.writeFileSync(path.join(site, 'pack.toml'), `name = "Test"\nversion = "1"\npack-format = "packwiz:1.1.0"\n[index]\nfile = "index.toml"\nhash-format = "sha256"\nhash = "${sha(index(withCfg))}"\n[versions]\nminecraft = "1.21.1"\nfabric = "0.16.10"\n`);
  };
  const ok = (c, m) => {
    if (!c) {
      console.error('FAIL', m);
      process.exit(1);
    }
    console.log('ok  ', m);
  };
  try {
    write(true);
    const a = await pack.sync(base + 'pack.toml', game);
    ok(a.fetched === 2 && fs.existsSync(path.join(game, 'mods', 'cool-mod.jar')), 'first sync installs the mod and the config');
    ok(!fs.existsSync(path.join(game, 'mods', 'srv.jar')), 'server-only mods are skipped');
    ok(fs.readFileSync(path.join(game, 'options.txt')).equals(opts), "the pack's default settings are put in place");
    ok(a.minecraft === '1.21.1' && a.fabric === '0.16.10', 'reads the Minecraft and Fabric versions');
    const b = await pack.sync(base + 'pack.toml', game);
    ok(b.fetched === 0, 'second sync downloads nothing');
    fs.writeFileSync(path.join(game, 'options.txt'), 'mine');
    write(false);
    await pack.sync(base + 'pack.toml', game);
    ok(!fs.existsSync(path.join(game, 'config', 'cool.properties')), 'a file dropped from the pack is removed');
    ok(fs.readFileSync(path.join(game, 'options.txt'), 'utf8') === 'mine', "default settings aren't put back over the player's changes");
    fs.writeFileSync(path.join(game, 'mods', 'cool-mod.jar'), 'tampered');
    const c = await pack.sync(base + 'pack.toml', game);
    ok(c.fetched === 1 && fs.readFileSync(path.join(game, 'mods', 'cool-mod.jar')).equals(jar), 'a changed or broken file is replaced');
    console.log('all good');
  } catch (e) {
    console.error('FAIL', e);
    process.exit(1);
  } finally {
    srv.close();
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});
