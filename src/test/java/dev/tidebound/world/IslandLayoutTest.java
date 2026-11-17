package dev.tidebound.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IslandLayoutTest {
    private final IslandLayout layout = new IslandLayout(123456789L);

    @Test
    void islandsAreFarApartAndSizedRight() {
        double minGap = Double.MAX_VALUE;
        int minD = Integer.MAX_VALUE, maxD = 0;
        for (int cx = -12; cx <= 12; cx++) {
            for (int cz = -12; cz <= 12; cz++) {
                IslandLayout.Island a = layout.island(cx, cz);
                if (!a.home()) {
                    minD = Math.min(minD, a.radius() * 2);
                    maxD = Math.max(maxD, a.radius() * 2);
                }
                for (int dx = 0; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz <= 0) continue;
                        IslandLayout.Island b = layout.island(cx + dx, cz + dz);
                        double gap = Math.hypot(a.x() - b.x(), a.z() - b.z()) - a.radius() - b.radius();
                        minGap = Math.min(minGap, gap);
                    }
                }
            }
        }
        System.out.printf("diameters %d..%d, min coast-to-coast gap %.0f%n", minD, maxD, minGap);
        assertTrue(minD >= 50 && maxD <= 150, "diameters " + minD + ".." + maxD);
        assertTrue(minGap > 300, "islands too close: " + minGap);
    }

    @Test
    void mostlyWater() {
        int land = 0, total = 0;
        for (int x = -6000; x < 6000; x += 16) {
            for (int z = -6000; z < 6000; z += 16) {
                total++;
                if (layout.surfaceHeight(x, z) > IslandLayout.SEA_SURFACE) land++;
            }
        }
        double frac = land / (double) total;
        System.out.printf("land fraction %.4f%n", frac);
        assertTrue(frac > 0.002 && frac < 0.03, "land fraction " + frac);
    }

    @Test
    void islandsRiseAboveTheSeaAndTheOceanIsDeep() {
        for (int cx = -5; cx <= 5; cx++) {
            for (int cz = -5; cz <= 5; cz++) {
                IslandLayout.Island is = layout.island(cx, cz);
                double centre = layout.surfaceHeight(is.x(), is.z());
                assertTrue(centre > 68, is.name() + " centre only at " + centre);
                double sea = layout.surfaceHeight(is.x() + 400, is.z());
                assertTrue(sea < 45, "sea floor at " + sea);
            }
        }
        IslandLayout.Island home = layout.island(0, 0);
        assertTrue(home.home());
        assertEquals(0, home.x());
        assertTrue(layout.surfaceHeight(0, 0) > 66);
    }

    @Test
    void deterministicAndSeedDependent() {
        IslandLayout same = new IslandLayout(123456789L);
        IslandLayout other = new IslandLayout(42L);
        assertEquals(layout.island(3, -2), same.island(3, -2));
        assertEquals(layout.surfaceHeight(1234, -777), same.surfaceHeight(1234, -777));
        assertTrue(layout.island(3, -2).x() != other.island(3, -2).x() || layout.island(3, -2).z() != other.island(3, -2).z());
    }

    @Test
    void nearestFindsTheRightIsland() {
        IslandLayout.Island is = layout.island(2, 1);
        assertEquals(is, layout.nearest(is.x() + 10, is.z() - 5));
        assertTrue(layout.shapeT(is, is.x(), is.z()) < 0.01);
    }
}
