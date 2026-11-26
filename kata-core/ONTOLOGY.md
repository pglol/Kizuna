# Kata Core: Ontology

Kata Core is the movement, combat and interaction layer. This document lists everything the core
deals with (entities), how those things connect (relationships), and the laws they obey (rules and
constraints). Every mechanic added to the core should name the entities it touches, follow the rules
below, and add any new ones here.

Guiding principle: **the body is a physical thing in a physical world.** Motion comes from forces,
momentum and contact, not from canned clips. Animation shows what the physics is doing; it never
moves the body somewhere the physics wouldn't.

---

## 1. Core entities

### 1.1 Actor
| Entity | What it is | Where it lives |
|---|---|---|
| **Player** | A person in the world: a body plus a will (input). | Minecraft `PlayerEntity` |
| **Body** | The physical person: mass, position, velocity, posture. | entity position + velocity |
| **Collider** | The body's solid extent for the world (0.6 wide, 1.8 tall). | Minecraft bounding box |
| **Rig** | The visible skeleton the body is drawn with. | `Rig`, `Pose`, `Bone` |
| **Bone** | One rigid part: root, chest, head, upper arm ×2, forearm ×2, thigh ×2, shin ×2. | `Bone` |
| **Joint** | Where two bones meet: neck, shoulder, elbow, waist, hip, knee. | implicit in `Rig` |
| **Limb** | A two-bone chain with an end effector: arm (shoulder → elbow → hand), leg (hip → knee → foot). | IK in `Animator` |
| **End effector** | A hand or foot: the point of contact with the world. | IK targets |
| **Ragdoll** | The body when nobody is in control of it: 15 point masses held by distance constraints. | `Ragdoll` |
| **Camera** | The player's eyes. Rides the body; never leads it. | Minecraft camera |

### 1.2 Will (input and intent)
| Entity | What it is |
|---|---|
| **Movement input** | Forward/back/strafe keys, as a direction relative to where the player looks. |
| **Jump input** | Tap, hold, or double tap of space. |
| **Crouch input** | Shift, which also times a landing brace. |
| **Movement speed** | Holding forward alone is a slow, paced walk (0.72 × vanilla walk). Double-tapping forward is a normal walk (1.0 ×). Holding sprint is a full sprint (2.0 ×, about 8.6 m/s); it burns stamina and always engages while held. |
| **Intent** | What the player is trying to do with the next contact: none, grab (catch a lip), climb (get on top). Expires. |

### 1.3 Body resources
| Entity | What it is |
|---|---|
| **Health** | Damage taken from impacts, falls and combat. |
| **Stamina** | Spent by running and run-ups; it recovers at rest. Being out of it ("winded") forces a fast walk. |
| **Control** | Whether the will drives the body (standing, moving, hanging) or physics alone does (ragdoll). |

### 1.4 World features (what the body can sense and touch)
| Entity | What it is |
|---|---|
| **Ground** | A walkable top surface under the feet. |
| **Wall** | A vertical solid face in front of or beside the body. |
| **Lip / ledge** | A wall's top edge with room above it, where hands can hold. |
| **Top** | A ledge's surface with room to stand (it can be climbed onto). |
| **Corner** | Where two lip edges meet: inside (the wall turns towards you) or outside (it turns away). |
| **Gap** | Missing ground between the body and a target: a drop of more than a block. |
| **Obstacle** | A low solid you can vault: thin, waist to chest high, with ground behind it. |
| **Marker** | The small bracket showing where the hands would land on the lip in reach. |

### 1.5 Forces and quantities
| Entity | Value |
|---|---|
| **Gravity** | 0.072 blocks/tick² (28.8 m/s²), the same for the body and the ragdoll. |
| **Air drag** | Grows with the square of speed: about 0.32 % of speed per tick per block/tick, plus 0.5 % per tick. Applies to both body and ragdoll. Vertical drag is 0.98 per tick. |
| **Ground friction** | Minecraft's, per block. |
| **Velocity / momentum** | Carried across every state change: ground → air, air → ragdoll, flight → fall. |
| **Impact** | The velocity at the instant of contact, which decides damage and whether the body loses control. |

### 1.6 Motion states (`Parkour.Mode`)
| State | Control | Description |
|---|---|---|
| **Free** (NONE) | will | Standing, walking, running, jumping, falling. Gait animation; air momentum physics. |
| **Vault** | will | Over a low obstacle at speed. |
| **Run-up** | will | Running up a wall to reach its top. |
| **Hang** | will | Holding a lip by the hands: shuffle, corner, ledge jump, climb, drop. |
| **Corner** | will | Hand over hand round a corner (lead hand first). |
| **Hop** | will | Leap from a hang to another lip: side, up or back with a turn. |
| **Pull / Mantle** | will | Getting up onto a top from a hang, or from in front of it. |
| **Land** | will | A caught, braced landing. |
| **Ragdoll** | physics | Limp. Falls, tumbles, settles, then gets up. |

### 1.7 Network
| Entity | What it is |
|---|---|
| **Act** (client → server) | "My move changed", plus BRACE (crouch-caught landing) and IMPACT (hit a wall at speed). |
| **ActOf** (server → others) | Relays a player's move so everyone animates it. |
| **Server body attributes** | Gravity, jump strength, safe fall distance; flight allowed; fall damage hook. |

---

## 2. Relationships

### 2.1 Body structure
- **Player** *has one* **Body**, *has one* **Rig**, and *may have one* **Ragdoll** (only while limp).
- **Rig** *is made of* **Bones** linked by **Joints**: root → chest → (head, arms); root → legs.
- **Limb** *ends in* an **End effector**; IK *solves* the limb so its effector reaches a target.
- **Camera** *is attached to* the **Body**. While limp, the **Body** *follows* the **Ragdoll** pelvis, so the camera rides the fall.

### 2.2 Will and state
- **Movement input** *drives* the **Body** only while **Control** is held.
- **Jump input** + nearby **World features** *produce* an **Intent**. The Intent *selects* the transition: grab, climb, vault, ledge jump, or a plain jump.
- **Motion state** *decides* which system moves the body: Minecraft travel, scripted paths (hang, pull, hop, corner), the air-momentum model, or the ragdoll.
- **Stamina** *gates* running and run-ups.

### 2.3 Body and world
- **Feet** *stand on* **Ground**. **Hands** *hold* a **Lip**, *press on* a **Wall** (in front or beside), or *brace* a fall.
- A **Wall** *carries* **Lips**. A **Lip** *may be* a **Top**. Two **Lips** *meet at* a **Corner**.
- A **Gap** *between* the body and a **Lip** *makes* the approach a leap. With ground all the way, the approach is a walk-in and a hop.
- The **Marker** *shows* the hand position for the **Lip** in reach.

### 2.4 Cause and effect
- **Impact** *causes* damage (server), *may cause* **Ragdoll**, and *seeds* the ragdoll's velocity.
- The **Ragdoll** *collides with* the world. It *settles*, then *gets up*: it rises facing the way it lay and turns to where the player looks.
- Body motion *drives* secondary motion. The chest *lags* the body, the arms *lag* the chest, and in the air the legs *lag* the body. While hanging, the body *swings from* the hands and the legs *swing from* the body.

---

## 3. Rules and constraints

### 3.1 Physical laws (always)
1. **Momentum is conserved across transitions.** No state change zeroes velocity unless something physically stopped it: a wall, a catch, or a landing.
2. **One gravity.** The body, the ragdoll and every leap use 0.072 blocks/tick² with the same air drag.
3. **Air control is limited.** In the air, input can steer the body up to about running speed (0.22 blocks/tick walking, 0.36 sprinting), at 0.02–0.026 blocks/tick² of push. It can't add speed beyond that, and air drag is realistic, so a launch carries.
4. **Nothing passes through solids.** Not the collider, not the ragdoll's points (swept collision), not hands or arms: a wall beside you takes your hand, and the body leans clear of it.
5. **The camera never leads the body.** During a fall, a ragdoll or a get-up, the camera's position comes from the body's.

### 3.2 Body constraints
6. **Joint limits.** Knees bend only forward; elbows only back. A joint folded the wrong way is mirrored back. Limbs keep at least 62 % of full length (they don't fold flat).
7. **The body lays out cleanly.** Feet stay at least 0.78 from the neck, knees 0.62, and hands 0.3 from the pelvis. Parts that aren't joined never overlap: the minimum distance between them is 0.2.
8. **IK has a natural pole.** Elbows hang down and slightly back and out; knees point forward. Solving is flip-free: no snapping when a target crosses shoulder height.
9. **The head turns like a neck allows.** It translates with the body and turns towards the look only up to 75° either side of the chest, 60° up and 70° down. A limp head isn't looking anywhere. Its roll is reset every frame.

### 3.3 Control and loss of control
10. **Ragdoll triggers** (any of these):
    - a fall predicted to total 13 or more blocks: limp after 3.5 blocks of falling
    - a fall of 7 or more blocks, not braced: limp about 1.2 blocks above the ground
    - landing from 13 blocks or more, or from 7 or more without a brace
    - slamming into a wall at more than 0.55 blocks/tick (about 11 m/s)
    - landing at more than 0.62 blocks/tick across the ground (a tumble)
    - flung through the air at more than 0.9 blocks/tick for 2 ticks
    - stopping flight in mid-air while moving faster than 0.6 blocks/tick
10b. **Real friction.** Sliding along a surface loses speed in proportion to how hard the part presses into it (μ = 0.6). A hand brushing a wall slides freely; a body lying on the ground stops.
10a. **Bodies bounce.** A ragdoll part hitting a surface faster than about 4 m/s rebounds 30–45 % of that speed; slower contacts stop dead so the body settles. Only the parts that hit are pushed back, so striking an edge or a fence flips the body over it.
10d. **Floating limp.** A limp body in water (a part with water above it counts as fully under) floats up and lies there for as long as you leave it. A fresh press of a movement or jump key brings you round (after at least two seconds; keys still held from before don't count): you stand if there's ground under you, otherwise you start swimming.
10c. **Limp in water.** A limp body hitting water fast smacks the surface: most of its speed is lost on impact and it splashes. Under the surface the water drags it and holds it up (torso and head float, limbs about neutral), so it floats. Coming round, it stands if there's ground within reach, otherwise it swims.
11. **Limp means no will.** No hands either: no strikes, guard, swings, block breaking or item use.
11b. **Limp means no will.** No walking, no sprinting, no sprint FOV. Position follows the pelvis.
12. **Getting up** starts once the body has been still for 0.2 s (after at least 0.55 s), or after 2.2 s at most. It runs push-up or sit-up, then kneel, then stand, over 1.15 s. No part of the body goes below the ground while getting up.

### 3.4 Falling and landing
13. **Safe fall is 4 blocks.**
14. **A caught landing** (crouch pressed shortly before touchdown) takes 35 % of the fall damage. Its posture is rougher the higher the fall.
15. **A wall slam** hurts like flying into a wall with elytra: about 10 × speed − 3. It's reported by the player about themselves only, and rate-limited to once every 10 ticks (half a second).

### 3.5 Ledges and climbing
16. **Hands go where the surface is.** Hold points snap to the real lip. Arms bend to reach them. A target that's too far means no grab, never a stretched arm.
17. **Hang height** puts the lip 1.72 blocks above the feet.
18. **Reading the approach.** Jump near a lip 1.7–3.45 blocks above the feet (hands reach about 2.15 above the feet when standing):
    - If there's ground all the way to the wall, walk in and hop straight up, only as high as the hands need.
    - If there's a gap, leap it with just enough distance and height to arrive at the lip.
    - Otherwise it's an ordinary jump.
19. **Leaps complete.** A ledge jump is planned through clear air before it starts: the lowest arc that's clear along the whole path. The body moves with the path's real velocity. A graze slides past. A real block in the way turns the leap into a free flight that keeps its speed, and for a second the hands still catch the lip it was aimed at.
20. **Corners are hand over hand.** The leading hand reaches round the edge onto the next face first. The body follows; then the trailing hand lets go and comes round. Hands travel round the corner's edge, never through the block or across open air.
21. **Shuffle rhythm.** One hand extends to its maximum and only then does the other close in. Stopping eases the cycle out smoothly.
22. **Keys are wall-relative while hanging.**
    - Toward the wall (W): climb.
    - Away from the wall (S): drop, or with space, a back jump that turns to face the other ledge.
    - Sideways (A/D): shuffle, or with space, a side jump.
    - Space alone: ledge jump up.
23e. **Limp catches.** Limp in the air, the hands can still catch a lip, but you stay limp: the body hangs and swings from its pinned hands. If you come round still hanging, you take hold properly and can move from the ledge.
23f. **Leaps complete.** Brushing the target lip in the last part of a ledge leap counts as the catch.
23a. **Catch reflex.** Falling past a lip (after at least 2.5 blocks of fall, and not the edge you just left), the hands grab it on any side, facing first. It works even while limp in the air. Falling faster than 1.1 blocks/tick tears the grip; holding crouch means letting yourself drop.
23c. **Jumping backwards** is a both-feet hop: the legs tuck evenly and the hands come up for balance from the moment the feet leave, with no one-leg-out pose.
23d. **Water carries you.** Not limp, you float up and tread water chest-deep (crouch to dive; swimming as normal). Water takes the fall, so reaching the bottom plays no landing squash. Hitting water hard (falling faster than 1 block/tick, about 10 blocks) smacks you limp on the surface.
23b. **Thin tops are climbed over.** A rail, fence or coping less than about half a block deep, with air beyond: you go over it and down the far side instead of standing on it.
23. **No spider climbing.** A wall can only be climbed through a lip, a run-up, or a mantle.

### 3.6 Proximity awareness
24. Within about a block of a wall in front, the hands come up and press flat on it, about 0.24 either side of centre.
25. Within about 0.7 of a wall beside you, that hand guides along it: ahead of the shoulder, at chest height.

### 3.7 Animation
26. **Animation never contradicts physics.** Poses come from state and velocity, never from a fixed clip that ignores contact.
27. **Everything flows.** All bones are damped springs. Secondary motion follows rule 2.4: the chest lags the body, the arms the chest, the legs the body.
28. **Footsteps match feet.** Step sounds land on each animated footfall.

### 3.8 Network and trust
29. **The client decides its own movement; the server decides damage.** The client may only report things that hurt itself (brace, impact), and the server rate-limits them.
30. **Everyone sees the same motion.** Mode and side are relayed so other clients run the same animation (and the same ragdoll, from the velocity they observe).

---

### 3.9 Hand to hand

**Entities:**
- **Style:** a fighting style, such as Taijutsu. It's defined in a data file, `kata_core/styles/<style>.json`, listed in `index.json`, with its colour, its whole move tree and its starter deck. Moves are namespaced (`taijutsu:jab`) and loaded on both client and server.
- **Move:** each move has these properties:
  - path: straight, hook, upper, overhand, palm, backfist, elbow, superman, front kick, roundhouse, side kick, back kick, low kick, knee or dropkick
  - limb: lead or rear, a hand or a foot depending on the path
  - light or heavy
  - from: the front stances, the back stances, or the air
  - spin, timing, damage, push and lift (upward launch)
  - hits: 1 or more, with an interval, optionally alternating limbs (a combo)
  - knockdown
- **Deck:** five rows of four slots (three lights as a chain, then a heavy): one row per stance quadrant, plus an AIR row. It's edited on the deck screen (K).
- **StanceQuadrant:** top-right, top-left, back-right or back-left: the body's orientation to the locked opponent.
- **Body parts (hitboxes):** capsules for the head, torso, upper arms, forearms, thighs and shins. For players and dummies they come from the posed skeleton.
- **Training dummy:** a sparring partner with the full rig.

**Rules:**
31. **Controls (bare hands).**
    - Quick click: the next light in the chain.
    - Hold: winds up the heavy, thrown on release.
    - Right click held: guard.
    - R: lock on.
    - K: the deck.
32. **Stance is body orientation.** Top stances have the chest to them, turned about 30°; back stances have the back turned, about 145°. The diamond at your feet shows the current one.
33. **Stance changes only by intent:** a deliberate mouse drag while locked on. A spinning back move also turns you round to the top stance on the same side.
34. **Chains flow by role.** Every light is an opener (the fastest, 70–85 ms to contact), a link (100–140 ms) or a finisher (140–160 ms, harder). Slot 1 takes openers, slot 2 openers or links, slot 3 links or finishers, so chains always build from fast to heavy.
34a. **Chains and combos.** Quick clicks run the row's light chain. A click during a strike queues the next one, though never across ground and air. A combo move lands several hits, each hit landing and pausing on its own.
35. **Light vs heavy.** Lights are fast. A heavy charges for up to 0.8 s, giving 0.5–1× damage and more knockback. A full charge breaks guard. Getting hit while winding up cancels the heavy.
36. **Air attacks.** Off the ground, only the AIR row's moves come out: superman punch, flying knee, jumping roundhouse, dropkick. You get one per jump unless it lands; a landed air strike lets the air chain continue. Each lunge sets your flying speed (superman 0.26, dropkick 0.3, knee 0.22 blocks/tick) rather than adding to it. Ground moves can't fire in the air, and air moves can't fire on the ground.
37. **Strikes have mass.**
    - The fist or foot follows a real path, solved with IK:
      - punches: straight, hooking, uppercut or overhand
      - kicks: chamber then extend
      - roundhouse: knee turned out
      - side kick: hips turned 70°
      - back kick: leaning forward
    - Hips and chest drive every strike, and weight shifts with it.
    - An uppercut dips into the legs and drives up onto the toes; a heavy one leaves the ground. A launching move lifts its target.
    - Strikes accelerate into contact and pause briefly on a hit.
38. **Any contact counts.** The moving fist or foot is swept against everyone's limbs. The first part it touches takes the hit and the strike stops there. If it hasn't touched anyone, it lands on whoever is first along its line. Damage by part: head 1.25×, torso 1×, legs 0.7×, arms 0.45×.
39. **Guard.**
    - Squared up, the hands are loose and lowish. Holding guard tightens into a high guard: forearms in front of the face, chin down.
    - A hit from where you face (within about 70°) is fully blocked. From the side or behind, 40 % gets through.
    - A blocked hit shoves you and jolts the guard back, turning the body away from that side.
40. **Perfect block (parry).** Guard raised within 3 ticks (0.15 s) of a hit arriving, facing it:
    - a punch is turned aside: the attacker staggers for 0.6 s, their chain broken, can't act
    - a kick is caught: you grab the foot and throw them off their feet
41. **Knockdowns.** A knockdown move (a full heavy, a push kick, a side kick, a dropkick and so on) puts the target into the ragdoll with 80 % of the hit's push and 75 % of its lift. A caught kick throws at 0.6 forward and 0.32 up. A hit while down sends the body flying again.
41b. **Spinning kicks sweep.** The leg chambers and goes out wide early, held through the turn. It hits the first body it touches on the way round.
41c. **Spinning strikes** use the limb on the side you're turned (back-right: right) and turn that way. A spinning kick swings out wide on that side through the turn and sweeps round, so fully out the foot lands on the target.
41d. **Kicks pivot.** The hips turn over the standing foot, which stays planted and pointing where it was, and the chest counter-twists.
41a. **Spins turn smoothly.** A spinning strike turns the long way round, its side leading, with an ease-in-out arriving exactly at contact (0.26–0.32 s), so the whole turn is visible.
42. **Footwork.** Locked on, a top stance walks in 15 % faster and backs off 15 % slower; a back stance is the reverse. Guarding ×0.75, winding up ×0.8, staggered ×0.5.
43. **Training dummy.**
    - It stands at ease at its spot until hit.
    - When hit, it chases whoever hit it and keeps to fighting distance.
    - It reads wind-ups and tries to raise its guard with human reaction time: it reads about 65 % of them, after 100–400 ms, so it's sometimes late. Of its well-timed blocks, about 30 % are parries.
    - It meets the strike on the side it comes from, worked out from where the striking hand really is relative to its body: that arm goes out to take any hand strike, a raised knee checks a roundhouse or low kick, and a high guard covers other kicks.
    - It attacks often: strings of 1–3 strikes, a 0.6–1.5 s breather between strings. It counters out of a block and steps in when you're just out of reach.
    - With no path to you (you're up on something), it heads straight for you and hops 1-block obstacles.
    - Knocked down, its real position travels with its body (a player's game reports it as it flies), so it can be hit again where it lies. It stays down until its body comes to rest (ten seconds at most). Then it stands there, lifted out of the floor if need be, and gets up over about a second before it moves again.
    - Its hitboxes come from its shown pose, ragdoll included. A downed dummy can be hit wherever its body lies (within 7 blocks).
    - Its strikes use the same IK as a player's: the fist or foot travels to you and stops on the first part of you it meets, such as a guarding forearm.
    - It throws jabs, straights, one-twos and kicks, which you can block, parry or catch. They shove a creative-mode player even though they do no damage.
    - It staggers and gets knocked down into a real ragdoll.
    - After 10 s left alone, or with the target 20+ blocks away, it walks home and settles. It never goes down for good.
44. **Taijutsu move tree.**
    - **Front lights:** jab, straight, one-two, flurry, lead hook, rear hook, hook-uppercut, uppercut, palm jab, front kick, roundhouse, low kick, double kick.
    - **Front heavies:** driving palm, overhand, rising uppercut, push kick, side kick.
    - **Back:** spinning backfist and back kick (lights); spinning elbow and spinning hook kick (heavies).
    - **Air:** superman punch, flying knee, jumping roundhouse (lights); dropkick (heavy).

## 4. Not yet covered (next for the core)
- **More of the Combat Deck:** kicks, grabs, weapon decks, guard breaks.
- **Combat contacts:** a hit as an impact on a body part. Knockback goes into momentum, and a big hit means ragdoll. Being shot while hanging swings the body from the hands.
- **Grabs and throws between players:** two bodies linked by a hand contact.
- **Ladders and poles** as hand and foot holds with the same IK and hang physics.
- **Slides and rolls:** keep momentum through a roll when you crouch on landing at speed.
- **Surface materials:** friction and sound per block (ice, mud, metal).
- **Water:** buoyancy and drag in place of gravity.

## Hit reactions (every body: players, dummies, you)
- Every landed hit is relayed to everyone tracking the body: where it landed (head, torso, arm, leg), the strike's path, which side it came from, and whether it was heavy.
- Head: an uppercut snaps the head back, looking at the sky (no launch). A straight, palm, superman, overhand, knee or front kick knocks the head back. A hook, backfist or any kick from the side whips the head away from that side.
- Torso: the body folds forward over the hit and turns away from its side. Leg: that knee buckles and the body drops. Arm: that arm is knocked back.
- Reactions are fast in (0.05 s) and eased out (0.38 s; 0.55 s and harder for heavies), added on top of whatever the body is doing.
- Uppercuts push back; they don't lift. Rising uppercut and flying knee don't lift either.

## Blocks are visible
- A blocked strike is relayed to everyone: the blocker's arm on that side goes out to meet it, forearm up, for a quarter second.

## Spinning kicks
- The kicking leg chambers and goes out wide on its own side, held in the body's frame so it turns with the spin. Only in the last 15% does it sweep onto the target.

## Dummy motion
- A dummy's shown pose never jumps between frames: each angle moves the short way round toward the solved pose, quickly but continuously.

## Floating limp
- A knocked-out body floats all the way up and lies out on the surface: torso, head, arms and thighs at the top, only the hands and feet dipping slightly under. Lift reaches full strength 0.12 blocks under the top.
- (Tuned) The float is a gentle drift up, not a launch: lift ramps over the last 0.2 blocks and water resists vertical motion most. Torso and head ride about 0.14 under the top, limbs a little lower, feet lowest.
- A dummy's pose is smoothed as whole rotations per bone, never angle by angle.

## Other players' ragdolls
- Your own game runs your fall and moves your player with it. Everyone else sees your limp body carried along with your player's real position (where your hitboxes are), with only the limbs simulated on their side; any drift is pulled back. A body more than 4 blocks off snaps to you.

## Network: everyone sees the same fight
- Everything that happens to a fighter's body goes to them and to everyone who can see them: blocked hits (the guard jolts and the arm meets the strike on that side), guard breaks (the guard drops), staggers (thrown off balance, the strike cancelled), parries (the slap-aside, the kick caught and thrown), hits (reactions) and knockdowns (ragdoll).
- A player coming into view gets the fighter's current guard and stance, not just later changes.

## Knockback
- An ordinary hit pushes you back along the ground, a step's worth, never up off it: fighters stay in range to trade back and forth. A blocked hit gives a little ground. Only knockdown moves (push kick, side kick, driving palm, spinning elbow, spinning hook kick, dropkick, full-charge heavies) send anyone flying.

## Movement while fighting
- Locked on, guarding or charging a heavy: walking pace only, no running.
- Ground speeds (walk, fast walk, run, guard and stance factors) apply on the player's own speed.

## Dummies fight back
- A dummy's strikes use their own damage type, the same on every difficulty (mob attacks do nothing on Peaceful). Reach 3.0 for punches and 3.3 for kicks; it steps in as it throws if you're backing off; head, body or legs by the strike; shorter breathers between strings.
- Every screen knows who a dummy is fighting and aims its strikes at them.

## Getting up
- On land: lie still briefly (0.12 s settled, at least 0.45 s down, at most 3 s), then 0.8 s to get up.

## Defense (Absolver-style, layered)
### Guard
- Hold guard (right button) from any stance: it blocks strikes from every side.
- Each blocked hit costs stamina by how hard it was (damage x 0.05, +0.15 for a heavy). The same breath as running. Stamina comes back a second after the last hit taken.
- Not enough left: the guard breaks, you're stunned (1 s) and the hit lands. A full-charge heavy breaks it outright.
- A blocked hit pushes your stance: struck on the right, you're turned to the left (top stays top, back stays back), changing your counters.
### Parry
- Raise the guard just as the strike arrives (3 ticks) while pressing toward the side it comes in on (A: left, D: right). A right-hand or right-leg strike comes in on your left.
- Right side: a punch is turned aside and the attacker is stunned long enough for a sure counter (18 ticks); a kick is caught and they're thrown. You get 0.25 stamina back.
- Wrong side: the guard is out of place, you're caught reaching (8 ticks) and the hit lands clean. No side pressed: just a block.
### Dodge
- Dodge key (Left Alt): a quick low step the way you're pressing (back if nothing). Costs 0.2 stamina; not when winded, in the air, striking, charging or staggered.
- Its first 5 ticks are invulnerable to strikes (they pass through). Then a recovery to 0.42 s with no guard and no strikes: a sweep or late strike catches you.
- Everyone sees it: forward ducks in, sideways leans out, back leans away; knees bent, low.

## Heavies
- Held 0.12 s it's a heavy; full charge at 0.5 s.

## Fight view
- Locked on: black bars close in top and bottom and the HUD slides up out of the bottom one.

## Side steps
- Step and close: the leg on the side you're going lifts and reaches out, the other stays planted as the body travels over it, then closes in. Feet apart, never crossing; knees soft.

## Knockdown distance
- Knockdowns throw a couple of body lengths. Every push and knockdown is multiplied by the attacker's might (1 for now; strength raises it later).

## Others getting up
- On other screens a limp body finishes its own get-up when its owner is already up, instead of snapping to standing.

## Hitstun
- A landed hit cancels what the one hit was throwing and stops them throwing (or dodging) for a moment: 85% of the strike's length, +3 ticks (+6 for a heavy). Enough that the attacker's next strike in the string arrives first.
- Guard and parry still work while reeling: blocking or parrying is the way out of a string. The server rejects strikes thrown during hitstun.
- Dummies too: hit, their swing is gone and their next strike waits.

## Defense, revised
### Guard is directional
- The guard faces forward (about 70 degrees either side): from behind or well round the side, a strike lands.
- Straight strikes meet any guard. Swinging strikes (hooks, roundhouses, backfists, elbows, low kicks, spins) come round it: hold A or D while guarding to turn the guard to that side. Turned that way, it stops them; turned the other way, they land; square, half gets through.
- Blocking still costs stamina, and a guard near empty shows the strain: arms shaking, the body sagging back, the head down behind it; jolts hit harder.
### Parries are seen and heard
- A parry flashes between the two fighters (sparks) with a ringing clang. The parrier sees PARRY; the one parried sees PARRIED. GUARD BROKEN and EVADE pop up the same way.
### Dodges and avoids (the dodge key, Left Alt)
- On the ground, with a direction: S or none, a short step back (about a block, eased out over 4 ticks), invulnerable for 5 ticks. W ducks under anything to the head. A or D slips aside past straight thrusts. Jump held: a hop over low kicks and anything at the legs. The avoids hold for 8 ticks but only against what they're for.
- An avoid or dodge that takes you out of a strike drains the attacker's stamina (0.15) and gives you some back.
- Recovery 0.3 s; cost 0.16 stamina. Use it right as the strike comes.
### Flips
- The dodge key in the air: a full flip whichever way you're pressing (any of 16 directions; none is a back flip), about the waist, tucked through the middle and opening to land. Once per jump; invulnerable as it starts.

## Strike spacing
- A beat between strikes: 0.07 s after a light, 0.16 s after a heavy, 0.1 s more after a miss. A press during a strike goes on the beat. The server refuses a new strike before the last has run three quarters of its length.

## Knockdowns in a fight
- At most 0.22 blocks a tick across and 0.3 up when thrown; the air slows a knocked-down body hard across, so even off a height it lands near.
- Up quickly: lying still a moment (0.3 s at least, 1.4 s at most), then 0.6 s to get up.

## HUD bars
- Health: a red brush stroke slanted at both ends, mottled, in a ragged black ink stroke. Black flames rise off its end, swaying and curling, shedding embers; ink beads under it, stretches and drips away (more often when health is low). A dim red stain shows what's empty and a pale trail shows what was just lost.
- Stamina: the same ink, finer and shorter, cream (dim when winded), left-aligned above the health stroke, clear of its flames, with small flames and rare drips.

## Reach and reading strikes
- A punch reaches 2.4 blocks from the eyes, a kick 2.8 (the server allows 0.5 more for lag). Dummies: 2.5 and 2.9, starting from under 2.9 and closing to 2.3.
- Every strike has a readable tell: lights take 0.13 to 0.24 s to land, kicks and heavies 0.26 to 0.36 s, spins up to 0.42 s.
- The first 45% of a hand strike is the load: drawn back and coiled away, weight onto the back foot of that side, the shoulder on the striking side dropped, the head tucked; a hook, backfist, elbow or overhand cocks its arm out wide on its side. Then it snaps through. A charging heavy holds the load, and kicks lean away over the standing leg with the arms opening.
- Everyone sees strikes from their start (the wind-up is relayed), and sees a heavy being charged.

## Air combat (replaces superman punch, flying knee, jumping roundhouse, dropkick)
- Taijutsu air set: Air jab (opener), Air hook (link), Rising knee (link), Whirlwind kick (finisher); held: Hammer smash.
- An air strike on someone standing lifts them off the ground; on someone in the air it pops them up a touch. Either way they're held there, nearly weightless (gravity -82%), for 0.8 s, long enough for the next hit, and the attacker hangs with them (gravity -82% for them too) and drifts in to stay in reach. Each air hit landed gives another air strike.
- Juggled bodies show it: tipped back, head thrown back, arms and legs loose and flailing.
- Holding attack in the air at any point (0.12 s) cuts the string off with the Hammer smash: a hitch at the top, then down they go hard, and the attacker dives after them. When they hit the ground: a burst of dust, a thud, knocked flat, then concussed for 3 s (head lolling, swaying, stars, slow, unable to strike).
