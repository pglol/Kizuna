// The launcher screen: the shelf of servers, the page for the chosen one, Play, friends.
(function () {
  const K = window.kizuna;
  const $ = (id) => document.getElementById(id);
  const state = { account: null, settings: {}, servers: [], selected: null, busy: false, playing: false, test: false, testMode: false, offline: false, friends: [] };
  const pings = {};
  const loadedIcons = new Set();

  function toast(msg) {
    const t = $('toast');
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toast.h);
    toast.h = setTimeout(() => (t.hidden = true), 5000);
  }
  const show = (id, on) => ($(id).hidden = !on);

  /**
   * Version numbers as people see them: updates are 0.1, 0.2…; patches move by 0.01 (0.11, 0.12…).
   * Internally 0.11 is 0.11.0 and 0.2 is 0.20.0, so they still sort correctly.
   */
  function shownVersion(v) {
    const m = /^(\d+)\.(\d+)\.(\d+)/.exec(String(v || ''));
    if (!m || m[1] !== '0') return String(v || '');
    const minor = Number(m[2]);
    return minor % 10 === 0 ? `0.${minor / 10}` : `0.${String(minor).padStart(2, '0')}`;
  }

  /** A yes/no question in the launcher's own style. Clicking outside counts as no. */
  let asking = null;
  function ask(title, text, yes) {
    if (asking) asking(false);
    $('ask-title').textContent = title;
    $('ask-text').textContent = text;
    $('ask-yes').textContent = yes || 'Yes';
    show('ask', true);
    return new Promise((res) => {
      asking = (v) => {
        asking = null;
        show('ask', false);
        res(v);
      };
    });
  }
  document.getElementById('ask-yes').addEventListener('click', () => asking && asking(true));
  document.getElementById('ask-no').addEventListener('click', () => asking && asking(false));

  // Click outside any window or drawer to close it, so you can carry on elsewhere.
  document.querySelectorAll('.overlay').forEach((o) => o.addEventListener('mousedown', (e) => {
    if (e.target !== o) return;
    if (o.id === 'ask') return asking && asking(false);
    if (o.id === 'update') return; // required: stays until updated
    o.hidden = true;
  }));
  document.addEventListener('mousedown', (e) => {
    const d = document.getElementById('friends');
    if (d.hidden || d.contains(e.target) || document.getElementById('friends-btn').contains(e.target)) return;
    d.hidden = true;
  });
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
    testing: ['TEAM TEST', '#a8432a'],
  };

  function renderAccount() {
    const b = $('account');
    b.innerHTML = '';
    b.classList.toggle('none', !state.account);
    if (!state.account) {
      b.textContent = state.test ? `${(state.settings && state.settings.testName) || 'Tester'} · test` : 'Sign in';
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
      if (s.icon && loadedIcons.has(s.icon)) {
        // Already loaded once: show it straight away (no letters flashing while the tabs redraw).
        ic.style.backgroundImage = `url("${s.icon}")`;
      } else {
        ic.textContent = (s.short || initials(s.name)).slice(0, 3);
        if (s.icon) {
          const im = new Image();
          im.onload = () => {
            loadedIcons.add(s.icon);
            ic.textContent = '';
            ic.style.backgroundImage = `url("${s.icon}")`;
          };
          im.src = s.icon;
        }
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
    const c = list.find((x) => x.id === want && !x.locked && !x.soon);
    return c || list.find((x) => !x.locked && !x.soon) || list[0] || { id: 'public', name: 'Public' };
  }

  function renderChannels(s) {
    const box = $('chans');
    box.innerHTML = '';
    const list = (s && s.channels) || [];
    const cur = channelOf(s);
    // Shown when there's a choice to make: more than one build, or more than one server for this build.
    const hosts = (cur && cur.addresses) || [];
    $('chans').closest('.builds').hidden = !s || s.status === 'soon' || (list.length < 2 && hosts.length < 2);
    $('chans').hidden = list.length < 2;
    $('chans').previousElementSibling.hidden = list.length < 2;
    for (const c of list) {
      const b = document.createElement('button');
      b.className = 'chan' + (c.id === cur.id ? ' on' : '') + (c.locked ? ' locked' : '') + (c.soon ? ' soon' : '');
      b.innerHTML = c.locked ? LOCK : '';
      b.append(document.createTextNode(c.name));
      b.title = c.soon ? `${c.name} isn't open yet` : c.note || c.name;
      if (c.soon) b.disabled = true;
      b.addEventListener('click', async () => {
        if (state.busy || (state.playing && s.id === state.playingId)) return;
        if (c.locked) return openCode(`${s.name}: ${c.name}`);
        state.settings = await K.settings({ channels: { ...(state.settings.channels || {}), [s.id]: c.id } });
        renderChannels(s);
        refreshPing(s);
        updateDock();
      });
      box.append(b);
    }
    $('chan-note').textContent = cur.note || '';
    renderHosts(s, cur);
  }

  /** A build with more than one address (e.g. Hamachi and Public) gets a switch. */
  function hostOf(s, c) {
    const list = (c && c.addresses) || [];
    const want = (state.settings.hosts || {})[`${s.id}:${c.id}`];
    return list.find((a) => a.label === want) || list[0] || null;
  }
  function renderHosts(s, c) {
    const box = $('hosts');
    box.innerHTML = '';
    const list = (c && c.addresses) || [];
    if (!s || list.length < 2) return;
    const cur = hostOf(s, c);
    for (const a of list) {
      const b = document.createElement('button');
      b.className = 'host' + (cur && a.label === cur.label ? ' on' : '');
      b.textContent = a.label;
      b.title = a.address;
      b.addEventListener('click', async () => {
        if (state.busy || (state.playing && s.id === state.playingId)) return;
        state.settings = await K.settings({ hosts: { ...(state.settings.hosts || {}), [`${s.id}:${c.id}`]: a.label } });
        renderHosts(s, c);
        refreshPing(s);
      });
      box.append(b);
    }
  }

  // News floats over the cover: one card at a time, changing every 6 seconds (paused while hovered).
  const NEWS_MS = 6000;
  let newsList = [], newsAt = 0, newsTimer = null;
  function renderNews(s) {
    newsList = ((s && s.news) || []).slice(0, 5);
    clearTimeout(newsTimer);
    $('news').hidden = !newsList.length;
    const dots = $('ndots');
    dots.innerHTML = '';
    newsList.forEach((_, i) => {
      const d = document.createElement('button');
      d.title = 'News ' + (i + 1);
      d.addEventListener('click', () => showNews(i));
      dots.append(d);
    });
    if (newsList.length) showNews(0, true);
  }
  function showNews(i, first) {
    clearTimeout(newsTimer);
    newsAt = (i + newsList.length) % newsList.length;
    const n = newsList[newsAt];
    const card = $('ncard');
    const fill = () => {
      card.innerHTML = '';
      card.className = 'ncard in' + (n.url ? ' link' : '');
      if (n.image) {
        const im = document.createElement('span');
        im.className = 'nimg';
        im.style.backgroundImage = `url("${n.image}")`;
        card.append(im);
      }
      const sm = document.createElement('small');
      sm.textContent = [n.tag, n.date].filter(Boolean).join(' · ');
      const b = document.createElement('b');
      b.textContent = n.title || '';
      card.append(sm, b);
      card.onclick = n.url ? () => K.openUrl(n.url) : null;
      [...$('ndots').children].forEach((d, k) => d.classList.toggle('on', k === newsAt));
      const bar = $('nfill');
      bar.classList.remove('run');
      void bar.offsetWidth;
      if (newsList.length > 1) {
        bar.style.animationDuration = NEWS_MS + 'ms';
        bar.classList.add('run');
      }
    };
    if (first) fill();
    else {
      card.className = 'ncard out';
      setTimeout(fill, 300);
    }
  }
  $('nfill').addEventListener('animationend', () => newsList.length > 1 && showNews(newsAt + 1));

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
      $('desc').textContent = '';
      $('tags').innerHTML = '';
      renderChannels(null);
      Scene.set('絆');
      updateDock();
      return;
    }
    document.documentElement.style.setProperty('--accent', s.accent || '#c42b1f');
    Scene.set(s.kanji || '絆', s.accent, [s.background, ...(s.gallery || [])]);
    Ground.set(s.theme, s.accent);
    $('vol').textContent = `VOL.${idx + 1}`;
    $('no').textContent = `#${String(idx + 1).padStart(2, '0')}` + (s.version ? `  ·  v${s.version}` : '');
    const [kanji, color] = STAMP[s.status] || STAMP.public;
    const st = $('chips');
    st.hidden = false;
    st.style.setProperty('--sc', color);
    st.textContent = kanji;
    $('title').textContent = s.name;
    $('tagline').textContent = s.tagline || '';
    $('desc').textContent = s.description || '';
    const tags = $('tags');
    tags.innerHTML = '';
    for (const g of s.tags || []) {
      const c = document.createElement('span');
      c.className = 'tag-chip';
      c.textContent = g;
      tags.append(c);
    }
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
    const ch = s && channelOf(s), h = s && hostOf(s, ch);
    const addr = s && ((h && h.address) || ch.address || s.address);
    if (!s || s.status === 'soon' || !addr) return;
    pings[s.id] = await K.ping(addr);
    if (current() === s) renderOnline(s);
    renderFriends();
  }
  async function pingAll() {
    await Promise.all(state.servers.map(refreshPing));
  }
  setInterval(pingAll, 30000);
  // News, art and builds change on GitHub without a launcher update: re-read the list now and then.
  setInterval(() => !state.busy && !state.playing && loadHub(), 10 * 60 * 1000);

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
    const rows = state.friends.map((f) => ({ n: f.name, from: f.from, at: whereIs(f.name) }));
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
        const src = r.from && state.servers.find((x) => x.id === r.from);
        sm.textContent = (r.at ? r.at.name : 'offline') + (src ? ` · friend in ${src.short || src.name}` : '');
        txt.append(b, sm);
        const x = document.createElement('button');
        x.className = 'x';
        x.title = 'Remove';
        x.textContent = '✕';
        x.addEventListener('click', async () => {
          state.friends = await K.friends(state.friends.filter((f) => !f.from && f.name !== r.n).map((f) => f.name));
          renderFriends();
        });
        row.append(img, txt);
        if (!r.from) row.append(x);
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
    if (state.friends.some((f) => f.name.toLowerCase() === name.toLowerCase())) return;
    state.friends = await K.friends([...state.friends.filter((f) => !f.from).map((f) => f.name), name]);
    $('friend-input').value = '';
    renderFriends();
  });

  function updateDock() {
    const s = current();
    const play = $('play'), label = play.querySelector('span');
    $('folder').hidden = !s || s.status === 'soon';
    play.classList.toggle('busy', !!state.busy);
    const here = state.playing && s && s.id === state.playingId;
    document.querySelector('.xp').classList.toggle('idle', !(state.busy || here));
    if (!state.busy && !here) {
      $('ptext').textContent = '';
      $('pfill').style.width = '0';
    }
    // "Playing" only on the tab whose game is running; other tabs can still be started (you're asked first).
    if (state.playing && (!s || s.id === state.playingId)) [play.disabled, label.textContent] = [true, 'PLAYING'];
    else if (state.busy) [play.disabled, label.textContent] = [true, 'LOADING'];
    else if (!s || s.status === 'soon') [play.disabled, label.textContent] = [true, s ? 'SOON' : 'PLAY'];
    // TEST only on development builds; Public and Early Access always say PLAY.
    else if (!state.account && !state.test) [play.disabled, label.textContent] = [false, 'SIGN IN'];
    else [play.disabled, label.textContent] = [false, channelOf(s).id === 'dev' ? 'TEST' : 'PLAY'];
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
    state.host = !!hub.host;
    $('host-btn').hidden = !state.host;
    state.offline = !!hub.offline;
    if (!current()) {
      const first = state.servers.find((s) => s.status !== 'soon') || state.servers[0];
      state.selected = first ? first.id : null;
    }
    renderRail();
    renderHero();
    pingAll();
    announceNews();
    if (hub.offline && state.servers.length) toast('Offline: showing the last server list.');
  }

  /** New news posts since last time: a desktop notification (the first run just remembers what's there). */
  async function announceNews() {
    const seen = new Set(state.settings.seenNews || []);
    const now = [];
    const fresh = [];
    for (const s of state.servers) for (const n of s.news || []) {
      const key = `${s.id}:${n.title}`;
      now.push(key);
      if (!seen.has(key)) fresh.push([s, n]);
    }
    if (state.settings.seenNews && fresh.length) {
      const [s, n] = fresh[0];
      K.notify(`${s.name}${fresh.length > 1 ? ` (+${fresh.length - 1} more)` : ''}`, n.title);
    }
    state.settings = await K.settings({ seenNews: now.slice(-200) });
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

  // Pressing Play first checks for a newer launcher; if there is one you're asked (Update now / Later),
  // and Later carries on into the game.
  async function play(skipCheck) {
    const s = current();
    if (!s || state.busy) return;
    if (state.playing) {
      if (s.id === state.playingId) return;
      const ok = await ask(`Close ${state.playingName}?`, `Only one game runs at a time. Close ${state.playingName} and start ${s.name}?`, 'Close and play');
      if (!ok) return;
      state.stopping = true;
      $('ptext').textContent = `Closing ${state.playingName}`;
      await K.stopGame();
    }
    if (!state.account && !state.test) return show('signin', true);
    state.busy = true;
    updateDock();
    if (skipCheck !== true) {
      $('ptext').textContent = 'Checking for updates';
      const u = await K.checkUpdate().catch(() => null);
      if (u && u.version) {
        // Launcher updates are required: Play waits until it's installed.
        state.busy = false;
        updateDock();
        showUpdate(u.version);
        return;
      }
    }
    $('ptext').textContent = 'Getting ready';
    const ch = channelOf(s), h = hostOf(s, ch);
    state.playingId = s.id;
    state.playingName = s.name;
    const r = await K.play(s.id, !state.account && state.test, ch.id, h ? h.label : '');
    state.busy = false;
    if (!r.ok) {
      if (r.code === 'signin') {
        state.account = null;
        renderAccount();
        show('signin', true);
      }
      toast(r.error);
    } else {
      state.playing = true;
      state.playingId = s.id;
      state.playingName = s.name;
    }
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
  K.on('exited', async (d) => {
    state.friends = await K.friends();
    renderFriends();
    state.playing = false;
    state.playingId = null;
    state.busy = false;
    updateDock();
    const stopped = state.stopping;
    state.stopping = false;
    if (d.code && !stopped) {
      $('crash-log').textContent = d.log || '';
      show('crash', true);
    }
  });
  // Launcher updates: ask, then show the download, then restart into the new version.
  let updStarted = false;
  let updReady = false;
  function showUpdate(version) {
      if (updReady) return show('update', true);
      $('upd-text').textContent = `Launcher ${shownVersion(version)} is out. Update to keep playing; it only takes a moment.`;
      $('upd-later').hidden = true;
      $('upd-bar').hidden = true;
      $('upd-go').textContent = 'Update now';
      $('upd-go').disabled = false;
      $('upd-go').onclick = () => {
        $('upd-go').disabled = true;
        $('upd-go').textContent = 'Downloading…';
        $('upd-bar').hidden = false;
        K.downloadUpdate();
      };
      show('update', true);
      // Required update: start downloading straight away.
      if (!updStarted) {
        updStarted = true;
        $('upd-go').onclick();
      } else {
        $('upd-go').disabled = true;
        $('upd-go').textContent = 'Downloading…';
        $('upd-bar').hidden = false;
      }
  }
  K.on('update', (d) => {
    if (d.state === 'available') {
      showUpdate(d.version);
    } else if (d.state === 'downloading') {
      $('upd-fill').style.width = (d.percent || 0) + '%';
    } else if (d.state === 'ready') {
      updReady = true;
      $('upd-text').textContent = state.playing ? `Update downloaded. Restarting closes ${state.playingName}.` : 'Update downloaded.';
      $('upd-bar').hidden = true;
      $('upd-go').disabled = false;
      $('upd-go').textContent = 'Restart';
      $('upd-go').onclick = async () => {
        if (state.playing) {
          const ok = await ask(`Close ${state.playingName}?`, `The launcher restarts to update. Close ${state.playingName} first?`, 'Close and update');
          if (!ok) return;
          state.stopping = true;
          $('upd-go').disabled = true;
          $('upd-go').textContent = 'Closing game…';
          await K.installUpdate(true);
        } else await K.installUpdate(false);
      };
      show('update', true);
    }
  });


  $('play').addEventListener('click', () => play());
  $('login').addEventListener('click', login);
  $('test-go').addEventListener('click', async () => {
    const n = ($('test-name').value || '').replace(/[^A-Za-z0-9_]/g, '').slice(0, 16);
    if (n.length < 3) {
      $('signin-err').textContent = 'Pick a test name (3+ letters), different from your friends.';
      return;
    }
    state.settings = await K.settings({ testName: n, testLogin: true });
    state.test = true;
    show('signin', false);
    renderAccount();
    updateDock();
  });
  $('folder').addEventListener('click', () => current() && K.openFolder(current().id, channelOf(current()).id));
  $('account').addEventListener('click', () => (state.account || state.test ? openAccount() : show('signin', true)));
  $('open-settings').addEventListener('click', openSettings);
  $('logs').addEventListener('click', () => K.openLogs());
  $('check-update').addEventListener('click', async () => {
    const b = $('check-update');
    b.disabled = true;
    b.textContent = 'Checking…';
    const u = await K.checkUpdate().catch(() => null);
    b.disabled = false;
    b.textContent = 'Check now';
    if (u && u.version) {
      show('settings', false);
      showUpdate(u.version);
    } else toast(`You're on the newest version (${shownVersion(state.version)}).`);
  });
  $('enter-code').addEventListener('click', () => {
    show('settings', false);
    openCode();
  });
  // ---- Hosting (only after the host code) ----
  let hostView = null;
  const hostStates = {};
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const HOST_LABEL = { stopped: 'Stopped', updating: 'Updating', starting: 'Starting', running: 'Running' };
  // The settings people actually change, laid out to fit without scrolling; the rest is one click away.
  const PROPS = [
    ['motd', 'Server name', 'text', 'A Minecraft Server'],
    ['max-players', 'Max players', 'range:1:100', '20'],
    ['difficulty', 'Difficulty', 'seg:peaceful,easy,normal,hard', 'easy'],
    ['gamemode', 'New players start in', 'seg:survival,adventure,creative', 'survival'],
    ['pvp', 'PvP', 'bool', 'true'],
    ['white-list', 'Whitelist only', 'bool', 'false'],
    ['online-mode', 'Real Microsoft accounts only', 'bool', 'true'],
    ['allow-flight', 'Allow flight (no gear kicks)', 'bool', 'false'],
    ['view-distance', 'View distance', 'range:4:32', '10'],
    ['simulation-distance', 'Simulation distance', 'range:4:32', '10'],
    ['spawn-protection', 'Spawn protection', 'range:0:64', '16'],
    ['server-port', 'Port', 'number', '25565'],
  ];
  function propsHtml(values) {
    return PROPS.map(([k, label, type, def]) => {
      const v = k in values && values[k] !== '' ? values[k] : def;
      let input;
      if (type === 'bool') input = `<label class="switch"><input type="checkbox" data-k="${k}" data-t="bool" ${v === 'true' ? 'checked' : ''}><i></i></label>`;
      else if (type.startsWith('seg:')) input = `<span class="seg" data-k="${k}" data-t="seg" data-v="${esc(v)}">${type.slice(4).split(',').map((o) => `<button type="button" data-o="${o}" class="${o === v ? 'on' : ''}">${o[0].toUpperCase() + o.slice(1)}</button>`).join('')}</span>`;
      else if (type.startsWith('range:')) {
        const [, lo, hi] = type.split(':');
        input = `<span class="rng"><input type="range" min="${lo}" max="${hi}" value="${esc(v)}" data-k="${k}"><b class="val">${esc(v)}</b></span>`;
      } else input = `<input type="${type}" data-k="${k}" value="${esc(v)}">`;
      return `<div class="pitem"><span>${esc(label)}</span>${input}</div>`;
    }).join('');
  }
  $('host-props').addEventListener('input', (e) => {
    if (e.target.type === 'range') e.target.nextElementSibling.textContent = e.target.value;
  });
  $('host-props').addEventListener('click', (e) => {
    const b = e.target.closest('.seg button');
    if (!b) return;
    const seg = b.parentElement;
    seg.dataset.v = b.dataset.o;
    seg.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b));
  });
  function setMode(m) {
    document.querySelectorAll('#host-mode button').forEach((b) => b.classList.toggle('on', b.dataset.mode === m));
  }
  document.querySelectorAll('#host-mode button').forEach((b) => b.addEventListener('click', () => {
    setMode(b.dataset.mode);
    K.hostSet(hostView.id, hostView.ch, { mode: b.dataset.mode });
  }));
  function setHostState(st) {
    $('host-state').textContent = HOST_LABEL[st] || st;
    $('host-state').className = 'hstate' + (st === 'running' ? ' running' : st === 'stopped' ? '' : ' busy');
    $('host-start').disabled = st !== 'stopped';
    $('host-stop').disabled = st === 'stopped';
  }
  function hostButton() {
    const on = Object.values(hostStates).some((s) => s && s !== 'stopped');
    $('host-btn').textContent = on ? 'Server running' : 'Host server';
    $('host-btn').classList.toggle('live', on);
  }
  function logLine(l) {
    const box = $('host-log');
    const stick = box.scrollTop + box.clientHeight >= box.scrollHeight - 30;
    const div = document.createElement('div');
    div.textContent = l;
    if (/\bWARN\b/.test(l)) div.className = 'warn';
    if (/\bERROR\b|Exception|Error:/.test(l)) div.className = 'err';
    box.append(div);
    while (box.childNodes.length > 1500) box.firstChild.remove();
    if (stick) box.scrollTop = box.scrollHeight;
  }
  async function openHost() {
    const s = current();
    if (!s) return;
    const ch = channelOf(s).id;
    const i = await K.hostInfo(s.id, ch);
    if (!i) return;
    hostView = { id: s.id, ch, key: i.key };
    $('host-title').textContent = 'Host · ' + i.name;
    $('host-folder').textContent = i.folder;
    $('host-folder').title = i.folder;
    $('host-eula').checked = i.eula;
    $('host-ram').value = i.ram;
    $('host-ram-label').textContent = i.ram + ' GB';
    // "<the game's own mod> only" when the game has one; otherwise the whole pack.
    $('host-mode-mod').hidden = !i.mainMod;
    if (i.mainMod) $('host-mode-mod').textContent = `${i.mainMod.name} only`;
    setMode(i.mode || 'pack');
    $('host-log').innerHTML = '';
    i.log.forEach(logLine);
    $('host-props').innerHTML = propsHtml(i.props || {});
    hostStates[i.key] = i.state;
    setHostState(i.state);
    show('host', true);
    $('host-log').scrollTop = $('host-log').scrollHeight;
  }
  K.on('host', (d) => {
    if (d.state) {
      hostStates[d.key] = d.state;
      hostButton();
    }
    if (!hostView || d.key !== hostView.key) return;
    if (d.line) logLine(d.line);
    if (d.state) setHostState(d.state);
  });
  $('host-btn').addEventListener('click', openHost);
  $('host-popout').addEventListener('click', () => K.hostConsole(hostView.id, hostView.ch));
  document.querySelectorAll('.htab[data-htab]').forEach((b) => b.addEventListener('click', () => {
    document.querySelectorAll('.htab[data-htab]').forEach((x) => x.classList.toggle('on', x === b));
    $('host-pane-console').hidden = b.dataset.htab !== 'console';
    $('host-pane-props').hidden = b.dataset.htab !== 'props';
  }));
  $('host-start').addEventListener('click', async () => {
    if (!$('host-eula').checked) return toast('Tick the Minecraft EULA box first.');
    setHostState('updating');
    await K.hostStart(hostView.id, hostView.ch);
  });
  $('host-stop').addEventListener('click', () => K.hostStop(hostView.id, hostView.ch));
  $('host-cmd-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const v = $('host-cmd').value.trim();
    if (!v) return;
    logLine('> ' + v);
    if (!(await K.hostCmd(hostView.id, hostView.ch, v))) logLine('The server is not running.');
    $('host-cmd').value = '';
  });
  $('host-eula').addEventListener('change', (e) => K.hostSet(hostView.id, hostView.ch, { eula: e.target.checked }));
  $('host-eula-link').addEventListener('click', (e) => {
    e.preventDefault();
    K.openUrl('https://aka.ms/MinecraftEULA');
  });
  $('host-ram').addEventListener('input', (e) => ($('host-ram-label').textContent = e.target.value + ' GB'));
  $('host-ram').addEventListener('change', (e) => K.hostSet(hostView.id, hostView.ch, { ram: Number(e.target.value) }));
  $('host-props-file').addEventListener('click', () => K.hostPropsFile(hostView.id, hostView.ch));
  $('host-folder-change').addEventListener('click', async () => {
    const f = await K.hostFolder(hostView.id, hostView.ch);
    if (f) {
      $('host-folder').textContent = f;
      const i = await K.hostInfo(hostView.id, hostView.ch);
      $('host-props').innerHTML = propsHtml(i.props || {});
    }
  });
  $('host-folder-open').addEventListener('click', () => K.hostOpen(hostView.id, hostView.ch));
  $('host-props-save').addEventListener('click', async () => {
    const props = {};
    document.querySelectorAll('#host-props [data-k]').forEach((el) => {
      props[el.dataset.k] = el.dataset.t === 'bool' ? String(el.checked) : el.dataset.t === 'seg' ? el.dataset.v : el.value;
    });
    await K.hostSet(hostView.id, hostView.ch, { props });
    toast(hostStates[hostView.key] && hostStates[hostView.key] !== 'stopped' ? 'Saved. Restart the server to apply.' : 'Server settings saved.');
  });

  $('crash-copy').addEventListener('click', () => navigator.clipboard.writeText($('crash-log').textContent));

  function openSettings() {
    const s = state.settings;
    $('ram').value = s.ram;
    $('ram-label').textContent = s.ram + ' GB';
    show('settings', true);
  }

  /** Who you're playing as: your face and name, switch to a Microsoft account, sign out. */
  function openAccount() {
    const name = state.account ? state.account.name : (state.settings.testName || 'Tester');
    const f = $('acct-face');
    f.innerHTML = '';
    f.append(face(name));
    $('acct-name').textContent = name;
    $('acct-kind').textContent = state.account ? 'Microsoft account' : 'Test login (offline)';
    $('acct-ms').hidden = !!state.account;
    show('acct', true);
  }
  $('acct-login').addEventListener('click', () => {
    show('acct', false);
    show('signin', true);
  });
  $('ram').addEventListener('input', async (e) => {
    $('ram-label').textContent = e.target.value + ' GB';
    state.settings = await K.settings({ ram: Number(e.target.value) });
  });
  $('keep-open').addEventListener('change', async (e) => (state.settings = await K.settings({ closeOnPlay: !e.target.checked })));
  $('logout').addEventListener('click', async () => {
    if (state.test && !state.account) {
      state.test = false;
      state.settings = await K.settings({ testLogin: false });
      renderAccount();
      updateDock();
      show('acct', false);
      show('signin', true);
      return;
    }
    await K.logout();
    state.account = null;
    renderAccount();
    updateDock();
    show('acct', false);
  });

  async function unlock() {
    const r = await K.unlock($('code-input').value);
    if (!r.ok) {
      $('code-err').textContent = 'No server with that code.';
      $('code-err').classList.add('err');
      return;
    }
    show('code', false);
    if (r.host) {
      await loadHub();
      toast('Server hosting unlocked: Host server is next to Play');
      return;
    }
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
    $('about').textContent = `You have ${init.name} ${shownVersion(init.version)}`;
    state.account = init.account;
    state.settings = init.settings;
    state.selected = init.settings.selected;
    $('keep-open').checked = !init.settings.closeOnPlay;
    state.testMode = !!init.testMode;
    state.version = init.version;
    $('test-go').hidden = !state.testMode;
    $('test-name').hidden = !state.testMode;
    $('test-name').value = (state.settings && state.settings.testName) || '';
    $('testtag').hidden = !state.testMode;
    state.friends = await K.friends();
    renderAccount();
    renderFriends();
    // A test login is remembered: no sign-in window every time.
    if (!state.account && state.testMode && state.settings.testLogin && state.settings.testName) state.test = true;
    renderAccount();
    updateDock();
    await loadHub();
    if (!state.account && !state.test) show('signin', true);
  })();
})();
