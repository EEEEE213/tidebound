package dev.tidebound;

import com.mojang.serialization.MapCodec;
import dev.tidebound.island.IslandGuardians;
import dev.tidebound.net.ShipServer;
import dev.tidebound.net.TideboundNetwork;
import dev.tidebound.ship.ShipCoreBlock;
import dev.tidebound.ship.ShipCoreBlockEntity;
import dev.tidebound.ship.ShipCoreItem;
import dev.tidebound.world.ArchipelagoBiomeSource;
import dev.tidebound.world.ArchipelagoSurface;
import dev.tidebound.world.IslandLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Tidebound.MODID)
public final class Tidebound {
    public static final String MODID = "tidebound";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_FUNCTIONS =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, MODID);
    public static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES = DeferredRegister.create(Registries.BIOME_SOURCE, MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredBlock<ShipCoreBlock> SHIP_CORE = BLOCKS.register("ship_core", () -> new ShipCoreBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5f, 6f).sound(SoundType.WOOD).noOcclusion().lightLevel(s -> 7)));
    public static final DeferredItem<ShipCoreItem> SHIP_CORE_ITEM = ITEMS.register("ship_core",
            () -> new ShipCoreItem(SHIP_CORE.get(), new Item.Properties().rarity(Rarity.UNCOMMON)));
    public static final DeferredItem<Item> TIDE_SHARD = ITEMS.register("tide_shard",
            () -> new Item(new Item.Properties().rarity(Rarity.RARE)));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ShipCoreBlockEntity>> SHIP_CORE_BE =
            BLOCK_ENTITIES.register("ship_core", () -> BlockEntityType.Builder.of(ShipCoreBlockEntity::new, SHIP_CORE.get()).build(null));

    static {
        DENSITY_FUNCTIONS.register("surface_height", () -> ArchipelagoSurface.MAP_CODEC);
        BIOME_SOURCES.register("archipelago", () -> ArchipelagoBiomeSource.CODEC);
        TABS.register("main", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.tidebound"))
                .icon(() -> new ItemStack(SHIP_CORE_ITEM.get()))
                .displayItems((p, out) -> {
                    out.accept(SHIP_CORE_ITEM.get());
                    out.accept(TIDE_SHARD.get());
                })
                .build());
    }

    public Tidebound(IEventBus modBus) {
        dev.tidebound.update.Updater.start();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        DENSITY_FUNCTIONS.register(modBus);
        BIOME_SOURCES.register(modBus);
        TABS.register(modBus);
        modBus.addListener(TideboundNetwork::register);
        NeoForge.EVENT_BUS.register(Tidebound.class);
        NeoForge.EVENT_BUS.register(IslandGuardians.class);
        NeoForge.EVENT_BUS.register(ShipServer.class);
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        IslandLayout.setSeed(event.getServer().getWorldData().worldGenOptions().seed());
    }

    /** New archipelago worlds spawn on the home island at 0,0. */
    @SubscribeEvent
    public static void onCreateSpawn(LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!(level.getChunkSource().getGenerator().getBiomeSource() instanceof ArchipelagoBiomeSource)) return;
        BlockPos best = null;
        for (int r = 0; r <= 16 && best == null; r += 2) {
            for (int dx = -r; dx <= r && best == null; dx += 2) {
                for (int dz = -r; dz <= r && best == null; dz += 2) {
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, dx, dz);
                    BlockPos below = new BlockPos(dx, y - 1, dz);
                    if (y > level.getSeaLevel() && level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)
                            && level.getFluidState(below).isEmpty() && !level.getBlockState(below).is(net.minecraft.tags.BlockTags.LEAVES)) {
                        best = new BlockPos(dx, y, dz);
                    }
                }
            }
        }
        if (best == null) best = new BlockPos(0, level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0), 0);
        event.getSettings().setSpawn(best, 0);
        event.setCanceled(true);
    }

    /** First time in an archipelago world: a Ship Core to get going. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!IslandGuardians.isArchipelago(player.getServer().overworld())) return;
        var data = player.getPersistentData();
        var keep = data.getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG);
        if (keep.getBoolean("tidebound_started")) return;
        keep.putBoolean("tidebound_started", true);
        data.put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG, keep);
        player.getInventory().add(new ItemStack(SHIP_CORE_ITEM.get()));
        player.sendSystemMessage(Component.translatable("message.tidebound.welcome1").withStyle(net.minecraft.ChatFormatting.AQUA));
        player.sendSystemMessage(Component.translatable("message.tidebound.welcome2").withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        TideboundCommands.register(event.getDispatcher());
    }
}
