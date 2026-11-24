// The launcher screen: the shelf of servers, the page for the chosen one, Play, friends.
(function () {
  const K = window.kizuna;
  const $ = (id) => document.getElementById(id);
  const state = { account: null, settings: {}, servers: [], selected: null, busy: false, playing: false, test: false, testMode: false, offline: false, friends: [] };
  const pings = {};

  function toast(msg) {
    const t = $('toast');
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toast.h);
    toast.h = setTimeout(() => (t.hidden = true), 5000);
  }
  const show = (id, on) => ($(id).hidden = !on);
  document.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => (b.closest('.overlay').hidden = true)));
  document.querySelectorAll('[data-win]').forEach((b) => b.addEventListener('click', () => K.win(b.dataset.win)));
  const current = () => state.servers.find((s) => s.id === state.selected);
  const initials = (n) => {
    const w = n.replace(/[^A-Za-z0-9 ]/g, '').split(/\s+/).filter(Boolean);
    return (w.length > 1 ? w[0][0] + w[1][0] : (w[0] || '?').slice(0, 2)).toUpperCase();
  };
  const head = (name) => `https://mc-heads.net/avatar/${encodeURIComponent(name)}/64`;
  /** A player's face, or their initial in a box if the picture can't load. */
  function face(name, cls) {
    const box = document.createElement('span');
    box.className = 'face ' + (cls || '');
    box.textContent = (name || '?')[0].toUpperCase();
    const img = new Image();
    img.onload = () => {
      box.textContent = '';
      box.style.backgroundImage = `url("${img.src}")`;
    };
    img.src = head(name);
    return box;
  }
  /** Sets a background picture only once it has actually loaded. */
  function bgOnLoad(el, url) {
    if (!url) return;
    const img = new Image();
    img.onload = () => (el.style.backgroundImage = `url("${url}")`);
    img.src = url;
  }

  const STAMP = {
    public: ['OPEN', '#2f7d4a'],
    beta: ['BETA', '#6b3fa0'],
    private: ['PRIVATE', '#15120f'],
    soon: ['COMING SOON', '#7a7064'],
  };

  function renderAccount() {
    const b = $('account');
    b.innerHTML = '';
    b.classList.toggle('none', !state.account);
    if (!state.account) {
      b.textContent = state.test ? 'Test' : 'Sign in';
      return;
    }
    b.append(face(state.account.name), document.createTextNode(state.account.name));
  }

  const LOCK = '<svg viewBox="0 0 24 24"><path d="M7 10V8a5 5 0 0 1 10 0v2h1v11H6V10h1zm2 0h6V8a3 3 0 0 0-6 0v2z"/></svg>';

  function openCode(forWhat) {
    $('code-input').value = '';
    $('code-err').textContent = '';
    $('code-for').textContent = forWhat ? `Enter the code for ${forWhat}.` : 'For Early Access, Development or a private server.';
    show('code', true);
    setTimeout(() => $('code-input').focus(), 50);
  }

  /** Servers as bookmark tabs along the top of the page. */
  function renderRail() {
    const rail = $('rail');
    rail.innerHTML = '';
    for (const s of state.servers) {
      const t = document.createElement('button');
      t.className = 'tab' + (s.id === state.selected ? ' on' : '');
      t.style.setProperty('--tc', s.accent || '#c4372a');
      t.title = s.name;
      const ic = document.createElement('span');
      ic.className = 'ic';
      ic.textContent = (s.short || initials(s.name)).slice(0, 3);
      if (s.icon) {
        const im = new Image();
        im.onload = () => {
          ic.textContent = '';
          ic.style.backgroundImage = `url("${s.icon}")`;
        };
        im.src = s.icon;
      }
      const name = document.createElement('span');
      name.textContent = s.name;
      t.append(ic, name);
      if (s.status === 'soon') {
        const soon = document.createElement('span');
        soon.className = 'soon';
        soon.textContent = 'soon';
        t.append(soon);
      }
      t.addEventListener('click', () => select(s.id));
      rail.append(t);
    }
    const add = document.createElement('button');
    add.className = 'tab add';
    add.title = 'Enter an access code';
    add.textContent = '+';
    add.addEventListener('click', () => openCode());
    rail.append(add);
  }

  /** The build the player picked for a server (Public unless they chose another they can use). */
  function channelOf(s) {
    const list = (s && s.channels) || [];
    const want = (state.settings.channels || {})[s && s.id];
    const c = list.find((x) => x.id === want && !x.locked);
    return c || list.find((x) => !x.locked) || list[0] || { id: 'public', name: 'Public' };
  }

  function renderChannels(s) {
    const box = $('chans');
    box.innerHTML = '';
    const list = (s && s.channels) || [];
    $('chans').closest('.builds').hidden = !s || s.status === 'soon' || list.length < 2;
    const cur = channelOf(s);
    for (const c of list) {
      const b = document.createElement('button');
      b.className = 'chan' + (c.id === cur.id ? ' on' : '') + (c.locked ? ' locked' : '');
      b.innerHTML = c.locked ? LOCK : '';
      b.append(document.createTextNode(c.name));
      b.title = c.note || c.name;
      b.addEventListener('click', async () => {
        if (state.busy || state.playing) return;
        if (c.locked) return openCode(`${s.name}: ${c.name}`);
        state.settings = await K.settings({ channels: { ...(state.settings.channels || {}), [s.id]: c.id } });
        renderChannels(s);
        refreshPing(s);
      });
      box.append(b);
    }
    $('chan-note').textContent = cur.note || '';
  }

  function renderNews(s) {
    const box = $('news');
    box.innerHTML = '';
    for (const n of ((s && s.news) || []).slice(0, 3)) {
      const c = document.createElement('button');
      c.className = 'npanel';
      if (n.image) {
        const im = new Image();
        im.onload = () => {
          c.classList.add('img');
          c.style.backgroundImage = `url("${n.image}")`;
        };
        im.src = n.image;
      }
      const cap = document.createElement('div');
      cap.className = 'cap';
      const sm = document.createElement('small');
      sm.textContent = [n.tag, n.date].filter(Boolean).join(' · ');
      cap.append(sm, document.createTextNode(n.title || ''));
      c.append(cap);
      if (n.url) c.addEventListener('click', () => K.openUrl(n.url));
      else c.style.cursor = 'default';
      box.append(c);
    }
  }

  function renderHero() {
    const idx = state.servers.findIndex((x) => x.id === state.selected);
    const s = state.servers[idx];
    const book = $('hero');
    book.classList.remove('turn');
    void book.offsetWidth;
    book.classList.add('turn');
    for (const c of document.querySelectorAll('.side > *')) {
      c.style.animation = 'none';
      void c.offsetWidth;
      c.style.animation = '';
    }
    renderNews(s);
    if (!s) {
      $('title').textContent = state.offline ? 'Offline' : '—';
      $('no').textContent = '';
      $('online').textContent = '';
      $('chips').hidden = true;
      $('tagline').textContent = '';
      renderChannels(null);
      Scene.set('絆');
      updateDock();
      return;
    }
    document.documentElement.style.setProperty('--accent', s.accent || '#c42b1f');
    Scene.set(s.kanji || '絆', s.accent, [s.background, ...(s.gallery || [])]);
    $('vol').textContent = `VOL.${idx + 1}`;
    $('no').textContent = `#${String(idx + 1).padStart(2, '0')}`;
    const [kanji, color] = STAMP[s.status] || STAMP.public;
    const st = $('chips');
    st.hidden = false;
    st.style.setProperty('--sc', color);
    st.textContent = kanji;
    $('title').textContent = s.name;
    $('tagline').textContent = s.tagline || '';
    renderChannels(s);
    renderOnline(s);
    refreshPing(s);
    updateDock();
  }

  function renderOnline(s) {
    const el = $('online');
    el.classList.remove('up');
    if (!s || s.status === 'soon') return (el.textContent = '');
    const p = pings[s.id];
    if (p === undefined) el.textContent = '';
    else if (p === null) el.textContent = 'offline';
    else {
      el.classList.add('up');
      el.textContent = `${p.online} online`;
    }
  }

  async function refreshPing(s) {
    const addr = s && (channelOf(s).address || s.address);
    if (!s || s.status === 'soon' || !addr) return;
    pings[s.id] = await K.ping(addr);
    if (current() === s) renderOnline(s);
    renderFriends();
  }
  async function pingAll() {
    await Promise.all(state.servers.map(refreshPing));
  }
  setInterval(pingAll, 30000);

  // ---- friends: added by Minecraft name; online when they're on one of our servers.
  function whereIs(name) {
    const n = name.toLowerCase();
    for (const s of state.servers) {
      const p = pings[s.id];
      if (p && p.names && p.names.some((x) => x.toLowerCase() === n)) return s;
    }
    return null;
  }

  function renderFriends() {
    const list = $('flist');
    list.innerHTML = '';
    const rows = state.friends.map((n) => ({ n, at: whereIs(n) }));
    const on = rows.filter((r) => r.at), off = rows.filter((r) => !r.at);
    $('friends-count').textContent = on.length;
    if (!rows.length) {
      list.innerHTML = '<div class="fempty">No friends yet.</div>';
      return;
    }
    for (const [label, group] of [['ONLINE ' + on.length, on], ['OFFLINE ' + off.length, off]]) {
      if (!group.length) continue;
      const g = document.createElement('div');
      g.className = 'fgroup';
      g.textContent = label;
      list.append(g);
      for (const r of group) {
        const row = document.createElement('div');
        row.className = 'friend ' + (r.at ? 'on' : 'off');
        const img = face(r.n);
        const txt = document.createElement('div');
        const b = document.createElement('b');
        b.textContent = r.n;
        const sm = document.createElement('small');
        sm.textContent = r.at ? r.at.name : 'offline';
        txt.append(b, sm);
        const x = document.createElement('button');
        x.className = 'x';
        x.title = 'Remove';
        x.textContent = '✕';
        x.addEventListener('click', async () => {
          state.friends = await K.friends(state.friends.filter((f) => f !== r.n));
          renderFriends();
        });
        row.append(img, txt, x);
        if (r.at) row.addEventListener('dblclick', () => select(r.at.id));
        list.append(row);
      }
    }
  }
  $('friends-btn').addEventListener('click', () => {
    show('friends', $('friends').hidden);
    renderFriends();
  });
  $('friends-close').addEventListener('click', () => show('friends', false));
  $('friend-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const name = $('friend-input').value.trim();
    if (!/^[A-Za-z0-9_]{3,16}$/.test(name)) return toast('That isn’t a Minecraft name.');
    if (state.friends.some((f) => f.toLowerCase() === name.toLowerCase())) return;
    state.friends = await K.friends([...state.friends, name]);
    $('friend-input').value = '';
    renderFriends();
  });

  function updateDock() {
    const s = current();
    const play = $('play'), label = play.querySelector('span');
    $('folder').hidden = !s || s.status === 'soon';
    play.classList.toggle('busy', !!state.busy);
    if (!state.busy && !state.playing) {
      $('ptext').textContent = '';
      $('pfill').style.width = '0';
    }
    if (state.playing) [play.disabled, label.textContent] = [true, 'PLAYING'];
    else if (state.busy) [play.disabled, label.textContent] = [true, 'LOADING'];
    else if (!s || s.status === 'soon') [play.disabled, label.textContent] = [true, s ? 'SOON' : 'PLAY'];
    else [play.disabled, label.textContent] = [false, state.account ? 'PLAY' : state.test ? 'TEST' : 'SIGN IN'];
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
    pingAll();
    if (hub.offline && state.servers.length) toast('Offline: showing the last server list.');
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
      err.textContent = r.error;
      err.classList.add('err');
    }
  }

  async function play() {
    const s = current();
    if (!s || state.busy || state.playing) return;
    if (!state.account && !state.test) return show('signin', true);
    state.busy = true;
    updateDock();
    $('ptext').textContent = 'Getting ready';
    const r = await K.play(s.id, !state.account && state.test, channelOf(s).id);
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
      $('crash-log').textContent = d.log || '';
      show('crash', true);
    }
  });
  K.on('update', (d) => {
    const u = $('update');
    u.hidden = false;
    u.textContent = d.state === 'ready' ? `Update ${d.version}` : `Updating to ${d.version}…`;
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
  $('folder').addEventListener('click', () => current() && K.openFolder(current().id, channelOf(current()).id));
  $('account').addEventListener('click', () => (state.account ? openSettings() : show('signin', true)));
  $('open-settings').addEventListener('click', openSettings);
  $('logs').addEventListener('click', () => K.openLogs());
  $('enter-code').addEventListener('click', () => {
    show('settings', false);
    openCode();
  });
  $('crash-copy').addEventListener('click', () => navigator.clipboard.writeText($('crash-log').textContent));

  function openSettings() {
    const s = state.settings;
    $('ram').value = s.ram;
    $('ram-label').textContent = s.ram + ' GB';
    $('close-on-play').checked = !!s.closeOnPlay;
    $('who').textContent = state.account ? state.account.name : 'Not signed in';
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
      $('code-err').textContent = 'No server with that code.';
      $('code-err').classList.add('err');
      return;
    }
    show('code', false);
    state.selected = r.id;
    state.settings = await K.settings({});
    await loadHub();
    toast(`Unlocked ${r.name}`);
  }
  $('code-go').addEventListener('click', unlock);
  $('code-input').addEventListener('keydown', (e) => e.key === 'Enter' && unlock());
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    document.querySelectorAll('.overlay').forEach((o) => o.id !== 'signin' && (o.hidden = true));
    show('friends', false);
  });

  (async () => {
    const init = await K.init();
    document.title = init.name;
    $('about').textContent = `${init.name} ${init.version}`;
    state.account = init.account;
    state.settings = init.settings;
    state.selected = init.settings.selected;
    state.testMode = !!init.testMode;
    $('test-go').hidden = !state.testMode;
    $('testtag').hidden = !state.testMode;
    state.friends = await K.friends();
    renderAccount();
    renderFriends();
    await loadHub();
    if (!state.account) show('signin', true);
  })();
})();
