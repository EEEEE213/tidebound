package dev.tidebound.client;

import dev.tidebound.Tidebound;
import dev.tidebound.update.Updater;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Tells the player when the updater has downloaded a new Tidebound. */
@EventBusSubscriber(modid = Tidebound.MODID, value = Dist.CLIENT)
public final class UpdateNotice {
    private static String shown;

    private UpdateNotice() {
    }

    @SubscribeEvent
    public static void onJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        String v = Updater.downloaded;
        if (v == null || v.equals(shown)) return;
        shown = v;
        event.getPlayer().displayClientMessage(Component.literal("Tidebound " + v + " has been downloaded. Restart Minecraft to play the new version.")
                .withStyle(ChatFormatting.AQUA), false);
    }
}
