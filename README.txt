CBC: Terminal Ballistics (NeoForge 1.21.1 Port)
===============================================

CBC: Terminal Ballistics is a Create: Big Cannons addon that changes how blocks
respond when hit by CBC projectiles.

This repository is a NeoForge 1.21.1 port of the original project
(MegiTicky/CBC-terminal-ballistics), with added Valkyrien Skies (Sable)
compatibility and Copycats+ framed armor block support.


Key Features
------------

Refined penetration mechanics:
  When blocks are hit by a projectile, the projectile may sometimes penetrate
  instead of breaking the block. A new material property, Ductility, has been
  added. It determines how many repeated hits it takes to break a block. For
  example, steel armor has high ductility, allowing it to take many hits
  before breaking.

Caliber matters:
  Heavy armor plates can now absorb hundreds of rapid-fire autocannon rounds
  without failing, requiring you to flank weaker, unprotected areas or use a
  stronger cannon that can punch through the plates at once. Higher-caliber
  cannons, such as big cannons, can deal substantial integrity damage to armor
  blocks and break them in two hits.

Projectile impact marks:
  Autocannons, small/small-medium cannons (CBCMS), medium cannons (CBCMW),
  and big cannons all produce different penetration holes, stopped dents, and
  ricochet marks. Impact marks have distinct visuals for metallic and generic
  surface materials.

Deadly spalling:
  When an AP shell penetrates a vehicle, it creates a deadly cone of flying
  metal fragments, or spall, inside the vehicle, damaging players and
  destroying internal components.

Armored copycat:
  Adds the Copycat Armor Layer and Framed Collapsible Copycat Armor Block.
  You can freely adjust their toughness with the Armor Upgrader item.

Valkyrien Skies / Sable compatibility (new in this port):
  Impact marks now correctly display on Sable physical structures (ships) and
  follow the structure as it moves and rotates. Spark particle effects on
  metallic armor hits. Spall cones also generate correctly inside physical
  structures.


Dependencies (NeoForge 1.21.1)
------------------------------

Required:
  - Create: 6.0.7-6.0.x (development build: 6.0.10-281)
  - Copycats+: 3.0.4+
  - Create Big Cannons: 5.10.x-5.11.7 (development build: 5.11.7)

Optional:
  - Valkyrien Skies (Sable): 1.2.2 or 2.0.3
    (All features work normally without it; install for impact marks that
     follow physical structures)
  - CBC More Shells: optional
  - CBC Modern Warfare: optional


Differences from the Original
-----------------------------

This port is based on MegiTicky's original CBC Terminal Ballistics.
Key changes:

  - Ported from Forge to NeoForge 1.21.1
  - Added Sable (Valkyrien Skies) compatibility for impact marks
  - Added Framed Collapsible Copycat Armor Block
  - Added spall cone visualization
  - Added armor impact spark particle effects
  - Improved Copycats+ integration


Sable compatibility notes
-------------------------

The same CBC: Terminal Ballistics jar supports both Sable 1.2.2 and the latest
tested release, Sable 2.0.3. Sable remains optional and is accessed through its
bundled Companion API, so separate builds are not required.

Sable 2.0.3 itself requires NeoForge 21.1.228+ and Create 6.0.10+. These are
requirements imposed by Sable; players staying on Sable 1.2.2 can keep their
existing compatible Create setup.


Credits
-------

Original Author: MegiTicky
  https://github.com/MegiTicky/CBC-terminal-ballistics

Port & additions: Erika

CBC: Terminal Ballistics is built as an addon for Create: Big Cannons.
Portions of the gameplay logic were developed with reference to the
Create: Big Cannons source code.
Some textures are based on, inspired by, or adapted from assets originally
created for Create and Create: Big Cannons.
Full credit for those assets and systems belongs to their respective authors.

The implementation and concept of the Copycat Armor Layer were partially
inspired by Create and Copycats+.
Portions of the code were developed with reference to the Copycats+ source code.

A huge thanks to the Create, Create: Big Cannons, and Copycats+ developers
for creating the projects that made this addon possible.


License
-------

CC BY-NC-SA 4.0 (Attribution-NonCommercial-ShareAlike 4.0 International)

  - Attribution: You must credit the original author (MegiTicky)
  - NonCommercial: Not for commercial use
  - ShareAlike: Modified versions must use the same license

Full license text: see LICENSE.txt
