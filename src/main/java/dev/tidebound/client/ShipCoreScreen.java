package dev.tidebound.client;

import dev.ryanhcode.sable.Sable;
import dev.tidebound.net.ShipServer;
import dev.tidebound.net.TideboundNetwork;
import dev.tidebound.ship.ShipCoreBlockEntity;
import dev.tidebound.ship.ShipStats;
import dev.tidebound.ship.ShipUpgrade;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** The Ship Core: take the helm, launch a hand-built hull, and install premade upgrades. */
public class ShipCoreScreen extends Screen {
    private static final int ROW_H = 24;
    private static final int PANEL_W = 340;
    /** Rendered previews (tools/render): the ship as it looks with each upgrade added. */
    private static final int PREVIEW_W = 176, PREVIEW_H = 132, PREVIEW_GAP = 6;
    private boolean preview;
    private final BlockPos pos;
    private final List<Button> buyButtons = new ArrayList<>();
    private int left, top;

    public ShipCoreScreen(BlockPos pos) {
        super(Component.translatable("screen.tidebound.ship_core"));
        this.pos = pos;
    }

    private ShipCoreBlockEntity core() {
        return minecraft != null && minecraft.level != null && minecraft.level.getBlockEntity(pos) instanceof ShipCoreBlockEntity c ? c : null;
    }

    private boolean onShip() {
        return minecraft != null && Sable.HELPER.getContaining(minecraft.level, pos) != null;
    }

    @Override
    protected void init() {
        int rows = ShipUpgrade.values().length;
        int panelH = 64 + rows * ROW_H;
        preview = width >= PANEL_W + PREVIEW_GAP + PREVIEW_W + 8;
        left = (width - PANEL_W - (preview ? PREVIEW_GAP + PREVIEW_W : 0)) / 2;
        top = Math.max(4, (height - panelH) / 2);
        buyButtons.clear();

        if (onShip()) {
            addRenderableWidget(Button.builder(Component.translatable("screen.tidebound.take_helm"), b -> {
                send(TideboundNetwork.ShipAction.TAKE_HELM, "");
                onClose();
            }).bounds(left + 8, top + 22, 110, 20).build());
        } else {
            addRenderableWidget(Button.builder(Component.translatable("screen.tidebound.launch"), b -> {
                send(TideboundNetwork.ShipAction.LAUNCH, "");
                onClose();
            }).tooltip(Tooltip.create(Component.translatable("screen.tidebound.launch.tip")))
                    .bounds(left + 8, top + 22, 110, 20).build());
        }

        int y = top + 56;
        for (ShipUpgrade u : ShipUpgrade.values()) {
            Button b = Button.builder(Component.translatable("screen.tidebound.install"), btn -> send(TideboundNetwork.ShipAction.BUY, u.id))
                    .bounds(left + PANEL_W - 62, y + 2, 54, 18).build();
            buyButtons.add(b);
            addRenderableWidget(b);
            y += ROW_H;
        }
        refresh();
    }

    private void send(int action, String arg) {
        PacketDistributor.sendToServer(new TideboundNetwork.ShipAction(pos, action, arg));
    }

    private void refresh() {
        ShipCoreBlockEntity core = core();
        if (core == null || minecraft == null || minecraft.player == null) return;
        ShipUpgrade[] all = ShipUpgrade.values();
        for (int i = 0; i < all.length; i++) {
            ShipUpgrade u = all[i];
            Button b = buyButtons.get(i);
            Component why = ShipServer.blocker(minecraft.player, core, u);
            b.active = why == null;
            b.setMessage(core.has(u) ? Component.literal("✔").withStyle(ChatFormatting.GREEN) : Component.translatable("screen.tidebound.install"));
            b.setTooltip(Tooltip.create(why == null ? Component.translatable(u.descKey())
                    : Component.translatable(u.descKey()).append("\n").append(why.copy().withStyle(ChatFormatting.RED))));
        }
    }

    @Override
    public void tick() {
        ShipCoreBlockEntity core = core();
        if (core == null) {
            onClose();
            return;
        }
        refresh();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        super.renderBackground(g, mx, my, pt);
        ShipCoreBlockEntity core = core();
        if (core == null) return;
        int rows = ShipUpgrade.values().length;
        int panelH = 64 + rows * ROW_H;
        g.fill(left, top, left + PANEL_W, top + panelH, 0xC0101820);
        g.renderOutline(left, top, PANEL_W, panelH, 0xFF3FC9E8);
        int y = top + 56;
        for (ShipUpgrade u : ShipUpgrade.values()) {
            g.fill(left + 4, y, left + PANEL_W - 4, y + ROW_H - 2, core.has(u) ? 0x40306030 : 0x30000000);
            y += ROW_H;
        }
        if (preview) {
            int px = left + PANEL_W + PREVIEW_GAP;
            g.fill(px, top, px + PREVIEW_W, top + PREVIEW_H + 26, 0xC0101820);
            g.fillGradient(px + 1, top + 1, px + PREVIEW_W - 1, top + PREVIEW_H, 0xFF3A5272, 0xFF6F8AA6);
            g.renderOutline(px, top, PREVIEW_W, PREVIEW_H + 26, 0xFF3FC9E8);
        }
    }

    /** The upgrade under the mouse, or null. */
    private ShipUpgrade hovered(int mx, int my) {
        if (mx < left || mx >= left + PANEL_W) return null;
        int i = (my - (top + 56)) / ROW_H;
        ShipUpgrade[] all = ShipUpgrade.values();
        return my >= top + 56 && i >= 0 && i < all.length ? all[i] : null;
    }

    private void renderPreview(GuiGraphics g, ShipCoreBlockEntity core, int mx, int my) {
        ShipUpgrade shown = hovered(mx, my);
        Component caption;
        if (shown != null) {
            caption = Component.translatable(core.has(shown) ? "screen.tidebound.preview_have" : "screen.tidebound.preview_with",
                    Component.translatable(shown.nameKey()));
        } else {
            for (ShipUpgrade u : ShipUpgrade.values()) if (core.has(u)) shown = u;
            caption = Component.translatable("screen.tidebound.preview_hover");
        }
        String id = shown == null ? "raft" : shown.id;
        int px = left + PANEL_W + PREVIEW_GAP;
        net.minecraft.resources.ResourceLocation tex = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                dev.tidebound.Tidebound.MODID, "textures/gui/preset/" + id + ".png");
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blit(tex, px + 1, top + 1, PREVIEW_W - 2, PREVIEW_H - 1, 0, 0, 320, 240, 320, 240);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(caption, PREVIEW_W - 8);
        int ly = top + PREVIEW_H + 4;
        for (int i = 0; i < Math.min(2, lines.size()); i++) {
            g.drawCenteredString(font, lines.get(i), px + PREVIEW_W / 2, ly + i * 10, 0xDDE6EE);
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        ShipCoreBlockEntity core = core();
        if (core == null) return;
        g.drawString(font, title.copy().withStyle(ChatFormatting.AQUA), left + 8, top + 7, 0xFFFFFF);
        if (preview) renderPreview(g, core, mx, my);

        ShipStats s = core.stats();
        g.drawString(font, Component.translatable("screen.tidebound.stats",
                String.format("%.0f", s.maxSpeed()), String.format("%.1f", s.turnRate()), String.format("%.1f", s.stability())),
                left + 126, top + 22, 0xBBCCDD);
        g.drawString(font, Component.translatable(onShip() ? "screen.tidebound.afloat" : "screen.tidebound.ashore")
                .withStyle(onShip() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), left + 126, top + 33, 0xFFFFFF);
        g.drawString(font, Component.translatable("screen.tidebound.upgrades").withStyle(ChatFormatting.GOLD), left + 8, top + 45, 0xFFFFFF);

        int y = top + 56;
        Minecraft mc = Minecraft.getInstance();
        for (ShipUpgrade u : ShipUpgrade.values()) {
            boolean have = core.has(u);
            g.drawString(font, Component.translatable(u.nameKey()).withStyle(have ? ChatFormatting.GREEN : ChatFormatting.WHITE),
                    left + 8, y + 7, 0xFFFFFF);
            int x = left + 138;
            if (!have) {
                for (ShipUpgrade.Cost c : u.costs) {
                    ItemStack icon = c.icon();
                    g.renderItem(icon, x, y + 3);
                    int haveN = mc.player == null ? 0 : ShipServer.count(mc.player.getInventory(), c);
                    boolean ok = haveN >= c.count() || (mc.player != null && mc.player.isCreative());
                    g.drawString(font, String.valueOf(c.count()), x + 17, y + 8, ok ? 0x80FF80 : 0xFF7070, true);
                    if (mx >= x && mx < x + 30 && my >= y + 2 && my < y + 20) {
                        Component label = c.label() != null ? Component.literal(c.label()) : icon.getHoverName();
                        g.renderTooltip(font, Component.literal(haveN + " / " + c.count() + "  ").append(label), mx, my);
                    }
                    x += 38;
                }
            } else {
                g.drawString(font, Component.translatable("screen.tidebound.installed").withStyle(ChatFormatting.DARK_GREEN), x, y + 7, 0xFFFFFF);
            }
            y += ROW_H;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
