package dev.tidebound.island;

import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The island guardians: giant, buffed vanilla mobs (1.21 scale attribute) with their own abilities, run by
 * {@link IslandGuardians}. Vanilla models keep them looking right without custom art.
 */
public enum BossType {
    HOLLOW_KING("hollow_king", EntityType.WITHER_SKELETON, 2.2, 190, 11, 0.30, Items.NETHERITE_SWORD,
            EntityType.SKELETON, BossEvent.BossBarColor.WHITE, true, false, false),
    THORNBACK("thornback", EntityType.RAVAGER, 1.45, 260, 14, 0.30, null,
            EntityType.VINDICATOR, BossEvent.BossBarColor.RED, false, true, false),
    TIDECALLER("tidecaller", EntityType.DROWNED, 2.5, 210, 10, 0.27, Items.TRIDENT,
            EntityType.DROWNED, BossEvent.BossBarColor.BLUE, true, false, true),
    BROODMOTHER("broodmother", EntityType.SPIDER, 2.7, 180, 9, 0.34, null,
            EntityType.CAVE_SPIDER, BossEvent.BossBarColor.PURPLE, false, true, false),
    HEXWEAVER("hexweaver", EntityType.EVOKER, 1.9, 170, 6, 0.36, null,
            EntityType.VINDICATOR, BossEvent.BossBarColor.YELLOW, false, false, true),
    GRAVE_TITAN("grave_titan", EntityType.HUSK, 2.9, 240, 12, 0.25, Items.IRON_AXE,
            EntityType.HUSK, BossEvent.BossBarColor.GREEN, true, true, false);

    public final String id;
    public final EntityType<? extends Mob> type;
    public final double scale;
    public final double health;
    public final double damage;
    public final double speed;
    public final Item weapon;
    public final EntityType<? extends Mob> minion;
    public final BossEvent.BossBarColor color;
    public final boolean slams;
    public final boolean leaps;
    public final boolean lightning;

    BossType(String id, EntityType<? extends Mob> type, double scale, double health, double damage, double speed, Item weapon,
             EntityType<? extends Mob> minion, BossEvent.BossBarColor color, boolean slams, boolean leaps, boolean lightning) {
        this.id = id;
        this.type = type;
        this.scale = scale;
        this.health = health;
        this.damage = damage;
        this.speed = speed;
        this.weapon = weapon;
        this.minion = minion;
        this.color = color;
        this.slams = slams;
        this.leaps = leaps;
        this.lightning = lightning;
    }

    public String nameKey() {
        return "boss.tidebound." + id;
    }

    public static BossType forRoll(int roll) {
        BossType[] v = values();
        return v[Math.floorMod(roll, v.length)];
    }

    public static BossType byId(String id) {
        for (BossType t : values()) if (t.id.equals(id)) return t;
        return null;
    }
}
