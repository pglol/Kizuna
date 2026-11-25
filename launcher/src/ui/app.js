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
        if (state.busy || state.playing) return;
        state.settings = await K.settings({ hosts: { ...(state.settings.hosts || {}), [`${s.id}:${c.id}`]: a.label } });
        renderHosts(s, c);
        refreshPing(s);
      });
      box.append(b);
    }
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
    Ground.set(s.theme, s.accent);
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
    document.querySelector('.xp').classList.toggle('idle', !(state.busy || state.playing));
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
  let playAfterPrompt = false;
  async function play(skipCheck) {
    const s = current();
    if (!s || state.busy || state.playing) return;
    if (!state.account && !state.test) return show('signin', true);
    state.busy = true;
    updateDock();
    if (skipCheck !== true) {
      $('ptext').textContent = 'Checking for updates';
      const u = await K.checkUpdate().catch(() => null);
      if (u && u.version && updDismissed !== u.version) {
        state.busy = false;
        updateDock();
        playAfterPrompt = true;
        showUpdate(u.version);
        return;
      }
    }
    $('ptext').textContent = 'Getting ready';
    const ch = channelOf(s), h = hostOf(s, ch);
    const r = await K.play(s.id, !state.account && state.test, ch.id, h ? h.label : '');
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
  K.on('exited', async (d) => {
    state.friends = await K.friends();
    renderFriends();
    state.playing = false;
    state.busy = false;
    updateDock();
    if (d.code) {
      $('crash-log').textContent = d.log || '';
      show('crash', true);
    }
  });
  // Launcher updates: ask, then show the download, then restart into the new version.
  let updDismissed = '';
  function showUpdate(version) {
      $('upd-text').textContent = `Version ${version} of the launcher is out.`;
      $('upd-bar').hidden = true;
      $('upd-go').textContent = 'Update now';
      $('upd-go').disabled = false;
      $('upd-go').onclick = () => {
        $('upd-go').disabled = true;
        $('upd-go').textContent = 'Downloading…';
        $('upd-bar').hidden = false;
        K.downloadUpdate();
      };
      $('upd-later').onclick = () => {
        updDismissed = version;
        show('update', false);
        if (playAfterPrompt) {
          playAfterPrompt = false;
          play(true);
        }
      };
      show('update', true);
  }
  K.on('update', (d) => {
    if (d.state === 'available') {
      if (updDismissed === d.version) return;
      showUpdate(d.version);
    } else if (d.state === 'downloading') {
      $('upd-fill').style.width = (d.percent || 0) + '%';
    } else if (d.state === 'ready') {
      $('upd-text').textContent = state.playing ? 'Update downloaded. It installs when you close the game.' : 'Update downloaded.';
      $('upd-bar').hidden = true;
      $('upd-go').disabled = !!state.playing;
      $('upd-go').textContent = 'Restart';
      $('upd-go').onclick = () => K.installUpdate();
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
    state.settings = await K.settings({ testName: n });
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
    setMode(i.mode || 'mod');
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
    $('about').textContent = `${init.name} ${init.version}`;
    state.account = init.account;
    state.settings = init.settings;
    state.selected = init.settings.selected;
    state.testMode = !!init.testMode;
    $('test-go').hidden = !state.testMode;
    $('test-name').hidden = !state.testMode;
    $('test-name').value = (state.settings && state.settings.testName) || '';
    $('testtag').hidden = !state.testMode;
    state.friends = await K.friends();
    renderAccount();
    renderFriends();
    await loadHub();
    if (!state.account) show('signin', true);
  })();
})();
