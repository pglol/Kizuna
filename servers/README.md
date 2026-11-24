# Our servers: the launcher, packs and access

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

1. Download **Warfront** (the installer from this repo's Releases page) and open it.
2. **Sign in with Microsoft**, the account they play Minecraft on. Once; it remembers them.
3. Pick a server on the left and press **PLAY**.

That's it. The launcher installs the right Java, Minecraft, Fabric and that server's mods, keeps
them up to date on every Play, starts the game and drops them straight onto the server. Each
server gets its own game folder, so packs never mix. When we ship a new launcher version it
updates itself.

(The Prism Launcher zips in `dist/instances/` still get built, as a fallback for anyone who
prefers Prism.)

## The launcher (launcher/)

An Electron app: `src/main.js` (window, sign-in, Play), `src/auth.js` (Microsoft → Xbox →
Minecraft sign-in), `src/java.js` (Mojang's Java runtimes), `src/pack.js` (packwiz sync with hash
checks), `src/game.js` (starts Minecraft with Fabric and joins the server), `src/ui/` (the screen
and the painted backdrops). It reads the server list from `dist/launcher.json`, which CI builds
from `servers/hub.json`, so adding or changing a server never needs a new launcher.

### One-time setup (you, once)

Minecraft only accepts sign-ins from launchers Microsoft has approved, so the launcher needs its
own app ID:

1. Go to https://portal.azure.com → **App registrations** → **New registration**.
   Name: `Warfront Launcher`. Supported account types: **Personal Microsoft accounts only**.
   Redirect URI: platform **Public client/native (mobile & desktop)**,
   `https://login.microsoftonline.com/common/oauth2/nativeclient`. Register.
2. In the app: **Authentication** → *Allow public client flows* → **Yes** → Save.
3. Copy the **Application (client) ID**.
4. Ask Microsoft to allow it to use Minecraft: fill in https://aka.ms/mce-reviewappid with that ID.
   Approval can take from a few days to a few weeks. Until then, sign-in gets as far as Xbox and then
   says the launcher isn't approved yet.
5. In GitHub: this repo → **Settings → Secrets and variables → Actions** → **New repository
   secret**, name `MS_CLIENT_ID`, value the ID. Every build after that carries it.

### Releasing a launcher version

- Every push that touches `launcher/` builds installers for Windows, Mac and Linux (they appear as
  downloads on the Actions run).
- To publish one to players: bump `version` in `launcher/package.json`, push, then **Actions →
  Build Warfront launcher → Run workflow** with *Publish* ticked (or push a tag `launcher-v1.0.1`).
  It becomes a GitHub Release; installed launchers see it and update themselves.

### Must be public

The launcher downloads `dist/launcher.json`, the packs and the release files without logging in to
GitHub. If this repo is private, those downloads fail. Either make the repo public, or create a
small public repo just for releases and the `dist/` + `pack/` files, then point the launcher at it:
set the repository variable `LAUNCHER_HUB_URL` (Settings → Secrets and variables → Actions →
Variables) to that repo's raw `dist/launcher.json` URL, and change `publish` in
`launcher/package.json` to that repo.

### Good to know

- Windows shows a SmartScreen warning for new unsigned apps ("More info → Run anyway") until a
  code-signing certificate is added. On Mac, the first open is right-click → Open.
- Private servers: give a server `"status": "private"` and a `"code"` in the hub; players press
  **+** in the launcher and type the code to see it. Playtests: `"status": "beta"` plus
  `/aotrpg access mode beta` on the server.

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

   Its mods go in `packs/jjk/mods.txt` (`modrinth <slug>` lines); CI looks up the downloads.
3. Push. CI refreshes every pack, rebuilds `dist/launcher.json`, and the new server shows up in
   everyone's launcher the next time they open it.
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
host, change `address` in the hub and push: every launcher picks up the new address on its next start.

The pack itself must still be downloadable without logging in: the repo (or a small public repo
holding only `pack/`, `packs/` and `dist/`) has to be public, or the files served from any web
host. See `pack/README.md`.
