const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('kizuna', {
  init: () => ipcRenderer.invoke('init'),
  hub: () => ipcRenderer.invoke('hub'),
  unlock: (code) => ipcRenderer.invoke('unlock', code),
  login: () => ipcRenderer.invoke('login'),
  logout: () => ipcRenderer.invoke('logout'),
  play: (id, test, channel, host) => ipcRenderer.invoke('play', id, !!test, channel, host),
  openLogs: () => ipcRenderer.invoke('open-logs'),
  ping: (address) => ipcRenderer.invoke('ping', address),
  friends: (list) => ipcRenderer.invoke('friends', list),
  notify: (title, body) => ipcRenderer.invoke('notify', title, body),
  openUrl: (url) => window.open(url),
  settings: (patch) => ipcRenderer.invoke('settings', patch),
  openFolder: (id, channel) => ipcRenderer.invoke('open-folder', id, channel),
  win: (what) => ipcRenderer.invoke('win', what),
  installUpdate: () => ipcRenderer.invoke('update-install'),
  downloadUpdate: () => ipcRenderer.invoke('update-download'),
  checkUpdate: () => ipcRenderer.invoke('update-check'),
  hostInfo: (id, ch) => ipcRenderer.invoke('host-info', id, ch),
  hostFolder: (id, ch) => ipcRenderer.invoke('host-folder', id, ch),
  hostOpen: (id, ch) => ipcRenderer.invoke('host-open', id, ch),
  hostSet: (id, ch, patch) => ipcRenderer.invoke('host-set', id, ch, patch),
  hostPropsFile: (id, ch) => ipcRenderer.invoke('host-props-file', id, ch),
  hostConsole: (id, ch) => ipcRenderer.invoke('host-console', id, ch),
  hostStart: (id, ch) => ipcRenderer.invoke('host-start', id, ch),
  hostStop: (id, ch) => ipcRenderer.invoke('host-stop', id, ch),
  hostCmd: (id, ch, text) => ipcRenderer.invoke('host-cmd', id, ch, text),
  on: (ch, fn) => {
    if (!['status', 'exited', 'update', 'host'].includes(ch)) return;
    ipcRenderer.on(ch, (_e, d) => fn(d));
  },
});
