package dev.tidebound.ship;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.tidebound.Tidebound;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Building ships: launching the starter raft, assembling a player-built ship, installing upgrades. */
public final class ShipBuilder {
    public static final int MAX_SHIP_BLOCKS = 8000;

    private ShipBuilder() {
    }

    /** Where a ship block is in the world right now (handles blocks on a Sable sub-level). */
    public static Vec3 worldCenter(Level level, BlockPos pos) {
        Vec3 c = Vec3.atCenterOf(pos);
        SubLevelAccess sub = Sable.HELPER.getContaining(level, pos);
        return sub == null ? c : sub.logicalPose().transformPosition(c);
    }

    @Nullable
    public static ServerSubLevel subLevelOf(ServerLevel level, BlockPos pos) {
        SubLevelAccess sub = Sable.HELPER.getContaining(level, pos);
        return sub instanceof ServerSubLevel s ? s : null;
    }

    /**
     * Builds the starter raft on the water surface and turns it into a physics ship.
     * {@code deckCenter} is the air block just above the water where the middle of the deck goes.
     */
    @Nullable
    /** Blocks of water needed under every part of the starter raft. */
    public static final int RAFT_MIN_DEPTH = 4;

    public static ServerSubLevel launchRaft(ServerLevel level, BlockPos deckCenter, Direction facing, List<Component> errors) {
        BlockPos core = deckCenter.offset(Blueprint.rel(0, 0, 0).multiply(-1).rotate(Blueprint.rotationFor(facing)));
        List<Map.Entry<BlockPos, BlockState>> raft = ShipUpgrade.raft().placed(core, facing);
        for (Map.Entry<BlockPos, BlockState> e : raft) {
            if (!level.getBlockState(e.getKey()).canBeReplaced()) {
                errors.add(Component.translatable("message.tidebound.raft_blocked"));
                return null;
            }
        }
        if (!level.getBlockState(core).canBeReplaced()) {
            errors.add(Component.translatable("message.tidebound.raft_blocked"));
            return null;
        }
        // A raft launched in the shallows sits on the seabed and can't float off: insist on open water.
        int surface = deckCenter.getY() - 1;
        for (Map.Entry<BlockPos, BlockState> e : raft) {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(e.getKey().getX(), surface, e.getKey().getZ());
            int water = 0;
            while (water < RAFT_MIN_DEPTH && level.getFluidState(p).is(net.minecraft.tags.FluidTags.WATER)) {
                water++;
                p.move(0, -1, 0);
            }
            if (water < RAFT_MIN_DEPTH) {
                errors.add(Component.translatable("message.tidebound.raft_shallow", RAFT_MIN_DEPTH));
                return null;
            }
        }
        List<BlockPos> blocks = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : raft) {
            level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_CLIENTS);
            blocks.add(e.getKey());
        }
        level.setBlock(core, Tidebound.SHIP_CORE.get().defaultBlockState().setValue(ShipCoreBlock.FACING, facing), Block.UPDATE_ALL);
        blocks.add(core);
        return assemble(level, core, blocks);
    }

    private static ServerSubLevel assemble(ServerLevel level, BlockPos anchor, List<BlockPos> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : blocks) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        BoundingBox3i bounds = new BoundingBox3i(minX, minY, minZ, maxX, maxY, maxZ);
        return SubLevelAssemblyHelper.assembleBlocks(level, anchor, blocks, bounds);
    }

    private static boolean holdsItems(ServerLevel level, BlockPos p) {
        return level.getBlockEntity(p) instanceof net.minecraft.world.Container c && !c.isEmpty();
    }

    /** Water, bubble columns and sea-floor life never become part of a ship (or a launch drags the sea along). */
    public static boolean isSea(BlockState s) {
        return s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock || s.is(Blocks.BUBBLE_COLUMN)
                || s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT) || s.is(Blocks.SEAGRASS) || s.is(Blocks.TALL_SEAGRASS)
                || s.is(Blocks.SEA_PICKLE) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.SOUL_SAND)
                || s.is(BlockTags.CORALS) || s.is(BlockTags.CORAL_BLOCKS) || s.is(BlockTags.WALL_CORALS);
    }

    /** Clears bubble columns a ship picked up: they can't work on a ship and just hang under the hull. */
    public static void removeStrayWater(ServerLevel level, SubLevel sub) {
        var box = sub.getPlot().getBoundingBox();
        if (box == null || box.maxX() < box.minX()) return;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int removed = 0;
        for (int x = box.minX(); x <= box.maxX(); x++)
            for (int y = box.minY(); y <= box.maxY(); y++)
                for (int z = box.minZ(); z <= box.maxZ(); z++)
                    if (level.getBlockState(p.set(x, y, z)).is(Blocks.BUBBLE_COLUMN)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                        removed++;
                    }
        if (removed > 0) org.slf4j.LoggerFactory.getLogger("tidebound").info("removed {} bubble column blocks from a ship", removed);
    }

    /** Terrain never becomes part of a ship when assembling from a core built on land or a jetty. */
    public static boolean isTerrain(BlockState s) {
        return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.GRAVEL)
                || s.is(Blocks.GRASS_BLOCK) || s.is(BlockTags.LEAVES) || s.is(Blocks.BEDROCK) || s.is(BlockTags.ICE)
                || s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.CLAY) || s.is(BlockTags.CORAL_BLOCKS)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.SEAGRASS)
                || s.is(Blocks.TALL_SEAGRASS) || s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT) || s.is(BlockTags.FLOWERS)
                || !s.getFluidState().isEmpty() && s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock;
    }

    /** Turns everything connected to a core that sits in the world (not yet on a ship) into a physics ship. */
    @Nullable
    public static ServerSubLevel launchFromCore(ServerLevel level, BlockPos core, List<Component> errors) {
        if (subLevelOf(level, core) != null) {
            errors.add(Component.translatable("message.tidebound.already_ship"));
            return null;
        }
        SubLevelAssemblyHelper.GatherResult gathered = SubLevelAssemblyHelper.gatherConnectedBlocks(core, level, MAX_SHIP_BLOCKS,
                (from, fromState, to, toState, dir) -> !isTerrain(toState) && !isSea(toState));
        if (gathered.assemblyState() != SubLevelAssemblyHelper.GatherResult.State.SUCCESS || gathered.blocks() == null) {
            errors.add(Component.translatable(gathered.assemblyState() == SubLevelAssemblyHelper.GatherResult.State.TOO_MANY_BLOCKS
                    ? "message.tidebound.too_big" : "message.tidebound.nothing", MAX_SHIP_BLOCKS));
            return null;
        }
        return SubLevelAssemblyHelper.assembleBlocks(level, core, gathered.blocks(), gathered.boundingBox());
    }

    /**
     * Builds an upgrade's premade layout around the core. Existing blocks are never replaced (the player's own
     * builds win), so the result is always safe. Returns how many blocks were placed.
     */
    public static int buildUpgrade(ServerLevel level, ShipCoreBlockEntity core, ShipUpgrade upgrade) {
        BlockPos corePos = core.anchor();  // layouts stay put even if the core was moved
        Direction facing = core.shipFacing();
        List<Map.Entry<BlockPos, BlockState>> blocks = new ArrayList<>(upgrade.blueprint().placed(corePos, facing));
        blocks.sort(Comparator.comparingDouble(e -> e.getKey().distSqr(corePos)));
        SubLevel sub = subLevelOf(level, corePos);
        // Blocks the raft or another upgrade put there, still untouched, may be rebuilt; anything else is the player's.
        Map<BlockPos, BlockState> premade = new java.util.HashMap<>();
        for (var e : ShipUpgrade.raft().placed(corePos, facing)) premade.put(e.getKey(), e.getValue());
        for (ShipUpgrade other : ShipUpgrade.values()) {
            if (other == upgrade || !core.has(other)) continue;
            for (var e : other.blueprint().placed(corePos, facing)) premade.put(e.getKey(), e.getValue());
        }
        List<BlockPos> placed = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : blocks) {
            BlockPos p = e.getKey();
            if (p.equals(corePos) || level.getBlockEntity(p) instanceof ShipCoreBlockEntity) continue;
            if (sub != null) sub.getPlot().expandIfNecessary(p);
            BlockState here = level.getBlockState(p);
            // premade and untouched: the same block the raft or an earlier upgrade put there (shape and
            // connections may differ), and never a chest or barrel that still holds something
            BlockState was = premade.get(p);
            boolean ours = was != null && here.is(was.getBlock()) && !holdsItems(level, p);
            if (e.getValue().isAir()) {
                if (ours) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                continue;
            }
            if (!here.canBeReplaced() && !ours) continue;
            if (here.equals(e.getValue())) continue;
            level.setBlock(p, e.getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            placed.add(p);
        }
        // connect fences / panes / stairs to their new neighbours
        for (BlockPos p : placed) {
            BlockState s = level.getBlockState(p);
            BlockState shaped = Block.updateFromNeighbourShapes(s, level, p);
            if (shaped != s) level.setBlock(p, shaped, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        core.markHullDirty();
        return placed.size();
    }
}
