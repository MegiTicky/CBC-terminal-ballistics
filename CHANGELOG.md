# Changelog

All notable changes to CBC Terminal Ballistics will be documented in this file.

## [0.4.0] - 2026-09-12

### Added
- Persistent shell embedding.
  - Shells that stop inside a block are saved and rendered persistently (`embeddedShellLifetimeTicks`, 0 = permanent).
  - Embed depth is based on the projectile's penetration ratio.
  - Embedded shells follow VS ships — stored ship-locally, rendered in the ship's live frame.
  - Loaded fuze and shattering projectiles retain their native behavior (they never embed).
  - Per-block and global render caps (`embeddedShellsPerBlock`, `maxRenderedEmbeddedShells`).
- Ballistic repair with CBC's existing Cannon Welder item.
  - Right-click a damaged block to clear impact marks, embedded shell visuals, and accumulated armor integrity damage.
  - Shift-click removes embedded shells only.
- Impact synchronization.
  - Impact marks and embedded shells are sent to players when they join.
  - Embedded shells resynchronize with nearby players every 2 seconds; impact marks update on change.
- Artillery & Terminal View (hitcam).
  - Cinematic artillery chase cam following shells in flight, with sticky targeting so rapid fire doesn't constantly retarget mid-flight.
  - Terminal x-ray view on impact: slow-motion penetration breakdown of the shell's path, plate hits, spall cones, and a verdict (CRITICAL HIT / HIT / RICOCHET / NO PENETRATION / DETONATION).
  - Critical-hit damage report: broken mechanical components and crew kills listed in the x-ray (data-driven `critical_blocks` datapack; PlayerRevive downs count as crew kills).
  - Multi-shell burst accumulation: follow-up shells merge into the running x-ray diagram — autocannon bursts render as layered live impacts instead of restarting the view.
  - Salvo ghost shells: other shells in the salvo render in the PiP alongside the tracked one.
  - Terrain streaming: real chunks stream around the shell's flight path and impact area; Distant Horizons compatibility modes (LOD fallback or suppression) for the chase cam; terminal streaming is exempt from the artillery `terrainStream` preference.
  - Ballistic Goggles: right-click a cannon mount to link the goggles to up to 16 cannons; linked cannons' shells get the artillery/terminal views. Links are ship-anchored so they survive the ship sailing. VS Analog Warfare's Vehicle Setup Block can automatically relink goggles on vehicle rebuild.
  - Spotter Goggles: opt in to viewing nearby shells from any cannon.
  - Configurable via the `hitcam` / `hitcam_xray` client config sections and the hitcam server config section.
- Optional Create contraption collision for projectiles (`hitCreateContraptions`, default off).
- Data-driven ignored blocks: pack authors can make projectiles phase through specified blocks untouched (`terminal_ballistics/ignored_blocks` datapack folder).
- Combatgear ammo racks cook off when broken by shells or spall.
- Ship-following impact decals.
- New `/cbctb` subcommands: `marks refresh|delete`, `shells refresh|delete`, `goggles list|clear`.

## [0.4.1] - 2026-09-16

### Added
- Ballistic Goggles improvements.
  - The shell-to-goggle match radius default is raised from 24 to 48 (`hitcamGoggleMatchRadius`). Shells spawn at the barrel muzzle, so long guns could spawn outside the old radius and open no view.
  - Failed matches are logged at INFO level with each link's resolved position and distance, instead of a DEBUG line.
  - Goggles now count wherever they sit in the inventory (main inventory, armor and offhand slots alike), not just when held in a hand.
- `/cbctb goggles debug` overlay.
  - Per-shell audience verdicts (CAPTURED, NO AUDIENCE, NEVER TICKED) with each linked mount's resolved position, distance and match result.
  - The client renders wireframe markers at the shell spawn and the resolved link positions, plus a top-left HUD verdict list.
- New `/cbctb viz` and `/cbctb trace` debug commands for contraption collision.
  - `viz [seconds]`: client-side wireframe of the exact contraption block geometry the raycast tests, with the entity position, the Create Interactive transform position and the local axes, so transform misalignment is visible directly.
  - `trace [seconds]`: logs every contraption shell raycast (endpoints, entities, per-entity HIT/MISS with miss distance) while active.
- The picture-in-picture display size is now a client option (`pipDisplayWidth` / `pipDisplayHeight` in the `hitcam` client section). The view still renders at full framebuffer resolution and is downscaled into the window.

### Changed
- Create contraption collision (`hitCreateContraptions`) works on Valkyrien Skies ships.
  - The VS ship transform is composed into the contraption compatibility transforms.
  - Ships are associated positionally (the ship-space position through each ship's transform must land on its own hull), server-side transforms sample the current tick, and contraption substitution only claims strictly nearer hits than world blocks.
  - When Create Interactive is present, ship-to-contraption transforms are delegated to it (shadow ships, chunk-claim ship space), with the VS2-composed path kept as fallback.

### Fixed
- Artillery/terminal picture-in-picture render scope.
  - The nested PiP pass is isolated from the main view's render state and staged through its own render events.
  - Main-screen depth restore is hardened.
  - The nested pass no longer participates in vanilla's entity-glow outline pipeline, so a running view no longer overwrites the main view's glow halo.
  - 1.20.1 API fixes in the PiP render scope (ShaderInstance import, PoseStack model-view restore, GL20 shader bind).
- Ship-block spall origin.
  - Spall rays on contraption blocks are rebased into the struck block's cell before shape testing, so ship impacts eject spall from real block geometry instead of the legacy fixed 1.05-block offset.
  - Block-entity-dependent shapes (framed collapsible armor) rebuild from the contraption's serialized NBT.
  - Rear-face spall cones on thin plates are no longer suppressed by their own mostly-empty cell.
- CBC block hit effects (impact smoke/splash) are force-displayed so they render past vanilla's ~32-block cull on unforced particles.
- Spall fragments eject from the plate's real exit surface instead of a fixed 1.05-block offset.

## [0.4.2] - 2026-09-28

### Added
- `/cbctb shelldebug [seconds]` overlay: every cannon projectile engaging a contraption reports a per-shell verdict with reasons. OUTCOME (green, processed), SKIP (orange, a vanilla block is nearer or the same cell was already consumed this tick), MISS (red, no contraption cell found, with nearest-block distance) and JAM (yellow, the breaker terminated the shell), rendered as in-world markers and HUD lines like the goggles debug overlay.
- Bouncing spall: fragment rays ricochet inside compartments.
  - Each fragment chains ray segments: a segment stops at the first surface, bleeds energy through the bodies it crosses, then embeds or ricochets with reduced energy and continues, so interior walls no longer fully shield a compartment's crew.
  - Deflection is keyed to the struck material's `spall_multiplier` (steel-like materials deflect fragments best, soft materials absorb them) instead of block toughness, so tough blocks no longer act as accidental fragment liners.
  - The ricochet roll scales linearly from the head-on fraction up to the full chance at a perfect graze.
  - Configured under the `spalling` section: `spallRicochet` (default true; false restores the legacy single-ray path), `spallMaxBounces`, `spallRicochetChance`, `spallRicochetHeadOn`, `spallBounceRetentionMin`/`spallBounceRetentionMax`, `spallEnergyFloor`.
- Goggle-triggered spall path replay: right-clicking with ballistic goggles replays the stored bounce paths of a recent spall cone in-world (`spallPathRenderTicks` — 0 disables recording and rendering; `spallPathGoggleRadius` — search radius for the spall cone to replay, 0 always replays the most recent spall in the dimension).

### Changed
- Terminal-view criteria narrowed: a shell opens the terminal x-ray only with real terminal content (armor plates, the shell's own blast, or entity/critical hits). Entity/critical-only engagements now dispatch a view where previously they never did; shells that die with no terminal content get no view and the artillery chase simply ends.
- Water-sink cut: a submerged shell that has slowed below `hitcamWaterCutSpeed` (default 1.0 blocks/tick) and lost at least 2/3 of its peak speed is judged to be sinking out and the chase view is cut. Steady slow munitions such as torpedoes keep being followed.
- Shell-removal logging now names the shell's tick age and the block/fluid state at (and below) the death position, so water sink vs. own structure vs. expiry is visible in the log.
- Spall ricochet chance and head-on fraction default to full (1.0): every fragment strike rolls at the material factor by default (steel always deflects, stone about half, soft materials still absorb).

### Fixed
- Server crash when dispatching an entity-only terminal x-ray (a null-blast dereference). Such diagrams now center on the first entity hit or critical instead of the world origin.
- Stale config migration with a one-time INFO log: a saved `hitcamGoggleMatchRadius` of 24 (the old default) reads as 48; saved `spallRicochetChance` values of 0.55 or 0.8 and a saved `spallRicochetHeadOn` of 0.35 read as 1.0. Deliberately set values are untouched.
- Contraption hit detection.
  - The raycast's exact entity and local position are used for impacts instead of re-deriving them from the world-snapped position, whose back-mapped center could cross into neighboring cells on rotated or moving contraptions.
  - The same contraption cell is no longer re-substituted within one tick, stopping jam loops where a shell re-impacted the same panel until the breaker terminated it.
  - Shipyard contraption entities (contraptions assembled on ships) are found through VS2's patched entity query, which vanilla's entity scan misses.
- CBC Modern Warfare medium cannon shells are recorded on every tick (the MW projectile overrides CBC's tick and skipped the first-tick recording gate on some ticks), and the debug overlays recognize mixed-case shell classes such as MediumcannonProjectile.

## [0.3.5] - 2026-08-10

### Fixed
- HE contact rounds now break the block they detonate on reliably.
- Entity damage is now applied before a projectile stops on block impact.
- Fixed client-server projectile impact outcome synchronization.
- NeoForge 1.21.1: test-launcher-fired shells are no longer removed on impact.

### Changed
- Mortar projectiles (mortar_stone, drop_mortar_shell) now bypass CBCTB terminal-ballistics handling so their native explosion behavior is preserved.

## [0.3.4] - 2026-07-31

### Added
- Realistic AP spall generation on penetration.
  - Fragment count scales with armor toughness, material spall properties, caliber, and shell type.
  - Fragment damage scales with the projectile's remaining mass and energy.
  - Spall is distributed in a dynamic cone based on projectile velocity and uses a center-biased pattern.
- New ricochet mechanics.
  - Ricochet probability is affected by armor toughness and projectile velocity.
  - Ricochets now apply configurable projectile mass loss.
- `/cbctb checkarmor` to scan loaded chunks for copycat armor above the configured level.
  - `/cbctb checkarmor fix` clamps exceeded armor levels.
- Additional spall and ricochet configuration options under the `spalling` and `ricochet` sections.

### Changed
- Armor integrity damage no longer scales directly with armor toughness. Tougher armor therefore requires more accumulated damage to break.
- Spall output is tuned by shell type, with AP-style projectiles producing the expected fragment quantities and damage.
- Copycat armor toughness and blast resistance continue to scale with armor level, with the configured maximum applied dynamically.
- Organized server configuration into general, projectile, armor-integrity, spalling, ricochet, visual, and copycat-armor sections.
- HEAP and HEAT projectiles bypass CBCTB terminal-ballistics handling for compatibility with their native behavior.
- CBC Modern Warfare HEAT and CBC:AT heavy autocannon/rotary-cannon classification and behavior were corrected.
- The ballistic test launcher now copies ammunition data before spawning a test projectile.

### Fixed
- Stopped projectiles now follow CBC's removal behavior, allowing fuze triggering to work correctly.
- Autocannon projectiles continue after successful perforation instead of being removed prematurely.
- Impact marks work on Valkyrien Skies ships when running a dedicated server.
- Ship impact-mark coordinates are transformed correctly for rendering.
- Impact-mark and spall visuals use corrected caliber handling, including heavy autocannon and small-medium calibers.

## [0.3.3] - Previous Release

### Added
- Copycat armor layer blocks with configurable max level (1-200)
- Material-aware terminal ballistics penetration system
- Armor integrity degradation mechanics
- Spall fragment generation on penetration
- Impact marks visualization
- `/cbctb` debug commands

[0.3.5]: https://github.com/MegiTicky/CBC-terminal-ballistics/compare/v0.3.4...v0.3.5
[0.3.4]: https://github.com/MegiTicky/CBC-terminal-ballistics/compare/v0.3.3...v0.3.4
[0.3.3]: https://github.com/MegiTicky/CBC-terminal-ballistics/releases/tag/v0.3.3
