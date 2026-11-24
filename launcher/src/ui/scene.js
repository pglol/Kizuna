// Painted, slowly moving backdrops for each server, drawn on a canvas so the launcher ships with
// no image files. A server can also give an image (art) to use instead. Moving the mouse shifts the
// layers a little (parallax); switching server cross-fades.
(function () {
  const cv = document.getElementById('scene');
  const g = cv.getContext('2d');
  let W = 0, H = 0, dpr = 1;
  let mx = 0, my = 0, px = 0, py = 0;
  let cur = { theme: 'none', accent: '#e8c36f', img: null, born: 0 };
  let prev = null;
  const t0 = performance.now();

  function resize() {
    dpr = Math.min(2, window.devicePixelRatio || 1);
    W = cv.clientWidth;
    H = cv.clientHeight;
    cv.width = Math.round(W * dpr);
    cv.height = Math.round(H * dpr);
  }
  window.addEventListener('resize', resize);
  window.addEventListener('mousemove', (e) => {
    mx = e.clientX / Math.max(1, W) - 0.5;
    my = e.clientY / Math.max(1, H) - 0.5;
  });
  resize();

  // A fixed pseudo-random sequence so the scene looks the same every time.
  function rng(seed) {
    let s = seed >>> 0;
    return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 4294967296);
  }

  const embers = Array.from({ length: 70 }, (_, i) => { const r = rng(i * 7 + 3); return { x: r(), y: r(), s: 0.4 + r() * 1.6, v: 0.01 + r() * 0.03, w: r() * 6.28 }; });
  const petals = Array.from({ length: 60 }, (_, i) => { const r = rng(i * 13 + 5); return { x: r(), y: r(), s: 2 + r() * 3, v: 0.02 + r() * 0.04, w: r() * 6.28 }; });

  function ridge(seed, y, amp, rough, color, shift) {
    const r = rng(seed);
    const pts = [];
    const n = 24;
    let v = 0;
    for (let i = 0; i <= n; i++) {
      v = v * 0.55 + (r() - 0.5) * rough;
      pts.push(v);
    }
    g.fillStyle = color;
    g.beginPath();
    g.moveTo(-60 + shift, H);
    for (let i = 0; i <= n; i++) g.lineTo(-60 + shift + (i / n) * (W + 120), y - amp * (0.5 + pts[i]));
    g.lineTo(W + 60 + shift, H);
    g.closePath();
    g.fill();
  }

  function titan(x, base, h, t, phase, eyes) {
    const bob = Math.sin(t * 0.8 + phase) * h * 0.012;
    const top = base - h + bob;
    g.fillStyle = '#0d0a0b';
    // Legs, mid-stride.
    const st = Math.sin(t * 0.8 + phase) * h * 0.06;
    g.fillRect(x - h * 0.09 + st, base - h * 0.45, h * 0.075, h * 0.45);
    g.fillRect(x + h * 0.02 - st, base - h * 0.45, h * 0.075, h * 0.45);
    // Body, arms, head.
    g.beginPath();
    g.ellipse(x, top + h * 0.38, h * 0.13, h * 0.22, 0, 0, Math.PI * 2);
    g.fill();
    g.fillRect(x - h * 0.19, top + h * 0.24, h * 0.06, h * 0.32);
    g.fillRect(x + h * 0.13, top + h * 0.24, h * 0.06, h * 0.32);
    g.beginPath();
    g.ellipse(x, top + h * 0.1, h * 0.075, h * 0.09, 0, 0, Math.PI * 2);
    g.fill();
    if (eyes) {
      g.fillStyle = eyes;
      g.shadowColor = eyes;
      g.shadowBlur = 10;
      g.fillRect(x - h * 0.035, top + h * 0.09, h * 0.018, h * 0.01);
      g.fillRect(x + h * 0.017, top + h * 0.09, h * 0.018, h * 0.01);
      g.shadowBlur = 0;
    }
  }

  function aot(t, a) {
    const sky = g.createLinearGradient(0, 0, 0, H);
    sky.addColorStop(0, '#141d2b');
    sky.addColorStop(0.45, '#3a3442');
    sky.addColorStop(0.72, '#b0704a');
    sky.addColorStop(1, '#2a1a14');
    g.fillStyle = sky;
    g.fillRect(0, 0, W, H);
    // Low sun and its glow.
    const sx = W * 0.72 - px * 20, sy = H * 0.6 - py * 10;
    const glow = g.createRadialGradient(sx, sy, 10, sx, sy, H * 0.7);
    glow.addColorStop(0, 'rgba(255,200,120,0.55)');
    glow.addColorStop(1, 'rgba(255,200,120,0)');
    g.fillStyle = glow;
    g.fillRect(0, 0, W, H);
    g.fillStyle = '#ffd9a0';
    g.beginPath();
    g.arc(sx, sy, H * 0.07, 0, Math.PI * 2);
    g.fill();
    ridge(11, H * 0.64, H * 0.12, 0.9, '#3c2b2c', -px * 18);
    // Titans far off, walking towards the wall.
    for (let i = 0; i < 5; i++) {
      const r = rng(40 + i);
      const x = ((r() * 1.4 + t * 0.004 * (0.5 + r())) % 1.4 - 0.2) * W - px * 30;
      titan(x, H * 0.7, H * (0.14 + r() * 0.12), t, i * 1.7, i === 2 ? '#ff4030' : null);
    }
    // The Wall: a long band with a parapet and a gate.
    const wy = H * 0.7 - py * 8, wh = H * 0.2;
    const wall = g.createLinearGradient(0, wy - wh, 0, wy);
    wall.addColorStop(0, '#4a3e38');
    wall.addColorStop(1, '#231b18');
    g.fillStyle = wall;
    g.fillRect(0, wy - wh, W, wh + 2);
    g.fillStyle = 'rgba(255,210,150,0.18)';
    g.fillRect(0, wy - wh, W, 2);
    for (let x = -px * 40 % 40; x < W; x += 40) g.fillRect(x, wy - wh - 6, 22, 6);
    const gx = W * 0.4 - px * 40;
    g.fillStyle = '#120d0c';
    g.beginPath();
    g.moveTo(gx - wh * 0.32, wy);
    g.lineTo(gx - wh * 0.32, wy - wh * 0.42);
    g.quadraticCurveTo(gx, wy - wh * 0.72, gx + wh * 0.32, wy - wh * 0.42);
    g.lineTo(gx + wh * 0.32, wy);
    g.fill();
    // Someone on gear, swinging along the wall top.
    const u = (t * 0.12) % 1;
    const ax = W * (0.15 + u * 0.7) - px * 40, ay = wy - wh - H * 0.12;
    const sw = Math.sin(u * Math.PI * 6) * 0.9;
    const fx = ax + Math.sin(sw) * H * 0.08, fy = ay + Math.cos(sw) * H * 0.08;
    g.strokeStyle = 'rgba(230,240,255,0.5)';
    g.lineWidth = 1;
    g.beginPath();
    g.moveTo(ax, ay);
    g.lineTo(fx, fy);
    g.stroke();
    g.fillStyle = '#0c0a0a';
    g.beginPath();
    g.arc(fx, fy, 3, 0, Math.PI * 2);
    g.fill();
    // Birds.
    g.strokeStyle = 'rgba(20,16,18,0.7)';
    g.lineWidth = 1.5;
    for (let i = 0; i < 7; i++) {
      const r = rng(90 + i);
      const bx = ((r() + t * 0.01 * (0.6 + r())) % 1.2 - 0.1) * W, by = H * (0.18 + r() * 0.2);
      const f = Math.sin(t * 6 + i) * 4;
      g.beginPath();
      g.moveTo(bx - 7, by - f);
      g.lineTo(bx, by);
      g.lineTo(bx + 7, by - f);
      g.stroke();
    }
    // Ground and embers.
    g.fillStyle = '#0d0a09';
    g.fillRect(0, wy, W, H - wy);
    for (const e of embers) {
      const y = (e.y - t * e.v) % 1;
      const yy = (y < 0 ? y + 1 : y) * H;
      const xx = e.x * W + Math.sin(t + e.w) * 20 - px * 50;
      g.fillStyle = `rgba(255,${150 + (e.s * 40) | 0},80,${0.5 * (yy / H)})`;
      g.fillRect(xx, yy, e.s, e.s);
    }
  }

  function duel(t, a) {
    const sky = g.createLinearGradient(0, 0, 0, H);
    sky.addColorStop(0, '#0a1020');
    sky.addColorStop(0.6, '#23324a');
    sky.addColorStop(1, '#0b0e14');
    g.fillStyle = sky;
    g.fillRect(0, 0, W, H);
    const mx0 = W * 0.66 - px * 15, my0 = H * 0.3 - py * 8;
    const glow = g.createRadialGradient(mx0, my0, 10, mx0, my0, H * 0.5);
    glow.addColorStop(0, 'rgba(190,220,255,0.35)');
    glow.addColorStop(1, 'rgba(190,220,255,0)');
    g.fillStyle = glow;
    g.fillRect(0, 0, W, H);
    g.fillStyle = '#e6eefc';
    g.beginPath();
    g.arc(mx0, my0, H * 0.11, 0, Math.PI * 2);
    g.fill();
    ridge(5, H * 0.58, H * 0.22, 1.1, 'rgba(60,80,110,0.85)', -px * 10);
    ridge(6, H * 0.68, H * 0.16, 1.0, 'rgba(36,50,72,0.92)', -px * 22);
    // Mist.
    for (let i = 0; i < 3; i++) {
      const y = H * (0.62 + i * 0.07);
      const m = g.createLinearGradient(0, y - 30, 0, y + 30);
      m.addColorStop(0, 'rgba(160,190,230,0)');
      m.addColorStop(0.5, `rgba(160,190,230,${0.12 - i * 0.02})`);
      m.addColorStop(1, 'rgba(160,190,230,0)');
      g.fillStyle = m;
      g.fillRect(0, y - 30, W, 60);
    }
    // A broken stone arch and the cliff two fighters stand on.
    const cx = W * 0.62 - px * 40, cy = H * 0.8 - py * 10;
    g.fillStyle = '#0a0d13';
    g.beginPath();
    g.moveTo(cx - W * 0.4, H);
    g.lineTo(cx - W * 0.22, cy);
    g.lineTo(cx + W * 0.2, cy + 6);
    g.lineTo(cx + W * 0.42, H);
    g.fill();
    g.fillRect(cx + W * 0.1, cy - H * 0.3, H * 0.03, H * 0.3);
    g.fillRect(cx + W * 0.1 + H * 0.16, cy - H * 0.22, H * 0.03, H * 0.22);
    g.fillRect(cx + W * 0.1 - H * 0.02, cy - H * 0.32, H * 0.14, H * 0.025);
    // The fighters: circling, then a clash.
    const k = (t * 0.5) % 4;
    const close = k > 3 ? 1 - Math.abs(k - 3.5) * 2 : 0;
    const gap = H * (0.11 - close * 0.06);
    const f1 = cx - gap, f2 = cx + gap, fh = H * 0.09;
    function fighter(x, dir) {
      g.fillStyle = '#05070b';
      g.beginPath();
      g.arc(x, cy - fh, fh * 0.13, 0, Math.PI * 2);
      g.fill();
      g.fillRect(x - fh * 0.08, cy - fh * 0.88, fh * 0.16, fh * 0.45);
      g.save();
      g.translate(x, cy - fh * 0.45);
      g.rotate(dir * 0.35);
      g.fillRect(-fh * 0.03, 0, fh * 0.07, fh * 0.45);
      g.restore();
      g.save();
      g.translate(x, cy - fh * 0.45);
      g.rotate(-dir * 0.45);
      g.fillRect(-fh * 0.03, 0, fh * 0.07, fh * 0.45);
      g.restore();
      g.save();
      g.translate(x, cy - fh * 0.78);
      g.rotate(dir * (1.1 - close * 0.6));
      g.fillRect(0, -fh * 0.03, fh * 0.42, fh * 0.06);
      g.restore();
    }
    fighter(f1, 1);
    fighter(f2, -1);
    if (close > 0.7) {
      const fl = (close - 0.7) / 0.3;
      g.fillStyle = `rgba(200,230,255,${fl * 0.8})`;
      g.beginPath();
      g.arc(cx, cy - fh * 0.8, 4 + fl * 14, 0, Math.PI * 2);
      g.fill();
    }
    // Falling petals.
    for (const p of petals) {
      const y = (p.y + t * p.v) % 1;
      const x = (p.x + t * p.v * 0.6) % 1;
      g.fillStyle = 'rgba(240,200,220,0.6)';
      g.save();
      g.translate(x * W - px * 60, y * H);
      g.rotate(t * 2 + p.w);
      g.fillRect(-p.s / 2, -p.s / 4, p.s, p.s / 2);
      g.restore();
    }
  }

  function plain(t, a) {
    g.fillStyle = '#0b0e0d';
    g.fillRect(0, 0, W, H);
    const r = g.createRadialGradient(W * 0.7, H * 0.4, 10, W * 0.7, H * 0.4, H);
    r.addColorStop(0, a + '55');
    r.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = r;
    g.fillRect(0, 0, W, H);
    for (const e of embers) {
      const y = ((e.y - t * e.v * 0.5) % 1 + 1) % 1;
      g.fillStyle = a + '66';
      g.fillRect(e.x * W - px * 40, y * H, e.s, e.s);
    }
  }

  function art(img, t) {
    const z = 1.06 + Math.sin(t * 0.05) * 0.03;
    const s = Math.max(W / img.width, H / img.height) * z;
    const w = img.width * s, h = img.height * s;
    g.drawImage(img, (W - w) / 2 - px * 30, (H - h) / 2 - py * 20, w, h);
  }

  function draw(sc, t) {
    if (sc.img && sc.img.complete && sc.img.naturalWidth) return art(sc.img, t);
    if (sc.theme === 'aot') return aot(t, sc.accent);
    if (sc.theme === 'duel') return duel(t, sc.accent);
    return plain(t, sc.accent);
  }

  function frame(now) {
    const t = (now - t0) / 1000;
    px += (mx - px) * 0.04;
    py += (my - py) * 0.04;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.globalAlpha = 1;
    const fade = Math.min(1, (now - cur.born) / 700);
    if (prev && fade < 1) {
      draw(prev, t);
      g.globalAlpha = fade;
    }
    draw(cur, t);
    g.globalAlpha = 1;
    if (fade >= 1) prev = null;
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);

  window.Scene = {
    set(theme, accent, artUrl) {
      if (cur.theme === theme && cur.accent === accent && (cur.url || '') === (artUrl || '')) return;
      prev = cur;
      let img = null;
      if (artUrl) {
        img = new Image();
        img.src = artUrl;
      }
      cur = { theme, accent: accent || '#e8c36f', img, url: artUrl || '', born: performance.now() };
    },
  };
})();
