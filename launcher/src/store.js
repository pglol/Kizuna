// Settings and the signed-in account, kept in the app's data folder. The Microsoft refresh token
// is encrypted with the OS keychain (safeStorage) when it's available.
const fs = require('fs');
const path = require('path');
const os = require('os');
const { app, safeStorage } = require('electron');

const dir = () => app.getPath('userData');
const read = (name, def) => {
  try {
    return JSON.parse(fs.readFileSync(path.join(dir(), name), 'utf8'));
  } catch {
    return def;
  }
};
const write = (name, data) => {
  fs.mkdirSync(dir(), { recursive: true });
  fs.writeFileSync(path.join(dir(), name), JSON.stringify(data, null, 2));
};

function defaults() {
  const gb = Math.floor(os.totalmem() / 1024 ** 3);
  return { ram: Math.max(2, Math.min(8, Math.floor(gb / 2))), closeOnPlay: true, selected: null, unlocked: [] };
}

const settings = {
  get() {
    return Object.assign(defaults(), read('settings.json', {}));
  },
  set(patch) {
    const s = Object.assign(settings.get(), patch);
    write('settings.json', s);
    return s;
  },
};

const account = {
  get() {
    const a = read('account.json', null);
    if (!a) return null;
    if (a.enc && a.refresh) {
      try {
        a.refresh = safeStorage.decryptString(Buffer.from(a.refresh, 'base64'));
      } catch {
        return null;
      }
    }
    return a;
  },
  set(a) {
    if (!a) {
      try {
        fs.unlinkSync(path.join(dir(), 'account.json'));
      } catch {}
      return;
    }
    const out = Object.assign({}, a);
    if (safeStorage.isEncryptionAvailable()) {
      out.refresh = safeStorage.encryptString(a.refresh).toString('base64');
      out.enc = true;
    }
    write('account.json', out);
  },
};

module.exports = { settings, account, read, write, dir };
