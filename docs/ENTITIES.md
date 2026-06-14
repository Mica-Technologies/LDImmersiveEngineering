# Custom Entities

Technical documentation for the custom entities in Immersive Engineering (Forge 1.12.2).

All custom entities live in `common/entities/`. They fall into three families:

- **Projectiles** - revolver/railgun/chemthrower shots, built on `EntityIEProjectile` or
  `EntityRevolvershot`.
- **Ridden / utility** - the skyhook line-rider (`EntitySkylineHook`).
- **World objects** - the powder-barrel explosive (`EntityIEExplosive`) and the fluorescent
  tube (`EntityFluorescentTube`).

Almost every entity overrides `onUpdate()` to implement its own physics rather than relying
on the base class, syncs construction-time data to the client through `DataParameter`
markers, and disables vanilla collision/damage.

---

## Projectile family

### EntityIEProjectile (abstract base)

`entities/EntityIEProjectile.java:32`. Extends `EntityArrow` ("Yes I have to extend arrow or
else it's all weird and broken", line 32). Base for the railgun and chemthrower shots.

**State:** last-block-hit coordinates (`blockX/Y/Z`, `inBlock`, `inMeta`), `inGround`,
`ticksInAir`, `ticksInGround`, a `tickLimit` (default 40), and the shooter's name synced via
`dataMarker_shooter` (`EntityIEProjectile.java:34-44`). Hitbox is `0.125 x 0.125` and pickup
is disabled.

**Tick (`onUpdate`, `EntityIEProjectile.java:124`):**
1. On the client, resolve the shooter from the synced name (`:126-127`).
2. Call `onEntityUpdate()` (subtypes hook this for per-tick effects).
3. If lodged in a block: if the block is unchanged, increment `ticksInGround` and die at
   `getMaxTicksInGround()` (default 100); if the block changed, pop free with randomised
   motion (`:142-160`).
4. Otherwise in flight: increment `ticksInAir`, die at `tickLimit` (`:163-169`).
5. **Block raytrace** from current to next position via `world.rayTraceBlocks` (`:173`).
6. **Entity sweep**: `world.getEntitiesInAABBexcluding(this, bb.expand(motion).grow(1), ...)`
   then a per-candidate `calculateIntercept` to pick the nearest hit
   (`:185-208`). The shooter is ignored for the first 5 ticks.
7. On hit: call the subtype's `onImpact(mop)` and die (entity hit) or lodge in the block and
   forward `onEntityCollision` to that block (`:211-249`).
8. Integrate motion, apply `getMotionDecayFactor()` (default 0.99, extra slow in water) and
   `getGravity()` (default 0.05), update rotation, then `doBlockCollisions()`
   (`:252-288`).

**Lifecycle:** spawned by the firing weapon; dies on impact, after `tickLimit` air ticks, or
after `getMaxTicksInGround()` lodged ticks. Immune to all damage
(`attackEntityFrom` returns false, `:359`).

Subtypes implement `onImpact(RayTraceResult)` and `allowFriendlyFire(EntityPlayer)`.

### EntityChemthrowerShot

`entities/EntityChemthrowerShot.java:41`. A short-lived `FluidStack` projectile from the
chemical thrower; optionally implements Albedo's `ILightProvider` for dynamic light.

- Carries a `FluidStack`, synced via `dataMarker_fluid` (`:43-44`).
- `getGravity()` (`:89`) is fluid-aware: gases get less gravity, negative-density fluids
  float upward.
- `canIgnite()` returns true for fluids flagged flammable by `ChemthrowerHandler` (`:101`).
- `onEntityUpdate` (`:107`) sets the shot on fire if it passes through fire/lava and the
  fluid is flammable.
- `onImpact` (`:127`): runs the fluid's registered `ChemthrowerEffect` on the hit
  entity/block; high-temperature fluids deal lava-style or fire damage directly
  (`:147-163`). Server-side only.
- Provides light based on fluid luminosity / burning state (`provideLight`, `:181`).

**Lifecycle:** same air/ground timeouts as the base; very short range in practice because of
the default 40-tick limit and decay.

### EntityRailgunShot

`entities/EntityRailgunShot.java:25`. Heavy, fast, long-range projectile fired by the
railgun.

- Larger hitbox (`0.5 x 0.5`), `pickupStatus = ALLOWED` so the ammo can be recovered
  (`:34-35`).
- Carries an `ItemStack` ammo, synced via `dataMarker_ammo` (`:27-28`); ammo defines
  `RailgunProjectileProperties` (gravity multiplier, damage) cached lazily in
  `getAmmoProperties()` (`:85`).
- Very low gravity (`0.005 * properties.gravity`, `:93`) and a long
  `getMaxTicksInGround()` of 500 (`:99`).
- `onImpact` (`:118`) applies `RailgunHandler` damage scaled by `Tools.railgun_damage`,
  unless the ammo overrides hit handling. Server-side only.

### EntityRevolvershot

`entities/EntityRevolvershot.java:45`. The base revolver bullet - extends raw `Entity` (not
`EntityArrow`) and reimplements its own arrow-style physics. Distinct from
`EntityIEProjectile`.

**State:** block-hit coords, `inGround`, `ticksInAir/Ground`, `movementDecay`, `gravity`,
`tickLimit` (default 40), a `bulletType` registry key, plus `bulletElectro` and
`bulletPotion` flags (`:47-62`). Shooter is an `EntityLivingBase` and is synced by name.

**Tick (`onUpdate`, `:146`):**
- Dies immediately if the shooter is dead (`:151`).
- Block raytrace + an entity sweep via
  `world.getEntitiesWithinAABBExcludingEntity(this, bb.offset(motion).grow(1))`
  with per-candidate intercept (`:204-239`).
- On any hit calls `onImpact` (`:241`).
- Integrates motion, applies decay/gravity, and every 4 ticks spawns a smoke particle
  (`:281-282`).

**`onImpact` (`:287`):** resolves the `IBullet` from `BulletHandler` and calls
`onHitTarget`, with a headshot check (`Utils.isVecInEntityHead`). Includes the
"birthday party" easter egg on a headshot kill of a baby mob (`:298-303`). `secondaryImpact`
(`:322`) handles the electro-bullet effect: slowness + draining flux items on the target
(`:324-345`). The class also carries a large block of commented-out legacy bullet behaviour
(wolfpack split, potion clouds) at `:347-421`.

**Lifecycle:** dies on impact or after `tickLimit` air ticks (calling `onExpire()` first);
lodged bullets live up to 1200 ground ticks (`:179`). Not collidable, immune to damage.

### EntityRevolvershotHoming

`entities/EntityRevolvershotHoming.java:18`. Extends `EntityRevolvershot` with target
tracking. After `trackCountdown` ticks it steers toward a target by lerping its motion vector
(`redirectionSpeed`, default 0.25) each tick (`:40-58`). `getTarget()` (`:61`) either uses a
forced `targetOverride` or scans a 20-block AABB for the nearest `EntityLivingBase` other
than the shooter (`world.getEntitiesWithinAABB`, `:68`).

### EntityWolfpackShot

`entities/EntityWolfpackShot.java:19`. Extends the homing shot with a faster
`trackCountdown` (15) and `redirectionSpeed` (0.1875). `onImpact` (`:43`) clears the target's
`hurtResistantTime` and deals `bulletDamage_WolfpackPart` damage. These are the secondary
projectiles spawned by the wolfpack bullet.

### EntityRevolvershotFlare

`entities/EntityRevolvershotFlare.java:33`. A flare bullet with a 400-tick lifetime
(`setTickLimit(400)`). Carries a colour synced via `dataMarker_colour`. On the client it
spits coloured redstone particles every tick and a burst after 40 ticks (`:86-122`). At tick
40 it slows to a gentle downward drift and computes a `lightPos` by scanning downward for the
ground (`:99-122`); `provideLight` (Albedo) then lights that spot. `onImpact` within the
first 40 ticks ignites the target or starts a fire on the hit block (`:126`).

---

## EntitySkylineHook

`entities/EntitySkylineHook.java:44`. The pulley/zipline rider attached to a wire
`Connection`. The player rides this entity as a passenger; it carries them along the
catenary of an Immersive Engineering wire.

**State:** the `Connection`, `linePos` (0 at start, 1 at end), `horizontalSpeed`, the catenary
`angle`, `friction` (0.99), the player's `hand`, a `limitSpeed` flag, and a set of
`ignoreCollisions` block positions (the connectors at each end, `:51-58`).

**Tick (`onUpdate`, `:125`):**
1. Requires a player passenger still holding the skyhook, else dies (`:129-138`).
2. Every 5 ticks sends a `MessageSkyhookSync` to the rider (`:140-141`).
3. Computes player-driven acceleration along the line from look/movement input, or falls back
   to catenary gravity physics (cosh/sinh based, `:177-195`) when not steering.
4. Optionally clamps speed (`LIMIT_SPEED` 0.25 / `MAX_SPEED` 2.5, `:197-203`).
5. Advances `linePos`, derives the new position from `connection.getVecAt(linePos)`, and sets
   motion from the delta (`:222-238`).
6. Validates the new position via `isValidPosition` (`:332`) - a collision heuristic that
   allows the rider through blocks only if the intersection is under ~10% of the player's
   bounding-box volume; otherwise the hook dies (drops the player).
7. At a line end, `switchConnection` (`:289`) searches connected wires at that node and picks
   the one closest to the player's look direction, converting speed between lines; if none
   fits, it dies.

**Dismount (`removePassenger` -> `handleDismount`, `:457-488`):** repositions the player,
transfers the hook's motion to them, sets fall distance from downward speed, releases the
skyhook-user capability, and applies a 10-tick cooldown on the skyhook item. Done on a future
server task to avoid mutating during the removal.

**Notable overrides:** invisible, not collidable, not pushed by water, `getMountedYOffset()`
of -2 (rider hangs below), `setPositionAndRotation` is a no-op (position is fully driven by
`linePos`), `attackEntityFrom` kills the hook. No NBT is persisted - the hook is transient.

---

## EntityIEExplosive (powder barrel)

`entities/EntityIEExplosive.java:30`. Extends `EntityTNTPrimed`; the primed/thrown form of
the powder barrel and other IE explosives.

**State:** `explosionPower`, `explosionSmoke`, `explosionFire`, `explosionDropChance`, and
the `IBlockState block` it renders as. The block and fuse are synced via `dataMarker_block` /
`dataMarker_fuse` (`:32-40`). `getName()` derives a display name from the block
(`:102`).

**Tick (`onUpdate`, `:139`):**
1. Client pulls the synced block on first tick (`:142`).
2. Applies TNT-style gravity and motion with ground friction (`:145-159`).
3. Decrements the fuse; at zero it dies and (server-side) builds an `IEExplosion` at its
   position, firing `ForgeEventFactory.onExplosionStart` before `doExplosionA/B`
   (`:160-174`). The `IEExplosion` honours `explosionFire`, `explosionSmoke`, and
   `explosionDropChance`.
4. While the fuse runs it does water-movement handling and spits a smoke particle (`:178`).

**Lifecycle:** persisted to NBT (power/smoke/fire flags + block state, `:117-135`); lives
until the fuse expires, then explodes once and dies.

---

## EntityFluorescentTube

`entities/EntityFluorescentTube.java:37`. A placed fluorescent tube that lights up near a
Tesla coil. Implements `ITeslaEntity` (receives coil hits) and Albedo's `ILightProvider`.

**State:** an `active` flag, an `rgb` colour, a horizontal angle, a `timer`, and
`tubeLength` 1.5. Colour, active state, and angle are all synced via `DataParameter`s
(`:39-49`). The hitbox is sized from the tube length (`:62`).

**Tick (`onUpdate`, `:67`):**
1. TNT-style falling physics with ground friction (`:70-85`).
2. On the first server tick, pushes its colour/angle into the data manager (`:86-93`).
3. Counts down `timer`; when it reaches 0 it sets `active` false (the tube goes dark)
   (`:95-100`).
4. On the client, reads `active`/`rgb`/`angle` back from the data manager (`:101-108`).

**`onHit(TileEntity, lowPower)` (`:168`):** when zapped by a `TileEntityTeslaCoil` that has
energy to spare, it drains 1 FE, lights up for a 35-tick `timer`, and sets `active` true.

**Interaction & lifecycle:** a hammer right-click rotates the tube's horizontal angle
(`applyPlayerInteraction`, `:178`). When attacked it drops itself as an
`itemFluorescentTube` (preserving the RGB) and dies (`:142-153`). Persists its colour and
angle to NBT (`:122-139`). `provideLight` emits a radius-10 coloured light only while active
(`:191`).
