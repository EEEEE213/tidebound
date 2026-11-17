package dev.tidebound.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.tidebound.Tidebound;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * The deep is dark. Below the surface the water fog closes in and turns black with depth, and past
 * {@link #LAMP_DEPTH} blocks a weak, dying head lamp switches on: a dim flickering cone that lets you see a little
 * way ahead. Works with Sodium (fog goes through the vanilla fog parameters, the darkness is a screen overlay).
 * Conduit Power or Night Vision keeps the deep lit.
 */
@EventBusSubscriber(modid = Tidebound.MODID, value = Dist.CLIENT)
public final class DepthDarkness {
    /** Depth below the water surface where it starts getting darker, and where it is as dark as it gets. */
    static final float DARK_START = 3, DARK_FULL = 30;
    /** Depth where the lamp clicks on (and off again a little shallower, so it doesn't chatter). */
    static final float LAMP_DEPTH = 14, LAMP_OFF = 11;

    private static final ResourceLocation MASK_ID = ResourceLocation.fromNamespaceAndPath(Tidebound.MODID, "lamp_mask");
    private static DynamicTexture mask;
    private static final RandomSource RNG = RandomSource.create();

    private static float depth, darkO, dark;      // depth in blocks, darkness 0..1 (prev/now, for smoothing)
    private static boolean lampOn;
    private static float lampO, lamp;             // lamp brightness 0..1 (prev/now)
    private static int stutter, stutterGap = 200, dropout;
    private static long ticks;

    private DepthDarkness() {
    }

    /** Blocks of water above the camera (0 when not underwater). */
    static float depthAt(Level level, Camera cam) {
        if (cam.getFluidInCamera() != FogType.WATER) return 0;
        BlockPos.MutableBlockPos p = BlockPos.containing(cam.getPosition()).mutable();
        double eye = cam.getPosition().y;
        for (int i = 0; i < 96; i++) {
            p.move(0, 1, 0);
            if (!level.getFluidState(p).is(FluidTags.WATER)) return (float) (p.getY() - eye);
        }
        return 96;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        darkO = dark;
        lampO = lamp;
        ticks++;
        if (mc.level == null || mc.player == null) {
            dark = lamp = 0;
            lampOn = false;
            return;
        }
        depth = depthAt(mc.level, mc.gameRenderer.getMainCamera());
        float target = Mth.clamp((depth - DARK_START) / (DARK_FULL - DARK_START), 0, 1);
        target = target * target * (3 - 2 * target); // ease in and out
        if (mc.player.hasEffect(MobEffects.CONDUIT_POWER) || mc.player.hasEffect(MobEffects.NIGHT_VISION)) target *= 0.25f;
        if (mc.player.isSpectator()) target = 0;
        dark += (target - dark) * 0.12f; // eyes adjust over ~half a second

        boolean wasOn = lampOn;
        lampOn = depth > (lampOn ? LAMP_OFF : LAMP_DEPTH) && target > 0.2f;
        if (lampOn && !wasOn) stutter = 12; // sputters when it clicks on
        lamp = lampOn ? flicker() : 0;
    }

    /** A cheap old lamp: wavering output, random stutters and the odd full dropout. */
    private static float flicker() {
        float t = ticks * 0.05f;
        float b = 0.78f + 0.07f * Mth.sin(t * 3.1f) * Mth.sin(t * 7.7f) + (RNG.nextFloat() - 0.5f) * 0.06f;
        if (dropout > 0) {
            dropout--;
            return 0.04f;
        }
        if (stutter > 0) {
            stutter--;
            return RNG.nextFloat() < 0.5f ? 0.08f + RNG.nextFloat() * 0.15f : b;
        }
        if (--stutterGap <= 0) {
            stutterGap = 80 + RNG.nextInt(260);
            if (RNG.nextFloat() < 0.18f) dropout = 10 + RNG.nextInt(30); // dies for up to 2 s
            else stutter = 4 + RNG.nextInt(14);
        }
        return b;
    }

    private static float darkness(float partial) {
        return Mth.lerp(partial, darkO, dark);
    }

    private static float lamp(float partial) {
        return Mth.lerp(partial, lampO, lamp);
    }

    // -------------------------------------------------------------------- fog

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void fog(ViewportEvent.RenderFog event) {
        if (event.getType() != FogType.WATER) return;
        float d = darkness((float) event.getPartialTick());
        if (d <= 0.001f) return;
        float l = lamp((float) event.getPartialTick());
        // visibility shrinks from the normal underwater distance down to a few blocks; the lamp buys back a little
        float far = Mth.lerp(d, event.getFarPlaneDistance(), 7f) + l * d * 20f;
        event.setFarPlaneDistance(Math.min(event.getFarPlaneDistance(), far));
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), -8f * d));
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void fogColor(ViewportEvent.ComputeFogColor event) {
        if (event.getCamera().getFluidInCamera() != FogType.WATER) return;
        float d = darkness((float) event.getPartialTick());
        float l = lamp((float) event.getPartialTick());
        // the lamp lights up the murk right around you (the overlay keeps everything outside the beam dark)
        float k = 1 - 0.97f * d * (1 - 0.7f * l);
        event.setRed(event.getRed() * k * k);
        event.setGreen(event.getGreen() * k);
        event.setBlue(event.getBlue() * (0.15f + 0.85f * k));
    }

    // ---------------------------------------------------------------- overlay

    @EventBusSubscriber(modid = Tidebound.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class Layers {
        @SubscribeEvent
        public static void register(RegisterGuiLayersEvent event) {
            event.registerBelowAll(ResourceLocation.fromNamespaceAndPath(Tidebound.MODID, "depth_darkness"), DepthDarkness::render);
        }
    }

    /** Black, alpha 0 in the centre rising smoothly to 1 at the rim (a soft round beam). */
    private static void ensureMask(Minecraft mc) {
        if (mask != null) return;
        int n = 256;
        NativeImage img = new NativeImage(n, n, false);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double r = Math.hypot(x + 0.5 - n / 2.0, y + 0.5 - n / 2.0) / (n / 2.0);
                double a = Mth.clamp((r - 0.18) / 0.82, 0, 1);
                a = a * a * (3 - 2 * a);
                img.setPixelRGBA(x, y, ((int) Math.round(a * 255)) << 24); // ABGR, black
            }
        }
        mask = new DynamicTexture(img);
        mc.getTextureManager().register(MASK_ID, mask);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float a = 0.93f * darkness(partial);   // how black the deep gets on screen
        if (a <= 0.004f) return;
        float b = lamp(partial);               // lamp output
        int w = g.guiWidth(), h = g.guiHeight();
        // The beam: inside it the darkness is cut by the lamp, outside it stays at a.
        float inner = a * (1 - 0.97f * b);
        float rim = inner >= 1 ? 0 : (a - inner) / (1 - inner);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.fill(0, 0, w, h, ((int) (inner * 255) << 24));
        if (rim > 0.004f) {
            ensureMask(mc);
            float wobble = Mth.sin((mc.player.tickCount + partial) * 0.11f) * 0.012f;
            int size = (int) (Math.min(w, h) * (0.95f + 0.25f * b + wobble));
            int cx = w / 2 + (int) (w * wobble), cy = h / 2 + (int) (h * 0.03f);
            int x0 = cx - size / 2, y0 = cy - size / 2;
            g.setColor(1, 1, 1, rim);
            g.blit(MASK_ID, x0, y0, size, size, 0, 0, 256, 256, 256, 256);
            g.setColor(1, 1, 1, 1);
            int c = ((int) (rim * 255)) << 24;
            g.fill(0, 0, w, Math.max(0, y0), c);
            g.fill(0, Math.max(0, y0 + size), w, h, c);
            g.fill(0, Math.max(0, y0), Math.max(0, x0), Math.min(h, y0 + size), c);
            g.fill(Math.min(w, x0 + size), Math.max(0, y0), w, Math.min(h, y0 + size), c);
        }
        RenderSystem.disableBlend();
    }
}
