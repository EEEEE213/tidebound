package dev.tidebound.island;

import dev.ryanhcode.sable.Sable;
import dev.tidebound.Tidebound;
import dev.tidebound.world.ArchipelagoBiomeSource;
import dev.tidebound.world.IslandLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Island guardians: spawns each island's miniboss when a player sails close, runs its abilities and boss bar,
 * keeps it on its island, protects the island's blocks until it dies, then frees the island and leaves loot.
 */
public final class IslandGuardians {
    public static final String TAG_BOSS = "tidebound_boss";
    public static final String TAG_MINION = "tidebound_minion";
    private static final String NBT_ISLAND = "tidebound_island";
    private static final String NBT_TYPE = "tidebound_boss_type";
    public static final int TRIGGER_MARGIN = 48;

    private static final class Active {
        final Mob mob;
        final BossType type;
        final IslandLayout.Island island;
        final ServerBossEvent bar;
        int age;
        boolean enraged;

        Active(Mob mob, BossType type, IslandLayout.Island island) {
            this.mob = mob;
            this.type = type;
            this.island = island;
            this.bar = new ServerBossEvent(mob.getDisplayName(), type.color, BossEvent.BossBarOverlay.NOTCHED_10);
        }
    }

    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    private IslandGuardians() {
    }

    /** True if the world's overworld is the Tidebound archipelago. */
    public static boolean isArchipelago(Level level) {
        return level instanceof ServerLevel sl && level.dimension() == Level.OVERWORLD
                && sl.getChunkSource().getGenerator().getBiomeSource() instanceof ArchipelagoBiomeSource;
    }

    public static BossType bossFor(IslandLayout.Island is) {
        return BossType.forRoll(is.bossRoll());
    }

    public static boolean isProtected(ServerLevel level, BlockPos pos) {
        if (!isArchipelago(level) || pos.getY() < 10) return false;
        if (Sable.HELPER.getContaining(level, pos) != null) return false; // ship blocks are never island blocks
        IslandLayout layout = IslandLayout.get();
        IslandLayout.Island is = layout.nearest(pos.getX() + 0.5, pos.getZ() + 0.5);
        if (is.home()) return false;
        if (layout.shapeT(is, pos.getX() + 0.5, pos.getZ() + 0.5) > 1.3) return false;
        return !IslandData.get(level).isCleared(is.key());
    }

    // ------------------------------------------------------------- spawning

    @Nullable
    public static Mob spawnGuardian(ServerLevel level, IslandLayout.Island is) {
        BossType type = bossFor(is);
        BlockPos top = surface(level, is.x(), is.z());
        Mob mob = type.type.create(level);
        if (mob == null) return null;
        mob.moveTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, level.random.nextFloat() * 360, 0);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(top), MobSpawnType.EVENT, null);
        mob.setCustomName(Component.translatable(type.nameKey()).append(" of " + is.name()).withStyle(ChatFormatting.GOLD));
        mob.setCustomNameVisible(true);
        mob.setPersistenceRequired();
        mob.addTag(TAG_BOSS);
        CompoundTag data = mob.getPersistentData();
        data.putLong(NBT_ISLAND, is.key());
        data.putString(NBT_TYPE, type.id);
        setBase(mob, Attributes.SCALE, type.scale);
        setBase(mob, Attributes.MAX_HEALTH, type.health);
        setBase(mob, Attributes.ATTACK_DAMAGE, type.damage);
        setBase(mob, Attributes.MOVEMENT_SPEED, type.speed);
        setBase(mob, Attributes.KNOCKBACK_RESISTANCE, 0.85);
        setBase(mob, Attributes.FOLLOW_RANGE, 48);
        setBase(mob, Attributes.ARMOR, 8);
        setBase(mob, Attributes.STEP_HEIGHT, 1.5);
        setBase(mob, Attributes.SAFE_FALL_DISTANCE, 12);
        mob.setHealth(mob.getMaxHealth());
        mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, -1, 0, false, false));
        if (type.weapon != null) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(type.weapon));
            mob.setDropChance(EquipmentSlot.MAINHAND, 0);
        }
        if (type == BossType.GRAVE_TITAN) {
            mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            mob.setDropChance(EquipmentSlot.HEAD, 0);
        }
        level.addFreshEntity(mob);
        level.playSound(null, top, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.7f, 1.4f);
        IslandData data2 = IslandData.get(level);
        IslandData.Entry e = data2.entry(is.key());
        e.boss = mob.getUUID();
        e.missingChecks = 0;
        data2.setDirty();
        track(mob);
        return mob;
    }

    private static void setBase(Mob mob, Holder<net.minecraft.world.entity.ai.attributes.Attribute> attr, double v) {
        AttributeInstance inst = mob.getAttribute(attr);
        if (inst != null) inst.setBaseValue(v);
    }

    public static BlockPos surface(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, Math.max(y, level.getSeaLevel()), z);
    }

    private static void track(Mob mob) {
        if (ACTIVE.containsKey(mob.getUUID())) return;
        CompoundTag data = mob.getPersistentData();
        BossType type = BossType.byId(data.getString(NBT_TYPE));
        if (type == null) return;
        long key = data.getLong(NBT_ISLAND);
        IslandLayout.Island is = IslandLayout.get().island((int) (key >> 32), (int) key);
        ACTIVE.put(mob.getUUID(), new Active(mob, type, is));
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof Mob mob && mob.getTags().contains(TAG_BOSS)) {
            track(mob);
        }
    }

    // ---------------------------------------------------------------- ticking

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isArchipelago(level)) return;
        if (level.getGameTime() % 20 == 0) checkPlayers(level);
        tickBosses(level);
    }

    private static void checkPlayers(ServerLevel level) {
        IslandLayout layout = IslandLayout.get();
        IslandData data = IslandData.get(level);
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            IslandLayout.Island is = layout.nearest(player.getX(), player.getZ());
            if (is.home()) continue;
            double d = Math.hypot(player.getX() - is.x(), player.getZ() - is.z());
            if (d > is.radius() + TRIGGER_MARGIN) continue;
            IslandData.Entry e = data.entry(is.key());
            if (e.cleared) continue;
            if (!level.hasChunkAt(new BlockPos(is.x(), 64, is.z()))) continue;
            if (e.boss != null) {
                Entity existing = level.getEntity(e.boss);
                if (existing != null && existing.isAlive()) {
                    e.missingChecks = 0;
                    continue;
                }
                if (++e.missingChecks < 5) continue; // give the chunk a moment to load its entities
            }
            Mob mob = spawnGuardian(level, is);
            if (mob != null) {
                announce(level, is, Component.translatable("message.tidebound.guardian_awakens", mob.getDisplayName())
                        .withStyle(ChatFormatting.RED));
            }
        }
    }

    private static void tickBosses(ServerLevel level) {
        List<UUID> dead = new ArrayList<>();
        for (Map.Entry<UUID, Active> entry : ACTIVE.entrySet()) {
            Active a = entry.getValue();
            Mob mob = a.mob;
            if (mob.level() != level) continue;
            if (mob.isRemoved() || !mob.isAlive()) {
                a.bar.removeAllPlayers();
                dead.add(entry.getKey());
                continue;
            }
            a.age++;
            a.bar.setProgress(mob.getHealth() / mob.getMaxHealth());
            if (a.age % 10 == 0) updateBar(level, a);
            if (a.age % 20 == 0) leash(level, a);
            abilities(level, a);
        }
        dead.forEach(ACTIVE::remove);
    }

    private static void updateBar(ServerLevel level, Active a) {
        for (ServerPlayer p : level.players()) {
            boolean near = p.distanceToSqr(a.mob) < 72 * 72 && p.isAlive();
            if (near && !a.bar.getPlayers().contains(p)) a.bar.addPlayer(p);
            else if (!near && a.bar.getPlayers().contains(p)) a.bar.removePlayer(p);
        }
    }

    private static void leash(ServerLevel level, Active a) {
        Mob mob = a.mob;
        IslandLayout.Island is = a.island;
        double d = Math.hypot(mob.getX() - is.x(), mob.getZ() - is.z());
        double limit = is.radius() * 0.85 + 4;
        if (d > is.radius() + 14 || (mob.isInWater() && a.type != BossType.TIDECALLER && d > limit)) {
            BlockPos top = surface(level, is.x(), is.z());
            mob.teleportTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
            mob.getNavigation().stop();
        } else if (d > limit) {
            mob.getNavigation().moveTo(is.x() + 0.5, mob.getY(), is.z() + 0.5, 1.2);
        }
        if (mob.getTarget() == null || !mob.getTarget().isAlive()) {
            Player p = level.getNearestPlayer(mob, 40);
            if (p != null && !p.isCreative() && !p.isSpectator()) mob.setTarget(p);
        }
    }

    private static void abilities(ServerLevel level, Active a) {
        Mob mob = a.mob;
        BossType type = a.type;
        LivingEntity target = mob.getTarget();
        RandomSource rnd = mob.getRandom();

        if (!a.enraged && mob.getHealth() < mob.getMaxHealth() * 0.35f) {
            a.enraged = true;
            AttributeInstance spd = mob.getAttribute(Attributes.MOVEMENT_SPEED);
            if (spd != null) spd.setBaseValue(type.speed * 1.3);
            level.playSound(null, mob.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.5f, 0.7f);
            level.sendParticles(ParticleTypes.ANGRY_VILLAGER, mob.getX(), mob.getEyeY(), mob.getZ(), 12, 1, 0.5, 1, 0);
            a.bar.setColor(BossEvent.BossBarColor.RED);
        }
        int pace = a.enraged ? 2 : 1;
        if (target == null) return;
        double dist = mob.distanceTo(target);

        if (type.slams && a.age % (160 / pace) == 0 && dist < 7) {
            slam(level, mob, 6.5, 7 + type.damage * 0.3);
        }
        if (type.leaps && a.age % (110 / pace) == 55 && dist > 5 && dist < 20 && mob.onGround()) {
            Vec3 to = target.position().subtract(mob.position());
            Vec3 h = new Vec3(to.x, 0, to.z).normalize().scale(Math.min(1.6, dist * 0.12));
            mob.setDeltaMovement(h.x, 0.75, h.z);
            mob.hurtMarked = true;
            level.playSound(null, mob.blockPosition(), SoundEvents.SPIDER_AMBIENT, SoundSource.HOSTILE, 1.5f, 0.5f);
        }
        if (type.lightning && a.age % (200 / pace) == 100 && dist < 30) {
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
            if (bolt != null) {
                Vec3 at = target.position().add(rnd.nextGaussian() * 1.5, 0, rnd.nextGaussian() * 1.5);
                bolt.moveTo(at);
                level.addFreshEntity(bolt);
            }
        }
        if (a.age % (300 / pace) == 150 && dist < 32) {
            summon(level, a, 2 + (a.enraged ? 1 : 0));
        }
    }

    private static void slam(ServerLevel level, Mob mob, double radius, double damage) {
        level.playSound(null, mob.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.2f, 0.6f);
        level.sendParticles(ParticleTypes.EXPLOSION, mob.getX(), mob.getY() + 0.3, mob.getZ(), 6, radius * 0.4, 0.2, radius * 0.4, 0);
        level.sendParticles(ParticleTypes.CLOUD, mob.getX(), mob.getY() + 0.2, mob.getZ(), 40, radius * 0.5, 0.1, radius * 0.5, 0.05);
        DamageSource src = mob.damageSources().mobAttack(mob);
        for (Player p : level.getEntitiesOfClass(Player.class, mob.getBoundingBox().inflate(radius))) {
            if (p.isCreative() || p.isSpectator()) continue;
            p.hurt(src, (float) damage);
            Vec3 push = p.position().subtract(mob.position());
            Vec3 h = new Vec3(push.x, 0, push.z).normalize().scale(1.4);
            p.push(h.x, 0.6, h.z);
            p.hurtMarked = true;
        }
    }

    private static void summon(ServerLevel level, Active a, int count) {
        Mob mob = a.mob;
        AABB area = mob.getBoundingBox().inflate(24);
        int alive = level.getEntitiesOfClass(Mob.class, area, m -> m.getTags().contains(TAG_MINION)).size();
        for (int i = 0; i < count && alive < 6; i++, alive++) {
            Mob minion = a.type.minion.create(level);
            if (minion == null) continue;
            double ang = mob.getRandom().nextDouble() * Math.PI * 2;
            int x = (int) Math.floor(mob.getX() + Math.cos(ang) * 3), z = (int) Math.floor(mob.getZ() + Math.sin(ang) * 3);
            BlockPos top = surface(level, x, z);
            minion.moveTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, (float) Math.toDegrees(ang), 0);
            minion.finalizeSpawn(level, level.getCurrentDifficultyAt(top), MobSpawnType.MOB_SUMMONED, null);
            minion.addTag(TAG_MINION);
            if (mob.getTarget() != null) minion.setTarget(mob.getTarget());
            level.addFreshEntity(minion);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, minion.getX(), minion.getY() + 0.5, minion.getZ(), 10, 0.3, 0.5, 0.3, 0.02);
        }
        level.playSound(null, mob.blockPosition(), SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1.2f, 0.8f);
    }

    /** Guardians don't burn in daylight, drown, or get hurt by their own lightning, falls or minions. */
    @SubscribeEvent
    public static void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!event.getEntity().getTags().contains(TAG_BOSS)) return;
        DamageSource src = event.getSource();
        if (src.is(net.minecraft.tags.DamageTypeTags.IS_FIRE) || src.is(net.minecraft.tags.DamageTypeTags.IS_LIGHTNING)
                || src.is(net.minecraft.tags.DamageTypeTags.IS_FALL) || src.is(net.minecraft.tags.DamageTypeTags.IS_DROWNING)
                || src.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL)) {
            event.setCanceled(true);
            return;
        }
        Entity attacker = src.getEntity();
        if (attacker != null && (attacker.getTags().contains(TAG_MINION) || attacker.getTags().contains(TAG_BOSS))) {
            event.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ death

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !mob.getTags().contains(TAG_BOSS)) return;
        if (!(mob.level() instanceof ServerLevel level)) return;
        long key = mob.getPersistentData().getLong(NBT_ISLAND);
        IslandLayout.Island is = IslandLayout.get().island((int) (key >> 32), (int) key);
        freeIsland(level, is, mob.blockPosition());
        Active a = ACTIVE.remove(mob.getUUID());
        if (a != null) a.bar.removeAllPlayers();
        // the minions lose heart
        for (Mob m : level.getEntitiesOfClass(Mob.class, mob.getBoundingBox().inflate(48), m -> m.getTags().contains(TAG_MINION))) {
            m.kill();
        }
    }

    /** Marks the island cleared, drops the loot cache and tells nearby players. */
    public static void freeIsland(ServerLevel level, IslandLayout.Island is, BlockPos near) {
        IslandData data = IslandData.get(level);
        if (data.isCleared(is.key())) return;
        data.setCleared(is.key(), true);
        BlockPos chestPos = placeLootCache(level, is, near);
        level.playSound(null, chestPos, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1, 1);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, chestPos.getX() + 0.5, chestPos.getY() + 1, chestPos.getZ() + 0.5,
                60, 0.6, 1.2, 0.6, 0.3);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(is.x(), p.getY(), is.z()) > 200 * 200) continue;
            p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("title.tidebound.freed").withStyle(ChatFormatting.GOLD)));
            p.connection.send(new ClientboundSetSubtitleTextPacket(Component.translatable("title.tidebound.freed.sub", is.name())));
        }
    }

    private static void announce(ServerLevel level, IslandLayout.Island is, Component msg) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(is.x(), p.getY(), is.z()) < 250 * 250) p.sendSystemMessage(msg);
        }
    }

    // ------------------------------------------------------------------ loot

    private static BlockPos placeLootCache(ServerLevel level, IslandLayout.Island is, BlockPos near) {
        BlockPos pos = surface(level, near.getX(), near.getZ());
        if (!level.getBlockState(pos).canBeReplaced()) pos = pos.above();
        level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            // name first: applying components resets the container contents
            chest.applyComponents(net.minecraft.core.component.DataComponentMap.builder().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.translatable("container.tidebound.island_cache", is.name())).build(), net.minecraft.core.component.DataComponentPatch.EMPTY);
            List<ItemStack> loot = lootFor(level, is);
            for (int i = 0; i < loot.size() && i < chest.getContainerSize(); i++) chest.setItem(i, loot.get(i));
            chest.setChanged();
        }
        return pos;
    }

    public static List<ItemStack> lootFor(ServerLevel level, IslandLayout.Island is) {
        RandomSource r = RandomSource.create(is.key() ^ level.getSeed());
        List<ItemStack> out = new ArrayList<>();
        int size = is.radius();
        Item log = logFor(level, is);
        out.add(new ItemStack(log, 32 + size / 3));
        out.add(new ItemStack(log, 16 + r.nextInt(16)));
        out.add(new ItemStack(Items.COBBLESTONE, 48 + r.nextInt(17)));
        out.add(new ItemStack(Items.IRON_INGOT, 6 + r.nextInt(6) + size / 12));
        out.add(new ItemStack(Items.COAL, 10 + r.nextInt(10)));
        out.add(new ItemStack(Items.COPPER_INGOT, 6 + r.nextInt(10)));
        out.add(new ItemStack(Items.WHITE_WOOL, 12 + r.nextInt(12)));
        out.add(new ItemStack(Items.STRING, 4 + r.nextInt(8)));
        out.add(new ItemStack(Items.GLASS, 6 + r.nextInt(6)));
        out.add(new ItemStack(Items.COOKED_COD, 6 + r.nextInt(6)));
        if (r.nextFloat() < 0.5f) out.add(new ItemStack(Items.IRON_BLOCK, 1 + r.nextInt(2)));
        if (r.nextFloat() < 0.4f) out.add(new ItemStack(Items.GOLD_INGOT, 3 + r.nextInt(5)));
        if (r.nextFloat() < 0.25f) out.add(new ItemStack(Items.DIAMOND, 1 + r.nextInt(2)));
        out.add(new ItemStack(Tidebound.TIDE_SHARD.get(), 2 + size / 30 + r.nextInt(2)));
        return out;
    }

    private static Item logFor(ServerLevel level, IslandLayout.Island is) {
        Holder<Biome> b = level.getBiome(new BlockPos(is.x(), 70, is.z()));
        ResourceKey<Biome> k = b.unwrapKey().orElse(Biomes.FOREST);
        if (k == Biomes.BIRCH_FOREST) return Items.BIRCH_LOG;
        if (k == Biomes.TAIGA || k == Biomes.SNOWY_TAIGA || k == Biomes.OLD_GROWTH_PINE_TAIGA || k == Biomes.WINDSWEPT_FOREST) return Items.SPRUCE_LOG;
        if (k == Biomes.JUNGLE || k == Biomes.SPARSE_JUNGLE) return Items.JUNGLE_LOG;
        if (k == Biomes.SAVANNA) return Items.ACACIA_LOG;
        if (k == Biomes.CHERRY_GROVE) return Items.CHERRY_LOG;
        if (k == Biomes.DARK_FOREST) return Items.DARK_OAK_LOG;
        return Items.OAK_LOG;
    }

    // ------------------------------------------------------------ protection

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getPlayer().isCreative()) return;
        if (isProtected(level, event.getPos())) {
            event.setCanceled(true);
            IslandLayout.Island is = IslandLayout.get().nearest(event.getPos().getX(), event.getPos().getZ());
            event.getPlayer().displayClientMessage(Component.translatable("message.tidebound.protected",
                    Component.translatable(bossFor(is).nameKey()), is.name()).withStyle(ChatFormatting.RED), true);
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (event.getEntity() instanceof Player p && p.isCreative()) return;
        // placing is allowed (pillars and bridges help in a fight), but not replacing island blocks
        if (isProtected(level, event.getPos()) && !event.getBlockSnapshot().getState().canBeReplaced()
                && !event.getBlockSnapshot().getState().isAir()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        event.getAffectedBlocks().removeIf(p -> isProtected(level, p));
    }

    @SubscribeEvent
    public static void onServerStop(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        ACTIVE.values().forEach(a -> a.bar.removeAllPlayers());
        ACTIVE.clear();
    }
}
