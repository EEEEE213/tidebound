package dev.tidebound.client;

import dev.tidebound.net.TideboundNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Entry points from common code into client-only code. Only ever called on the client. */
public final class ClientAccess {
    private ClientAccess() {
    }

    public static void openShipCore(BlockPos pos) {
        Minecraft.getInstance().setScreen(new ShipCoreScreen(pos));
    }

    public static void helmState(BlockPos core, boolean active) {
        if (active) PilotClient.start(core);
        else PilotClient.stopLocal();
    }

    public static void hud(TideboundNetwork.ShipHud hud) {
        PilotClient.hud = hud;
    }
}
