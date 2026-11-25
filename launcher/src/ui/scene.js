// The cover panel's picture, drawn in ink: screentone skies, speed lines and silhouettes, redrawn a
// few times a second with a little wobble so the lines "boil" the way hand-drawn animation does.
// A server can give a picture (art) instead; it's shown in black and white with a screentone.
(function () {
  const cv = document.getElementById('scene');
  const img = document.getElementById('art');
  const g = cv.getContext('2d');
  const INK = '#15120f', PAPER = '#101013';
  let W = 0, H = 0, dpr = 1;
  let cur = { theme: '絆', accent: '#c42b1f' };
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
    const k = cur.theme || '絆';
    // Dark stage: a glow of the game's colour behind a huge faint kanji.
    const glow = g.createRadialGradient(W * 0.62, H * 0.42, 0, W * 0.62, H * 0.42, Math.max(W, H) * 0.7);
    glow.addColorStop(0, cur.accent + '66');
    glow.addColorStop(1, 'rgba(16,16,19,0)');
    g.fillStyle = glow;
    g.fillRect(0, 0, W, H);
    g.fillStyle = 'rgba(255,255,255,0.05)';
    g.font = `${Math.min(W, H) * 0.95}px 'Yuji Syuku', serif`;
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.fillText(k, W * 0.7, H * 0.5);
    g.fillStyle = 'rgba(255,255,255,0.14)';
    g.font = `${Math.min(W, H) * 0.55}px 'Yuji Syuku', serif`;
    g.fillText(k, W * 0.64, H * 0.44);
  }

  function draw() {
    if (!W) return;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.fillStyle = PAPER;
    g.fillRect(0, 0, W, H);
    wob = rng(seed);
    cover();
  }


  // Pictures: the main one, then any gallery ones as a slideshow (crossfading every 6 seconds).
  const dots = document.getElementById('dots');
  let slides = [], at = 0, timer = null, token = 0;

  function showSlide(i) {
    if (!slides.length) return;
    at = (i + slides.length) % slides.length;
    img.style.opacity = '0';
    setTimeout(() => {
      img.src = slides[at];
      img.style.opacity = '1';
    }, 220);
    [...dots.children].forEach((d, k) => d.classList.toggle('on', k === at));
  }

  window.Scene = {
    set(theme, accent, urls) {
      cur = { theme: theme || '絆', accent: accent || '#c42b1f' };
      const my = ++token;
      clearInterval(timer);
      slides = [];
      dots.innerHTML = '';
      img.hidden = true;
      cv.hidden = false;
      resize();
      draw();
      if (document.fonts) document.fonts.load("100px 'Yuji Syuku'", cur.theme).then(draw, () => {});
      const list = (Array.isArray(urls) ? urls : [urls]).filter(Boolean);
      // Keep only the pictures that actually load, in order; the kanji cover stays if none do.
      list.forEach((u, k) => {
        const probe = new Image();
        probe.onload = () => {
          if (my !== token) return;
          slides[k] = u;
          slides = slides.filter(Boolean);
          if (slides.length === 1) {
            img.src = u;
            img.style.opacity = '1';
            img.hidden = false;
            cv.hidden = true;
          }
          dots.innerHTML = '';
          if (slides.length > 1) {
            slides.forEach((_, i) => {
              const d = document.createElement('button');
              d.className = 'dot' + (i === at ? ' on' : '');
              d.addEventListener('click', () => {
                showSlide(i);
                clearInterval(timer);
                timer = setInterval(() => showSlide(at + 1), 6000);
              });
              dots.append(d);
            });
            clearInterval(timer);
            timer = setInterval(() => showSlide(at + 1), 6000);
          }
        };
        probe.src = u;
      });
      at = 0;
    },
  };
  document.fonts && document.fonts.ready.then(draw);
})();
