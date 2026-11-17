package dev.tidebound.ship;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A premade block layout, authored in "deck coordinates" for a ship facing NORTH:
 * r = blocks to the right (east), y = height above the deck (deck = 0), f = blocks forward (north).
 * The Ship Core sits at deck (0, 1, -2), so offsets are stored relative to the core.
 */
public final class Blueprint {
    public static final int CORE_F = -2;

    private final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();

    public static Blueprint of(Consumer<Blueprint> builder) {
        Blueprint b = new Blueprint();
        builder.accept(b);
        return b;
    }

    /** Loads data/tidebound/ship_blueprints/<stage>.json: [[r, y, f, "block[state]"], ...] (air clears a cell). */
    public static Blueprint load(String stage) {
        String path = "/data/tidebound/ship_blueprints/" + stage + ".json";
        try (var in = Blueprint.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            Blueprint b = new Blueprint();
            for (var e : json.getAsJsonArray("blocks")) {
                var a = e.getAsJsonArray();
                BlockState st = net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(), a.get(3).getAsString(), false).blockState();
                b.put(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), st);
            }
            return b;
        } catch (Exception ex) {
            throw new IllegalStateException("bad ship blueprint " + stage, ex);
        }
    }

    public static BlockPos rel(int r, int y, int f) {
        return new BlockPos(r, y - 1, -(f - CORE_F));
    }

    public Blueprint put(int r, int y, int f, BlockState state) {
        blocks.put(rel(r, y, f), state);
        return this;
    }

    public Blueprint putIfAbsent(int r, int y, int f, BlockState state) {
        blocks.putIfAbsent(rel(r, y, f), state);
        return this;
    }

    public boolean has(int r, int y, int f) {
        return blocks.containsKey(rel(r, y, f));
    }

    public Map<BlockPos, BlockState> blocks() {
        return blocks;
    }

    public static Rotation rotationFor(Direction facing) {
        return switch (facing) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** Block positions and states for a core at {@code core} facing {@code facing}. */
    public List<Map.Entry<BlockPos, BlockState>> placed(BlockPos core, Direction facing) {
        Rotation rot = rotationFor(facing);
        List<Map.Entry<BlockPos, BlockState>> out = new ArrayList<>(blocks.size());
        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            out.add(Map.entry(core.offset(e.getKey().rotate(rot)), e.getValue().rotate(rot)));
        }
        return out;
    }
}
