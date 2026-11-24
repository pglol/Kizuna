# AoT RPG: auto-updating launcher setup

Players install once; after that the game updates itself every time they press Play, then drops
them straight onto the server.

**Easiest:** CI builds a ready Prism instance for every server in `servers/hub.json`, at
`dist/instances/<id>.zip`. Players import it (Add Instance → Import) and press Play; the steps
below are what that zip already has set up. Running several servers (other packs, betas, who gets
in): see `servers/README.md`.

## One-time setup by hand (Prism Launcher)

1. Install [Prism Launcher](https://prismlauncher.org) and create an instance: Minecraft **1.21.1**, **Fabric** loader.
2. Download `packwiz-installer-bootstrap.jar` from
   https://github.com/packwiz/packwiz-installer-bootstrap/releases and put it in the instance's
   `.minecraft` folder (Instance → Folder).
3. Instance → Edit → Settings → Custom commands → tick it, and set **Pre-launch command** to:

   ```
   "$INST_JAVA" -jar packwiz-installer-bootstrap.jar <PACK_URL>
   ```

   where `<PACK_URL>` is the address of `pack/pack.toml` (see "Hosting" below).
4. Instance → Edit → Settings → Miscellaneous (Prism 8+: "Launch") → **Join server on launch** →
   the server's address.

Press Play: it checks the pack, downloads anything new (only what changed), and joins the server.
If a player's mod is ever out of date anyway, the server turns them away with a message telling
them to restart the launcher.

To hand this out, set it up once, then right-click the instance → **Export** → Prism zip, and give
players that file (Add Instance → Import). Everything above comes with it.

## What's in the pack

`pack.toml` / `index.toml` / `mods/*.pw.toml` (packwiz format). The CI build refreshes the AoT RPG
mod entry (URL and hash) every time it builds the jar, so players always get the newest build.

The other mods the server needs (Fabric API, Danny's AoT mod, Iris and so on) should be added as
entries too. With the packwiz CLI (https://packwiz.infra.link): `cd pack` then
`packwiz modrinth add fabric-api` (and the same for any mod on Modrinth or CurseForge). For a mod
that's only a file, copy `mods/aot-rpg.pw.toml`, change the name, filename, URL and hash.
Run `python3 pack/update.py <raw base url>` (or `packwiz refresh`) after editing by hand.

## Hosting

The launcher has to be able to download `pack.toml` without logging in. A private GitHub repo's
raw URLs need a login, so either:

- make the repo (or a small separate one holding only `pack/` and `dist/`) public, and use
  `https://raw.githubusercontent.com/<owner>/<repo>/<branch>/pack/pack.toml`, or
- serve `pack/` and `dist/` from any web host (GitHub Pages, the server's own web panel, a CDN),
  and run `python3 pack/update.py <that base url>` so the mod's download URL points there.
