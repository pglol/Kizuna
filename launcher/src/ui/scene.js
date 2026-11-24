// The cover panel's picture, drawn in ink: screentone skies, speed lines and silhouettes, redrawn a
// few times a second with a little wobble so the lines "boil" the way hand-drawn animation does.
// A server can give a picture (art) instead; it's shown in black and white with a screentone.
(function () {
  const cv = document.getElementById('scene');
  const img = document.getElementById('art');
  const g = cv.getContext('2d');
  const INK = '#15120f', PAPER = '#faf7f0';
  let W = 0, H = 0, dpr = 1;
  let cur = { theme: 'none', accent: '#c42b1f' };
  let seed = 1;

  function resize() {
    const r = cv.getBoundingClientRect();
    dpr = Math.min(2, window.devicePixelRatio || 1);
    W = Math.max(1, r.width);
    H = Math.max(1, r.height);
    cv.width = Math.round(W * dpr);
    cv.height = Math.round(H * dpr);
  }
  new ResizeObserver(() => {
    resize();
    draw();
  }).observe(cv);

  function rng(s) {
    let x = s >>> 0;
    return () => ((x = (x * 1664525 + 1013904223) >>> 0) / 4294967296);
  }
  // Wobble: each redraw nudges every line a hair, from a seed that changes a few times a second.
  let wob;
  const j = (n = 1) => (wob() - 0.5) * n;

  /** Screentone: dots on a grid that grow towards the dark side (a vertical gradient). */
  function tone(x0, y0, x1, y1, from, to, step = 5) {
    g.fillStyle = INK;
    for (let y = y0; y < y1; y += step) {
      const k = from + (to - from) * ((y - y0) / Math.max(1, y1 - y0));
      const r = Math.max(0, k) * step * 0.55;
      if (r < 0.25) continue;
      const off = (Math.round(y / step) % 2) * step * 0.5;
      for (let x = x0 + off; x < x1; x += step) {
        g.beginPath();
        g.arc(x, y, r, 0, 6.283);
        g.fill();
      }
    }
  }

  /** Speed lines radiating from a point, leaving a clear centre. */
  function focus(cx, cy, inner, n, width) {
    const r = rng(77);
    g.fillStyle = INK;
    const R = Math.hypot(W, H);
    for (let i = 0; i < n; i++) {
      const a = (i / n) * 6.283 + r() * 0.05;
      const w = (0.002 + r() * 0.01) * width;
      const start = inner * (0.9 + r() * 0.5) + j(6);
      g.beginPath();
      g.moveTo(cx + Math.cos(a) * start, cy + Math.sin(a) * start);
      g.lineTo(cx + Math.cos(a - w) * R, cy + Math.sin(a - w) * R);
      g.lineTo(cx + Math.cos(a + w) * R, cy + Math.sin(a + w) * R);
      g.closePath();
      g.fill();
    }
  }

  /** A shaky ink line. */
  function stroke(pts, w) {
    g.strokeStyle = INK;
    g.lineWidth = w;
    g.lineJoin = g.lineCap = 'round';
    g.beginPath();
    pts.forEach(([x, y], i) => (i ? g.lineTo(x + j(1.2), y + j(1.2)) : g.moveTo(x + j(1.2), y + j(1.2))));
    g.stroke();
  }

  function aot() {
    // Sky: tone getting darker towards the top, the way a dusk sky is shaded in print.
    tone(0, 0, W, H * 0.62, 0.55, 0.05, 5);
    // A wall across the panel, drawn as blocks.
    const wy = H * 0.5, wh = H * 0.5;
    g.fillStyle = PAPER;
    g.fillRect(0, wy, W, wh);
    stroke([[0, wy], [W, wy]], 4);
    const rows = 7;
    for (let r = 1; r < rows; r++) {
      const y = wy + (wh / rows) * r;
      stroke([[0, y], [W, y]], 1.4);
      const off = (r % 2) * 26;
      for (let x = off; x < W; x += 52) stroke([[x, y - wh / rows], [x, y]], 1.2);
    }
    // Shading down the wall's left side.
    tone(0, wy + 2, W * 0.35, H, 0.35, 0.6, 4);
    // A giant peering over the top: only the crown, the brow and the eyes clear the wall.
    const hx = W * 0.6, hr = Math.min(W * 0.34, H * 0.42), hy = wy + hr * 0.3;
    g.save();
    g.beginPath();
    g.rect(0, 0, W, wy);
    g.clip();
    g.fillStyle = PAPER;
    g.beginPath();
    g.ellipse(hx, hy, hr * 0.78, hr, 0, 0, 6.283);
    g.fill();
    // Hatching down the shadowed side of the face.
    g.strokeStyle = INK;
    g.lineWidth = 1;
    for (let k = 0; k < 26; k++) {
      const x = hx + hr * 0.2 + k * hr * 0.022;
      g.beginPath();
      g.moveTo(x + j(), hy - hr + k * 2);
      g.lineTo(x - hr * 0.12 + j(), wy);
      g.stroke();
    }
    g.lineWidth = 3.5;
    g.beginPath();
    g.ellipse(hx + j(0.8), hy + j(0.8), hr * 0.78, hr, 0, 0, 6.283);
    g.stroke();
    // Hair: ragged strands falling over the forehead.
    g.fillStyle = INK;
    const hr2 = rng(9);
    for (let k = 0; k < 34; k++) {
      const a0 = Math.PI * (1.03 + (k / 34) * 0.94);
      const x0 = hx + Math.cos(a0) * hr * 0.8, y0 = hy + Math.sin(a0) * hr * 1.02;
      const len = hr * (0.08 + hr2() * 0.16);
      g.beginPath();
      g.moveTo(x0 - 6, y0);
      g.lineTo(x0 + 6, y0);
      g.lineTo(x0 + (hx - x0) * 0.15 + j(3), y0 + len);
      g.closePath();
      g.fill();
    }
    // Sunken sockets in tone, wide staring eyes, pinprick pupils, a heavy brow.
    for (const sd of [-1, 1]) {
      const ex = hx + sd * hr * 0.3, ey = wy - hr * 0.2;
      g.save();
      g.beginPath();
      g.ellipse(ex, ey, hr * 0.22, hr * 0.14, 0, 0, 6.283);
      g.clip();
      tone(ex - hr * 0.24, ey - hr * 0.16, ex + hr * 0.24, ey + hr * 0.16, 0.8, 0.5, 3);
      g.restore();
      g.fillStyle = '#fff';
      g.beginPath();
      g.ellipse(ex, ey, hr * 0.13, hr * 0.075, 0, 0, 6.283);
      g.fill();
      g.lineWidth = 2.5;
      g.stroke();
      g.fillStyle = INK;
      g.beginPath();
      g.arc(ex + j(1.2), ey + j(1.2), hr * 0.022, 0, 6.283);
      g.fill();
      stroke([[ex - sd * hr * 0.2, ey - hr * 0.2], [ex + sd * hr * 0.12, ey - hr * 0.13]], 4);
    }
    g.restore();
    // Fingertips hooked over the top of the wall.
    for (const sd of [-1, 1]) {
      for (let f = 0; f < 4; f++) {
        const x = hx + sd * (hr * 0.95 + f * hr * 0.11);
        g.fillStyle = PAPER;
        g.beginPath();
        g.roundRect(x - hr * 0.045, wy - hr * 0.09, hr * 0.09, hr * 0.17, hr * 0.04);
        g.fill();
        g.lineWidth = 2.5;
        g.stroke();
        stroke([[x - hr * 0.03, wy - hr * 0.035], [x + hr * 0.03, wy - hr * 0.035]], 1.2);
      }
    }
    // Someone tiny, on gear lines, in front of it.
    const px = W * 0.22, py = H * 0.3;
    stroke([[px, py], [W * 0.05, H * 0.08]], 1.2);
    stroke([[px, py], [W * 0.36, H * 0.12]], 1.2);
    g.fillStyle = INK;
    g.beginPath();
    g.arc(px + j(), py + j(), 4, 0, 6.283);
    g.fill();
    stroke([[px, py + 3], [px - 6, py + 14]], 3);
    stroke([[px - 2, py + 6], [px + 10, py + 2]], 2);
    // Motion streaks behind them.
    for (let k = 0; k < 6; k++) stroke([[px + 10 + k * 3, py + 6 + k * 4], [px + 50 + k * 8, py + 18 + k * 6]], 1);
  }

  function duel() {
    focus(W * 0.5, H * 0.52, Math.min(W, H) * 0.26, 140, 40);
    // Two fighters mid-clash, in silhouette against the clear centre.
    const cx = W * 0.5, cy = H * 0.6, s = Math.min(W, H) * 0.2;
    function fighter(x, dir) {
      g.fillStyle = INK;
      g.beginPath();
      g.arc(x + j(), cy - s * 1.05 + j(), s * 0.14, 0, 6.283);
      g.fill();
      stroke([[x, cy - s * 0.9], [x - dir * s * 0.08, cy - s * 0.35]], s * 0.16);
      stroke([[x - dir * s * 0.08, cy - s * 0.35], [x - dir * s * 0.45, cy + s * 0.15]], s * 0.1);
      stroke([[x - dir * s * 0.08, cy - s * 0.35], [x + dir * s * 0.25, cy + s * 0.15]], s * 0.1);
      stroke([[x, cy - s * 0.8], [x + dir * s * 0.45, cy - s * 0.95]], s * 0.08);
      stroke([[x, cy - s * 0.75], [x - dir * s * 0.4, cy - s * 0.55]], s * 0.08);
    }
    fighter(cx - s * 0.55, 1);
    fighter(cx + s * 0.55, -1);
    // The clash: a white burst with a black outline.
    g.fillStyle = '#fff';
    g.strokeStyle = INK;
    g.lineWidth = 2.5;
    g.beginPath();
    const n = 12;
    for (let i = 0; i <= n * 2; i++) {
      const a = (i / (n * 2)) * 6.283;
      const r = (i % 2 ? s * 0.12 : s * 0.3) + j(3);
      const x = cx + Math.cos(a) * r, y = cy - s * 0.92 + Math.sin(a) * r;
      i ? g.lineTo(x, y) : g.moveTo(x, y);
    }
    g.fill();
    g.stroke();
    // Ground.
    tone(0, cy + s * 0.18, W, H, 0.2, 0.55, 5);
    stroke([[0, cy + s * 0.17], [W, cy + s * 0.17]], 3);
  }

  function plain() {
    focus(W * 0.5, H * 0.5, Math.min(W, H) * 0.3, 110, 28);
    g.fillStyle = INK;
    g.font = `${Math.min(W, H) * 0.42}px 'Yuji Syuku', serif`;
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.fillText('絆', W * 0.5 + j(2), H * 0.52 + j(2));
  }

  function draw() {
    if (!W) return;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.fillStyle = PAPER;
    g.fillRect(0, 0, W, H);
    wob = rng(seed);
    if (cur.theme === 'aot') aot();
    else if (cur.theme === 'duel') duel();
    else plain();
  }

  // Boil a few times a second; quiet when the window is hidden.
  setInterval(() => {
    if (document.hidden || !img.hidden) return;
    seed = (seed % 3) + 1;
    draw();
  }, 160);

  window.Scene = {
    set(theme, accent, artUrl) {
      cur = { theme: theme || 'none', accent: accent || '#c42b1f' };
      if (artUrl) {
        img.src = artUrl;
        img.hidden = false;
        cv.hidden = true;
      } else {
        img.hidden = true;
        img.removeAttribute('src');
        cv.hidden = false;
        resize();
        draw();
      }
    },
  };
  document.fonts && document.fonts.ready.then(draw);
})();
