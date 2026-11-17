package dev.tidebound.client.dev;

import com.mojang.logging.LogUtils;
import dev.tidebound.Tidebound;
import dev.tidebound.client.ShipCoreScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

/**
 * Developer-only scripted client (inert unless {@code -Dtidebound.autotest=<script>}). One action per line:
 * newworld NAME SEED | wait TICKS | cmd COMMAND | shot NAME | look YAW PITCH | hold KEY TICKS | useitem | useon |
 * corescreen | click TEXT | close | log TEXT | quit.  Keys: forward back left right jump sneak.
 */
@EventBusSubscriber(modid = Tidebound.MODID, value = Dist.CLIENT)
public final class AutoTest {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String SCRIPT = System.getProperty("tidebound.autotest");
    private static Deque<String> lines;
    private static int waiting;
    private static KeyMapping held;
    private static int heldTicks;
    private static net.minecraft.core.BlockPos lastCore;

    private AutoTest() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (SCRIPT == null) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (lines == null) {
                if (!(mc.screen instanceof TitleScreen)) return;
                lines = new ArrayDeque<>(Files.readAllLines(Path.of(SCRIPT)));
                LOG.info("[autotest] running {} ({} lines)", SCRIPT, lines.size());
            }
            if (held != null) {
                held.setDown(true);
                if (--heldTicks <= 0) {
                    held.setDown(false);
                    held = null;
                }
                return;
            }
            if (waiting > 0) {
                waiting--;
                return;
            }
            while (!lines.isEmpty() && waiting == 0 && held == null) {
                String line = lines.poll().trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                LOG.info("[autotest] > {}", line);
                run(mc, line);
                if (line.startsWith("newworld") || line.startsWith("shot")) break;
            }
        } catch (Exception e) {
            LOG.error("[autotest] failed", e);
            mc.stop();
        }
    }

    private static KeyMapping key(Minecraft mc, String name) {
        return switch (name) {
            case "forward" -> mc.options.keyUp;
            case "back" -> mc.options.keyDown;
            case "left" -> mc.options.keyLeft;
            case "right" -> mc.options.keyRight;
            case "jump" -> mc.options.keyJump;
            case "sneak" -> mc.options.keyShift;
            case "attack" -> mc.options.keyAttack;
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static void run(Minecraft mc, String line) {
        String[] a = line.split("\\s+", 2);
        String arg = a.length > 1 ? a[1] : "";
        switch (a[0]) {
            case "newworld" -> {
                String[] p = arg.split("\\s+");
                long seed = p.length > 1 ? Long.parseLong(p[1]) : 0L;
                LevelSettings settings = new LevelSettings(p[0], GameType.CREATIVE, false, Difficulty.NORMAL, true,
                        new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(p[0], settings, new WorldOptions(seed, true, false),
                        ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(),
                        mc.screen);
                waiting = 100;
            }
            case "wait" -> waiting = Integer.parseInt(arg);
            case "cmd" -> {
                if (mc.player != null) mc.player.connection.sendCommand(arg);
                waiting = 2;
            }
            case "shot" -> {
                Screenshot.grab(mc.gameDirectory, arg + ".png", mc.getMainRenderTarget(), msg -> LOG.info("[autotest] {}", msg.getString()));
                waiting = 2;
            }
            case "look" -> {
                String[] p = arg.split("\\s+");
                mc.player.setYRot(Float.parseFloat(p[0]));
                mc.player.setXRot(Float.parseFloat(p[1]));
                mc.player.yRotO = mc.player.getYRot();
                mc.player.xRotO = mc.player.getXRot();
            }
            case "turn" -> {
                String[] p = arg.split("\s+");
                mc.player.setYRot(mc.player.getYRot() + Float.parseFloat(p[0]));
                mc.player.setXRot(Float.parseFloat(p[1]));
                mc.player.yRotO = mc.player.getYRot();
                mc.player.xRotO = mc.player.getXRot();
            }
            case "faceisland" -> {
                // singleplayer only: the integrated server's layout is in this JVM
                var layout = dev.tidebound.world.IslandLayout.get();
                var is = layout.nearest(mc.player.getX(), mc.player.getZ());
                if (is.home()) {
                    var best = is;
                    double bd = Double.MAX_VALUE;
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        var c = layout.island(dx, dz);
                        double d = Math.hypot(c.x() - mc.player.getX(), c.z() - mc.player.getZ());
                        if (!c.home() && d < bd) { bd = d; best = c; }
                    }
                    is = best;
                }
                double dx = is.x() + 0.5 - mc.player.getX(), dz = is.z() + 0.5 - mc.player.getZ();
                mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
                mc.player.setXRot(arg.isEmpty() ? 0 : Float.parseFloat(arg));
                mc.player.yRotO = mc.player.getYRot();
                mc.player.xRotO = mc.player.getXRot();
                LOG.info("[autotest] facing {} at {}, {} ({} blocks)", is.name(), is.x(), is.z(), (int) Math.hypot(dx, dz));
            }
            case "hold" -> {
                String[] p = arg.split("\\s+");
                held = key(mc, p[0]);
                heldTicks = Integer.parseInt(p[1]);
            }
            case "tpisland" -> {
                var layout = dev.tidebound.world.IslandLayout.get();
                var is = layout.nearest(mc.player.getX(), mc.player.getZ());
                String[] p = arg.split("\s+");
                int dx = p.length > 1 ? Integer.parseInt(p[1]) : 0;
                double y = layout.surfaceHeight(is.x() + dx, is.z()) + Double.parseDouble(p[0]);
                mc.player.connection.sendCommand(String.format("tp @s %d %.1f %d", is.x() + dx, y, is.z()));
                waiting = 2;
            }
            case "faceboss" -> {
                var boss = mc.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, mc.player.getBoundingBox().inflate(80),
                        m -> m.getTags().contains("tidebound_boss") || m.hasCustomName()).stream().findFirst().orElse(null);
                if (boss == null) {
                    LOG.warn("[autotest] no boss in sight");
                    return;
                }
                double dx = boss.getX() - mc.player.getX(), dz = boss.getZ() - mc.player.getZ();
                double dy = boss.getY() + boss.getBbHeight() * 0.5 - mc.player.getEyeY();
                mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
                mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
                mc.player.yRotO = mc.player.getYRot();
                mc.player.xRotO = mc.player.getXRot();
                LOG.info("[autotest] boss {} hp {}/{} at {} ({} blocks), size {}", boss.getName().getString(), boss.getHealth(),
                        boss.getMaxHealth(), boss.blockPosition(), (int) Math.hypot(dx, dz), boss.getBbHeight());
            }
            case "fly" -> {
                mc.player.getAbilities().flying = !arg.equals("off");
                mc.player.onUpdateAbilities();
            }
            case "useitem" -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            case "useon" -> {
                if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, bhr);
                } else {
                    LOG.warn("[autotest] useon: not looking at a block ({})", mc.hitResult);
                }
            }
            case "aimcore" -> {
                // sweep the view until the crosshair lands on a Ship Core (on a ship it sits in Sable plot space)
                for (int pitch = 0; pitch <= 90; pitch += 3) {
                    for (int yaw = 0; yaw < 360; yaw += 4) {
                        mc.player.setYRot(yaw);
                        mc.player.setXRot(pitch);
                        mc.gameRenderer.pick(1f);
                        if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK
                                && mc.level.getBlockState(bhr.getBlockPos()).is(Tidebound.SHIP_CORE.get())) {
                            mc.player.yRotO = yaw;
                            mc.player.xRotO = pitch;
                            LOG.info("[autotest] aimed at core {} (yaw {}, pitch {})", bhr.getBlockPos(), yaw, pitch);
                            return;
                        }
                    }
                }
                LOG.warn("[autotest] aimcore: no core in reach");
            }
            case "shipinfo" -> {
                var pos = dev.tidebound.client.PilotClient.activeCore();
                var server = mc.getSingleplayerServer();
                if (pos == null || server == null) {
                    LOG.info("[autotest] shipinfo {}: not piloting", arg);
                    return;
                }
                server.execute(() -> {
                    if (server.overworld().getBlockEntity(pos) instanceof dev.tidebound.ship.ShipCoreBlockEntity core) {
                        var sub = dev.tidebound.ship.ShipBuilder.subLevelOf(server.overworld(), pos);
                        LOG.info("[autotest] shipinfo {}: speed {} stuck {} submerged {} centre {}", arg,
                                String.format("%.2f", core.lastForwardSpeed), core.stuckTicks, String.format("%.1f", core.lastSubmerged),
                                sub == null ? "-" : dev.tidebound.ship.ShipBuilder.worldCenter(server.overworld(), pos));
                    }
                });
            }
            case "tpcore" -> {
                // teleport relative to the last core opened with corescreen: tpcore <dx> <dy> <dz> <yaw> <pitch>
                var server = mc.getSingleplayerServer();
                String[] p = arg.split("\s+");
                var c = dev.tidebound.ship.ShipBuilder.worldCenter(server.overworld(), lastCore);
                mc.player.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f %s %s",
                        c.x + Double.parseDouble(p[0]), c.y - 0.5 + Double.parseDouble(p[1]), c.z + Double.parseDouble(p[2]), p[3], p[4]));
                waiting = 2;
            }
            case "corescreen" -> {
                if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                    lastCore = bhr.getBlockPos().immutable();
                    mc.setScreen(new ShipCoreScreen(bhr.getBlockPos()));
                } else {
                    LOG.warn("[autotest] corescreen: not looking at a block");
                }
            }
            case "click" -> {
                if (mc.screen == null) return;
                for (GuiEventListener l : List.copyOf(mc.screen.children())) {
                    if (l instanceof AbstractWidget w && w.active && w.getMessage().getString().contains(arg)) {
                        w.onClick(w.getX() + 1, w.getY() + 1);
                        LOG.info("[autotest] clicked {}", w.getMessage().getString());
                        return;
                    }
                }
                LOG.warn("[autotest] no active button '{}'", arg);
            }
            case "close" -> mc.setScreen(null);
            case "mouse" -> {
                // move the cursor to a spot given as fractions of the screen (0..1)
                String[] p = arg.split("\s+");
                double fx = Double.parseDouble(p[0]) * mc.getWindow().getScreenWidth();
                double fy = Double.parseDouble(p[1]) * mc.getWindow().getScreenHeight();
                try {
                    var m = net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
                    m.setAccessible(true);
                    m.invoke(mc.mouseHandler, mc.getWindow().getWindow(), fx, fy);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }
            case "block" -> {
                if (mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
                    LOG.info("[autotest] block {}: {} at {}", arg, mc.level.getBlockState(bhr.getBlockPos()), bhr.getBlockPos());
                } else {
                    LOG.info("[autotest] block {}: none in reach", arg);
                }
            }
            case "slot" -> mc.player.getInventory().selected = Integer.parseInt(arg);
            case "log" -> LOG.info("[autotest] {} | pos {} | looking at {}", arg,
                    mc.player == null ? "-" : mc.player.position(), mc.hitResult == null ? "-" : mc.hitResult.getLocation());
            case "leave" -> {
                if (mc.level != null) mc.level.disconnect();
                mc.disconnect();
                mc.setScreen(new TitleScreen());
                waiting = 60;
            }
            case "connect" -> {
                // join a server as a remote player: connect host:port
                var addr = net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(arg);
                net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new TitleScreen(), mc, addr,
                        new net.minecraft.client.multiplayer.ServerData("test", arg, net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
                waiting = 200;
            }
            case "openworld" -> {
                mc.createWorldOpenFlows().openWorld(arg, () -> mc.setScreen(new TitleScreen()));
                waiting = 200;
            }
            case "quit" -> mc.stop();
            default -> LOG.warn("[autotest] unknown action {}", a[0]);
        }
    }
}
