# CBC Terminal Ballistics

CBC Terminal Ballistics (CBCTB) is a Create Big Cannons add-on for Minecraft 1.20.1 (Forge) that replaces CBC's simple armor check with material-aware terminal ballistics: every shell impact is resolved against the material it actually strikes, producing perforations, embeds, ricochets and shatters, with accumulated armor integrity damage, spall fragments and persistent impact marks.

- Source: <https://github.com/MegiTicky/CBC-terminal-ballistics> (branch `vs2.3`)
- Current version: **0.4.2-vs2.3** — see [CHANGELOG.md](CHANGELOG.md)
- Author: Erika · License: [CC BY-NC-SA 4.0](LICENSE.txt)

> This is the `vs2.3` line of the mod: a legacy build targeting **Valkyrien Skies 2.3** and the older Create Big Cannons stack. It is not the current CBC / Create line.

## Features

- **Material-aware terminal ballistics** — impacts are resolved per block from the struck material's stats (toughness, brittleness, ductility, spall multiplier). Shells perforate, embed, ricochet or shatter instead of passing a single pass/fail roll, and lose mass and velocity accordingly.
- **Armor integrity** — blocks accumulate damage from repeated hits and break once their threshold is exceeded. CBC's Cannon Welder repairs blocks (right-click clears impact marks, embedded shell visuals and damage; shift-click removes embedded shells only).
- **Spall** — penetrating hits throw a fragment cone scaled by material, caliber and shell type. Fragments ricochet inside compartments ("bouncing spall"), so interior walls no longer fully shield a crew; right-clicking with ballistic goggles replays a recent spall cone's path in-world.
- **Ricochet** — shells bounce off sufficiently tough armor with configurable mass loss, depending on toughness, velocity and impact angle.
- **Impact marks & embedded shells** — persistent impact decals and embedded shell visuals that follow VS ships and resynchronize with joining players. Embedding can be permanent or time-limited.
- **Copycat armor** — copycat armor layer blocks and framed collapsible copycat armor with a configurable maximum level (1–200), applied with the Armor Upgrader tool.
- **Artillery & Terminal View (hitcam)** — a cinematic chase camera follows shells in flight, and impacts open a slow-motion terminal x-ray (shell path, plate outcomes, spall, crew hits) with a verdict (CRITICAL HIT / HIT / RICOCHET / NO PENETRATION / DETONATION). Includes critical-hit damage reports, multi-shell burst accumulation, salvo ghost shells, terrain streaming with Distant Horizons modes, **Ballistic Goggles** (link up to 16 cannon mounts, ship-anchored) and **Spotter Goggles**.
- **Create contraption collision (optional)** — with `hitCreateContraptions` enabled (default off), projectiles hit moving Create contraption blocks with full CBCTB ballistics, including contraptions on Valkyrien Skies ships and Create Interactive ship frames.
- **Datapack support** — pack authors can make projectiles phase through specified blocks and override material stats; examples live in [`datapack example/`](datapack%20example/).
- **Debug tooling** — the `/cbctb` command suite, a Ballistic Test Launcher item and an Advanced Block Armor Inspection Tool item.

## Requirements

| Dependency | Version range | Required |
|------------|---------------|----------|
| Minecraft | 1.20.1 | yes |
| Forge | 47.x | yes |
| Java | 17 | yes |
| Create | 0.5.1.j, below 0.6.0 | yes |
| Copycats | 2.2.2, below 3.0.0 | yes |
| Create Big Cannons | 5.8.2, below 5.9.0 | yes |
| Valkyrien Skies | 2.3.0-beta.5, below 2.4.0 | optional (needed for ship features) |
| CBC: More Shells (`cbcmoreshells`) | 0.2.2, below 0.3.0 | optional |
| CBC Modern Warfare (`cbcmodernwarfare`) | 0.0.6c, below 0.0.7 | optional |
| `rha` | 1.1.4 or newer | optional |
| `s_a_b` | 1.4.3 or newer | optional |

These match the dependency ranges declared in `src/main/resources/META-INF/mods.toml`.

## Commands

All `/cbctb` subcommands require operator permission level 2.

| Command | What it does |
|---------|--------------|
| `/cbctb` | Status summary: armor level config, material mappings, loaded compatibility mods. |
| `/cbctb inspect` | Full terminal-ballistics readout of the targeted block (material, integrity, last impact, spall, shatter). |
| `/cbctb clear` | Clear CBCTB integrity data and embedded shells at the targeted block. |
| `/cbctb projectile_slow [factor]` | Slow projectiles down for testing; `reset` restores normal speed. |
| `/cbctb marks refresh\|delete` | Resynchronize or delete all impact marks. |
| `/cbctb shells refresh\|delete` | Resynchronize or delete all embedded shells. |
| `/cbctb checkarmor [fix]` | Scan loaded chunks for copycat armor above the configured max level; `fix` clamps exceeded levels. |
| `/cbctb hitcam [stop]` | Replay (or stop) the hitcam replay of the last projectile flight. |
| `/cbctb goggles list\|clear\|debug` | List or clear the held goggles' cannon-mount links, or toggle the goggles debug overlay. |
| `/cbctb testray [distance]` | Fire a debug ray from your view and report what contraption geometry it hits. |
| `/cbctb viz [seconds]` | Wireframe overlay of the exact contraption geometry the collision tests. |
| `/cbctb trace [seconds]` | Log every contraption shell raycast while active. |
| `/cbctb shelldebug [seconds]` | Per-shell contraption verdict overlay (OUTCOME / SKIP / MISS / JAM). |
| `/cbctb classes` | Debug dump of key CBC and RitchiesProjectileLib classes. |

## Configuration highlights

The mod uses Forge configs with a **server** side (gameplay) and a **client** side (rendering preferences).

Server sections: `general`, `projectiles`, `armor_integrity`, `spalling`, `ricochet`, `visuals`, `copycat_armor`, `hitcam`. Client sections: `hitcam`, `hitcam_xray`.

Some notable options:

- `general.enabled` — master switch for the terminal-ballistics override (default on).
- `general.hitCreateContraptions` — projectiles collide with Create contraption blocks, including on VS ships (default **off**).
- `spalling.globalSpallMultiplier`, `spalling.maxSpallFragmentsPerImpact` — spall volume tuning.
- `spalling.spallRicochet` (default on), `spalling.spallMaxBounces`, `spalling.spallRicochetChance`, `spalling.spallRicochetHeadOn` — bouncing spall; setting `spallRicochet` to false restores the legacy single-ray spall.
- `ricochet.minToughness` — blocks below this toughness absorb shells instead of bouncing them.
- `visuals.embeddedShellLifetimeTicks` — embedded shell lifetime in ticks; 0 (default) keeps them permanently.
- `copycat_armor.maxLevel` — maximum copycat armor level (default 20, range 1–200).
- `hitcam.hitcamGoggleMatchRadius` — how close to a linked cannon mount a shell must spawn to open the goggle views (default 48).
- Client `hitcam.pipDisplayWidth` / `hitcam.pipDisplayHeight` — picture-in-picture window size; client `hitcam.terrainStream` / `hitcam.terminalStream` — per-client terrain streaming preferences.

## Documentation

- [CHANGELOG.md](CHANGELOG.md) — release history.
- [docs/CONTRAPTION_COLLISION_HISTORY.md](docs/CONTRAPTION_COLLISION_HISTORY.md) — how Create contraption collision (world, ships, Create Interactive) was implemented, attempt by attempt.
- [`datapack example/`](datapack%20example/) — example datapacks for ignored blocks and material stat overrides.

## License & credits

- CBCTB is licensed under [CC BY-NC-SA 4.0](LICENSE.txt) (Attribution-NonCommercial-ShareAlike).
- By Erika. Published on CurseForge as **CBC: Terminal Ballistics (Offical)**.
- Built on the [Minecraft Forge MDK](README.txt); `CREDITS.txt` and `changelog.txt` in this repository are the MDK's own Forge files.
- Requires and builds on Create, Create Big Cannons, Copycats and Valkyrien Skies by their respective authors.
