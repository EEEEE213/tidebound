package dev.tidebound.client;

import dev.tidebound.Tidebound;
import dev.tidebound.net.TideboundNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Piloting: while at the helm, WASD steers the ship instead of walking (W/S throttle, A/D rudder),
 * sneak lets go of the helm. Also draws the sailing HUD.
 */
@EventBusSubscriber(modid = Tidebound.MODID, value = Dist.CLIENT)
public final class PilotClient {
    @Nullable private static BlockPos active;
    private static int lastThrottle = 99, lastTurn = 99, sinceSend;
    private static int throttle, turn;
    static TideboundNetwork.ShipHud hud;

    private PilotClient() {
    }

    @Nullable
    public static BlockPos activeCore() {
        return active;
    }

    public static boolean isPiloting() {
        return active != null;
    }

    static void start(BlockPos core) {
        active = core.immutable();
        lastThrottle = 99;
        hud = null;
    }

    static void stopLocal() {
        active = null;
        hud = null;
    }

    public static void release() {
        if (active != null) {
            PacketDistributor.sendToServer(new TideboundNetwork.ShipAction(active, TideboundNetwork.ShipAction.RELEASE_HELM, ""));
        }
        stopLocal();
    }

    @SubscribeEvent
    public static void onInput(MovementInputUpdateEvent event) {
        if (active == null) return;
        Input in = event.getInput();
        Minecraft mc = Minecraft.getInstance();
        throttle = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        turn = (mc.options.keyRight.isDown() ? 1 : 0) - (mc.options.keyLeft.isDown() ? 1 : 0);
        boolean leave = mc.options.keyShift.isDown();
        in.forwardImpulse = 0;
        in.leftImpulse = 0;
        in.up = in.down = in.left = in.right = false;
        in.jumping = false;
        in.shiftKeyDown = false;
        if (leave) release();
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (active == null) return;
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) {
            stopLocal();
            return;
        }
        if (mc.player.getAbilities().flying) {
            mc.player.getAbilities().flying = false; // stay on deck while steering, or the ship sails off without you
            mc.player.onUpdateAbilities();
        }
        if (mc.screen != null) {
            throttle = 0;
            turn = 0;
        }
        sinceSend++;
        if (throttle != lastThrottle || turn != lastTurn || sinceSend >= 5) {
            PacketDistributor.sendToServer(new TideboundNetwork.HelmInput(active, (byte) throttle, (byte) turn));
            lastThrottle = throttle;
            lastTurn = turn;
            sinceSend = 0;
        }
    }

    // ------------------------------------------------------------------- HUD

    @EventBusSubscriber(modid = Tidebound.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Layers {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerAbove(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath(Tidebound.MODID, "helm"), PilotClient::render);
        }
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        if (active == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        int w = g.guiWidth(), h = g.guiHeight();
        var font = mc.font;
        int y = h - 72;
        float speed = hud == null ? 0 : hud.speed();
        float max = hud == null ? 1 : hud.maxSpeed();
        String sp = String.format("%.1f / %.0f b/s", Math.abs(speed), max);
        Component title = Component.translatable("hud.tidebound.helm").withStyle(ChatFormatting.AQUA);
        g.drawCenteredString(font, title, w / 2, y - 24, 0xFFFFFF);
        int barW = 120, bx = w / 2 - barW / 2;
        g.fill(bx - 1, y - 13, bx + barW + 1, y - 7, 0x90000000);
        int fill = (int) (barW * Mth.clamp(Math.abs(speed) / Math.max(1, max), 0, 1));
        g.fill(bx, y - 12, bx + fill, y - 8, speed >= 0 ? 0xFF3FC9E8 : 0xFFE8A93F);
        g.drawCenteredString(font, sp, w / 2, y - 4, 0xDDEEFF);
        String tTxt = throttle > 0 ? "AHEAD" : throttle < 0 ? "ASTERN" : "IDLE";
        String rTxt = turn > 0 ? "  STARBOARD >" : turn < 0 ? "< PORT  " : "";
        g.drawCenteredString(font, tTxt + "  " + rTxt, w / 2, y + 6, 0xAAAAAA);
        g.drawString(font, Component.translatable("hud.tidebound.keys"), 6, h - 14, 0x99FFFFFF, true);

        if (hud != null && hud.locator() && !hud.targetName().isEmpty()) {
            double dx = hud.targetX() + 0.5 - mc.player.getX();
            double dz = hud.targetZ() + 0.5 - mc.player.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            double bearing = Math.toDegrees(Math.atan2(-dx, dz));
            double rel = Mth.wrapDegrees(bearing - mc.player.getYRot());
            String arrow = rel > 22 ? ">>" : rel > 6 ? ">" : rel < -22 ? "<<" : rel < -6 ? "<" : "^";
            String txt = String.format("%s  %s  %.0fm  %s", rel < -6 ? arrow : "", hud.targetName(), dist, rel > 6 ? arrow : (Math.abs(rel) <= 6 ? "^" : ""));
            g.drawCenteredString(font, Component.literal(txt).withStyle(ChatFormatting.GOLD), w / 2, 12, 0xFFFFFF);
            g.drawCenteredString(font, Component.translatable("hud.tidebound.locator"), w / 2, 2, 0x99FFFFFF);
        }
    }
}
