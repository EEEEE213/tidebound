package dev.tidebound.ship;

import dev.tidebound.Tidebound;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * The premade ship upgrades bought at the Ship Core. Each one builds a fixed block layout onto the ship
 * (never replacing blocks the player put there) and may improve the ship's handling.
 */
public enum ShipUpgrade {
    EXPANDED_HULL("expanded_hull", Set.of(), 0, 1.0, 1.0, 1.0, false,
            List.of(cost(ItemTags.PLANKS, 128, "Any planks"), cost(ItemTags.LOGS, 32, "Any logs"))),
    GUNWALES("gunwales", Set.of("expanded_hull"), 0, 1.0, 1.0, 1.1, false,
            List.of(cost(ItemTags.PLANKS, 48, "Any planks"), cost(Items.STICK, 24))),
    MAINMAST("mainmast", Set.of("expanded_hull"), 5, 1.0, 1.0, 1.0, false,
            List.of(cost(ItemTags.LOGS, 32, "Any logs"), cost(ItemTags.WOOL, 48, "Any wool"), shards(1))),
    KEEL("keel", Set.of("expanded_hull"), 0, 1.0, 1.6, 2.0, false,
            List.of(cost(ItemTags.LOGS, 24, "Any logs"), cost(Items.IRON_INGOT, 12), shards(1))),
    CABIN("cabin", Set.of("expanded_hull"), 0, 1.0, 1.0, 1.0, false,
            List.of(cost(ItemTags.PLANKS, 128, "Any planks"), cost(Items.GLASS, 8), cost(Items.IRON_INGOT, 4), shards(2))),
    CROWS_NEST("crows_nest", Set.of("mainmast"), 0, 1.0, 1.0, 1.0, true,
            List.of(cost(ItemTags.PLANKS, 24, "Any planks"), cost(ItemTags.LOGS, 8, "Any logs"), shards(1))),
    FOREMAST("foremast", Set.of("expanded_hull", "mainmast"), 4, 1.0, 1.0, 1.0, false,
            List.of(cost(ItemTags.LOGS, 24, "Any logs"), cost(ItemTags.WOOL, 32, "Any wool"), shards(2))),
    IRON_PROW("iron_prow", Set.of("expanded_hull"), 2, 1.0, 1.0, 1.3, false,
            List.of(cost(Items.IRON_INGOT, 16), cost(Items.IRON_BARS, 8), cost(Items.COBBLESTONE, 16), shards(2))),
    PADDLE_STEAMER("paddle_steamer", Set.of("keel", "cabin"), 8, 1.8, 1.2, 1.0, false,
            List.of(cost(Items.IRON_INGOT, 32), cost(Items.COPPER_INGOT, 16), cost(Items.COBBLESTONE, 48), shards(4)));

    public final String id;
    public final Set<String> requires;
    public final double speedBonus;
    public final double accelMul;
    public final double turnMul;
    public final double stabilityMul;
    public final boolean locator;
    public final List<Cost> costs;
    private Blueprint blueprint;

    ShipUpgrade(String id, Set<String> requires, double speedBonus, double accelMul, double turnMul, double stabilityMul,
                boolean locator, List<Cost> costs) {
        this.id = id;
        this.requires = requires;
        this.speedBonus = speedBonus;
        this.accelMul = accelMul;
        this.turnMul = turnMul;
        this.stabilityMul = stabilityMul;
        this.locator = locator;
        this.costs = costs;
    }

    public synchronized Blueprint blueprint() {
        if (blueprint == null) blueprint = Blueprint.load(id);
        return blueprint;
    }

    public String nameKey() {
        return "upgrade.tidebound." + id;
    }

    public String descKey() {
        return "upgrade.tidebound." + id + ".desc";
    }

    public static ShipUpgrade byId(String id) {
        for (ShipUpgrade u : values()) if (u.id.equals(id)) return u;
        return null;
    }

    // ------------------------------------------------------------------ costs

    public record Cost(Ingredient ingredient, int count, String label) {
        public boolean matches(ItemStack stack) {
            return ingredient.test(stack);
        }

        public ItemStack icon() {
            ItemStack[] items = ingredient.getItems();
            return items.length == 0 ? ItemStack.EMPTY : items[0].copyWithCount(1);
        }
    }

    private static Cost cost(TagKey<Item> tag, int count, String label) {
        return new Cost(Ingredient.of(tag), count, label);
    }

    private static Cost cost(Item item, int count) {
        return new Cost(Ingredient.of(item), count, null);
    }

    private static Cost shards(int count) {
        return new Cost(Ingredient.of(TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(Tidebound.MODID, "tide_shards"))),
                count, "Tide Shard");
    }

    // ------------------------------------------------------------- blueprints

    /** The starter raft (7 x 11). Layouts are generated by tools/ship_design.py into data/tidebound/ship_blueprints. */
    private static Blueprint raft;

    public static synchronized Blueprint raft() {
        if (raft == null) raft = Blueprint.load("raft");
        return raft;
    }
}
