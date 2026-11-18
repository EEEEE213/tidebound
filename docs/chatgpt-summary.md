I'm continuing a Minecraft modding project that I built with another AI assistant. Here is the full context. Please read it, then help me with what I ask next.

## The project: Tidebound
A NeoForge 1.21.1 Minecraft mod (Java 21, NeoForge 21.1.266). Public repo: https://github.com/EEEEE213/tidebound (local folder E:\Tidebound). Current version: 1.2.1.

### What the mod does
- **World generation:** new "Default" worlds are about 99% deep ocean, with one island (50-150 blocks across) per 1000x1000 area, at least ~450 blocks apart. A guardian-free "Home Isle" sits at 0,0, and players get a Ship Core on first join. It works through a custom density function and biome source that override the `minecraft:normal` world preset.
- **Island guardians:** each island has a miniboss (6 types: Hollow King, Thornback, Tidecaller, Broodmother, Hexweaver, Grave Titan). They are scaled vanilla mobs with boss bars and abilities (slam, leap, lightning, minions, enrage). The island's blocks can't be broken until its guardian dies; then a loot chest appears with island materials and Tide Shards (the upgrade currency).
- **Physics ships:** built on the Sable physics mod (Rapier rigid bodies, "sub-levels"). Using a Ship Core on water spawns a floating raft, and players can walk on and build on the moving ship. "Take the Helm" steers it (W/S throttle, A/D rudder, Sneak to let go).
  - Buoyancy uses the hull's column envelope against real water blocks.
  - The physics also handles water drag, cancels Sable's own damping (applied at the hull, so the ship doesn't pitch), and adds a stabiliser and heave damping.
  - Premade hulls "self-trim": they get extra buoyancy so the waterline stays under their lowest deck floor, which keeps the interiors dry.
- **Ship Core upgrades (premade designs):** Expanded Hull (a 41-block hull with a furnished lower deck), Gunwales, Mainmast & Sail, Keel & Rudder, Captain's Cabin, Crow's Nest (island locator on the HUD), Foremast & Jib, Iron Prow, Paddle Steamer. The final upgrade is **The Galleon**: a 77-block, three-masted pirate galleon with a Jolly Roger and three decks (main deck, gun deck with 30 cannons, and an orlop with cargo hold, armory, strongroom and brig), plus a great cabin, officers' cabins, a chart room and a galley.
  - Upgrades never replace player blocks. They only rebuild untouched premade blocks, and never remove a container that holds items.
  - Each ship keeps a per-ship record (ShipRegistry, keyed by the Sable sub-level UUID) of its upgrades, layout anchor and forward direction. A moved core therefore keeps its upgrades, and only the helm core runs physics.
- **Underwater darkness (client):** the fog darkens with depth, and below ~14 blocks a flickering "dying" head-lamp overlay switches on. This is built into the mod, not a shader, so it works with Sodium. Conduit Power or Night Vision cancels it.
- **Auto-updater:** at startup the mod checks the latest GitHub release, downloads the newer jar into mods/, and swaps it (the running jar is renamed out of the way; otherwise the swap happens on exit). It shows a chat notice, and can be disabled in config/tidebound-updater.properties.
- **Ship Core GUI:** lists the upgrades with cost icons and Install buttons. Hovering an upgrade shows a Blender-rendered preview of the ship with that upgrade.

### Tooling
- `tools/ship_design.py` and `tools/galleon_design.py` generate every upgrade's blueprint as JSON (`src/main/resources/data/tidebound/ship_blueprints/*.json`, deck coordinates; the core sits at (0,1,-2) facing north).
  - The designer guarantees every stage is one face-connected piece, adding "bridge" blocks where needed, because Sable splits disconnected parts into separate falling bodies.
  - It orders placements outward from the core and removals deepest-first.
- `tools/render/render_presets.py` (Blender 4.5, Cycles) renders each stage, a cross-section and a hero shot from the exact blueprint blocks, using real vanilla models and textures. `tools/render/make_images.py` makes the GUI previews and README cards.
- Tests: `./gradle.sh runGameTestServer` runs 8 headless game tests.
  - They cover floating, sailing, turning and every upgrade; guardian loot; a moved core keeping its ship; launches leaving water and bubble columns behind; the lower decks staying dry at every stage; no splitting; and a Sable split sanity check.
  - A scripted hidden-window, muted test client (`client/dev/AutoTest`, `tools/autotest/*.txt`) checks things in the real game.
- Releases: `sh tools/release.sh <version> "notes"` builds, tags, pushes and creates the GitHub release with the jar and the modpack.

### The modpack
A Modrinth pack (`Tidebound-<version>.mrpack`) with Tidebound bundled, plus Sable, Create, Create Aeronautics (with Simulated and Offroad), Create Propulsion: Simulated, Create Big Cannons, Xaero's Minimap and World Map, JEI, Jade, AppleSkin, Farmer's Delight, Aquaculture 2, Supplementaries, Comforts, Sodium, ModernFix, FerriteCore, EntityCulling, ImmediatelyFast and Essential (for hosting multiplayer). Let it Walk! and Sable Photomancy are left out because they aren't on Modrinth. It's installed in my Modrinth App profile "Tidebound-1.0.0", and I play it with my sister through Essential.

### History of fixes (so you don't repeat them)
- Sable's built-in damping capped ships at ~2 b/s and made them nose-dive. It's now cancelled live per ship, and `universal_drag` is set to 0 in the Overworld.
- Rafts launched in shallow water sat aground. Launching now needs 4+ blocks of water, and the helm shows an "aground" message.
- Flying pilots got left behind by the ship. Taking the helm now turns off creative flight.
- The bow dipped in the direction of travel. Fixed by applying the damping cancellation at the hull cells.
- Moving the core rebuilt and duplicated the hull. Fixed with the per-ship record.
- "Launch Ship" dragged bubble columns along. Sea blocks are now filtered out, and stray bubble columns are cleaned up.
- The lower deck flooded as upgrades added weight. Fixed with self-trimming buoyancy and a lift cap.
- Building the galleon in one tick flung the ship into the air, because Sable's body mass lags behind. Fixed with a settle ramp after upgrades and a rise/spin governor.
- 1.2.0's galleon broke apart (sail bellies, ropes, the bowsprit and some hull planks were only diagonally attached). Fixed in 1.2.1 by making every design one face-connected piece; the Launch Ship limit was also raised from 8000 to 20000 blocks. Ships already broken in 1.2.0 can't be repaired, so a new ship is needed.

### My preferences
I want real, good-looking content, tested and verified (tests run muted, with no window stealing focus). I like Blender-rendered previews, and I want designs reworked until they look right.
