// The launcher's screen: the server rail, the hero, the Play dock, and the small dialogs.
(function () {
  const W = window.kizuna;
  const $ = (id) => document.getElementById(id);
  let state = { account: null, settings: {}, servers: [], selected: null, busy: false, playing: false };

  const STATUS = {
    public: ['OPEN', '#5bd35b'],
    beta: ['BETA', '#b06ae0'],
    private: ['PRIVATE', '#e8c36f'],
    soon: ['COMING SOON', '#8f8a7a'],
  };

  function toast(msg) {
    const t = $('toast');
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toast.h);
    toast.h = setTimeout(() => (t.hidden = true), 6000);
  }

  function show(id, on) {
    $(id).hidden = !on;
  }
  document.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => (b.closest('.overlay').hidden = true)));
  document.querySelectorAll('[data-win]').forEach((b) => b.addEventListener('click', () => W.win(b.dataset.win)));

  function initials(name) {
    const w = name.replace(/[^A-Za-z0-9 ]/g, '').split(/\s+/).filter(Boolean);
    return (w.length > 1 ? w[0][0] + w[1][0] : (w[0] || '?').slice(0, 2)).toUpperCase();
  }

  function renderAccount() {
    const a = state.account, b = $('account');
    b.innerHTML = '';
    b.classList.toggle('none', !a);
    if (!a) {
      b.textContent = 'Sign in';
      return;
    }
    const img = document.createElement('img');
    img.src = `https://mc-heads.net/avatar/${a.uuid}/44`;
    img.alt = '';
    img.onerror = () => img.remove();
    b.append(img, document.createTextNode(a.name));
  }

  function renderRail() {
    const rail = $('rail');
    rail.innerHTML = '';
    for (const s of state.servers) {
      const t = document.createElement('button');
      t.className = 'tile' + (s.id === state.selected ? ' on' : '');
      t.style.setProperty('--tc', s.accent || '#e8c36f');
      t.title = s.name;
      const sp = document.createElement('span');
      sp.textContent = (s.short || initials(s.name)).slice(0, 4);
      const st = document.createElement('i');
      st.className = 'st ' + (s.status === 'beta' ? 'beta' : s.status === 'soon' ? 'soon' : '');
      t.append(sp, st);
      t.addEventListener('click', () => select(s.id));
      rail.append(t);
    }
    const add = document.createElement('button');
    add.className = 'tile add';
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

  function renderHero() {
    const s = state.servers.find((x) => x.id === state.selected);
    const hero = $('hero');
    // Restart the entrance animation.
    hero.style.animation = 'none';
    for (const c of hero.children) {
      c.style.animation = 'none';
      void c.offsetWidth;
      c.style.animation = '';
    }
    if (!s) {
      $('title').textContent = state.offline ? 'Offline' : 'No servers yet';
      $('tagline').textContent = state.offline ? "Can't reach the server list. Check your connection." : '';
      $('desc').textContent = '';
      $('features').innerHTML = '';
      $('chips').innerHTML = '';
      Scene.set('none', '#e8c36f');
      updateDock();
      return;
    }
    document.documentElement.style.setProperty('--accent', s.accent || '#e8c36f');
    Scene.set(s.theme || 'none', s.accent, s.art);
    const [label, color] = STATUS[s.status] || STATUS.public;
    $('chips').innerHTML = '';
    const chip = document.createElement('span');
    chip.className = 'chip';
    chip.style.color = color;
    chip.textContent = label;
    $('chips').append(chip);
    if (s.minecraft) {
      const v = document.createElement('span');
      v.className = 'chip';
      v.style.color = '#8f8a7a';
      v.textContent = 'MINECRAFT ' + s.minecraft;
      $('chips').append(v);
    }
    $('title').textContent = s.name;
    $('tagline').textContent = s.tagline || '';
    $('desc').textContent = s.description || '';
    const f = $('features');
    f.innerHTML = '';
    for (const x of s.features || []) {
      const li = document.createElement('li');
      li.textContent = x;
      f.append(li);
    }
    updateDock();
  }

  function updateDock() {
    const s = state.servers.find((x) => x.id === state.selected);
    const play = $('play');
    const label = play.querySelector('span');
    $('folder').hidden = !s || s.status === 'soon';
    if (state.playing) {
      play.disabled = true;
      label.textContent = 'PLAYING';
      return;
    }
    if (state.busy) {
      play.disabled = true;
      label.textContent = 'LOADING';
      return;
    }
    if (!s) {
      play.disabled = true;
      label.textContent = 'PLAY';
      return;
    }
    if (s.status === 'soon') {
      play.disabled = true;
      label.textContent = 'SOON';
      $('ptext').textContent = `${s.name} isn't open yet. Keep an eye out.`;
      $('pfill').style.width = '0';
      return;
    }
    play.disabled = false;
    label.textContent = state.account ? 'PLAY' : 'SIGN IN';
    $('ptext').textContent = state.account ? `Ready · ${s.name}` : 'Sign in with Microsoft to play';
    $('pfill').style.width = '0';
  }

  function select(id) {
    state.selected = id;
    W.settings({ selected: id });
    renderRail();
    renderHero();
  }

  async function loadHub() {
    const hub = await W.hub();
    state.servers = hub.servers || [];
    state.offline = !!hub.offline;
    if (!state.servers.find((s) => s.id === state.selected)) {
      const first = state.servers.find((s) => s.status !== 'soon') || state.servers[0];
      state.selected = first ? first.id : null;
    }
    renderRail();
    renderHero();
    if (hub.offline && state.servers.length) toast("Couldn't refresh the server list; showing the last one.");
  }

  async function login() {
    $('signin-err').textContent = '';
    $('signin-err').classList.remove('err');
    const r = await W.login();
    if (r.ok) {
      state.account = r.account;
      show('signin', false);
      renderAccount();
      updateDock();
      return true;
    }
    if (r.code !== 'cancelled') {
      const msg = r.code === 'appid' || r.code === 'noclient' ? r.error + ' Tell the server admin.' : r.error;
      $('signin-err').textContent = msg;
      $('signin-err').classList.add('err');
      if ($('signin').hidden) toast(msg);
    }
    return false;
  }

  async function play() {
    const s = state.servers.find((x) => x.id === state.selected);
    if (!s || state.busy || state.playing) return;
    if (!state.account) {
      show('signin', true);
      return;
    }
    state.busy = true;
    updateDock();
    const r = await W.play(s.id);
    state.busy = false;
    if (!r.ok) {
      $('pfill').style.width = '0';
      if (r.code === 'signin') {
        state.account = null;
        renderAccount();
        show('signin', true);
      }
      $('ptext').textContent = r.error;
      toast(r.error);
    } else {
      state.playing = true;
    }
    updateDock();
  }

  W.on('status', (d) => {
    $('ptext').textContent = d.text;
    $('pfill').style.width = Math.round((d.fraction || 0) * 100) + '%';
    if (d.playing) {
      state.playing = true;
      updateDock();
    }
  });
  W.on('exited', (d) => {
    state.playing = false;
    state.busy = false;
    updateDock();
    if (d.code && d.code !== 0) {
      $('crash-log').textContent = d.log || '(no log)';
      show('crash', true);
    }
  });
  W.on('update', (d) => {
    const u = $('update');
    u.hidden = false;
    u.innerHTML = '';
    if (d.state === 'ready') {
      u.append(document.createTextNode(`Launcher ${d.version} is ready.`));
      const b = document.createElement('button');
      b.textContent = 'Restart';
      b.onclick = () => W.installUpdate();
      u.append(b);
    } else {
      u.textContent = `Downloading launcher ${d.version}…`;
    }
  });

  $('play').addEventListener('click', play);
  $('login').addEventListener('click', login);
  $('folder').addEventListener('click', () => state.selected && W.openFolder(state.selected));
  $('account').addEventListener('click', () => (state.account ? openSettings() : show('signin', true)));
  $('open-settings').addEventListener('click', openSettings);
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
    state.settings = await W.settings({ ram: Number(e.target.value) });
  });
  $('close-on-play').addEventListener('change', async (e) => (state.settings = await W.settings({ closeOnPlay: e.target.checked })));
  $('logout').addEventListener('click', async () => {
    await W.logout();
    state.account = null;
    renderAccount();
    updateDock();
    show('settings', false);
  });

  async function unlock() {
    const r = await W.unlock($('code-input').value);
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
    const init = await W.init();
    $('brand').textContent = init.brand;
    document.title = init.name;
    $('about').textContent = `${init.brand} Launcher ${init.version}`;
    state.account = init.account;
    state.settings = init.settings;
    state.selected = init.settings.selected;
    $('ram').max = 16;
    renderAccount();
    await loadHub();
    if (!state.account) show('signin', true);
  })();
})();
