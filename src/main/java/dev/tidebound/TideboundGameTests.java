package dev.tidebound;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.tidebound.island.IslandData;
import dev.tidebound.island.IslandGuardians;
import dev.tidebound.ship.ShipBuilder;
import dev.tidebound.ship.ShipCoreBlockEntity;
import dev.tidebound.ship.ShipUpgrade;
import dev.tidebound.world.IslandLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import org.slf4j.Logger;

/** Headless end-to-end tests: a raft floating and sailing on real water, every upgrade, and a guardian fight. */
@GameTestHolder(Tidebound.MODID)
@PrefixGameTestTemplate(false)
public final class TideboundGameTests {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int DEPTH = 12;

    private TideboundGameTests() {
    }

    /** A big forced-loaded pool of still water far from the test grid. Returns the water surface height. */
    private static BlockPos pool(ServerLevel level, int originX, int sizeX, int sizeZ) {
        BlockPos probe = new BlockPos(originX, 0, 0);
        level.getChunkAt(probe);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, probe.getX(), probe.getZ()) - 1;
        BlockPos pad = new BlockPos(probe.getX(), ground, 0);
        for (int cx = -(sizeX / 32) - 2; cx <= sizeX / 32 + 2; cx++) {
            for (int cz = -3; cz <= sizeZ / 16 + 3; cz++) {
                level.setChunkForced((pad.getX() >> 4) + cx, cz, true);
            }
        }
        for (int x = -sizeX / 2 - 1; x <= sizeX / 2 + 1; x++) {
            for (int z = -21; z <= sizeZ + 1; z++) {
                boolean wall = x == -sizeX / 2 - 1 || x == sizeX / 2 + 1 || z == -21 || z == sizeZ + 1;
                level.setBlock(pad.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 1; y <= DEPTH; y++) {
                    level.setBlock(pad.offset(x, y, z), wall ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState(), 2);
                }
            }
        }
        return pad.above(DEPTH); // the top water block; the surface is at its top face
    }

    private static double tiltDegrees(Pose3dc pose) {
        Vector3d up = pose.orientation().transform(new Vector3d(0, 1, 0));
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, up.y))));
    }

    private static double yawOf(Pose3dc pose, Direction facing) {
        Vector3d f = pose.orientation().transform(new Vector3d(facing.getStepX(), 0, facing.getStepZ()));
        return Math.toDegrees(Math.atan2(f.x, f.z));
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void raftFloatsSailsTurnsAndTakesEveryUpgrade(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = pool(level, 50 * 96, 110, 460);
        double surface = top.getY() + 1.0;
        List<Component> errors = new ArrayList<>();
        ServerSubLevel ship = ShipBuilder.launchRaft(level, top.above().south(40), Direction.SOUTH, errors);
        if (ship == null) {
            h.fail("raft launch failed: " + errors);
            return;
        }
        UUID pilot = UUID.randomUUID();
        ShipCoreBlockEntity[] core = {null};
        Vec3[] start = {null};
        double[] startYaw = {0};
        double[] maxTilt = {0};
        int[] t = {0};
        StringBuilder report = new StringBuilder();

        h.onEachTick(() -> {
            t[0]++;
            if (core[0] == null) {
                for (var be : ship.getPlot().getBlockEntityActors()) {
                    if (be instanceof ShipCoreBlockEntity c) core[0] = c;
                }
                if (core[0] == null) {
                    if (t[0] > 40) h.fail("no ship core on the sub-level");
                    return;
                }
            }
            ShipCoreBlockEntity c = core[0];
            Pose3dc pose = ship.logicalPose();
            Vec3 at = ShipBuilder.worldCenter(level, c.getBlockPos());
            double tilt = tiltDegrees(pose);
            if (t[0] > 60) maxTilt[0] = Math.max(maxTilt[0], tilt);

            if (t[0] > 320 && t[0] < 520 && t[0] % 5 == 0) {
                Vector3d up = pose.orientation().transform(new Vector3d(0, 1, 0));
                Vector3d fw = pose.orientation().transform(new Vector3d(c.facing().getStepX(), 0, c.facing().getStepZ()));
                LOG.info("[tidebound-test] diag t={} y={} pitch={} rollish={} speed={} sub={}", t[0], fmt(at.y - surface),
                        fmt(Math.toDegrees(Math.asin(fw.y))), fmt(Math.toDegrees(Math.atan2(up.x, up.y))), fmt(c.lastForwardSpeed), fmt(c.lastSubmerged));
            }
            if (t[0] % 20 == 0) {
                LOG.info("[tidebound-test] t={} core=({}, {}, {}) surface={} tilt={} yaw={} speed={} submerged={} cells={} mass={}",
                        t[0], fmt(at.x), fmt(at.y), fmt(at.z), surface, fmt(tilt), fmt(yawOf(pose, c.facing())),
                        fmt(c.lastForwardSpeed), fmt(c.lastSubmerged), c.hullCells().length / 3, fmt(ship.getMassTracker().getMass()));
            }

            // phase 1 (0-120): settle on the water
            if (t[0] == 120) {
                double coreAbove = at.y - surface;
                report.append(String.format("settled: core %.2f above water, tilt %.1f; ", coreAbove, tilt));
                if (coreAbove < 0.4 || coreAbove > 2.6) h.fail("raft not floating right: core " + coreAbove + " above the surface");
                start[0] = at;
                startYaw[0] = yawOf(pose, c.facing());
            }
            // phase 2 (120-320): raft full ahead for 10 s (starter top speed 7 b/s)
            if (t[0] > 120 && t[0] <= 320) c.setInput(pilot, 1, 0);
            if (t[0] == 320) {
                double run = at.subtract(start[0]).horizontalDistance();
                report.append(String.format("raft ahead 10s: %.1f blocks, speed %.2f; ", run, c.lastForwardSpeed));
                if (run < 45) h.fail("raft too slow under full throttle: " + run);
            }
            // phase 3 (320-400): coast and install every premade upgrade
            if (t[0] > 320 && t[0] <= 460) c.setInput(pilot, 0, 0);
            if (t[0] == 330) {
                int blocks = 0;
                for (ShipUpgrade u : ShipUpgrade.values()) {
                    blocks += ShipBuilder.buildUpgrade(level, c, u);
                    c.install(u);
                }
                report.append(String.format("all upgrades: %d blocks built; ", blocks));
                if (blocks < 2000) h.fail("upgrades built too few blocks: " + blocks);
            }
            if (t[0] == 460) {
                double coreAbove = at.y - surface;
                report.append(String.format("upgraded ship: core %.2f above water, tilt %.1f, speed %.2f; ", coreAbove, tilt, c.lastForwardSpeed));
                if (coreAbove < -0.2 || coreAbove > 3.5) h.fail("upgraded ship floats wrong: " + coreAbove);
                start[0] = at;
            }
            // phase 4 (460-620): the full ship, 8 s ahead (top speed 26 b/s)
            if (t[0] > 460 && t[0] <= 620) c.setInput(pilot, 1, 0);
            if (t[0] == 620) {
                double run = at.subtract(start[0]).horizontalDistance();
                report.append(String.format("upgraded ahead 8s: %.1f blocks, speed %.2f; ", run, c.lastForwardSpeed));
                if (run < 100) h.fail("upgraded ship too slow: " + run);
                startYaw[0] = yawOf(pose, c.facing());
            }
            // phase 5 (620-720): hard to starboard, then reverse throttle to stop
            if (t[0] > 620 && t[0] <= 720) c.setInput(pilot, 0, 1);
            if (t[0] == 720) {
                double turned = Math.abs(net.minecraft.util.Mth.wrapDegrees(yawOf(pose, c.facing()) - startYaw[0]));
                report.append(String.format("turned %.0f deg in 5s; ", turned));
                if (turned < 60) h.fail("rudder did not turn the ship enough: " + turned);
            }
            if (t[0] > 720 && t[0] <= 800) c.setInput(pilot, -1, 0);
            if (t[0] == 800) {
                report.append(String.format("after 4s astern: speed %.2f, max tilt %.1f", c.lastForwardSpeed, maxTilt[0]));
                LOG.info("[tidebound-test] REPORT {}", report);
                if (maxTilt[0] > 35) h.fail("ship rolled too far: " + maxTilt[0]);
                h.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void guardianDiesAndFreesItsIsland(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = pool(level, 54 * 96, 30, 30);
        // dry land on top of the pool for the fight
        for (int x = -12; x <= 12; x++) for (int z = -6; z <= 18; z++) level.setBlock(top.offset(x, 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        IslandLayout layout = IslandLayout.get();
        IslandLayout.Island real = layout.island(7, 9);
        IslandLayout.Island fake = new IslandLayout.Island(real.cellX(), real.cellZ(), top.getX(), top.getZ() + 6, 40, 20,
                real.biomeRoll(), real.bossRoll(), false);
        IslandData.get(level).setCleared(fake.key(), false);
        Mob boss = IslandGuardians.spawnGuardian(level, fake);
        if (boss == null) {
            h.fail("guardian did not spawn");
            return;
        }
        double scale = boss.getAttributeValue(Attributes.SCALE);
        LOG.info("[tidebound-test] guardian {} hp {} scale {} at {}", boss.getName().getString(), boss.getMaxHealth(), scale, boss.blockPosition());
        if (scale < 1.4 || boss.getMaxHealth() < 150) h.fail("guardian is not boss-sized");
        int[] t = {0};
        h.onEachTick(() -> {
            t[0]++;
            if (t[0] == 40) boss.kill();
            if (t[0] == 60) {
                boolean cleared = IslandData.get(level).isCleared(fake.key());
                ChestBlockEntity chest = null;
                BlockPos bp = boss.blockPosition();
                for (BlockPos p : BlockPos.betweenClosed(new BlockPos(bp.getX() - 3, -64, bp.getZ() - 3), new BlockPos(bp.getX() + 3, 90, bp.getZ() + 3))) {
                    if (level.getBlockEntity(p) instanceof ChestBlockEntity c) chest = c;
                }
                int shards = 0, stacks = 0;
                if (chest != null) {
                    for (int i = 0; i < chest.getContainerSize(); i++) {
                        if (!chest.getItem(i).isEmpty()) stacks++;
                        if (chest.getItem(i).is(Tidebound.TIDE_SHARD.get())) shards += chest.getItem(i).getCount();
                    }
                }
                LOG.info("[tidebound-test] cleared={} chest={} stacks={} shards={}", cleared, chest != null, stacks, shards);
                if (!cleared) h.fail("island not freed");
                else if (chest == null || stacks < 8 || shards < 2) h.fail("no proper loot cache");
                else h.succeed();
            }
        });
    }

    /** Diagnostics: the full ship at rest, then coasting from 8 b/s with and without damping compensation. */
    private static final UUID PROBE_PILOT = UUID.randomUUID();

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void probeUpgradedShip(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = pool(level, 58 * 96, 110, 300);
        double surface = top.getY() + 1.0;
        ServerSubLevel ship = ShipBuilder.launchRaft(level, top.above().south(40), Direction.SOUTH, new ArrayList<>());
        if (ship == null) {
            h.fail("no raft");
            return;
        }
        ShipCoreBlockEntity[] core = {null};
        int[] t = {0};
        h.onEachTick(() -> {
            t[0]++;
            if (core[0] == null) {
                for (var be : ship.getPlot().getBlockEntityActors()) if (be instanceof ShipCoreBlockEntity c) core[0] = c;
                if (core[0] == null) return;
                for (ShipUpgrade u : ShipUpgrade.values()) {
                    ShipBuilder.buildUpgrade(level, core[0], u);
                    core[0].install(u);
                }
            }
            ShipCoreBlockEntity c = core[0];
            Pose3dc pose = ship.logicalPose();
            var handle = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(ship);
            // full throttle ahead from t=200, full astern from t=500
            if (t[0] >= 200) c.setInput(PROBE_PILOT, t[0] < 500 ? 1 : -1, 0);
            if (t[0] % 10 == 0) {
                Vector3d up = pose.orientation().transform(new Vector3d(0, 1, 0));
                Vector3d fw = pose.orientation().transform(new Vector3d(c.facing().getStepX(), 0, c.facing().getStepZ()));
                Vector3d v = handle.getLinearVelocity(new Vector3d());
                Vector3d w = handle.getAngularVelocity(new Vector3d());
                var com = ship.getMassTracker().getCenterOfMass();
                LOG.info("[tidebound-probe] t={} damp={} y={} pitch={} roll={} vWorld=({}, {}, {}) w=({}, {}, {}) fwdSpeed={} sub={} mass={} com=({}, {}, {}) corePlot={}",
                        t[0], fmt(c.physics.nativeDamping), fmt(ShipBuilder.worldCenter(level, c.getBlockPos()).y - surface),
                        fmt(Math.toDegrees(Math.asin(fw.y))), fmt(Math.toDegrees(Math.atan2(up.x, up.y))),
                        fmt(v.x), fmt(v.y), fmt(v.z), fmt(w.x), fmt(w.y), fmt(w.z), fmt(c.lastForwardSpeed), fmt(c.lastSubmerged),
                        fmt(ship.getMassTracker().getMass()), fmt(com.x()), fmt(com.y()), fmt(com.z()), c.getBlockPos().toShortString());
            }
            if (t[0] == 1) LOG.info("[tidebound-probe] universal drag = {}", dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData.getUniversalDrag(level));
            if (t[0] == 800) {
                dev.tidebound.ship.ShipPhysics.testDragScale = 1.0;
                h.succeed();
            }
        });
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    /** Writes every ship stage's blocks (after neighbour shape updates) to run/blueprints.json for the preset renders. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void exportBlueprints(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos core = new BlockPos(-30000, 200, 30000);
        java.util.Map<BlockPos, Integer> stageOf = new java.util.LinkedHashMap<>();
        List<String> stages = new ArrayList<>();
        stages.add("raft");
        List<java.util.Map.Entry<BlockPos, net.minecraft.world.level.block.state.BlockState>> raft = new ArrayList<>(ShipUpgrade.raft().placed(core, Direction.NORTH));
        raft.add(java.util.Map.entry(core, Tidebound.SHIP_CORE.get().defaultBlockState()));
        for (var e : raft) {
            level.setBlock(e.getKey(), e.getValue(), 2 | 16);
            stageOf.put(e.getKey(), 0);
        }
        for (ShipUpgrade u : ShipUpgrade.values()) {
            stages.add(u.id);
            for (var e : u.blueprint().placed(core, Direction.NORTH)) {
                if (!level.getBlockState(e.getKey()).isAir()) continue; // upgrades never replace blocks
                level.setBlock(e.getKey(), e.getValue(), 2 | 16);
                stageOf.put(e.getKey(), stages.size() - 1);
            }
        }
        StringBuilder json = new StringBuilder("{\"stages\": [");
        for (int i = 0; i < stages.size(); i++) json.append(i > 0 ? "," : "").append('"').append(stages.get(i)).append('"');
        json.append("], \"blocks\": [\n");
        boolean first = true;
        for (var e : stageOf.entrySet()) {
            BlockPos p = e.getKey();
            var st = net.minecraft.world.level.block.Block.updateFromNeighbourShapes(level.getBlockState(p), level, p);
            BlockPos r = p.subtract(core);
            json.append(first ? "" : ",\n").append(String.format("[%d,%d,%d,%d,\"%s\"]", r.getX(), r.getY(), r.getZ(), e.getValue(),
                    stateString(st)));
            first = false;
        }
        json.append("]}\n");
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("blueprints.json"), json);
        } catch (java.io.IOException ex) {
            throw new RuntimeException(ex);
        }
        LOG.info("exported {} blocks in {} stages", stageOf.size(), stages.size());
        h.succeed();
    }

    private static String stateString(net.minecraft.world.level.block.state.BlockState st) {
        StringBuilder b = new StringBuilder(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
        if (!st.getValues().isEmpty()) {
            b.append('[');
            boolean f = true;
            for (var e : st.getValues().entrySet()) {
                b.append(f ? "" : ",").append(e.getKey().getName()).append('=').append(net.minecraft.Util.getPropertyName(e.getKey(), e.getValue()));
                f = false;
            }
            b.append(']');
        }
        return b.toString();
    }

    /** Moving the Ship Core: the new core keeps the ship's upgrades, builds around the original anchor, and only one core drives. */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void movedCoreKeepsTheShip(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = pool(level, 66 * 96, 110, 200);
        double surface = top.getY() + 1.0;
        ServerSubLevel ship = ShipBuilder.launchRaft(level, top.above().south(60), Direction.SOUTH, new ArrayList<>());
        if (ship == null) {
            h.fail("no raft");
            return;
        }
        ShipCoreBlockEntity[] first = {null}, second = {null};
        BlockPos[] secondPos = {null};
        int[] t = {0};
        h.onEachTick(() -> {
            t[0]++;
            if (first[0] == null) {
                for (var be : ship.getPlot().getBlockEntityActors()) if (be instanceof ShipCoreBlockEntity c) first[0] = c;
                return;
            }
            ShipCoreBlockEntity c1 = first[0];
            if (t[0] == 20) {
                ShipBuilder.buildUpgrade(level, c1, ShipUpgrade.EXPANDED_HULL);
                c1.install(ShipUpgrade.EXPANDED_HULL);
            }
            if (t[0] == 120) {
                // place a new core 4 blocks aft on the deck (facing a different way), then break the old one
                Direction fwd = c1.facing();
                secondPos[0] = c1.getBlockPos().relative(fwd.getOpposite(), 4).relative(fwd.getClockWise(), 2);
                level.setBlock(secondPos[0], Tidebound.SHIP_CORE.get().defaultBlockState()
                        .setValue(dev.tidebound.ship.ShipCoreBlock.FACING, fwd.getClockWise()), 3);
                level.setBlock(c1.getBlockPos(), Blocks.AIR.defaultBlockState(), 3);
            }
            if (t[0] == 160) {
                if (!(level.getBlockEntity(secondPos[0]) instanceof ShipCoreBlockEntity c2)) {
                    h.fail("second core missing");
                    return;
                }
                second[0] = c2;
                if (!c2.has(ShipUpgrade.EXPANDED_HULL)) h.fail("moved core forgot the Expanded Hull");
                if (!c2.isHelm()) h.fail("moved core did not take over as the helm");
                if (c2.shipFacing() != c1.facing()) h.fail("ship forward changed with the core's facing");
                if (!c2.anchor().equals(c1.getBlockPos())) h.fail("layout anchor moved with the core");
                int placed = ShipBuilder.buildUpgrade(level, c2, ShipUpgrade.GUNWALES);
                c2.install(ShipUpgrade.GUNWALES);
                // a gunwale post must stand on the hull's side, measured from the ORIGINAL core spot
                BlockPos post = dev.tidebound.ship.Blueprint.rel(6, 2, -10).rotate(dev.tidebound.ship.Blueprint.rotationFor(c1.facing())).offset(c1.getBlockPos());
                LOG.info("[tidebound-test] moved core: gunwales placed {}, post {} is {}", placed, post, level.getBlockState(post));
                if (!level.getBlockState(post).is(Blocks.SPRUCE_FENCE)) h.fail("gunwales built in the wrong place: " + level.getBlockState(post));
            }
            if (t[0] == 400) {
                double y = ShipBuilder.worldCenter(level, secondPos[0]).y - surface;
                LOG.info("[tidebound-test] moved core: second core {} above water", y);
                if (y < 0.5 || y > 5) h.fail("ship floats wrong after moving the core: " + y);
                h.succeed();
            }
        });
    }

    /** Launching a hull that sits on a bubble column must not drag the column (or the magma under it) along. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void launchLeavesTheSeaBehind(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = pool(level, 74 * 96, 40, 60);
        BlockPos deck = top.above().south(20);
        // a magma block on the pool floor feeding a bubble column up to the surface
        for (int y = 1; y < 12; y++) level.setBlock(deck.below(y), Blocks.BUBBLE_COLUMN.defaultBlockState(), 2);
        level.setBlock(deck.below(12), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) level.setBlock(deck.offset(dx, 0, dz), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        level.setBlock(deck.above(), Tidebound.SHIP_CORE.get().defaultBlockState(), 3);
        List<Component> errors = new ArrayList<>();
        ServerSubLevel ship = ShipBuilder.launchFromCore(level, deck.above(), errors);
        if (ship == null) {
            h.fail("launch failed: " + errors);
            return;
        }
        var box = ship.getPlot().getBoundingBox();
        int sea = 0, blocks = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++)
            for (int y = box.minY(); y <= box.maxY(); y++)
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    var st = level.getBlockState(p.set(x, y, z));
                    if (st.isAir()) continue;
                    blocks++;
                    if (ShipBuilder.isSea(st)) sea++;
                }
        LOG.info("[tidebound-test] launch over a bubble column: {} blocks on the ship, {} sea blocks", blocks, sea);
        if (sea > 0) h.fail("the ship took " + sea + " sea blocks with it");
        else if (blocks != 26) h.fail("expected 25 planks + core, got " + blocks);
        else h.succeed();
    }
}
