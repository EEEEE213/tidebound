package dev.tidebound.world;

public final class IslandNames {
    private static final String[] FIRST = {
        "Brine", "Gull", "Salt", "Coral", "Drift", "Kelp", "Storm", "Mist", "Ember", "Hollow",
        "Thorn", "Raven", "Amber", "Frost", "Wreck", "Tide", "Gale", "Moss", "Iron", "Ash",
        "Pearl", "Shark", "Sunken", "Whale", "Crab", "Lantern", "Rook", "Briar", "Copper", "Dusk"
    };
    private static final String[] SECOND = {
        "haven", "rock", "reach", "isle", "cay", "holm", "point", "spire", "crown", "shelf",
        "tooth", "barrow", "key", "mount", "garden", "watch", "cove", "fell", "knoll", "rest"
    };

    private IslandNames() {
    }

    public static String name(IslandLayout.Island is) {
        if (is.home()) return "Home Isle";
        int r = is.biomeRoll() * 7 + is.bossRoll() * 13;
        String a = FIRST[Math.floorMod(r, FIRST.length)];
        String b = SECOND[Math.floorMod(r / FIRST.length + is.cellX() * 3 + is.cellZ(), SECOND.length)];
        return a + b;
    }
}
