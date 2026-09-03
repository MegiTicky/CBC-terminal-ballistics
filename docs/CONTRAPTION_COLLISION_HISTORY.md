# Create Contraption Collision — Implementation History

## Objective

Add a config toggle (default off) to CBC Terminal Ballistics that makes CBC projectiles collide with Create contraption blocks with full CBCTB ballistics, instead of passing through them.

**Scope**: Phase 1 only — hit detection, penetration, ricochet, integrity damage, spall, marks.
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
(`Contraption.blocks` — a `Map<BlockPos, StructureBlockInfo>` in local coordinates). Vanilla
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

**Result**: Failed. `toLocalVector()` produced wildly incorrect local coordinates — blocks at
local `(0,0,0)` but the ray mapped to local `(-12, 0.9, -6)`. The offset was 12-17 blocks.

**Root cause**: Misunderstanding of Create's coordinate system. The `toLocalVector()` transform
involves:
```
toLocalVector(worldVec, pt):
    anchor = getAnchorVec()                     // entity position
    centerOfOrigin = VecHelper.getCenterOf(ZERO) // (0.5, 0.5, 0.5) ← critical offset
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

**Result**: Partial — entities were found and blocks enumerated, but:
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
2. Calls `Level.clip(ClipContext(COLLIDER, NONE))` → stores as `BlockHitResult` ordinal 0
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

#### `ContraptionCompat.java` — Complete Rewrite

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
   - Tier 1: `blockInfo.getClass().getMethod("state")` — deobfuscated record accessor
   - Tier 2: `blockInfo.getClass().getMethod("f_74676_")` — Forge SRG-mapped accessor
   - Tier 3: `blockInfo.getClass().getDeclaredField("state") + setAccessible(true)` — direct field
   - Tier 4: Iterate `getDeclaredMethods()` looking for any method returning `BlockState`

4. **Added `findMethodUpHierarchy`**: Walks the class hierarchy to find methods. This handles
   abstract methods declared on `AbstractContraptionEntity` but implemented on subclasses.

5. **Reduced logging noise**: Removed per-block dumps from production `raycast()`; kept detailed
   output only in `raycastDebug()`.

6. **Limited debug output**: `raycastDebug()` now shows first 5 blocks instead of all blocks
   to avoid chat spam on large contraptions.

#### `AbstractCannonProjectileContraptionMixin.java` — Documentation Cleanup

- Added comprehensive Javadoc explaining the mixin strategy, variable ordinals, and flow
- Removed redundant logging from `ctb$logHead` and `ctb$modifyBlockResult`
- The `@ModifyVariable` approach is correct and unchanged

---

## Create Coordinate System Reference

From decompiling `AbstractContraptionEntity` in Create 0.5.1.j:

```
// Local → World
toGlobalVector(localVec, pt):
    anchor = getAnchorVec()                         // entity.position()
    centerOfOrigin = VecHelper.getCenterOf(BlockPos.ZERO)  // Vec3(0.5, 0.5, 0.5)
    v = localVec - centerOfOrigin                   // shift to rotation origin
    v = applyRotation(v, pt)                        // rotate by entity angle
    v = v + centerOfOrigin + anchor                 // shift back + translate to world
    return v

// World → Local
toLocalVector(worldVec, pt):
    anchor = getAnchorVec()
    centerOfOrigin = VecHelper.getCenterOf(BlockPos.ZERO)
    v = worldVec - anchor - centerOfOrigin          // translate to origin
    v = reverseRotation(v, pt)                      // rotate by -entity angle
    v = v - centerOfOrigin                          // shift to block coordinate origin
    return v
```

Key: The `(0.5, 0.5, 0.5)` center-of-origin offset is applied **twice** in each direction —
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
   - `Entities found: N` — at least 1 entity found
   - `Blocks: N` — blocks enumerated
   - `local=X worldCenter=Y state=Z` — world positions are correct (not null)
   - `-> HIT!` or `-> MISS` — raycast result
5. If HIT: fire a cannon at the contraption and verify penetration/ricochet

---

## File Reference

| File | Purpose |
|------|---------|
| `util/ContraptionCompat.java` | Create integration: entity search, raycast, coordinate transforms |
| `mixin/AbstractCannonProjectileContraptionMixin.java` | Mixin into CBC's `clipAndDamage()` |
| `config/TBConfig.java` | `HIT_CREATE_CONTRAPTIONS` toggle (default: false) |
| `command/TBCommands.java` | `/cbctb testray [distance]` diagnostic command |
| `ballistics/TBImpactService.java` | Marks-clear guard for contraption blocks |

---

## Next Steps (Phase 2)

1. **Verify hit detection** in-game with `/cbctb testray` after this fix
2. **Test full projectile flow** — fire cannon at contraption, verify penetration
3. **Handle rotated contraptions** — the `center +/- 0.5` AABB approximation may need
   inflation for rotated contraptions (diagonal blocks)
4. **Block break (deferred)** — contraption blocks should break off when destroyed
5. **Performance** — for contraptions with 1000+ blocks, consider spatial partitioning or
   AABB-based prefiltering
