package dev.tidebound.world;

/**
 * Where the islands are. The world is split into square cells of {@link #CELL} blocks; every cell holds
 * exactly one island, jittered inside the cell but kept {@link #MARGIN} blocks from its edges, so islands are
 * always at least {@code 2 * MARGIN} blocks apart. Island diameters are 50..150 blocks. The cell at the origin
 * holds the home island (centred on 0,0, no guardian).
 *
 * Pure Java and deterministic for a seed, so terrain, biomes, bosses and the client HUD all agree.
 */
public final class IslandLayout {
    public static final int CELL = 1000;
    public static final int MARGIN = 260;
    public static final int MIN_RADIUS = 25;
    public static final int MAX_RADIUS = 75;
    public static final int HOME_RADIUS = 42;
    public static final double SEA_SURFACE = 63.0;

    private static volatile IslandLayout current = new IslandLayout(0L);

    private final long seed;
    private final Noise2D coast;
    private final Noise2D hills;
    private final Noise2D floor;
    private final Noise2D climate;

    public IslandLayout(long seed) {
        this.seed = seed;
        this.coast = new Noise2D(seed * 31 + 1);
        this.hills = new Noise2D(seed * 31 + 2);
        this.floor = new Noise2D(seed * 31 + 3);
        this.climate = new Noise2D(seed * 31 + 4);
    }

    public static IslandLayout get() {
        return current;
    }

    public static void setSeed(long seed) {
        if (current.seed != seed) current = new IslandLayout(seed);
    }

    public long seed() {
        return seed;
    }

    // ---------------------------------------------------------------- islands

    public record Island(int cellX, int cellZ, int x, int z, int radius, double peak, int biomeRoll, int bossRoll, boolean home) {
        public long key() {
            return cellKey(cellX, cellZ);
        }

        public String name() {
            return IslandNames.name(this);
        }
    }

    public static long cellKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    public static int cellOf(double coord) {
        return (int) Math.floor((coord + CELL / 2.0) / CELL);
    }

    private long hash(int cx, int cz, int salt) {
        long h = seed ^ (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xC2B2AE3D27D4EB4FL) ^ (salt * 0x165667B19E3779F9L);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    private double unit(int cx, int cz, int salt) {
        return (hash(cx, cz, salt) >>> 11) * 0x1.0p-53;
    }

    public Island island(int cx, int cz) {
        if (cx == 0 && cz == 0) {
            return new Island(0, 0, 0, 0, HOME_RADIUS, 16, 0, -1, true);
        }
        int span = CELL - 2 * MARGIN;
        int x = cx * CELL - CELL / 2 + MARGIN + (int) (unit(cx, cz, 1) * span);
        int z = cz * CELL - CELL / 2 + MARGIN + (int) (unit(cx, cz, 2) * span);
        int radius = MIN_RADIUS + (int) Math.round(unit(cx, cz, 3) * (MAX_RADIUS - MIN_RADIUS));
        double peak = 8 + (radius - MIN_RADIUS) * 0.32 + unit(cx, cz, 4) * 14;
        int biomeRoll = (int) (hash(cx, cz, 5) >>> 40);
        int bossRoll = (int) (hash(cx, cz, 6) >>> 40);
        return new Island(cx, cz, x, z, radius, peak, biomeRoll, bossRoll, false);
    }

    public Island islandAt(double x, double z) {
        return island(cellOf(x), cellOf(z));
    }

    /** Nearest island centre to a point (checks the 3x3 cells around it). */
    public Island nearest(double x, double z) {
        int cx = cellOf(x), cz = cellOf(z);
        Island best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Island is = island(cx + dx, cz + dz);
                double d = sq(is.x - x) + sq(is.z - z);
                if (d < bestD) {
                    bestD = d;
                    best = is;
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- shape

    /**
     * Normalised distance from the island centre: below 1 is land, 1 is the coastline.
     * The coastline wobbles with noise so islands are not circles.
     */
    public double shapeT(Island is, double x, double z) {
        double dx = x - is.x, dz = z - is.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        double wobble = coast.fbm(x + is.cellX * 977, z - is.cellZ * 541, 1 / 42.0, 3) * 0.26
                + coast.sample(x / 13.0, z / 13.0) * 0.05;
        return d / (is.radius * (1 + wobble));
    }

    public double oceanFloor(double x, double z) {
        return 31 + floor.fbm(x, z, 1 / 220.0, 3) * 9 + floor.sample(x / 37.0, z / 37.0) * 2;
    }

    /** Terrain surface height (in blocks, fractional) at a column. */
    public double surfaceHeight(double x, double z) {
        Island is = nearest(x, z);
        double floorY = oceanFloor(x, z);
        double t = shapeT(is, x, z);
        if (t >= 2.4) return floorY;
        if (t >= 1.0) {
            double s = smooth((t - 1.0) / 1.4);
            return lerp(s, 61.0, floorY);
        }
        double profile = Math.pow(1 - t * t, 1.4);
        double h = 61.0 + (is.peak + 3) * profile;
        double hill = hills.fbm(x, z, 1 / 28.0, 3);
        h += hill * 7 * (1 - t) * (1 - t);
        // a gentle sandy shelf at the coast so the beach is walkable
        if (t > 0.86) h = Math.min(h, 63.6 + (1 - t) * 4);
        return h;
    }

    /** Large-scale water temperature, -1 (cold) .. 1 (warm). */
    public double temperature(double x, double z) {
        return climate.fbm(x, z, 1 / 2600.0, 2) * 1.6;
    }

    private static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double sq(double v) {
        return v * v;
    }
}
