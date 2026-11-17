package dev.tidebound.island;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/** Per-world state of every island that has been visited: guardian alive/defeated. */
public class IslandData extends SavedData {
    public static final String NAME = "tidebound_islands";

    public static final class Entry {
        public boolean cleared;
        @Nullable public UUID boss;
        public int missingChecks;
    }

    private final Map<Long, Entry> islands = new HashMap<>();

    public static IslandData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(IslandData::new, IslandData::load, null), NAME);
    }

    public Entry entry(long key) {
        return islands.computeIfAbsent(key, k -> new Entry());
    }

    public boolean isCleared(long key) {
        Entry e = islands.get(key);
        return e != null && e.cleared;
    }

    public void setCleared(long key, boolean cleared) {
        Entry e = entry(key);
        e.cleared = cleared;
        if (cleared) e.boss = null;
        setDirty();
    }

    public int clearedCount() {
        int n = 0;
        for (Entry e : islands.values()) if (e.cleared) n++;
        return n;
    }

    public static IslandData load(CompoundTag tag, HolderLookup.Provider registries) {
        IslandData d = new IslandData();
        ListTag list = tag.getList("islands", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Entry e = new Entry();
            e.cleared = t.getBoolean("cleared");
            if (t.hasUUID("boss")) e.boss = t.getUUID("boss");
            d.islands.put(t.getLong("key"), e);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        islands.forEach((k, e) -> {
            CompoundTag t = new CompoundTag();
            t.putLong("key", k);
            t.putBoolean("cleared", e.cleared);
            if (e.boss != null) t.putUUID("boss", e.boss);
            list.add(t);
        });
        tag.put("islands", list);
        return tag;
    }
}
