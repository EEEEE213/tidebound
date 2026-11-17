package dev.tidebound.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Density function {@code tidebound:surface_height}: the terrain height of the archipelago at a column.
 * The noise settings combine it as {@code cache_2d(surface_height) - y}, so positive = solid.
 */
public final class ArchipelagoSurface implements DensityFunction.SimpleFunction {
    public static final ArchipelagoSurface INSTANCE = new ArchipelagoSurface();
    public static final MapCodec<ArchipelagoSurface> MAP_CODEC = MapCodec.unit(INSTANCE);
    public static final KeyDispatchDataCodec<ArchipelagoSurface> CODEC = KeyDispatchDataCodec.of(MAP_CODEC);

    private ArchipelagoSurface() {
    }

    @Override
    public double compute(FunctionContext ctx) {
        return IslandLayout.get().surfaceHeight(ctx.blockX(), ctx.blockZ());
    }

    @Override
    public double minValue() {
        return -64;
    }

    @Override
    public double maxValue() {
        return 320;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
