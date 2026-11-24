// The strip along the bottom: each game's own world, built from blocks and inked like a manga
// panel. Attack on Titan gets the Wall (with someone peeking over now and then), Stancebound gets
// stepped mountain peaks with drifting petals. Colours come from the server's accent.
(function () {
  const cv = document.getElementById('ground');
  const g = cv.getContext('2d');
  const INK = '#1c1712', PAPER = '#fbf7ee';
  let W = 0, H = 0, dpr = 1, theme = '', accent = '#c4372a', t0 = performance.now();

  function resize() {
    const r = cv.getBoundingClientRect();
    dpr = Math.min(2, window.devicePixelRatio || 1);
    W = r.width;
    H = r.height;
    cv.width = Math.round(W * dpr);
    cv.height = Math.round(H * dpr);
  }
  new ResizeObserver(resize).observe(cv);

  const rng = (s) => () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
  function mix(hex, k) {
    const n = parseInt(hex.slice(1), 16);
    const f = (c) => Math.round(c + (255 - c) * k);
    return `rgb(${f(n >> 16)},${f((n >> 8) & 255)},${f(n & 255)})`;
  }
  function block(x, y, s, fill) {
    g.fillStyle = fill;
    g.fillRect(x, y, s, s);
    g.strokeStyle = INK;
    g.lineWidth = 1.5;
    g.strokeRect(x + 0.75, y + 0.75, s - 1.5, s - 1.5);
  }
  function tone(x0, y0, x1, y1, a) {
    g.fillStyle = `rgba(28,23,18,${a})`;
    for (let y = y0; y < y1; y += 4) for (let x = x0 + ((y / 4) % 2) * 2; x < x1; x += 4) g.fillRect(x, y, 1.2, 1.2);
  }
  function clouds(t, y, s) {
    for (let i = 0; i < 4; i++) {
      const x = ((i * 0.31 + t * 0.004 * (1 + i * 0.3)) % 1.3 - 0.15) * W;
      for (const [dx, dy] of [[0, 0], [1, 0], [2, 0], [1, -1], [3, 0]]) block(x + dx * s, y + i % 2 * 6 + dy * s, s, '#fff');
    }
  }

  function wall(t) {
    const s = 14, top = H * 0.4;
    // Rooftops behind the wall, in the accent colour.
    const r = rng(5);
    for (let x = 0; x < W; x += s * 4) {
      const h = 2 + Math.floor(r() * 2);
      for (let k = 0; k < h; k++) block(x + k * s * 0.5, top - (k + 1) * s * 0.55 + 18, s * 1.2, mix(accent, 0.35 + k * 0.15));
    }
    // A titan peeking over the wall every so often.
    const cyc = (t * 0.06) % 1, peek = cyc < 0.25 ? Math.sin((cyc / 0.25) * Math.PI) : 0;
    if (peek > 0) {
      const hx = W * 0.7, hs = 9, rise = peek * hs * 5;
      for (let y = 0; y < 6; y++) for (let x = -3; x <= 3; x++) {
        if (Math.abs(x) === 3 && y === 0) continue;
        block(hx + x * hs, top + 14 - rise + y * hs, hs, y === 2 && Math.abs(x) === 1 ? INK : '#f1d9c4');
      }
    }
    // The wall: offset rows of stone blocks with a gate.
    for (let row = 0; top + row * s < H; row++) {
      const off = (row % 2) * s * 0.5;
      for (let x = -s + off; x < W; x += s * 2) {
        const gate = x > W * 0.28 && x < W * 0.28 + s * 6 && row > 1;
        if (!gate) {
          block(x, top + row * s, s * 2, row === 0 ? '#e9e1cf' : '#ddd3bd');
        }
      }
    }
    g.fillStyle = INK;
    g.fillRect(W * 0.28 + s * 0.5, top + s * 2, s * 5, H);
    tone(0, top, W * 0.25, H, 0.18);
    clouds(t, 6, 6);
  }

  function peaks(t) {
    const s = 12;
    const r = rng(11);
    for (let layer = 0; layer < 2; layer++) {
      let h = 3;
      for (let x = 0; x < W; x += s) {
        h = Math.max(1, Math.min(7 - layer * 2, h + (r() < 0.5 ? -1 : 1)));
        for (let k = 0; k < h + layer * 2; k++) block(x, H - (k + 1) * s - layer * 4, s, layer === 0 ? mix(accent, 0.75) : mix(accent, 0.45));
      }
    }
    // Petals drifting across.
    const pr = rng(3);
    for (let i = 0; i < 26; i++) {
      const x = ((pr() + t * 0.02 * (0.5 + pr())) % 1) * W, y = ((pr() + t * 0.03) % 1) * H;
      g.save();
      g.translate(x, y);
      g.rotate(t + i);
      g.fillStyle = mix('#e88aa0', 0.2);
      g.fillRect(-3, -2, 6, 4);
      g.restore();
    }
    clouds(t, 8, 6);
  }

  function plain(t) {
    const s = 14;
    for (let x = 0; x < W; x += s) {
      const h = 2 + Math.round(Math.sin(x * 0.01) * 1.5);
      for (let k = 0; k < h; k++) block(x, H - (k + 1) * s, s, mix(accent, 0.6 + k * 0.08));
    }
    clouds(t, 10, 6);
  }

  function frame(now) {
    if (W && !document.hidden) {
      const t = (now - t0) / 1000;
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.fillStyle = PAPER;
      g.fillRect(0, 0, W, H);
      if (theme === 'wall') wall(t);
      else if (theme === 'peaks') peaks(t);
      else plain(t);
      g.fillStyle = INK;
      g.fillRect(0, 0, W, 2.5);
    }
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);

  window.Ground = {
    set(th, acc) {
      theme = th || '';
      accent = /^#[0-9a-f]{6}$/i.test(acc || '') ? acc : '#c4372a';
    },
  };
})();
