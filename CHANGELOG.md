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
