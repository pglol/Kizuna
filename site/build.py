"""Builds the website (GitHub Pages) from servers/hub.json: a page for the launcher, one per
server, and a privacy page. Run from the repo root: python3 site/build.py <out-folder>
"""
import html, json, os, shutil, sys

repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(repo, "_site")
hub = json.load(open(os.path.join(repo, "servers", "hub.json")))
servers = [s for s in hub["servers"] if s.get("status") != "private"]
REPO_URL = "https://github.com/pglol/minecraft"
RELEASES = REPO_URL + "/releases/latest"
e = html.escape

shutil.rmtree(out, ignore_errors=True)
os.makedirs(os.path.join(out, "servers"))
for f in ("style.css", "fonts.css", "favicon.png"):
    shutil.copy(os.path.join(repo, "site", f), out)
shutil.copytree(os.path.join(repo, "site", "fonts"), os.path.join(out, "fonts"))
os.makedirs(os.path.join(out, "art"))

def art(path):
    """Copy a picture from the repo into the site; returns its site path (or '')."""
    if not path or path.startswith("http"):
        return path or ""
    src = os.path.join(repo, path)
    if not os.path.exists(src):
        return ""
    shutil.copy(src, os.path.join(out, "art"))
    return "art/" + os.path.basename(path)

STAMP = {"public": ("OPEN", "#2f7d4a"), "beta": ("BETA", "#6b3fa0"), "soon": ("COMING SOON", "#7a7064")}

def page(title, body, depth=0, desc=""):
    up = "../" * depth
    nav = "".join(f'<a class="tag" href="{up}servers/{e(s["id"])}.html">{e(s["name"])}</a>' for s in servers)
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{e(title)}</title><meta name="description" content="{e(desc)}">
<link rel="icon" href="{up}favicon.png"><link rel="stylesheet" href="{up}fonts.css"><link rel="stylesheet" href="{up}style.css"></head>
<body><header><a class="k" href="{up}index.html">絆</a><b>KIZUNA</b><nav>{nav}<a class="tag" href="{RELEASES}">Download</a></nav></header>
{body}
<footer><a href="{up}index.html">Kizuna</a><a href="{up}privacy.html">Privacy</a><a href="{REPO_URL}">GitHub</a><br>
Not affiliated with Mojang or Microsoft. Minecraft is a trademark of Mojang Synergies AB.</footer>
<script>
// The download button points at the newest Windows installer when GitHub can tell us which one it is.
fetch('https://api.github.com/repos/pglol/minecraft/releases/latest').then(r => r.ok ? r.json() : null).then(r => {{
  const a = r && (r.assets || []).find(x => /\\.exe$/i.test(x.name));
  if (a) document.querySelectorAll('[data-dl]').forEach(el => el.href = a.browser_download_url);
}}).catch(() => {{}});
</script></body></html>"""

def cover(s, up, cls=""):
    bg = art(s.get("background", ""))
    style = f' style="background-image:url(\'{up}{bg}\')"' if bg else ""
    inner = "" if bg else f'<div class="kanji">{e(s.get("kanji", "絆"))}</div>'
    label, color = STAMP.get(s.get("status", "public"), STAMP["public"])
    return f'<div class="panel cover {cls}"{style}>{inner}<span class="stamp" style="--sc:{color}">{label}</span></div>'

# The launcher's page.
cards = "".join(f'''<a class="card" href="servers/{e(s["id"])}.html"><div class="panel">{cover(s, "")}
<div class="cap"><b>{e(s["name"])}</b>{e(s.get("tagline", ""))}</div></div></a>''' for s in servers)
index = f"""<main class="page">
<section class="hero"><div class="panel cover" style="background:#fffdf8"><div class="kanji">絆</div><span class="vol">KIZUNA</span></div>
<div class="text"><span class="no">LAUNCHER</span><h1>One launcher for all our servers.</h1>
<p class="lead">Sign in with your Microsoft account, pick a server, press Play. Kizuna installs Minecraft, the right Java, mods and updates for you, then drops you straight into the server.</p>
<a class="cta" data-dl href="{RELEASES}">DOWNLOAD</a><span class="small">Windows · also on Mac and Linux from the releases page</span></div></section>
<h2>Servers</h2><div class="grid">{cards}</div>
<h2>How it works</h2><ol class="steps"><li>Download and install Kizuna.</li><li>Sign in with the Microsoft account you play Minecraft on.</li>
<li>Choose a server and a build (Public, or Early Access and Development with a code).</li><li>Press Play. Updates download by themselves every time.</li></ol>
</main>"""
open(os.path.join(out, "index.html"), "w").write(page("Kizuna Launcher", index, 0, "One launcher for all our modded Minecraft servers."))

# One page per server.
for i, s in enumerate(servers):
    up = "../"
    builds = "".join(f'<span class="{"locked" if c.get("code") or c.get("codeHash") else ""}">{e(c.get("name", c["id"]))}{" · code" if c.get("code") or c.get("codeHash") else ""}</span>' for c in s.get("channels", []))
    news = "".join(f'<div><small>{e(" · ".join(x for x in (n.get("tag", ""), n.get("date", "")) if x))}</small>{e(n.get("title", ""))}</div>' for n in s.get("news", []))
    play = (f'<a class="cta" data-dl href="{RELEASES}">PLAY WITH KIZUNA</a>' if s.get("status") != "soon"
            else '<span class="cta" style="background:#8a8173">COMING SOON</span>')
    body = f"""<main class="page" style="--accent:{e(s.get('accent', '#c4372a'))}">
<section class="hero">{cover(s, up).replace('<span class="stamp"', f'<span class="vol">VOL.{i + 1}</span><span class="stamp"')}
<div class="text"><span class="no">#{i + 1:02d} · MINECRAFT {e(s.get("minecraft", ""))}</span><h1>{e(s["name"])}</h1>
<p class="lead">{e(s.get("tagline", ""))}</p><p>{e(s.get("description", ""))}</p>{play}</div></section>
{f'<h2>Builds</h2><div class="builds">{builds}</div>' if builds else ''}
{f'<h2>News</h2><div class="news">{news}</div>' if news else ''}
<h2>How to join</h2><ol class="steps"><li>Get the Kizuna launcher.</li><li>Sign in with Microsoft.</li><li>Pick <b>{e(s["name"])}</b> and press Play.</li></ol>
</main>"""
    open(os.path.join(out, "servers", f"{s['id']}.html"), "w").write(page(f"{s['name']} · Kizuna", body, 1, s.get("tagline", "")))

privacy = """<main class="page"><div class="text"><h1>Privacy</h1>
<p>Kizuna is a launcher for our Minecraft servers. Here is everything it does with your information.</p>
<h2>Your Microsoft account</h2><p>You sign in on Microsoft's own page. The launcher receives a sign-in token, uses it to get your
Minecraft profile (name and id) and to start the game, and keeps the token only on your computer, encrypted with your system's
keychain where available. We never see your password, and the token is never sent to us.</p>
<h2>What we receive</h2><p>Nothing beyond what any Minecraft server receives when you join it: your Minecraft name and id. The
launcher downloads files (mods, the server list, updates) from GitHub and Mojang, and player heads from mc-heads.net.</p>
<h2>On your computer</h2><p>Settings, your friends list, game files and logs are stored in the launcher's folder on your computer.
Uninstalling the launcher and deleting that folder removes them. Sign out from Settings at any time.</p>
<h2>Contact</h2><p>Questions: open an issue on <a href="https://github.com/pglol/minecraft">GitHub</a>.</p></div></main>"""
open(os.path.join(out, "privacy.html"), "w").write(page("Privacy · Kizuna", privacy, 0))
print("site built:", out, "pages:", 2 + len(servers))
