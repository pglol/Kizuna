// A hosted server's console in its own window: the log as it happens, commands, start and stop.
(function () {
  const K = window.kizuna;
  const $ = (id) => document.getElementById(id);
  const q = new URLSearchParams(location.search);
  const id = q.get('id'), ch = q.get('channel');
  let key = null;
  const LABEL = { stopped: 'Stopped', updating: 'Updating', starting: 'Starting', running: 'Running' };
  const history = [];
  let online = null;
  let hi = 0;

  function line(l) {
    const box = $('log');
    const stick = box.scrollTop + box.clientHeight >= box.scrollHeight - 30;
    const d = document.createElement('div');
    d.textContent = l;
    if (/^> /.test(l)) d.className = 'me';
    else if (/\bWARN\b/.test(l)) d.className = 'warn';
    else if (/\bERROR\b|Exception|Error:/.test(l)) d.className = 'err';
    box.append(d);
    while (box.childNodes.length > 3000) box.firstChild.remove();
    if (stick) box.scrollTop = box.scrollHeight;
    // Who's on, from the log itself.
    const m = /There are (\d+) of a max of (\d+) players online/.exec(l);
    if (m) online = Number(m[1]);
    else if (/\]: Done \(/.test(l)) online = 0;
    else if (/\]: \w{3,16} joined the game/.test(l)) online++;
    else if (/\]: \w{3,16} left the game/.test(l)) online = Math.max(0, online - 1);
    $('players').textContent = online === null ? '' : `${online} online`;
  }
  function state(s) {
    $('state').textContent = LABEL[s] || s;
    $('state').className = 'state' + (s === 'running' ? ' running' : s === 'stopped' ? '' : ' busy');
    $('start').disabled = s !== 'stopped';
    $('stop').disabled = s === 'stopped';
  }
  K.on('host', (d) => {
    if (key && d.key !== key) return;
    if (d.line) line(d.line);
    if (d.state) state(d.state);
  });
  (async () => {
    const i = await K.hostInfo(id, ch);
    if (!i) return line('Hosting is not unlocked on this computer.');
    key = i.key;
    $('name').textContent = i.name;
    document.title = 'Server console · ' + i.name;
    i.log.forEach(line);
    state(i.state);
    $('log').scrollTop = $('log').scrollHeight;
    $('cmd').focus();
  })();
  $('start').addEventListener('click', () => {
    state('updating');
    K.hostStart(id, ch);
  });
  $('stop').addEventListener('click', () => K.hostStop(id, ch));
  $('cmd-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const v = $('cmd').value.trim();
    if (!v) return;
    history.push(v);
    hi = history.length;
    line('> ' + v);
    if (!(await K.hostCmd(id, ch, v))) line('The server is not running.');
    $('cmd').value = '';
  });
  // Up / down: earlier commands.
  $('cmd').addEventListener('keydown', (e) => {
    if (e.key === 'ArrowUp' && hi > 0) $('cmd').value = history[--hi];
    else if (e.key === 'ArrowDown') $('cmd').value = hi < history.length - 1 ? history[++hi] : ((hi = history.length), '');
    else return;
    e.preventDefault();
  });
})();
