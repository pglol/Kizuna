"""Refreshes the packwiz manifests after a build: the AoT RPG mod's download URL and hash (in any
pack that carries it), then each pack's index and pack hashes. Every pack listed in
servers/hub.json is refreshed (and pack/ always). Run from the repo root:

    python3 pack/update.py <raw-base-url> [pack-folder ...]

(raw-base-url: where this repo's files are served, e.g.
https://raw.githubusercontent.com/<owner>/<repo>/<branch>)."""
import hashlib, json, os, re, sys

base = sys.argv[1].rstrip("/")
repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()

def set_field(text, section, key, value):
    out, cur = [], None
    for line in text.splitlines():
        m = re.match(r"\[(.+)\]", line.strip())
        if m:
            cur = m.group(1)
        if cur == section and re.match(rf"\s*{key}\s*=", line):
            line = f'{key} = "{value}"'
        out.append(line)
    return "\n".join(out) + "\n"

def update(root):
    # The mod itself, straight from dist/.
    mod = os.path.join(root, "mods", "aot-rpg.pw.toml")
    jar = os.path.join(repo, "dist", "aot-rpg.jar")
    if os.path.exists(mod) and os.path.exists(jar):
        t = open(mod).read()
        t = set_field(t, "download", "url", f"{base}/dist/aot-rpg.jar")
        t = set_field(t, "download", "hash", sha(jar))
        open(mod, "w").write(t)
    # Every metafile (and any plain file) under the pack, in the index.
    lines = ['hash-format = "sha256"', ""]
    for dirpath, dirs, files in os.walk(root):
        dirs.sort()
        for f in sorted(files):
            p = os.path.join(dirpath, f)
            rel = os.path.relpath(p, root).replace(os.sep, "/")
            if rel in ("pack.toml", "index.toml", "update.py", "README.md"):
                continue
            lines += ["[[files]]", f'file = "{rel}"', f'hash = "{sha(p)}"']
            if rel.endswith(".pw.toml"):
                lines.append("metafile = true")
            lines.append("")
    open(os.path.join(root, "index.toml"), "w").write("\n".join(lines))
    pack = os.path.join(root, "pack.toml")
    open(pack, "w").write(set_field(open(pack).read(), "index", "hash", sha(os.path.join(root, "index.toml"))))
    print("pack updated:", os.path.relpath(root, repo))

folders = sys.argv[2:]
if not folders:
    folders = ["pack"]
    hub = os.path.join(repo, "servers", "hub.json")
    if os.path.exists(hub):
        for s in json.load(open(hub))["servers"]:
            if s["pack"] not in folders:
                folders.append(s["pack"])
for f in folders:
    root = os.path.join(repo, f)
    if os.path.exists(os.path.join(root, "pack.toml")):
        update(root)
    else:
        print("no pack.toml in", f)
