package dev.tidebound.net;

import dev.tidebound.Tidebound;
import dev.tidebound.client.ClientAccess;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class TideboundNetwork {
    public static final String VERSION = "1";

    private TideboundNetwork() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> mk(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Tidebound.MODID, path));
    }

    /** Client -> server: a button in the Ship Core screen. */
    public record ShipAction(BlockPos core, int action, String arg) implements CustomPacketPayload {
        public static final int TAKE_HELM = 0, BUY = 1, LAUNCH = 2, RELEASE_HELM = 3;
        public static final Type<ShipAction> TYPE = mk("ship_action");
        public static final StreamCodec<ByteBuf, ShipAction> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ShipAction::core, ByteBufCodecs.VAR_INT, ShipAction::action,
                ByteBufCodecs.STRING_UTF8, ShipAction::arg, ShipAction::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> server: helm keys while piloting (-1/0/1 each). */
    public record HelmInput(BlockPos core, byte throttle, byte turn) implements CustomPacketPayload {
        public static final Type<HelmInput> TYPE = mk("helm_input");
        public static final StreamCodec<ByteBuf, HelmInput> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, HelmInput::core, ByteBufCodecs.BYTE, HelmInput::throttle,
                ByteBufCodecs.BYTE, HelmInput::turn, HelmInput::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: start (or stop, when active is false) piloting the ship at core. */
    public record HelmState(BlockPos core, boolean active) implements CustomPacketPayload {
        public static final Type<HelmState> TYPE = mk("helm_state");
        public static final StreamCodec<ByteBuf, HelmState> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, HelmState::core, ByteBufCodecs.BOOL, HelmState::active, HelmState::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: numbers for the sailing HUD, sent twice a second to pilots. */
    public record ShipHud(float speed, float maxSpeed, boolean locator, int targetX, int targetZ, String targetName) implements CustomPacketPayload {
        public static final Type<ShipHud> TYPE = mk("ship_hud");
        public static final StreamCodec<ByteBuf, ShipHud> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, ShipHud::speed, ByteBufCodecs.FLOAT, ShipHud::maxSpeed,
                ByteBufCodecs.BOOL, ShipHud::locator, ByteBufCodecs.VAR_INT, ShipHud::targetX,
                ByteBufCodecs.VAR_INT, ShipHud::targetZ, ByteBufCodecs.STRING_UTF8, ShipHud::targetName, ShipHud::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(VERSION);
        r.playToServer(ShipAction.TYPE, ShipAction.CODEC,
                (p, ctx) -> ShipServer.handleAction((ServerPlayer) ctx.player(), p));
        r.playToServer(HelmInput.TYPE, HelmInput.CODEC,
                (p, ctx) -> ShipServer.handleInput((ServerPlayer) ctx.player(), p));
        r.playToClient(HelmState.TYPE, HelmState.CODEC, (p, ctx) -> ClientAccess.helmState(p.core(), p.active()));
        r.playToClient(ShipHud.TYPE, ShipHud.CODEC, (p, ctx) -> ClientAccess.hud(p));
    }
}
