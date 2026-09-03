# Changelog

All notable changes to CBC Terminal Ballistics will be documented in this file.

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
