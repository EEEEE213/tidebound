package dev.tidebound.ship;

import java.util.Set;

/** Handling numbers derived from the installed upgrades. Speeds in blocks/second. */
public record ShipStats(double maxSpeed, double accel, double turnRate, double stability, boolean locator) {
    public static final double BASE_SPEED = 7.0;
    public static final double BASE_ACCEL = 2.6;
    public static final double BASE_TURN = 0.55;
    public static final double BASE_STABILITY = 1.0;

    public static ShipStats of(Set<String> upgrades) {
        double speed = BASE_SPEED, accel = BASE_ACCEL, turn = BASE_TURN, stab = BASE_STABILITY;
        boolean locator = false;
        for (String id : upgrades) {
            ShipUpgrade u = ShipUpgrade.byId(id);
            if (u == null) continue;
            speed += u.speedBonus;
            accel *= u.accelMul;
            turn *= u.turnMul;
            stab *= u.stabilityMul;
            locator |= u.locator;
        }
        return new ShipStats(speed, accel, turn, stab, locator);
    }
}
