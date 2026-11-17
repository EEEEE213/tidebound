package dev.tidebound.ship;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What a ship is, independent of where its Ship Core happens to sit: the upgrades it has, the anchor its premade
 * layouts are built around (the spot the first core stood on), its forward direction, and which core is the helm.
 * Keyed by the Sable sub-level's id, so a core can be broken and placed elsewhere on the ship without the ship
 * forgetting its upgrades or building a second hull next to the first.
 */
public class ShipRegistry extends SavedData {
    private static final String NAME = "tidebound_ships";

    public static final class Ship {
        public final Set<String> upgrades = new LinkedHashSet<>();
        public BlockPos anchor;
        public Direction facing;
        public BlockPos helm;
    }

    private final Map<UUID, Ship> ships = new HashMap<>();

    public static ShipRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(ShipRegistry::new, ShipRegistry::load, null), NAME);
    }

    public Ship get(UUID id) {
        return ships.get(id);
    }

    /** The ship's record, created from this core (its position, facing and upgrades) if the ship has none yet. */
    public Ship getOrCreate(UUID id, ShipCoreBlockEntity core) {
        Ship s = ships.get(id);
        if (s == null) {
            s = new Ship();
            s.upgrades.addAll(core.ownUpgrades());
            s.anchor = core.getBlockPos().immutable();
            s.facing = core.facing();
            s.helm = s.anchor;
            ships.put(id, s);
            setDirty();
        }
        return s;
    }

    public static ShipRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        ShipRegistry r = new ShipRegistry();
        CompoundTag all = tag.getCompound("ships");
        for (String key : all.getAllKeys()) {
            CompoundTag t = all.getCompound(key);
            Ship s = new Ship();
            ListTag ups = t.getList("upgrades", Tag.TAG_STRING);
            for (int i = 0; i < ups.size(); i++) s.upgrades.add(ups.getString(i));
            s.anchor = NbtUtils.readBlockPos(t, "anchor").orElse(BlockPos.ZERO);
            s.helm = NbtUtils.readBlockPos(t, "helm").orElse(s.anchor);
            s.facing = Direction.from2DDataValue(t.getInt("facing"));
            r.ships.put(UUID.fromString(key), s);
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        ships.forEach((id, s) -> {
            CompoundTag t = new CompoundTag();
            ListTag ups = new ListTag();
            for (String u : s.upgrades) ups.add(StringTag.valueOf(u));
            t.put("upgrades", ups);
            t.put("anchor", NbtUtils.writeBlockPos(s.anchor));
            t.put("helm", NbtUtils.writeBlockPos(s.helm));
            t.putInt("facing", s.facing.get2DDataValue());
            all.put(id.toString(), t);
        });
        tag.put("ships", all);
        return tag;
    }
}
