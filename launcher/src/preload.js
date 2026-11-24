const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('kizuna', {
  init: () => ipcRenderer.invoke('init'),
  hub: () => ipcRenderer.invoke('hub'),
  unlock: (code) => ipcRenderer.invoke('unlock', code),
  login: () => ipcRenderer.invoke('login'),
  logout: () => ipcRenderer.invoke('logout'),
  play: (id, test) => ipcRenderer.invoke('play', id, !!test),
  openLogs: () => ipcRenderer.invoke('open-logs'),
  ping: (address) => ipcRenderer.invoke('ping', address),
  openUrl: (url) => window.open(url),
  settings: (patch) => ipcRenderer.invoke('settings', patch),
  openFolder: (id) => ipcRenderer.invoke('open-folder', id),
  win: (what) => ipcRenderer.invoke('win', what),
  installUpdate: () => ipcRenderer.invoke('update-install'),
  on: (ch, fn) => {
    if (!['status', 'exited', 'update'].includes(ch)) return;
    ipcRenderer.on(ch, (_e, d) => fn(d));
  },
});
