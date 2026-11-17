package dev.tidebound.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * Biome source {@code tidebound:archipelago}: oceans everywhere (warm/cold by a huge climate noise, deep
 * away from land), a beach ring around every island, and one land biome per island.
 */
public final class ArchipelagoBiomeSource extends BiomeSource {
    public static final MapCodec<ArchipelagoBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Biome.LIST_CODEC.fieldOf("island_biomes").forGetter(s -> s.islandBiomes),
            Biome.CODEC.fieldOf("home_biome").forGetter(s -> s.home),
            Biome.CODEC.fieldOf("beach").forGetter(s -> s.beach),
            Biome.CODEC.fieldOf("snowy_beach").forGetter(s -> s.snowyBeach),
            Biome.CODEC.fieldOf("ocean").forGetter(s -> s.ocean),
            Biome.CODEC.fieldOf("deep_ocean").forGetter(s -> s.deepOcean),
            Biome.CODEC.fieldOf("warm_ocean").forGetter(s -> s.warmOcean),
            Biome.CODEC.fieldOf("lukewarm_ocean").forGetter(s -> s.lukewarmOcean),
            Biome.CODEC.fieldOf("deep_lukewarm_ocean").forGetter(s -> s.deepLukewarmOcean),
            Biome.CODEC.fieldOf("cold_ocean").forGetter(s -> s.coldOcean),
            Biome.CODEC.fieldOf("deep_cold_ocean").forGetter(s -> s.deepColdOcean)
    ).apply(i, ArchipelagoBiomeSource::new));

    private final HolderSet<Biome> islandBiomes;
    private final List<Holder<Biome>> islandList;
    private final Holder<Biome> home, beach, snowyBeach, ocean, deepOcean, warmOcean, lukewarmOcean, deepLukewarmOcean,
            coldOcean, deepColdOcean;

    public ArchipelagoBiomeSource(HolderSet<Biome> islandBiomes, Holder<Biome> home, Holder<Biome> beach,
                                  Holder<Biome> snowyBeach, Holder<Biome> ocean, Holder<Biome> deepOcean,
                                  Holder<Biome> warmOcean, Holder<Biome> lukewarmOcean, Holder<Biome> deepLukewarmOcean,
                                  Holder<Biome> coldOcean, Holder<Biome> deepColdOcean) {
        this.islandBiomes = islandBiomes;
        this.islandList = islandBiomes.stream().toList();
        this.home = home;
        this.beach = beach;
        this.snowyBeach = snowyBeach;
        this.ocean = ocean;
        this.deepOcean = deepOcean;
        this.warmOcean = warmOcean;
        this.lukewarmOcean = lukewarmOcean;
        this.deepLukewarmOcean = deepLukewarmOcean;
        this.coldOcean = coldOcean;
        this.deepColdOcean = deepColdOcean;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        List<Holder<Biome>> all = new ArrayList<>(islandList);
        all.addAll(List.of(home, beach, snowyBeach, ocean, deepOcean, warmOcean, lukewarmOcean, deepLukewarmOcean,
                coldOcean, deepColdOcean));
        return all.stream().distinct();
    }

    public Holder<Biome> islandBiome(IslandLayout.Island is) {
        if (is.home() || islandList.isEmpty()) return home;
        return islandList.get(Math.floorMod(is.biomeRoll(), islandList.size()));
    }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        IslandLayout layout = IslandLayout.get();
        int x = (qx << 2) + 2, z = (qz << 2) + 2;
        IslandLayout.Island is = layout.nearest(x, z);
        double t = layout.shapeT(is, x, z);
        if (t < 0.9) return islandBiome(is);
        if (t < 1.08) {
            Holder<Biome> land = islandBiome(is);
            return land.value().getBaseTemperature() < 0.15f ? snowyBeach : beach;
        }
        boolean deep = t > 3.2;
        double temp = layout.temperature(x, z);
        if (temp > 0.55) return deep ? deepLukewarmOcean : warmOcean;
        if (temp > 0.2) return deep ? deepLukewarmOcean : lukewarmOcean;
        if (temp < -0.4) return deep ? deepColdOcean : coldOcean;
        return deep ? deepOcean : ocean;
    }
}
