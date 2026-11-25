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
| **Sprint input** | Fast walk (double-tap forward, 0.82 × vanilla sprint) or run (sprint held, 1.6 ×: about twice the fast walk). |
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
9. **The head only translates with the body.** The look direction belongs to the player; the head's roll is reset every frame.

### 3.3 Control and loss of control
10. **Ragdoll triggers** (any of these):
    - a fall predicted to total 13 or more blocks: limp after 3.5 blocks of falling
    - a fall of 7 or more blocks, not braced: limp about 1.2 blocks above the ground
    - landing from 13 blocks or more, or from 7 or more without a brace
    - slamming into a wall at more than 0.55 blocks/tick (about 11 m/s)
    - landing at more than 0.62 blocks/tick across the ground (a tumble)
    - flung through the air at more than 1.4 blocks/tick for 3 ticks
11. **Limp means no will.** No walking, no sprinting, no sprint FOV. Position follows the pelvis.
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
19. **Leaps complete.** A ledge jump is planned through clear air before it starts: the lowest arc that's clear along the whole path. A leap that only grazes an edge slides past it; only a real block in the path ends it.
20. **Corners are hand over hand.** The leading hand reaches round the edge onto the next face first. The body follows; then the trailing hand lets go and comes round. Hands travel round the corner's edge, never through the block or across open air.
21. **Shuffle rhythm.** One hand extends to its maximum and only then does the other close in. Stopping eases the cycle out smoothly.
22. **Keys are wall-relative while hanging.**
    - Toward the wall (W): climb.
    - Away from the wall (S): drop, or with space, a back jump that turns to face the other ledge.
    - Sideways (A/D): shuffle, or with space, a side jump.
    - Space alone: ledge jump up.
23a. **Catch reflex.** Falling past a lip (after at least 2.5 blocks of fall, and not the edge you just left), the hands grab it on any side, facing first. It works even while limp in the air. Falling faster than 1.1 blocks/tick tears the grip; holding crouch means letting yourself drop.
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

## 4. Not yet covered (next for the core)
- **Combat contacts:** a hit as an impact on a body part. Knockback goes into momentum, and a big hit means ragdoll. Being shot while hanging swings the body from the hands.
- **Grabs and throws between players:** two bodies linked by a hand contact.
- **Ladders and poles** as hand and foot holds with the same IK and hang physics.
- **Slides and rolls:** keep momentum through a roll when you crouch on landing at speed.
- **Surface materials:** friction and sound per block (ice, mud, metal).
- **Water:** buoyancy and drag in place of gravity.
