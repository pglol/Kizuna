"""Turns each pack's mods.txt into packwiz entries (mods/<name>.pw.toml) with exact download URLs
and hashes. Entries it already wrote are kept as they are (so builds stay stable) unless you pass
--upgrade; entries dropped from mods.txt are removed. Run from the repo root:

    python3 servers/resolve_mods.py <raw-base-url> [--upgrade]
"""
import hashlib, json, os, re, sys, urllib.parse, urllib.request

MARK = "# managed by mods.txt"
base = sys.argv[1].rstrip("/")
upgrade = "--upgrade" in sys.argv
repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UA = {"User-Agent": "warfront-pack-builder/1.0"}

def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
        return r.read()

def versions(pack):
    t = open(os.path.join(pack, "pack.toml")).read()
    mc = re.search(r'minecraft\s*=\s*"([^"]+)"', t).group(1)
    return mc

def entry(name, filename, side, url, algo, h, extra=""):
    return (f'{MARK}\nname = "{name}"\nfilename = "{filename}"\nside = "{side}"\n\n'
            f'[download]\nurl = "{url}"\nhash-format = "{algo}"\nhash = "{h}"\n' + extra)

def modrinth(slug, mc):
    q = urllib.parse.urlencode({"loaders": json.dumps(["fabric"]), "game_versions": json.dumps([mc])})
    vs = json.loads(get(f"https://api.modrinth.com/v2/project/{slug}/version?{q}"))
    if not vs:
        raise RuntimeError(f"{slug}: no Fabric build for {mc}")
    v = next((x for x in vs if x["version_type"] == "release"), vs[0])
    f = next((x for x in v["files"] if x.get("primary")), v["files"][0])
    proj = json.loads(get(f"https://api.modrinth.com/v2/project/{slug}"))
    return proj["title"], f["filename"], f["url"], f["hashes"]["sha512"], v["project_id"], v["id"]

problems = 0
hub = json.load(open(os.path.join(repo, "servers", "hub.json")))
packs = sorted({"pack"} | {s["pack"] for s in hub["servers"]})
for p in packs:
    pack = os.path.join(repo, p)
    lst = os.path.join(pack, "mods.txt")
    if not os.path.exists(lst):
        continue
    mc = versions(pack)
    mods = os.path.join(pack, "mods")
    os.makedirs(mods, exist_ok=True)
    wanted = set()
    for raw in open(lst):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        kind = parts[0]
        try:
            if kind == "modrinth":
                slug, side = parts[1], (parts[2] if len(parts) > 2 else "both")
                out = os.path.join(mods, f"{slug}.pw.toml")
                wanted.add(out)
                if os.path.exists(out) and not upgrade and f'side = "{side}"' in open(out).read():
                    continue
                title, fn, url, h, pid, vid = modrinth(slug, mc)
                open(out, "w").write(entry(title, fn, side, url, "sha512", h,
                    f'\n[update]\n[update.modrinth]\nmod-id = "{pid}"\nversion = "{vid}"\n'))
                print(f"{p}: {slug} -> {fn}")
            elif kind == "url":
                fn, url, side = parts[1], parts[2], (parts[3] if len(parts) > 3 else "both")
                out = os.path.join(mods, re.sub(r"\.jar$", "", fn) + ".pw.toml")
                wanted.add(out)
                if os.path.exists(out) and not upgrade and url in open(out).read():
                    continue
                h = hashlib.sha256(get(url)).hexdigest()
                open(out, "w").write(entry(fn, fn, side, url, "sha256", h))
                print(f"{p}: {fn} (url)")
            elif kind == "file":
                rel, side = parts[1], (parts[2] if len(parts) > 2 else "both")
                src = os.path.join(repo, rel)
                fn = os.path.basename(rel)
                out = os.path.join(mods, re.sub(r"\.jar$", "", fn) + ".pw.toml")
                wanted.add(out)
                h = hashlib.sha256(open(src, "rb").read()).hexdigest()
                open(out, "w").write(entry(fn, fn, side, f"{base}/{rel}", "sha256", h))
            else:
                raise RuntimeError(f"unknown line: {line}")
        except Exception as e:
            problems += 1
            print(f"WARNING {p}: {line}: {e}")
    # Drop entries this script wrote that are no longer listed.
    for f in os.listdir(mods):
        fp = os.path.join(mods, f)
        if f.endswith(".pw.toml") and fp not in wanted and open(fp).read().startswith(MARK):
            os.remove(fp)
            print(f"{p}: removed {f}")
print("mods resolved" + (f" with {problems} warning(s)" if problems else ""))
