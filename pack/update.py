"""Refreshes the packwiz manifest after a build: the mod's download URL and hash, then the index
and pack hashes. Run from the repo root: python3 pack/update.py <raw-base-url>
(raw-base-url: where this repo's files are served, e.g.
https://raw.githubusercontent.com/<owner>/<repo>/<branch>)."""
import hashlib, os, re, sys

base = sys.argv[1].rstrip("/")
root = os.path.dirname(os.path.abspath(__file__))
repo = os.path.dirname(root)

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

# The mod itself, straight from dist/.
mod = os.path.join(root, "mods", "aot-rpg.pw.toml")
t = open(mod).read()
t = set_field(t, "download", "url", f"{base}/dist/aot-rpg.jar")
t = set_field(t, "download", "hash", sha(os.path.join(repo, "dist", "aot-rpg.jar")))
open(mod, "w").write(t)

# Every metafile (and any plain file) under the pack, in the index.
lines = ['hash-format = "sha256"', ""]
for dirpath, _, files in os.walk(root):
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
print("pack updated")
