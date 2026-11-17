package dev.tidebound.net;

import dev.tidebound.island.IslandData;
import dev.tidebound.island.IslandGuardians;
import dev.tidebound.ship.ShipBuilder;
import dev.tidebound.ship.ShipCoreBlockEntity;
import dev.tidebound.ship.ShipUpgrade;
import dev.tidebound.world.IslandLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/** Server side of the Ship Core: helm, buying upgrades, launching, and the pilot HUD feed. */
public final class ShipServer {
    public static final double USE_RANGE = 8.0;
    private static final Map<UUID, BlockPos> PILOTS = new HashMap<>();

    private ShipServer() {
    }

    /** How far a pilot can be from the core before the helm lets go. */
    public static final double PILOT_RANGE = 16.0;

    @Nullable
    private static ShipCoreBlockEntity core(ServerPlayer player, BlockPos pos) {
        return core(player, pos, USE_RANGE);
    }

    @Nullable
    private static ShipCoreBlockEntity core(ServerPlayer player, BlockPos pos, double range) {
        ServerLevel level = player.serverLevel();
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof ShipCoreBlockEntity core)) return null;
        Vec3 at = ShipBuilder.worldCenter(level, pos);
        if (at.distanceToSqr(player.getEyePosition()) > range * range) return null;
        return core;
    }

    public static void handleAction(ServerPlayer player, TideboundNetwork.ShipAction msg) {
        ShipCoreBlockEntity core = core(player, msg.core());
        if (core == null) return;
        switch (msg.action()) {
            case TideboundNetwork.ShipAction.TAKE_HELM -> takeHelm(player, core);
            case TideboundNetwork.ShipAction.RELEASE_HELM -> releaseHelm(player);
            case TideboundNetwork.ShipAction.BUY -> buy(player, core, ShipUpgrade.byId(msg.arg()));
            case TideboundNetwork.ShipAction.LAUNCH -> {
                List<Component> errors = new ArrayList<>();
                if (ShipBuilder.launchFromCore(player.serverLevel(), msg.core(), errors) != null) {
                    player.displayClientMessage(Component.translatable("message.tidebound.launched"), true);
                } else if (!errors.isEmpty()) {
                    player.displayClientMessage(errors.get(0).copy().withStyle(ChatFormatting.RED), true);
                }
            }
            default -> {
            }
        }
    }

    public static void takeHelm(ServerPlayer player, ShipCoreBlockEntity core) {
        if (ShipBuilder.subLevelOf(player.serverLevel(), core.getBlockPos()) == null) {
            player.displayClientMessage(Component.translatable("message.tidebound.not_a_ship").withStyle(ChatFormatting.RED), true);
            return;
        }
        UUID other = core.pilot();
        if (other != null && !other.equals(player.getUUID())) {
            player.displayClientMessage(Component.translatable("message.tidebound.helm_taken").withStyle(ChatFormatting.RED), true);
            return;
        }
        if (player.getAbilities().flying) {
            player.getAbilities().flying = false; // a flying pilot gets left behind by the ship
            player.onUpdateAbilities();
        }
        core.claimHelm(); // the core you steer from is the one that drives the ship
        PILOTS.put(player.getUUID(), core.getBlockPos().immutable());
        core.setInput(player.getUUID(), 0, 0);
        PacketDistributor.sendToPlayer(player, new TideboundNetwork.HelmState(core.getBlockPos(), true));
    }

    public static void releaseHelm(ServerPlayer player) {
        BlockPos pos = PILOTS.remove(player.getUUID());
        if (pos != null && player.serverLevel().getBlockEntity(pos) instanceof ShipCoreBlockEntity core) {
            core.releaseHelm(player.getUUID());
        }
        PacketDistributor.sendToPlayer(player, new TideboundNetwork.HelmState(pos == null ? BlockPos.ZERO : pos, false));
    }

    public static void handleInput(ServerPlayer player, TideboundNetwork.HelmInput msg) {
        BlockPos pos = PILOTS.get(player.getUUID());
        if (pos == null || !pos.equals(msg.core())) return;
        ShipCoreBlockEntity core = core(player, pos, PILOT_RANGE);
        if (core == null) {
            player.displayClientMessage(Component.translatable("message.tidebound.helm_left").withStyle(ChatFormatting.GOLD), true);
            releaseHelm(player);
            return;
        }
        core.setInput(player.getUUID(), msg.throttle(), msg.turn());
    }

    // ------------------------------------------------------------- upgrades

    /** Why the upgrade can't be bought, or null if it can. */
    @Nullable
    public static Component blocker(Player player, ShipCoreBlockEntity core, ShipUpgrade u) {
        if (core.has(u)) return Component.translatable("message.tidebound.installed");
        for (String req : u.requires) {
            if (!core.upgrades().contains(req)) {
                ShipUpgrade r = ShipUpgrade.byId(req);
                return Component.translatable("message.tidebound.requires", Component.translatable(r == null ? req : r.nameKey()));
            }
        }
        if (player.isCreative()) return null;
        for (ShipUpgrade.Cost c : u.costs) {
            if (count(player.getInventory(), c) < c.count()) return Component.translatable("message.tidebound.missing_materials");
        }
        return null;
    }

    public static int count(Inventory inv, ShipUpgrade.Cost c) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && c.matches(s)) n += s.getCount();
        }
        return n;
    }

    private static void take(Inventory inv, ShipUpgrade.Cost c) {
        int left = c.count();
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && c.matches(s)) {
                int n = Math.min(left, s.getCount());
                s.shrink(n);
                left -= n;
            }
        }
    }

    public static boolean buy(ServerPlayer player, ShipCoreBlockEntity core, @Nullable ShipUpgrade u) {
        if (u == null) return false;
        Component why = blocker(player, core, u);
        if (why != null) {
            player.displayClientMessage(why.copy().withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (!player.isCreative()) for (ShipUpgrade.Cost c : u.costs) take(player.getInventory(), c);
        int placed = ShipBuilder.buildUpgrade(player.serverLevel(), core, u);
        core.install(u);
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.6f, 1.1f);
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.3f);
        player.displayClientMessage(Component.translatable("message.tidebound.upgrade_done",
                Component.translatable(u.nameKey()), placed).withStyle(ChatFormatting.AQUA), true);
        return true;
    }

    // ------------------------------------------------------------------- HUD

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 10 != 0) return;
        PILOTS.entrySet().removeIf(e -> {
            ServerPlayer p = event.getServer().getPlayerList().getPlayer(e.getKey());
            if (p == null) return true;
            ShipCoreBlockEntity core = core(p, e.getValue(), PILOT_RANGE);
            if (core == null || !e.getKey().equals(core.pilot()) && core.pilot() != null) {
                if (core == null) p.displayClientMessage(Component.translatable("message.tidebound.helm_left").withStyle(ChatFormatting.GOLD), true);
                PacketDistributor.sendToPlayer(p, new TideboundNetwork.HelmState(e.getValue(), false));
                return true;
            }
            sendHud(p, core);
            return false;
        });
    }

    private static void sendHud(ServerPlayer p, ShipCoreBlockEntity core) {
        boolean locator = core.stats().locator();
        int tx = 0, tz = 0;
        String name = "";
        if (locator && IslandGuardians.isArchipelago(p.level())) {
            IslandLayout layout = IslandLayout.get();
            IslandData data = IslandData.get(p.serverLevel());
            IslandLayout.Island best = null;
            double bestD = Double.MAX_VALUE;
            int cx = IslandLayout.cellOf(p.getX()), cz = IslandLayout.cellOf(p.getZ());
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    IslandLayout.Island is = layout.island(cx + dx, cz + dz);
                    if (is.home() || data.isCleared(is.key())) continue;
                    double d = Math.hypot(is.x() - p.getX(), is.z() - p.getZ());
                    if (d < bestD) {
                        bestD = d;
                        best = is;
                    }
                }
            }
            if (best != null) {
                tx = best.x();
                tz = best.z();
                name = best.name();
            }
        }
        if (core.stuckTicks > 40) {
            p.displayClientMessage(Component.translatable("message.tidebound.aground").withStyle(net.minecraft.ChatFormatting.GOLD), true);
        }
        PacketDistributor.sendToPlayer(p, new TideboundNetwork.ShipHud((float) core.lastForwardSpeed,
                (float) core.stats().maxSpeed(), locator && !name.isEmpty(), tx, tz, name));
    }
}
