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

  /** No picture yet: a typographic cover (big brush kanji, the series name) instead of a drawing. */
  function cover() {
    const k = KANJI[cur.theme] || '絆';
    g.fillStyle = 'rgba(21,18,15,0.07)';
    g.font = `${Math.min(W, H) * 0.95}px 'Yuji Syuku', serif`;
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.fillText(k, W * 0.55, H * 0.5);
    g.fillStyle = INK;
    g.font = `${Math.min(W, H) * 0.5}px 'Yuji Syuku', serif`;
    g.fillText(k, W * 0.5, H * 0.46);
    g.fillStyle = cur.accent;
    g.fillRect(W * 0.5 - 22, H * 0.78, 44, 4);
  }
  const KANJI = { aot: '巨', duel: '武', none: '絆' };

  function draw() {
    if (!W) return;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.fillStyle = PAPER;
    g.fillRect(0, 0, W, H);
    wob = rng(seed);
    cover();
  }


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
        // Canvas text doesn't pull in a font by itself: load the brush glyph, then draw again.
        if (document.fonts) document.fonts.load("100px 'Yuji Syuku'", KANJI[cur.theme] || '絆').then(draw, () => {});
      }
    },
  };
  document.fonts && document.fonts.ready.then(draw);
})();
