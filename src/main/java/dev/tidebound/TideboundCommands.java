package dev.tidebound;

import com.mojang.brigadier.CommandDispatcher;
import dev.tidebound.island.IslandData;
import dev.tidebound.island.IslandGuardians;
import dev.tidebound.world.IslandLayout;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** /tidebound island | nearest | free | goto  -- info for everyone, cheats for ops. */
final class TideboundCommands {
    private TideboundCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("tidebound")
                .then(Commands.literal("island").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    IslandLayout.Island is = IslandLayout.get().nearest(p.getX(), p.getZ());
                    boolean cleared = is.home() || IslandData.get(p.serverLevel()).isCleared(is.key());
                    ctx.getSource().sendSuccess(() -> Component.literal(String.format("%s at %d, %d (diameter ~%d) - guardian: %s - %s, %.0f blocks away",
                            is.name(), is.x(), is.z(), is.radius() * 2,
                            is.home() ? "none" : Component.translatable(IslandGuardians.bossFor(is).nameKey()).getString(),
                            cleared ? "free to loot" : "protected", Math.hypot(is.x() - p.getX(), is.z() - p.getZ()))), false);
                    return 1;
                }))
                .then(Commands.literal("nearest").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    IslandLayout.Island is = nearestUncleared(p);
                    ctx.getSource().sendSuccess(() -> Component.literal(String.format("Nearest unclaimed island: %s at %d, %d (%.0f blocks)",
                            is.name(), is.x(), is.z(), Math.hypot(is.x() - p.getX(), is.z() - p.getZ()))), false);
                    return 1;
                }))
                .then(Commands.literal("free").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    IslandLayout.Island is = IslandLayout.get().nearest(p.getX(), p.getZ());
                    IslandGuardians.freeIsland(p.serverLevel(), is, IslandGuardians.surface(p.serverLevel(), is.x(), is.z()));
                    return 1;
                }))
                .then(Commands.literal("goto").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    IslandLayout.Island is = nearestUncleared(p);
                    ServerLevel level = p.serverLevel();
                    double ang = Math.atan2(p.getZ() - is.z(), p.getX() - is.x());
                    int x = (int) (is.x() + Math.cos(ang) * (is.radius() + 60));
                    int z = (int) (is.z() + Math.sin(ang) * (is.radius() + 60));
                    p.teleportTo(level, x + 0.5, 66, z + 0.5, p.getYRot(), p.getXRot());
                    BlockPos at = new BlockPos(x, 66, z);
                    ctx.getSource().sendSuccess(() -> Component.literal("Teleported off the coast of " + is.name() + " " + at.toShortString()), false);
                    return 1;
                })));
    }

    private static IslandLayout.Island nearestUncleared(ServerPlayer p) {
        IslandLayout layout = IslandLayout.get();
        IslandData data = IslandData.get(p.serverLevel());
        IslandLayout.Island best = null;
        double bestD = Double.MAX_VALUE;
        int cx = IslandLayout.cellOf(p.getX()), cz = IslandLayout.cellOf(p.getZ());
        for (int r = 0; r < 6 && best == null; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    IslandLayout.Island is = layout.island(cx + dx, cz + dz);
                    if (is.home() || data.isCleared(is.key())) continue;
                    double d = Math.hypot(is.x() - p.getX(), is.z() - p.getZ());
                    if (d < bestD) {
                        bestD = d;
                        best = is;
                    }
                }
            }
        }
        return best == null ? layout.island(cx + 1, cz) : best;
    }
}
