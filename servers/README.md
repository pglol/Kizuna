# Our servers: packs, launcher instances and access

Everything for running more than one server (AoT now, other anime servers later) lives here.

```
servers/hub.json          the list of servers: name, pack folder, address, public/beta/private
servers/make_instances.py builds a ready launcher instance per server (CI runs it)
pack/                     the AoT RPG pack (packwiz)
packs/<id>/               any further pack, same layout as pack/
dist/instances/<id>.zip   a Prism Launcher instance for each server (built by CI)
dist/instances/hub.json   the public list (no addresses, no private servers)
```

## What players do

1. Install [Prism Launcher](https://prismlauncher.org) (free, Windows/Mac/Linux) and sign in.
2. Get the server's zip from you (`dist/instances/<id>.zip`). In Prism: **Add Instance → Import** → pick the zip.
3. Press **Play**. Before the game starts it downloads anything new in that server's pack, then
   joins the server directly. Every later launch does the same, so nobody is ever out of date.

One zip per server. Someone who plays on two of our servers has two instances side by side.

## Adding a new server

1. Make its pack: copy `pack/` to `packs/<id>/` (e.g. `packs/jjk/`), edit `pack.toml` (name,
   Minecraft and Fabric versions), and put that server's mods in `mods/` (with the packwiz CLI:
   `cd packs/jjk && packwiz modrinth add <mod>`; or copy a `.pw.toml` and change the URL and hash).
2. Add an entry to `servers/hub.json`:

   ```json
   {
     "id": "jjk",
     "name": "Jujutsu RPG",
     "tagline": "Sorcerers, curses and domains",
     "status": "beta",
     "pack": "packs/jjk",
     "address": "25.12.34.56:25566",
     "minecraft": "1.21.1",
     "fabric": "0.16.10"
   }
   ```

3. Push. CI refreshes every pack's hashes and builds `dist/instances/jjk.zip`.
   (Or run `python3 pack/update.py <base url>` and `python3 servers/make_instances.py <base url>` yourself.)

Changing a pack later (a new mod, a config) needs nothing from players: their next Play picks it up.

## Who gets in: public, beta, private

The pack and the zip only get people to the door. **The server decides who comes in**, so a leaked
zip doesn't matter. On each server, as an admin (op level 3):

| Command | What it does |
|---|---|
| `/aotrpg access mode open` | Anyone who can connect plays (the vanilla whitelist still applies if it's on). |
| `/aotrpg access mode beta` | Testers and admins only. Everyone else is turned away with a message. |
| `/aotrpg access mode closed` | Admins only, e.g. for maintenance. |
| `/aotrpg access add <player>` / `remove <player>` | Give or take a tester spot (works for offline players by name). |
| `/aotrpg access list` | The current mode and every tester. |
| `/aotrpg access message <text>` | What turned-away players read. |
| `/aotrpg extraction allow <player>` / `deny` / `list` | Extraction mode is closed by default; this lets someone into it. |

So a playtest looks like: set the server's `status` to `beta` in the hub, hand the zip to the
testers, `/aotrpg access mode beta`, then `/aotrpg access add` each tester. When it goes live,
`/aotrpg access mode open` and change the hub entry to `public`.

`status` in the hub is just a label for us and for the public list: `private` servers are left
out of `dist/instances/hub.json` entirely, so a future website or launcher page won't show them.

## Hamachi and other private networks

While we're on Hamachi, `address` is the host's Hamachi IPv4 (the 25.x.x.x one) and the port.
Players need to be in the Hamachi network before pressing Play, or the join will time out (the
game still starts; they can join from the server list once Hamachi is up). When we move to a real
host, change `address` in the hub, push, and send people the new zip (or they can edit
**Edit → Settings → Launch → Join server on launch** themselves).

The pack itself must still be downloadable without logging in: the repo (or a small public repo
holding only `pack/`, `packs/` and `dist/`) has to be public, or the files served from any web
host. See `pack/README.md`.

## Later: our own launcher

`dist/instances/hub.json` is the list a custom launcher would read (names, taglines, status,
instance and pack links). If we ever build a branded launcher, it reads that, shows the servers we
run, and does what the Prism instance does now: run the packwiz bootstrap, then start the game
with the server address. Until then, Prism with these zips gives players the same result.
