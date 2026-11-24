// The launcher screen. Everything visible about a server (background, logo, icon, news) comes from
// servers/hub.json; nothing is drawn or invented here.
(function () {
  const K = window.kizuna;
  const $ = (id) => document.getElementById(id);
  const state = { account: null, settings: {}, servers: [], selected: null, busy: false, playing: false, test: false, testMode: false, offline: false };
  const pings = {};

  function toast(msg) {
    const t = $('toast');
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toast.h);
    toast.h = setTimeout(() => (t.hidden = true), 6000);
  }
  const show = (id, on) => ($(id).hidden = !on);
  document.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => (b.closest('.overlay').hidden = true)));
  document.querySelectorAll('[data-win]').forEach((b) => b.addEventListener('click', () => K.win(b.dataset.win)));

  const current = () => state.servers.find((s) => s.id === state.selected);
  const initials = (name) => {
    const w = name.replace(/[^A-Za-z0-9 ]/g, '').split(/\s+/).filter(Boolean);
    return (w.length > 1 ? w[0][0] + w[1][0] : (w[0] || '?').slice(0, 2)).toUpperCase();
  };

  function renderAccount() {
    const a = state.account, b = $('account');
    b.style.backgroundImage = '';
    b.textContent = '';
    if (a) {
      b.style.backgroundImage = `url("https://mc-heads.net/avatar/${a.uuid}/72")`;
      b.title = a.name;
    } else {
      b.textContent = state.test ? 'TEST' : '?';
      b.title = 'Sign in';
    }
  }

  function renderRail() {
    const rail = $('rail');
    rail.innerHTML = '';
    for (const s of state.servers) {
      const t = document.createElement('button');
      t.className = 'srv' + (s.id === state.selected ? ' on' : '');
      t.title = s.name;
      if (s.icon) t.style.backgroundImage = `url("${s.icon}")`;
      else t.textContent = (s.short || initials(s.name)).slice(0, 3);
      if (s.status === 'soon' || s.status === 'private') {
        const l = document.createElement('span');
        l.className = 'lock';
        l.innerHTML = '<svg viewBox="0 0 24 24" width="11" height="11"><path fill="#9b9ca3" d="M7 10V8a5 5 0 0 1 10 0v2h1v11H6V10h1zm2 0h6V8a3 3 0 0 0-6 0v2z"/></svg>';
        t.append(l);
      }
      t.addEventListener('click', () => select(s.id));
      rail.append(t);
    }
    const add = document.createElement('button');
    add.className = 'srv add';
    add.title = 'Enter an access code';
    add.textContent = '+';
    add.addEventListener('click', () => {
      $('code-input').value = '';
      $('code-err').textContent = '';
      show('code', true);
      setTimeout(() => $('code-input').focus(), 50);
    });
    rail.append(add);
  }

  function setBackground(s) {
    const img = $('bg-img'), vid = $('bg-video');
    const src = s && s.background;
    const isVideo = src && /\.(mp4|webm)(\?|$)/i.test(src);
    vid.hidden = !isVideo;
    img.hidden = !src || isVideo;
    if (isVideo) {
      if (vid.getAttribute('src') !== src) vid.src = src;
      vid.play().catch(() => {});
    } else {
      vid.pause();
      vid.removeAttribute('src');
      if (src) img.src = src;
    }
  }

  function renderNews(s) {
    const box = $('news');
    box.innerHTML = '';
    for (const n of (s && s.news) || []) {
      const c = document.createElement('button');
      c.className = 'card';
      if (n.image) c.style.backgroundImage = `url("${n.image}")`;
      if (n.tag) {
        const tag = document.createElement('span');
        tag.className = 'tag';
        tag.textContent = n.tag;
        c.append(tag);
      }
      const txt = document.createElement('div');
      txt.className = 'txt';
      const b = document.createElement('b');
      b.textContent = n.title || '';
      const sm = document.createElement('small');
      sm.textContent = n.date || '';
      txt.append(b, sm);
      c.append(txt);
      if (n.url) c.addEventListener('click', () => K.openUrl(n.url));
      else c.style.cursor = 'default';
      box.append(c);
    }
    box.hidden = !box.children.length;
  }

  function renderHero() {
    const s = current();
    const hero = $('hero');
    hero.style.animation = 'none';
    void hero.offsetWidth;
    hero.style.animation = '';
    setBackground(s);
    renderNews(s);
    if (!s) {
      $('logo').hidden = true;
      $('title').hidden = false;
      $('title').textContent = state.offline ? "Can't reach the server list" : 'No servers yet';
      $('tagline').textContent = state.offline ? 'Check your internet connection and reopen the launcher.' : '';
      $('online').textContent = '';
      updateDock();
      return;
    }
    document.documentElement.style.setProperty('--accent', s.accent || '#d0603a');
    const logo = $('logo');
    if (s.logo) {
      logo.src = s.logo;
      logo.alt = s.name;
      logo.hidden = false;
      $('title').hidden = true;
    } else {
      logo.hidden = true;
      $('title').hidden = false;
      $('title').textContent = s.name;
    }
    $('tagline').textContent = s.tagline || '';
    renderOnline(s);
    refreshPing(s);
    updateDock();
  }

  function renderOnline(s) {
    const el = $('online');
    el.classList.remove('up');
    if (s.status === 'soon') {
      el.textContent = 'Coming soon';
      return;
    }
    const p = pings[s.id];
    if (p === undefined) el.textContent = '';
    else if (p === null) el.textContent = 'Server offline';
    else {
      el.classList.add('up');
      el.textContent = `${p.online} playing now`;
    }
  }

  async function refreshPing(s) {
    if (!s || s.status === 'soon') return;
    pings[s.id] = await K.ping(s.address);
    if (current() === s) renderOnline(s);
  }
  setInterval(() => current() && refreshPing(current()), 30000);

  function updateDock() {
    const s = current();
    const play = $('play'), label = play.querySelector('span');
    $('folder').hidden = !s || s.status === 'soon';
    $('progress').hidden = !(state.busy || state.playing);
    if (state.playing) {
      play.disabled = true;
      label.textContent = 'Playing';
    } else if (state.busy) {
      play.disabled = true;
      label.textContent = 'Starting';
    } else if (!s || s.status === 'soon') {
      play.disabled = true;
      label.textContent = s ? 'Coming soon' : 'Play';
    } else {
      play.disabled = false;
      label.textContent = state.account || state.test ? 'Play' : 'Sign in';
    }
  }

  function select(id) {
    state.selected = id;
    K.settings({ selected: id });
    renderRail();
    renderHero();
  }

  async function loadHub() {
    const hub = await K.hub();
    state.servers = hub.servers || [];
    state.offline = !!hub.offline;
    if (!current()) {
      const first = state.servers.find((s) => s.status !== 'soon') || state.servers[0];
      state.selected = first ? first.id : null;
    }
    renderRail();
    renderHero();
    if (hub.offline && state.servers.length) toast("Couldn't refresh the server list, showing the last one.");
  }

  async function login() {
    const err = $('signin-err');
    err.textContent = '';
    err.classList.remove('err');
    const r = await K.login();
    if (r.ok) {
      state.account = r.account;
      show('signin', false);
      renderAccount();
      updateDock();
    } else if (r.code !== 'cancelled') {
      err.textContent = r.code === 'appid' || r.code === 'noclient' ? r.error + ' Tell the server admin.' : r.error;
      err.classList.add('err');
    }
  }

  async function play() {
    const s = current();
    if (!s || state.busy || state.playing) return;
    if (!state.account && !state.test) {
      show('signin', true);
      return;
    }
    state.busy = true;
    $('ptext').textContent = 'Getting ready';
    $('pfill').style.width = '0';
    updateDock();
    const r = await K.play(s.id, !state.account && state.test);
    state.busy = false;
    if (!r.ok) {
      if (r.code === 'signin') {
        state.account = null;
        renderAccount();
        show('signin', true);
      }
      toast(r.error);
    } else state.playing = true;
    updateDock();
  }

  K.on('status', (d) => {
    $('ptext').textContent = d.text;
    $('pfill').style.width = Math.round((d.fraction || 0) * 100) + '%';
    if (d.playing) {
      state.playing = true;
      updateDock();
    }
  });
  K.on('exited', (d) => {
    state.playing = false;
    state.busy = false;
    updateDock();
    if (d.code) {
      $('crash-log').textContent = d.log || '(no log)';
      show('crash', true);
    }
  });
  K.on('update', (d) => {
    const u = $('update');
    u.hidden = false;
    u.textContent = d.state === 'ready' ? `Update ${d.version} ready.` : `Downloading update ${d.version}…`;
    if (d.state === 'ready') {
      const b = document.createElement('button');
      b.textContent = 'Restart';
      b.onclick = () => K.installUpdate();
      u.append(b);
    }
  });

  $('play').addEventListener('click', play);
  $('login').addEventListener('click', login);
  $('test-go').addEventListener('click', () => {
    state.test = true;
    show('signin', false);
    renderAccount();
    updateDock();
  });
  $('folder').addEventListener('click', () => state.selected && K.openFolder(state.selected));
  $('account').addEventListener('click', () => (state.account ? openSettings() : show('signin', true)));
  $('open-settings').addEventListener('click', openSettings);
  $('logs').addEventListener('click', () => K.openLogs());
  $('crash-copy').addEventListener('click', () => navigator.clipboard.writeText($('crash-log').textContent));

  function openSettings() {
    const s = state.settings;
    $('ram').value = s.ram;
    $('ram-label').textContent = s.ram + ' GB';
    $('close-on-play').checked = !!s.closeOnPlay;
    $('who').textContent = state.account ? 'Signed in as ' + state.account.name : 'Not signed in';
    $('logout').hidden = !state.account;
    show('settings', true);
  }
  $('ram').addEventListener('input', async (e) => {
    $('ram-label').textContent = e.target.value + ' GB';
    state.settings = await K.settings({ ram: Number(e.target.value) });
  });
  $('close-on-play').addEventListener('change', async (e) => (state.settings = await K.settings({ closeOnPlay: e.target.checked })));
  $('logout').addEventListener('click', async () => {
    await K.logout();
    state.account = null;
    renderAccount();
    updateDock();
    show('settings', false);
  });

  async function unlock() {
    const r = await K.unlock($('code-input').value);
    if (!r.ok) {
      $('code-err').textContent = "That code doesn't match any server.";
      $('code-err').classList.add('err');
      return;
    }
    show('code', false);
    state.selected = r.id;
    await loadHub();
    toast(`Unlocked ${r.name}.`);
  }
  $('code-go').addEventListener('click', unlock);
  $('code-input').addEventListener('keydown', (e) => e.key === 'Enter' && unlock());
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') document.querySelectorAll('.overlay').forEach((o) => o.id !== 'signin' && (o.hidden = true));
  });

  (async () => {
    const init = await K.init();
    document.title = init.name;
    if (init.mark) $('mark').style.backgroundImage = `url("${init.mark}")`;
    $('about').textContent = `${init.name} Launcher ${init.version}`;
    state.account = init.account;
    state.settings = init.settings;
    state.selected = init.settings.selected;
    state.testMode = !!init.testMode;
    $('test-go').hidden = !state.testMode;
    $('testtag').hidden = !state.testMode;
    renderAccount();
    await loadHub();
    if (!state.account) show('signin', true);
  })();
})();
