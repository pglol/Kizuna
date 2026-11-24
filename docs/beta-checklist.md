# Beta test checklist

Tick each line on a real server with at least two players. Anything that fails: `/report <what happened>` in game.

## Joining
- [ ] Same jar on server and client: joins normally.
- [ ] Old jar on the client: turned away with "Your AoT RPG mod is out of date" (not a crash).
- [ ] Prism instance with the pack: Play updates and joins the server directly.
- [ ] 1 of 1 cosmetics show on join without changing anything.

## The AR layer
- [ ] Compass under the top bar; turning moves it; quest and activity diamonds sit at the right bearing.
- [ ] World markers float well above their spot, stay sharp with shaders on, ease out when you walk up.
- [ ] Turning away: markers become arrows at the screen edge.
- [ ] Light columns over quests and activities are bright with shaders.
- [ ] Quest tracker under the minimap lists the quest and every activity with distances; slims in a fight.
- [ ] Display settings (pause → Display): every switch works and is remembered after a restart.

## World activities and pacing
- [ ] Three activities always on offer; each kind completes and pays: Supply Cache, Bandit Camp, Titan Sighting, Survivors, Lost Scout.
- [ ] Going 900+ blocks away replaces an activity.
- [ ] Out in the wilds there's usually something going on; fights aren't joined by new groups or titans; a breather follows a fight.
- [ ] Marley hunters arrive after (not during) a fight.

## Combat
- [ ] Enemy warning flashes: red dodge, purple grab (step back works), gold block, white don't swing.
- [ ] Troop grab is telegraphed and can be stepped out of.
- [ ] Nobody swings while grabbed, pinned or in a finisher (no arm, no sound).
- [ ] Pinned to a wall: can't block; hit again and they smash through 1-4 walls, tumbling; holes refill after 30s; none in homes or arenas.
- [ ] Pursuit: strike at a pinned or flying enemy from range with gear on: zip, hit, chain.
- [ ] Finishers kill players (including downed ones); in duels and arenas they end the round.
- [ ] Lock-on: small mouse moves don't switch; a sharp flick does; NPCs lockable; next target after a kill.
- [ ] PvP: a level 100 vs level 10 isn't a one-sided slaughter; killing far-lower players posts a contract.

## Cutscenes
- [ ] Intros don't play mid-fight or mid-flight (title card instead), never repeat for the same enemy, never stack.
- [ ] Camera eases out and back in with no snap; hold Space skips.
- [ ] No damage taken or dealt during a cutscene.

## Progression and endgame
- [ ] Level 100: Veteran ranks fill and pay; Prestige button appears.
- [ ] Prestige screen is sharp (not blurred); perks selectable; hold to confirm; resets level, stats and skills; keeps gear and Marks.
- [ ] Prestige I, V and X grant their kill effect and title; emblem shows on name plates; /prestige top.
- [ ] Mythic raid refuses a party with an unprestiged member; pays Mythic gear.
- [ ] Weekly operation toast on join; its bonus applies.
- [ ] Ranked season: rating soft-resets at a new season; top three paid at next login.

## Interface
- [ ] Hold Tab: stat cards flip in on both sides; pause menu shows them too.
- [ ] Every vanilla station (chest, barrel, furnace, brewing stand, enchanting, anvil, smithing, grindstone, stonecutter, trading, beacon, horse) is in the glass style and works.
- [ ] Item tooltips show "Used in"; pork dishes cook; Craftsman buys odds and ends.
- [ ] Extraction lobby shows Right and Left with sheathed swords.
- [ ] Guests in someone's home can't open or use anything but doors.

## Safety
- [ ] Moving a whole stash at once still works (rate limits don't block it).
- [ ] `aot_rpg_backups/` fills every 30 minutes and on shutdown.
- [ ] `/report` saves a file; operators see it; `/reports` lists them.
- [ ] `/aotrpg swingstats` shows heights after some flying.
