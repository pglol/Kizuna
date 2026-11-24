"""Builds one Prism Launcher instance per server in servers/hub.json, as dist/instances/<id>.zip.

Each instance is set to Minecraft + Fabric at the server's versions, runs packwiz-installer-bootstrap
before every launch (so it downloads whatever changed in that server's pack), then joins the
server's address. Players import the zip once (Prism: Add Instance -> Import) and just press Play
from then on. Run from the repo root:

    python3 servers/make_instances.py <raw-base-url>

<raw-base-url> is where this repo's files are served (the same one pack/update.py uses).
"""
import hashlib, io, json, os, sys, urllib.request, zipfile

BOOTSTRAP = "https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar"

base = sys.argv[1].rstrip("/")
repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
hub = json.load(open(os.path.join(repo, "servers", "hub.json")))
out = os.path.join(repo, "dist", "instances")
os.makedirs(out, exist_ok=True)

cache = os.path.join(repo, "servers", ".bootstrap.jar")
jar = None
try:
    if not os.path.exists(cache):
        with urllib.request.urlopen(BOOTSTRAP, timeout=60) as r:
            open(cache, "wb").write(r.read())
    jar = open(cache, "rb").read()
except Exception as e:
    print("could not fetch packwiz-installer-bootstrap:", e)

listing = []
launcher = []
for s in hub["servers"]:
    pack_url = f"{base}/{s['pack'].strip('/')}/pack.toml"
    # What the Kizuna launcher shows and uses.
    e = {k: s.get(k, "") for k in ("id", "name", "short", "tagline", "description", "status", "address", "minecraft", "fabric", "accent", "icon", "logo", "background")}
    e["pack"] = pack_url
    # Pictures kept in the repo (e.g. servers/art/aot-bg.png) are served from the same place as the packs.
    def url(v):
        return v if not v or v.startswith("http") else f"{base}/{v.lstrip('/')}"
    for k in ("icon", "logo", "background"):
        e[k] = url(e.get(k, ""))
    e["news"] = [dict(n, image=url(n.get("image", ""))) for n in s.get("news", [])]
    if s["status"] == "private":
        code = str(s.get("code", ""))
        if not code:
            print("private server without a code, left out of the launcher:", s["id"])
            e = None
        else:
            e["codeHash"] = hashlib.sha256(code.strip().upper().encode()).hexdigest()
    if e:
        launcher.append(e)
    if s["status"] == "soon":
        continue
    pack_url = f"{base}/{s['pack'].strip('/')}/pack.toml"
    cfg = "\n".join([
        "[General]",
        "InstanceType=OneSix",
        f"name={s['name']}",
        "iconKey=default",
        "OverrideCommands=true",
        f'PreLaunchCommand="$INST_JAVA" -jar "$INST_MC_DIR/packwiz-installer-bootstrap.jar" {pack_url}',
        "JoinServerOnLaunch=" + ("false" if "CHANGE_ME" in s["address"] else "true"),
        f"JoinServerOnLaunchAddress={s['address']}",
        f"notes={s.get('tagline', '')}",
        "",
    ])
    pack = {
        "formatVersion": 1,
        "components": [
            {"uid": "net.minecraft", "version": s["minecraft"], "important": True},
            {"uid": "net.fabricmc.intermediary", "version": s["minecraft"], "dependencyOnly": True},
            {"uid": "net.fabricmc.fabric-loader", "version": s["fabric"]},
        ],
    }
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        # Fixed timestamps, so the zip only changes when its contents do.
        def put(name, data):
            info = zipfile.ZipInfo(name, (2024, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
        put("instance.cfg", cfg)
        put("mmc-pack.json", json.dumps(pack, indent=2))
        if jar:
            put(".minecraft/packwiz-installer-bootstrap.jar", jar)
    data = buf.getvalue()
    path = os.path.join(out, f"{s['id']}.zip")
    if not os.path.exists(path) or open(path, "rb").read() != data:
        open(path, "wb").write(data)
    listing.append({
        "id": s["id"], "name": s["name"], "tagline": s.get("tagline", ""), "status": s["status"],
        "instance": f"{base}/dist/instances/{s['id']}.zip", "pack": pack_url,
    })
    print("instance", s["id"], "->", path, "" if jar else "(no bootstrap jar inside!)")

open(os.path.join(repo, "dist", "launcher.json"), "w").write(json.dumps({"servers": launcher}, indent=2) + "\n")

# A public listing without addresses: what a future launcher (or a website) reads to show the servers.
public = [l for l in listing if l["status"] not in ("private", "soon")]
open(os.path.join(out, "hub.json"), "w").write(json.dumps({"servers": public}, indent=2) + "\n")
