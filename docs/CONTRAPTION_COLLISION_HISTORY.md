# Create Contraption Collision -- Implementation History

## Objective

Add a config toggle (default off) to CBC Terminal Ballistics that makes CBC projectiles collide with Create contraption blocks with full CBCTB ballistics, instead of passing through them.

**Scope**: Phase 1 only -- hit detection, penetration, ricochet, integrity damage, spall, marks.
Contraption blocks do NOT break off (deferred).

---

## Environment

| Item | Value |
|------|-------|
| Branch | `vs2.3` |
| Minecraft | 1.20.1 |
| Forge | 47.4.0 |
| Create | 0.5.1.j |
| CBC | 5.8.2 |
| Deploy target | `C:\Users\lauya\curseforge\minecraft\Instances\JPCreate2.5` |

---

## Fundamental Challenge

Create contraption blocks are **not placed in the world**. They are stored in entity-local data
(`Contraption.blocks` -- a `Map<BlockPos, StructureBlockInfo>` in local coordinates). Vanilla
`Level.clip()` / `Level.getBlockState()` see air at contraption block positions. Projectiles
pass straight through.

To make projectiles hit contraption blocks, we must:
1. **Find** nearby contraption entities
2. **Raycast** against their blocks in world space
3. **Replace** the vanilla clip result and block state in CBC's `clipAndDamage()` loop
4. Feed the contraption block's real state into `calculateBlockPenetration()`

---

## Attempt 1: DDA Voxel Traversal in Local Space

**Approach**: Transform the world-space ray into the contraption's local coordinate system using
`toLocalVector()`, then run a DDA (Digital Differential Analyzer) voxel traversal over the block
map.

**Result**: Failed. `toLocalVector()` produced wildly incorrect local coordinates -- blocks at
local `(0,0,0)` but the ray mapped to local `(-12, 0.9, -6)`. The offset was 12-17 blocks.

**Root cause**: Misunderstanding of Create's coordinate system. The `toLocalVector()` transform
involves:
```
toLocalVector(worldVec, pt):
    anchor = getAnchorVec()                     // entity position
    centerOfOrigin = VecHelper.getCenterOf(ZERO) // (0.5, 0.5, 0.5) <-- critical offset
    v = worldVec - anchor - centerOfOrigin
    v = reverseRotation(v, pt)
    v = v - centerOfOrigin
    return v
```
The `centerOfOrigin` subtraction happens **twice** (once before rotation, once after), which
was not accounted for.

---

## Attempt 2: Direct Block Iteration + World-Space AABB (Manual Transform)

**Approach**: Instead of transforming the ray to local space, transform each block's local AABB
corners to world space using `reverseRotateAndTranslate()` (a custom method doing
`anchor + reverseRotation(localVec, 0f)`), then test the world-space ray against each block's
world AABB using the slab method.

**Result**: Partial -- entities were found and blocks enumerated, but:
- `reverseRotateAndTranslate()` returned `null` for most entity types (e.g., `stationary_contraption`, `pitch_contraption`)
- When it DID work (e.g., `decoration_bearing_contraption`), world positions were slightly off
- `getBlockState()` returned `null` for all blocks

**Root causes**:
1. `reverseRotateAndTranslate` was missing the `centerOfOrigin` offset from Create's transform.
   The correct formula is `toGlobalVector()` which does:
   ```
   toGlobalVector(localVec, pt):
       anchor = getAnchorVec()
       centerOfOrigin = VecHelper.getCenterOf(ZERO) // (0.5, 0.5, 0.5)
       v = localVec - centerOfOrigin
       v = applyRotation(v, pt)
       v = v + centerOfOrigin + anchor
       return v
   ```
2. `reverseRotation()` is **abstract** on `AbstractContraptionEntity`. Each subclass
   implements it differently. The reflection `entity.getClass().getMethod(...)` may find it
   or not depending on the subclass hierarchy.

---

## Attempt 3: `@Redirect` on `Level.clip()`

**Approach**: Use `@Redirect` on the vanilla `Level.m_45547_()` (clip) calls in CBC's
`clipAndDamage()` to intercept and extend the raycast with contraption collision.

**Result**: Failed with mixin errors. The `@Mixin(remap=false)` setting prevents the
`@At` target remapping. Since `Level.clip()` has SRG name `m_45547_`, the redirect
target couldn't be resolved.

**Root cause**: Mixin `remap=false` applies to the entire mixin class, including `@At`
annotations. The vanilla method's official name and SRG name differ, and without remapping,
the injection point can't find the method.

---

## Attempt 4: `@ModifyVariable` on `clipAndDamage()` (Current Approach)

**Approach**: Use `@ModifyVariable` to intercept the local variable stores in `clipAndDamage()`:
- **Ordinal 0** for `BlockHitResult`: intercept the block clip result and replace with contraption hit
- **Ordinal 1** for `BlockState`: intercept the penetration block state and replace with contraption state

**Bytecode analysis of `clipAndDamage()`**:

The method performs a loop (up to 100 steps). Each step:
1. Computes `nextStepPos = currentPos + motion * remainingEnergy`
2. Calls `Level.clip(ClipContext(COLLIDER, NONE))` -> stores as `BlockHitResult` ordinal 0
3. On step 0 only, calls `Level.clip(ClipContext(OUTLINE, ANY))` for fluid detection
4. Sweeps entities in the AABB
5. If block clip hit something:
   - Fetches `blockState = LevelChunk.getBlockState(hit.blockPos)`
   - Calls `calculateBlockPenetration(ctx, blockState, hit)`
   - Handles PENETRATE / RICOCHET / STOP outcomes

Variable stores (type-filtered):
| Type | Ordinal | Bytecode | Content |
|------|---------|----------|---------|
| `BlockHitResult` | 0 | `astore 21` | Block clip result |
| `BlockState` | 0 | `astore 24` | Fluid block state |
| `BlockState` | 1 | `astore 28` | **Penetration block state** |

**Result**: Compiles and loads without crashes. The `@ModifyVariable` injections fire
correctly. However, hit detection was never confirmed because the underlying raycast
(Attempt 2's `reverseRotateAndTranslate`) was broken.

---

## Attempt 5: Fix with `toGlobalVector` (Current Fix)

**Key insight from bytecode analysis**: Create's `toGlobalVector()` and `toLocalVector()` are
the correct pair of transforms. The previous `reverseRotateAndTranslate()` was a manual
reimplementation that missed the `centerOfOrigin(0.5, 0.5, 0.5)` offset.

### Changes Made

#### `ContraptionCompat.java` -- Complete Rewrite

1. **Replaced `reverseRotateAndTranslate` with `toGlobalVector`**: Uses Create's own
   `toGlobalVector(Vec3, float)` method via reflection. This correctly handles anchor offset,
   centerOfOrigin, and rotation.

2. **Simplified world AABB computation**: Instead of transforming 8 AABB corners, we compute
   the world center via `toGlobalVector(Vec3.atCenterOf(localPos), 0f)` and build a simple
   `center +/- 0.5` AABB. This works because:
   - For unrotated contraptions, blocks are axis-aligned
   - For rotated contraptions, the center position is still correct; the +/- 0.5 approximation
     may slightly overlap adjacent blocks but won't miss the target

3. **Fixed `getBlockState` reflection** with 4-tier fallback:
   - Tier 1: `blockInfo.getClass().getMethod("state")` -- deobfuscated record accessor
   - Tier 2: `blockInfo.getClass().getMethod("f_74676_")` -- Forge SRG-mapped accessor
   - Tier 3: `blockInfo.getClass().getDeclaredField("state") + setAccessible(true)` -- direct field
   - Tier 4: Iterate `getDeclaredMethods()` looking for any method returning `BlockState`

4. **Added `findMethodUpHierarchy`**: Walks the class hierarchy to find methods. This handles
   abstract methods declared on `AbstractContraptionEntity` but implemented on subclasses.

5. **Reduced logging noise**: Removed per-block dumps from production `raycast()`; kept detailed
   output only in `raycastDebug()`.

6. **Limited debug output**: `raycastDebug()` now shows first 5 blocks instead of all blocks
   to avoid chat spam on large contraptions.

#### `AbstractCannonProjectileContraptionMixin.java` -- Documentation Cleanup

- Added comprehensive Javadoc explaining the mixin strategy, variable ordinals, and flow
- Removed redundant logging from `ctb$logHead` and `ctb$modifyBlockResult`
- The `@ModifyVariable` approach is correct and unchanged

---

## Create Coordinate System Reference

From decompiling `AbstractContraptionEntity` in Create 0.5.1.j:

```
// Local -> World
toGlobalVector(localVec, pt):
    anchor = getAnchorVec()                         // entity.position()
    centerOfOrigin = VecHelper.getCenterOf(BlockPos.ZERO)  // Vec3(0.5, 0.5, 0.5)
    v = localVec - centerOfOrigin                   // shift to rotation origin
    v = applyRotation(v, pt)                        // rotate by entity angle
    v = v + centerOfOrigin + anchor                 // shift back + translate to world
    return v

// World -> Local
toLocalVector(worldVec, pt):
    anchor = getAnchorVec()
    centerOfOrigin = VecHelper.getCenterOf(BlockPos.ZERO)
    v = worldVec - anchor - centerOfOrigin          // translate to origin
    v = reverseRotation(v, pt)                      // rotate by -entity angle
    v = v - centerOfOrigin                          // shift to block coordinate origin
    return v
```

Key: The `(0.5, 0.5, 0.5)` center-of-origin offset is applied **twice** in each direction --
once before rotation and once after. Missing even one causes multi-block position errors.

---

## StructureBlockInfo Access

`StructureTemplate.StructureBlockInfo` is a **Java record** in 1.20.1. Record components
have public accessor methods named after the component (`state()`, `pos()`, `nbt()`). However,
in Forge's SRG-mapped environment, the method names may differ:

| Lookup | Method | Notes |
|--------|--------|-------|
| Deobfuscated | `state()` | Works in dev environment |
| SRG-mapped | `f_74676_()` | Works in production Forge |
| Field access | `getDeclaredField("state")` | Fallback if both above fail |
| Method scan | Any method returning `BlockState` | Last resort |

Create's own code accesses them via SRG: `info.f_74676_()` for state, `info.f_74675_()` for pos,
`info.f_74677_()` for nbt.

---

## Entity Search Strategy

`Level.getEntitiesOfClass(AbstractContraptionEntity.class, searchBox)` is used to find
contraption entities. The search box is the ray's AABB inflated by 16 blocks.

**Known issues**:
- `AbstractContraptionEntity` does NOT resize its AABB to match the contraption structure.
  The entity AABB remains small (1x1x1 default).
- Broken/out-of-world entities at `|pos| > 10000` are filtered out.
- Some contraption types (e.g., `stationary_contraption`) may have their entity at the
  contraption's anchor position, not necessarily near the visible blocks.

---

## Testing Workflow

1. Enable config: set `hitCreateContraptions = true` in server config
2. Place a Create contraption (e.g., mechanical bearing with blocks)
3. Run `/cbctb testray` while looking directly at the contraption blocks
4. Check chat output for:
   - `Entities found: N` -- at least 1 entity found
   - `Blocks: N` -- blocks enumerated
   - `local=X worldCenter=Y state=Z` -- world positions are correct (not null)
   - `-> HIT!` or `-> MISS` -- raycast result
5. If HIT: fire a cannon at the contraption and verify penetration/ricochet

---

## File Reference

| File | Purpose |
|------|---------|
| `util/ContraptionCompat.java` | Create integration: entity search, raycast, point-in-AABB, coordinate transforms |
| `mixin/AbstractCannonProjectileContraptionMixin.java` | Mixin into CBC's `clipAndDamage()` |
| `mixin/BigCannonProjectileMixin.java` | Intercepts `calculateBlockPenetration` at HEAD |
| `config/TBConfig.java` | `HIT_CREATE_CONTRAPTIONS` toggle (default: false) |
| `command/TBCommands.java` | `/cbctb testray`, refresh commands, snapshot iteration |
| `ballistics/TBImpactService.java` | Core impact logic, spall guard, contraption-aware marks |
| `client/ClientImpactMarks.java` | Impact marks renderer with contraption state threading |
| `state/ArmorIntegritySavedData.java` | `currentBlockState()` uses `ContraptionCompat.blockStateAt()` |
| `state/EmbeddedShellSavedData.java` | Same `currentBlockState()` pattern |
| `CBCTerminalBallistics.java` | Login handler with snapshot iteration |

---

## Attempt 6: Point-in-AABB Containment (Bug Fix Session -- Sep 3 2026)

**Context**: After Attempt 5, hit detection was confirmed via `/cbctb testray` and actual cannon
shots. However, 4 downstream bugs persisted:

| Bug | Symptom |
|-----|---------|
| Issue 1 | Impact marks not rendering on contraption blocks |
| Issue 2 | Spall extending through contraption ships (should be contained) |
| Issue 3 | `ConcurrentModificationException` crash on player login |
| Issue 4 | Stale integrity/embedded-shell entries never cleaned up |

**Root Cause Analysis**:

All 4 bugs traced to a single underlying issue: `isContraptionBlock()`, `blockStateAt()`, and
`isContraptionPos()` in `ContraptionCompat.java` all called `raycastSingle(level, center, center, entity)`
with `from == to` (zero-length ray). Inside `raycastSingle()`, the ray length check at line 310:
```java
if (rayLen < 1e-6) return null;  // <-- always triggers for from==to
```
returned `null` unconditionally. This meant:
- `isContraption` flag in `TBImpactService.calculate()` was **always false** for contraption blocks
- `worldBlockMatchesState` in the `clipAndDamage` modifier always fell through to world state (air)
- Client `blockStateAt()` returned null -> marks render skipped
- `currentBlockState()` in `ArmorIntegritySavedData` / `EmbeddedShellSavedData` returned null -> stale entry deletion

**Fixes Applied**:

### Fix 1: `ContraptionCompat.java` -- Point-in-AABB Containment

Replaced all three broken methods with a new approach using `pointInContraptionBlock()`:

```java
// Old (broken): zero-length raycast
Object hit = raycastSingle(level, center, center, entity); // always returns null

// New: iterate blocks, transform center to world space, test point-in-AABB
private static BlockState pointInContraptionBlock(Vec3 point, Entity entity) {
    Object contraption = getContraption(entity);
    Map<BlockPos, ?> blocks = getBlocks(contraption);
    for (Map.Entry<BlockPos, ?> entry : blocks.entrySet()) {
        Vec3 worldCenter = toGlobalVectorImpl(entity, Vec3.atCenterOf(localPos), 0f);
        AABB worldBox = new AABB(worldCenter.x-0.5, worldCenter.y-0.5, worldCenter.z-0.5,
                                  worldCenter.x+0.5, worldCenter.y+0.5, worldCenter.z+0.5);
        if (worldBox.contains(point)) return getBlockState(entry.getValue());
    }
}
```

`isContraptionPos()` and `isContraptionBlock()` now delegate to `blockStateAt() != null`.
The `raycastSingle()` method is **preserved** for actual projectile raycasts (used by the mixin).

### Fix 2: `ClientImpactMarks.java` -- Thread BlockState Through Rendering

The render loop already resolved the contraption state via `ContraptionCompat.blockStateAt()`
into a local variable `state`, but passed `renderPos` (the world BlockPos) to `renderMark()`,
which then re-read `level.getBlockState(pos)` -> air.

Changed: `renderMark()`, `intersectsAttachment()`, and `clippedFaceRects()` now accept
`BlockState resolvedState` parameter and use it directly instead of re-reading the world.

```java
// Old
renderMark(buffers, poseStack, level, renderPos, mark, camera, age, lifetime);
// New -- resolvedState passed explicitly
renderMark(buffers, poseStack, level, renderPos, state, mark, camera, age, lifetime);
```

### Fix 3: `TBImpactService.java` -- Guard Spall Block-Break

Spall fragments that accumulated enough integrity damage would call `level.setBlock(bp, AIR)`,
which corrupts contraption entity data. Now guarded:

```java
if (currentDamage >= threshold) {
    if (!ContraptionCompat.isContraptionBlock(level, bp)) {
        level.setBlock(bp, Blocks.AIR.defaultBlockState(), 3);
    }
    playBreakSound(level, bp, st);
    clearMarks(level, bp);
}
```

Integrity damage still accumulates and plays break sounds, but the block is not removed.

### Fix 4: `ConcurrentModificationException` (Login Crash)

`CBCTerminalBallistics` login handler called `syncAllImpactMarksToPlayer()` and
`syncAllEmbeddedShellsToPlayer()`, which iterate over `ArmorIntegritySavedData.data` and
`EmbeddedShellSavedData.data`. These maps can be modified by `addDamage()` / `addShell()`
on the server tick thread concurrently with the login sync.

Fixed by snapshotting: `new HashMap<>(data)` before iterating. Same fix applied to
`TBCommands` refresh loops.

### Build & Deploy

Compiled, built, and deployed `cbc_terminal_ballistics-0.4.0-vs2.3.jar` (SHA256 verified)
to `JPCreate2.5\mods\`.

---

## Attempt 7: In-Game Test Results (Sep 3 2026)

### Progress (Working)

1. **Impact marks now render** on contraption blocks -- the point-in-AABB fix resolved the
   root cause; marks appear where projectiles hit contraption surfaces.
2. **Shell penetration/stopping works correctly** -- projectiles penetrate or stop based on
   armor properties of the contraption block, using the full CBCTB ballistics pipeline.

### Remaining Bugs

#### Bug 1: Impact Mark Edge Clipping (Visual)

Impact marks are always clipped at block edges. The `clippedFaceRects()` function clips
marks at block boundaries, and for contraption blocks it never finds a neighboring solid
block to allow overflow. This is because the neighbor check reads the world chunk
(`level.getBlockState(neighbor)`), which returns air for all contraption block positions.

**Root cause**: `clippedFaceRects()` at line ~285 calls `level.getBlockState(pos)` for the
neighboring block check. For contraption blocks, this always returns air -> neighbor always
"empty" -> mark always clipped.

**Fix needed**: Pass neighbor-block awareness into `clippedFaceRects()` -- either:
- (a) Pre-resolve the neighbor block states via `ContraptionCompat.blockStateAt()` for
  positions near the contraption, or
- (b) Skip clipping entirely for contraption blocks (simpler, slightly less accurate), or
- (c) Treat the contraption ship as a single surface for clipping purposes.

#### Bug 2: Spall Escaping Contraptions (Gameplay)

Spall fragments sometimes pass through contraption blocks and damage entities/blocks
behind the ship, even when the projectile did NOT penetrate. Spall range was previously
capped to 4 blocks for contraption origins, but this is still too large for some
configurations (e.g., thin single-block ships).

**Root cause**: The spall range formula `clamp(5.0 + massRatio*12 + caliber*1.5, 5, 24)`
has a minimum of 5 blocks. For contraptions, this allows spall to reach outside the ship.
The cap was applied in a previous fix but may not be tight enough.

**Fix needed**: Calculate the actual contraption extent and clamp spall range to
`min(cappedRange, contraptionMaxExtent)`. Or: check each spall block position with
`isContraptionBlock()` and skip blocks outside the ship.

#### Bug 3: No Embedded Shells

Projectiles that should embed (penetrate with remaining energy < threshold) are not
leaving embedded shells in contraption blocks. The `EmbeddedShellSavedData` integration
may not be triggered for contraption impacts.

**Root cause**: Likely the `isContraption` flag still gates some code path in
`TBImpactService.calculate()` that stores embedded shells. Need to trace the
`embedShell()` call path and verify it fires for contraption blocks.

**Fix needed**: Verify `embedShell()` is called for contraption impacts; if gated behind
an `isContraption == false` check, remove the gate.

#### Bug 4: Marks Not Transformed with Contraption (Visual)

When the contraption moves after impact, the impact marks stay at the original world
position and do not follow the ship. Marks are stored as absolute world coordinates
in `ArmorIntegritySavedData` / `ImpactMarksSavedData`.

**Root cause**: Marks are stored with `worldPos` = the hit position at impact time. The
render loop looks up marks by `BlockPos` in world space. When the contraption moves, the
block positions change but the mark keys do not.

**Fix needed**: Store marks in **local contraption coordinates** (with a contraption entity
UUID reference), and transform to world space at render time using the current
`toGlobalVector()`. This requires:
1. Extending `ImpactMark` to include `contraptionEntityId` + `localBlockPos`
2. Updating the render loop to resolve marks via the contraption's current transform
3. Updating `syncAllImpactMarksToPlayer()` to include contraption identity

---

## Attempt 8: Applied Fixes (Sep 4 2026) — 0.4.1-vs2.3

All four Attempt-7 bugs fixed. Network channel bumped `11 → 12`; `mod_version=0.4.1-vs2.3`.

### Bug 1 — Mark edge clipping (FIXED)

`ClientImpactMarks.isSupportedOverflowNeighbor()` now falls back to
`ContraptionCompat.blockStateAt(level, neighbor)` when the world neighbor is air,
so overflow bands on contraption hulls get proper neighbor support checks.

### Bug 2 — Spall escaping contraptions (FIXED)

- `ContraptionCompat.ContraptionSnapshot`: immutable world-space snapshot of a
  contraption's solid blocks (built once via `snapshot(entity)`), with pure-math
  slab-method `raycast(from, to)` / `rayDistance(from, to)`. Rays starting inside a
  block ignore that block (spall origin inside the impact block is not a barrier).
- `TBImpactService.calculate()` resolves the owning contraption entity once
  (`ContraptionCompat.contraptionEntityAt`) and passes it into both `spawnSpall`
  call sites. The old origin-BlockPos re-detection (which missed because the origin
  is advanced 1.05 blocks) is gone.
- Per fragment: nearest of vanilla clip vs snapshot hit stops the fragment; hull
  hits apply the shared `applyFragmentBlockDamage()` (integrity damage + sounds,
  never setBlock on contraption blocks). Direct-hit comparison and cone-lethality
  LOS occlusion also use the snapshot, so entities behind hull walls are protected.
  The `min(range, 4.0)` contraption cap is retained, now keyed off the entity.

### Bugs 3+4 — Marks and shells now follow the contraption (FIXED)

Both decal systems are anchored at the SavedData **entry level** to the contraption
entity UUID + contraption-local `BlockPos`; the `ImpactMark` / `EmbeddedShell`
records are unchanged.

- `ContraptionCompat` additions: `contraptionEntityAt`, `localPosAt`,
  `blockStateForLocal` (direct map lookup, movement-proof), `worldCenterForLocal`,
  `toLocalVector`, `localFaceAt`, `orientationDegrees`, `snapshot`.
- `ArmorIntegritySavedData` / `EmbeddedShellSavedData`: `Entry` gained
  `contraptionUuid` + `localPos` (null for world entries). Contraption entries are
  keyed by `contraptionKey(uuid, localPos)` (stable across movement). Validation
  resolves state via entity-by-UUID + `blockStateForLocal`; entries whose entity is
  temporarily unloaded are kept until TTL expiry. `cleanup()` now returns removed
  `Entry` objects; NBT stores `Contraption`/`LocalPos`.
- `TBImpactService`: impact resolves `contraptionEntity` + `contraptionLocalPos`
  once; mark/exit fractions and faces are computed in contraption-local space
  (`toLocalVector` / `localFaceAt`); embedded shell positions/directions likewise.
  Sync helpers build contraption-aware packets; `syncAll*` resolves live entities.
- Packets (`ClientboundImpactMarksPacket`, `ClientboundEmbeddedShellsPacket`):
  optional `{UUID, localPos}` identity appended (hasContraption boolean).
- Client: `ClientImpactMarks` / `ClientEmbeddedShells` gained
  `Map<UUID, Map<BlockPos, List<Mark>>>` stores and render loops that resolve the
  entity each frame (linear scan — `ClientLevel` has no UUID lookup in 1.20.1) and
  draw in the contraption's live rotated frame. Rotation uses `contraptionBasisMatrix()`:
  the local→world linear part is sampled from `toGlobalVector` around the block
  center, so marks rotate exactly with any contraption type (no yaw/pitch guessing).
  Embedded shells render through the same basis with a -0.5 base offset; the
  world-air skip gate is bypassed for contraption entries via `blockStateForLocal`.
  Flame particles spawn at live-transformed world points.
- `TBCommands` marks/shells refresh+delete and the `CBCTerminalBallistics` cleanup
  loop are entry-identity aware (`clearMarksForEntry` / `clearShellsForEntry`).

### Known limitations

- Server-side integrity damage for contraption hulls is still keyed at the snapped
  world BlockPos (accumulated hull damage follows the entry anchoring only for
  decals, not for the `/cbctb clear` world-pos workflow).
- The 1×1×1 world AABB approximation per block is unchanged; heavily rotated
  contraptions still use center-containment lookups (consistent with Phase 1).

### Build & Deploy

`cbc_terminal_ballistics-0.4.1-vs2.3.jar` built and deployed (SHA256-verified) to
`JPCreate2.5\mods\`. In-game verification pending.

---

## Attempt 9: Follow-up Fixes (Sep 4 2026) — 0.4.2-vs2.3

In-game testing of Attempt 8 exposed four issues. All traced to two geometry
root causes plus one behavioral gap.

| Symptom | Root cause |
|---------|-----------|
| Shells float off the armor / wrong position | Double offset in the contraption render path: the frame already shifts to the block corner (`translate(-0.5)` after the basis matrix) AND `renderShell` was passed `baseOffset = -0.5` → shells drawn half a block toward the block's local min corner. |
| Contraption angle ignored — armor behaves as if flat (90°), "random" penetrations, spall leaking | ALL collision geometry (`raycastSingle`, `pointInContraptionBlock`, `ContraptionSnapshot`) transformed block centers to WORLD space at pt=0 and tested axis-aligned ±0.5 boxes. For a rotated contraption those boxes are wrong (gaps/overlaps), and `determineFace()` always produced an axis-aligned face → wrong incidence normal in `calculate()` → momentum math treated angled plate as flat. |
| Shell hits its own firing cannon contraption | The cannon is itself a contraption; the mixin raycast immediately "hit" the cannon's own hull blocks at the muzzle (vanilla never saw them — air in the world). |

### Fixes

1. **Shell render offset (client)** — contraption path now passes `baseOffset = 0.0`
   to `renderShell`; the frame's `translate(-0.5)` alone places fractions correctly.

2. **Exact local-space collision (`ContraptionCompat`)** — the RAY is transformed into
   the contraption's local frame once via `toLocalVector(entity, ·, 0f)` and tested
   against local block cells (`AABB(localPos)`), which are exact for any rotation
   (rigid transform, no scale). Applied to `raycastSingle`, `pointInContraptionBlockDetailed`,
   and `ContraptionSnapshot` (snapshot now stores local cells + entity — also cheaper:
   no per-block reflection transforms at build time; local cells tile perfectly so the
   spall-leak gaps are gone).
   - Local face of the hit → exact world-space normal via `toGlobalVector(center + n_local) − center`
     (`trueNormalFromLocal`), and an axis-snapped world `Direction` (`dominantDirection`)
     for the vanilla `BlockHitResult`.
   - `ContraptionHit` record extended: `entity`, `trueNormal`, `localPos`.
   - Normal delivery to the impact service: `raycast()` records the returned hit's
     `hitLocation`+`trueNormal` in a static side-channel; `TBImpactService.calculate()`
     overrides `rawNormal` with `ContraptionCompat.matchedContraptionNormal(hitLocation)`
     when `isContraption` (proximity-matched against staleness — the normal cannot ride
     through CBC's axis-aligned `BlockHitResult`). Incidence, ricochet chance, marks
     placement, and penetration ratios now all use the true armor angle.

3. **Self-hit suppression (mixin)** — `AbstractCannonProjectileContraptionMixin`
   records the source contraption on the projectile (`@Unique` fields): within the
   first 3 clip steps, `ContraptionCompat.sourceContraptionCandidate(Entity)` returns
   the contraption whose local structure bounding box (inflated 1.5) contains the
   projectile position; that UUID is excluded from `raycast(level, from, to, exclude)`
   for 100 ticks, after which the same ship can be hit normally (returning artillery).

### Notes

- `toLocalVector` (public wrapper) is used for all ray/point conversions; the
  class-level coordinate-system doc (double centerOfOrigin offset) still applies.
- Snapshot `Box`/`ContraptionHit` records changed shape; no external constructor
  callers existed.

### Build & Deploy

`cbc_terminal_ballistics-0.4.2-vs2.3.jar` built and deployed (SHA256-verified) to
`JPCreate2.5\mods\`.

---

## Attempt 10: Create Contraptions on VS2 Ships (Sep 16 2026)

Reported: nothing works when the mechanical bearing contraption is assembled ON a
Valkyrien Skies ship.

### Root cause (verified in VS2 2.3 sources)

VS2 pairs Create's contraption entity types with the `valkyrienskies:shipyard`
entity handler (`data/create/vs_entities/*.json`). Ship-mounted contraption
entities live in **SHIP-LOCAL coordinates** — `AbstractShipyardEntityHandler.
applyRenderTransform` renders them by mapping `entity.getPosition()` through
`ship.renderTransform.shipToWorld` and applying `shipToWorldRotation`. Every
ContraptionCompat conversion treated Create's `toGlobalVector()` output as WORLD
coordinates, so on any ship displaced/rotated from its assembly point all
collision, decals, spall, and normals tested the wrong place. Entity search was
also world-frame (`getEntitiesOfClass` with a world AABB) and could never find
entities whose stored positions are ship-local.

### Fixes

1. **Ship-frame composition layer (`ContraptionCompat`)**:
   - `shipOf(Entity)` — resolves + caches (WeakHashMap, 20-tick TTL) the mounted
     ship: `VSCompat.shipManaging(level, entity.blockPosition())` on server
     (shipyard chunk lookup accepts ship-local positions — the frame ship-block
     code already uses), `VSClientCompat.shipManaging(ClientLevel, BlockPos)`
     (new public wrapper) on client.
   - `localToWorld(entity, local, pt)` = `toGlobalVector` + ship transform on top
     (client uses the interpolated render transform via new
     `VSClientCompat.shipRenderLocalToWorld`, so decals don't lag during motion).
   - `worldToLocal(entity, world, pt)` = `toShipLocal` + `toLocalVector`.
   - True surface normals sampled through `localToWorld` (ship rotation included).
   - Rewired: `raycastSingle`, `pointInContraptionBlockDetailed`,
     `ContraptionSnapshot.raycast`, `sourceContraptionCandidate`,
     `worldCenterForLocal`, `localFaceAt`, `raycastDebug` display.
2. **Frame-independent entity search**: `findContraptionEntities` now iterates
   `ServerLevel.getAllEntities()` / `ClientLevel.entitiesForRendering()` filtered
   by the reflected contraption class (world-frame boxes can't find ship-local
   entities; contraption entities are few, so full iteration is cheap).
3. **Client render**: `contraptionBasisMatrix` + flame particles sample
   `localToWorld`, so marks/shells rotate with the combined ship+bearing motion.

Both branches: main received the same fix first (e4c1a1f, where it also uncovered
that main never got e88e153's PipRenderScope 1.20.1 API fix — cherry-picked
together); vs2.3 got it via cherry-pick (379fc09). No version bump (release
naming stays 0.4.0-vs2.3 per the 0.4.0 release process).

Deployed `cbc_terminal_ballistics-0.4.0-vs2.3.jar` (SHA256-verified,
0a4de3aa...) to `JPCreate2.5\mods\`.

## Attempt 10b: shipOf could never resolve the ship + stale partial ticks (Sep 16 2026)

Attempt 10 failed in-game ("still not working on ship") and rotating WORLD
contraptions were still missed intermittently. Three distinct bugs:

### Root causes

1. **`shipOf` fed SHIP-LOCAL coordinates into a WORLD-chunk ship lookup.**
   `VSCompat.shipManaging` → `tryDirectShipWorldAccess` → `isChunkInShipyard(
   pos.x>>4, pos.z>>4, dim)` resolves ships by WORLD chunk; a shipyard entity's
   `blockPosition()` is in ship space (`WorldEntityHandler.
   moveEntityFromShipyardToWorld` confirms shipyard entities live there), so the
   lookup hit the wrong chunk (or none) and the ship was NEVER found — the whole
   Attempt 10 composition layer never engaged. VS2 2.3 exposes no authoritative
   entity→ship map (its own `getEntities` patch resolves shipyard entities
   positionally per ship too).

2. **All server-side transforms sampled the PREVIOUS tick pose.** Every
   `worldToLocal`/`localToWorld`/`worldCenterForLocal` call passed
   `partialTick = 0f`, which lerps to `getPrevAnchorVec()`/prev yaw (VS2's
   `redirectToGlobalVector` and Create's `reverseRotation` both lerp by pt). A
   rotating bearing is stale by one tick of rotation → ray tests land off the
   block → intermittent misses and slightly misplaced marks/spall. (Client render
   paths already passed the real render partialTick — correct.)

3. **Contraption substitution ignored hit distance.** `ctb$modifyBlockResult`
   replaced the vanilla clip result whenever ANY contraption hit existed on the
   tick segment, even when the vanilla world block was NEARER — swallowing ship
   hull plates (hulls are real world blocks) and any world block in front of a
   contraption. Ghost geometry (pre-fix ship-local poses tested against the world
   ray) made this random in practice.

### Fixes

1. **Positional ship association** (`VSCompat.shipMountedAt(ServerLevel, Vec3)`
   + client mirror `VSClientCompat.shipMountedAt(ClientLevel, Vec3)`): for each
   loaded ship, map the entity's ship-space position through that ship's
   `shipToWorld` and test containment in the ship's WORLD hull AABB (all 8
   ship-space `getShipAABB()` corners transformed, inflated 3 blocks); tie-break
   by distance to hull. Ship spaces are anchored at the assembly location, so
   only the true owner maps the position onto its own hull. Known residual edge:
   ships co-assembled within a few blocks AND sailing within hull distance of
   each other may mis-pair. World contraptions resolve null (a false positive
   would require shipToWorld ≈ identity, where composing it is harmless).
   `shipOf` now calls these with `entity.position()`; the 20-tick WeakHashMap
   cache stays.
2. **`partialTick 0f → 1.0f`** at every server transform site
   (`ContraptionCompat` internals, `TBImpactService`, `/cbctb` debug output).
   pt=1 = current-tick pose, matching the projectile's current segment.
3. **`worldToLocal` client branch** now uses new
   `VSClientCompat.shipRenderWorldToLocal` (render transform's `getWorldToShip`,
   physics fallback) so the round trip is render-consistent on moving ships.
4. **Nearest-hit gate in `ctb$modifyBlockResult`**: the contraption hit replaces
   the clip result only when vanilla MISSed or the contraption hit is strictly
   nearer than the vanilla block hit.
5. `findContraptionEntities` corruption guard 10000 → 1000000 (ship-space coords
   legitimately exceed 10k for far-built ships).

Both branches (this commit vs2.3, cherry-picked to main). No version bump.
Deployed `cbc_terminal_ballistics-0.4.0-vs2.3.jar` (SHA256-verified, 5a2ed7a9…)
to `JPCreate2.5\mods\`.

## Attempt 11: Create Interactive is the live compat layer — delegate transforms to it (Sep 16 2026)

10b still failed in-game ("still not hitting contraption on ships"). User asked
to check how VS and Create actually handle contraptions on ships vs worldspace.
Findings overturned the 10b premise:

### Investigation findings

1. **The pack runs Create Interactive 1.1.1-beta.2** and the tt16 VS2 fork logs
   "[VS2] Create Interactive ship-to-contraption lookup enabled" at world load.
   CI ships its own mixins into `AbstractContraptionEntity`, `ContraptionCollider`,
   `OrientedContraptionEntity`, etc. — the VS2-base Create compat in the reference
   tree (MixinContraptionCollider's world-side entity handling, the shipyard render
   handler) is largely superseded at runtime.
2. **VS2 chunk claims are a reserved virtual region** (core `ChunkAllocator`:
   claims allocated into a fixed X/Z band; `isChunkInShipyard` is just a
   reserved-region bounds check). `getShipManagingPos`/`getByChunkPos` therefore
   natively resolve SHIP-SPACE ("shipyard") coordinates — Attempt 10b's "the ship
   lookup is world-only, so it can never resolve shipyard entities" premise was
   WRONG. Attempt 10's real failure was almost certainly
   `findContraptionEntities`' ±10000 position guard rejecting shipyard-coordinate
   entities (±112000 blocks); 10b fixed that guard but simultaneously replaced the
   (working) native lookup with a positional hull test, which CI's shadow ships
   can steer at the wrong ship (double transform).
3. **CI's authoritative API** (`CreateInteractiveUtil`, Kotlin object,
   `ContraptionPosRot{pos, rot, scale}`):
   `getContraptionPosRot(entity)` = the contraption's full world transform
   (parent ship resolved via `getShipManagingPos(entity.position())` and composed
   with `shipToWorld`; shadow-ship cases handled internally);
   `getContraptionPosRotForRender(entity, partialTick: Double)` = interpolated
   client variant. CI's `MixinContraptionCollider` cancels Create's
   `collideEntities` whenever a contraption's shadow ship is loaded — collision
   goes through the ship pipeline. Hand-rolled frame assumptions cannot track
   this layer; delegation can.

### Fixes

1. **New `util/CreateInteractiveBridge.java`** — reflection, optional-safe:
   `posRotTick(entity)` / `posRotRender(entity, partialTick)` →
   `{px,py,pz,qx,qy,qz,qw,scale}` or null (signature drift tolerated).
2. **`ContraptionCompat.localToWorld`/`worldToLocal` are CI-first**: when the
   bridge resolves, `world(p) = pos + q·(scale·(p − 0.5))` (inverse via
   conjugate), server tick variant / client interpolated render variant with the
   frame's real partialTick. Fallback: the 10b ship-composition path, unchanged.
   Every consumer (raycast, snapshot/spall, marks basis, flame particles,
   embedded shells, source-candidate) inherits this.
3. **`shipOf` ordering**: native `shipManaging(level, blockPosition())` FIRST
   (CI's own pattern), positional hull test only as fallback.
4. Kept from 10b: `partialTick = 1.0f` server transforms, mixin nearest-hit
   gate, 1e6 entity guard (load-bearing for shipyard coords).
5. **Diagnostics**: one-shot INFO per entity on first shell contact
   (`[ctb] contraption contact: ...` — which path resolved, ship id, hit
   local/world pos, CI posRot) and `/cbctb` raycastDebug now prints CI
   availability + per-entity posRot.

Both branches (this commit vs2.3, cherry-picked to main). No version bump.
Deployed `cbc_terminal_ballistics-0.4.0-vs2.3.jar` (SHA256-verified, 27a6e6c1…)
to `JPCreate2.5\mods\`.
