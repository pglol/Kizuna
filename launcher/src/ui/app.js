// The launcher's screen: the server rail, the hero, the Play dock, and the small dialogs.
(function () {
  const W = window.kizuna;
  const $ = (id) => document.getElementById(id);
  let state = { test: false, testMode: false, account: null, settings: {}, servers: [], selected: null, busy: false, playing: false };


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

  const STAMP = {
    public: ['連載中', 'OPEN NOW', '#c42b1f'],
    beta: ['試読', 'PLAYTEST', '#6b3fa0'],
    private: ['秘密', 'PRIVATE', '#15120f'],
    soon: ['近日', 'COMING SOON', '#7a7064'],
  };

  function renderRail() {
    const rail = $('rail');
    rail.innerHTML = '';
    state.servers.forEach((s, i) => {
      const t = document.createElement('button');
      t.className = 'spine' + (s.id === state.selected ? ' on' : '');
      t.style.setProperty('--tc', s.accent || '#c42b1f');
      t.title = s.name;
      const band = document.createElement('span');
      band.className = 'band';
      band.textContent = (s.short || initials(s.name)).slice(0, 4);
      const name = document.createElement('span');
      name.className = 'name';
      name.textContent = s.name;
      const num = document.createElement('span');
      num.className = 'num';
      num.innerHTML = `${i + 1}<small>巻</small>`;
      t.append(band, name, num);
      if (s.status === 'soon') {
        const soon = document.createElement('i');
        soon.className = 'soon';
        soon.textContent = '近日';
        t.append(soon);
      }
      t.addEventListener('click', () => select(s.id));
      rail.append(t);
    });
    const add = document.createElement('button');
    add.className = 'spine add';
    add.title = 'Enter an access code';
    add.innerHTML = '+<span>code</span>';
    add.addEventListener('click', () => {
      $('code-input').value = '';
      $('code-err').textContent = '';
      show('code', true);
      setTimeout(() => $('code-input').focus(), 50);
    });
    rail.append(add);
  }

  function renderHero() {
    const idx = state.servers.findIndex((x) => x.id === state.selected);
    const s = state.servers[idx];
    // Ink the panels in again.
    for (const c of $('hero').children) {
      c.style.animation = 'none';
      void c.offsetWidth;
      c.style.animation = '';
    }
    const f = $('features');
    f.innerHTML = '';
    if (!s) {
      $('title').textContent = state.offline ? 'Offline' : 'Nothing on the shelf yet';
      $('tagline').textContent = state.offline ? "Can't reach the server list. Check your connection." : '';
      $('desc').textContent = '';
      $('chips').hidden = true;
      $('vol').textContent = 'VOL.—';
      $('no').textContent = '';
      Scene.set('none');
      updateDock();
      return;
    }
    document.documentElement.style.setProperty('--accent', s.accent || '#c42b1f');
    Scene.set(s.theme || 'none', s.accent, s.art);
    const n = String(idx + 1).padStart(2, '0');
    $('vol').textContent = `VOL.${idx + 1}`;
    $('no').textContent = `#${n}${s.minecraft ? ' · MINECRAFT ' + s.minecraft : ''}`;
    const [kanji, label, color] = STAMP[s.status] || STAMP.public;
    const st = $('chips');
    st.hidden = false;
    st.style.setProperty('--sc', color);
    st.innerHTML = '';
    st.append(document.createTextNode(kanji));
    const sm = document.createElement('small');
    sm.textContent = label;
    st.append(sm);
    $('title').textContent = s.name;
    $('tagline').textContent = s.tagline || '';
    $('desc').textContent = s.description || '';
    (s.features || []).forEach((x, i) => {
      const li = document.createElement('li');
      const b = document.createElement('b');
      b.textContent = `第${i + 1}話`;
      li.append(b, document.createTextNode(x));
      f.append(li);
    });
    updateDock();
  }

  function updateDock() {
    const s = state.servers.find((x) => x.id === state.selected);
    const play = $('play');
    const label = play.querySelector('span');
    $('folder').hidden = !s || s.status === 'soon';
    play.classList.toggle('busy', !!state.busy);
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
      $('ptext').textContent = `${s.name} isn't out yet. Keep an eye on the shelf.`;
      $('pfill').style.width = '0';
      return;
    }
    play.disabled = false;
    label.textContent = state.account ? 'PLAY' : state.test ? 'TEST' : 'SIGN IN';
    $('ptext').textContent = state.account ? `Ready when you are · ${s.name}` : state.test ? 'Test mode: installs everything and opens the game at the title screen' : 'Sign in with Microsoft to play';
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
    if (!state.account && !state.test) {
      show('signin', true);
      return;
    }
    state.busy = true;
    updateDock();
    const r = await W.play(s.id, !state.account && state.test);
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
  $('test-go').addEventListener('click', () => {
    state.test = true;
    show('signin', false);
    updateDock();
  });
  $('logs').addEventListener('click', () => W.openLogs());
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
    state.testMode = !!init.testMode;
    $('test-go').hidden = !state.testMode;
    $('testtag').hidden = !state.testMode;
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
