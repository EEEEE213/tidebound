package dev.tidebound.ship;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix3dc;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/**
 * Water physics for a ship sub-level: Sable simulates the rigid body, this adds buoyancy, hydrodynamic drag,
 * thrust, rudder and a gentle self-righting moment, all as impulses in the body's local frame (which is what
 * Sable's {@link RigidBodyHandle#applyLinearAndAngularImpulse} expects, positions in plot coordinates).
 *
 * Buoyancy uses the hull <em>envelope</em>: for every column of the ship, every cell from its lowest to its highest
 * block displaces water, so enclosed air in a hull floats it like a real boat. Each cell's submerged fraction is
 * measured against the actual water blocks in the world at that point.
 */
public final class ShipPhysics {
    /** Mass per fully submerged block of displaced water (Sable masses: wood 0.5, stone 1, iron 2). */
    public static final double WATER_DENSITY = 1.0;
    static final double HEAVE_DAMPING = 1.6;
    /** Most upward push buoyancy may give, in multiples of the ship's weight (stops hulls being launched). */
    static final double MAX_LIFT = 1.6;
    static final double LATERAL_DAMPING = 1.4;
    /** Coasting deceleration along the keel, 1/s (about 4 s to lose 2/3 of the speed). */
    static final double FORWARD_DRAG = 0.25;
    /** Share of the damping cancellation applied as a twist at the hull (measured: 0.8 sails level, bow a touch up). */
    static final double HULL_DAMPING_TRIM = 0.8;
    /**
     * Sable's Rapier bodies carry their own linear damping (about 0.85-1.2 per second, different per body, plus
     * the "universal drag", which Tidebound sets to 0 in the Overworld). Left alone it caps hulls at a couple of
     * blocks per second. Each ship measures it live (predicted vs actual velocity every substep) and cancels it
     * while afloat, so the water drag below is the only drag a sailing ship feels.
     */
    static final double MAX_NATIVE_DAMPING = 3.0;
    /** Stabiliser natural frequency (rad/s) and damping ratio, scaled by the ship's stability stat. */
    static final double STABILISER_FREQ = 1.6;
    static final double STABILISER_ZETA = 0.9;
    /** Test hook: scales every drag term (0 = only buoyancy, thrust and righting). */
    public static double testDragScale = 1.0;

    private ShipPhysics() {
    }

    // -------------------------------------------------------------- hull scan

    /** Envelope cells of the ship (plot-space block centres), packed xyz. */
    public static double[] scanHull(ServerSubLevel sub) {
        ServerLevel level = sub.getLevel();
        BoundingBox3ic box = sub.getPlot().getBoundingBox();
        if (box == null) return new double[0];
        int minX = box.minX(), minY = box.minY(), minZ = box.minZ(), maxX = box.maxX(), maxY = box.maxY(), maxZ = box.maxZ();
        if (maxX < minX) return new double[0];
        double[] out = new double[256 * 3];
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (int y = minY; y <= maxY; y++) {
                    BlockState s = level.getBlockState(p.set(x, y, z));
                    if (s.isAir() || !s.getFluidState().isEmpty() && s.getCollisionShape(level, p).isEmpty()) continue;
                    if (y < lo) lo = y;
                    hi = y;
                }
                if (lo > hi) continue;
                for (int y = lo; y <= hi; y++) {
                    if (n + 3 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                    out[n++] = x + 0.5;
                    out[n++] = y + 0.5;
                    out[n++] = z + 0.5;
                }
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    // ------------------------------------------------------------ water query

    /** Height of the water surface inside block (x,y,z) if it holds water, else NaN. Cached per call batch. */
    static final class WaterProbe {
        final ServerLevel level;
        final Long2DoubleOpenHashMap cache = new Long2DoubleOpenHashMap();
        final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        WaterProbe(ServerLevel level) {
            this.level = level;
            cache.defaultReturnValue(Double.NEGATIVE_INFINITY);
        }

        double top(int x, int y, int z) {
            long k = BlockPos.asLong(x, y, z);
            double v = cache.get(k);
            if (v != Double.NEGATIVE_INFINITY) return v;
            p.set(x, y, z);
            double r = Double.NaN;
            if (level.isLoaded(p)) {
                FluidState f = level.getFluidState(p);
                if (f.is(FluidTags.WATER)) {
                    FluidState above = level.getFluidState(p.set(x, y + 1, z));
                    r = above.is(FluidTags.WATER) ? y + 1.0 : y + f.getHeight(level, p.set(x, y, z));
                }
            }
            cache.put(k, r);
            return r;
        }

        /** Submerged fraction of a unit cube centred at world y=cy over column (x,z). */
        double submerged(double wx, double cy, double wz) {
            int x = (int) Math.floor(wx), z = (int) Math.floor(wz);
            double lo = cy - 0.5, hi = cy + 0.5;
            double sum = 0;
            for (int by = (int) Math.floor(lo); by <= (int) Math.floor(hi); by++) {
                double top = top(x, by, z);
                if (Double.isNaN(top)) continue;
                double a = Math.max(lo, by), b = Math.min(hi, top);
                if (b > a) sum += b - a;
            }
            return Math.min(1, sum);
        }
    }

    // ------------------------------------------------------ damping estimate

    /** Per-ship live estimate of Sable's native linear damping. */
    public static final class DampingProbe {
        public double nativeDamping = 0.9;
        private final Vector3d expected = new Vector3d();
        private boolean have;

        /** Compare the velocity now with what our last impulse alone should have produced. */
        void observe(Vector3d vNow, double dt) {
            if (!have) return;
            have = false;
            double ex = expected.x, ez = expected.z;
            double sp = Math.sqrt(ex * ex + ez * ez);
            if (sp < 0.8) return;
            double along = (vNow.x * ex + vNow.z * ez) / sp;
            if (along < 0.05) return;
            double d = (sp / along - 1) / dt;
            if (d < -0.5 || d > MAX_NATIVE_DAMPING + 1) return; // a collision, not damping
            nativeDamping = Math.max(0, Math.min(MAX_NATIVE_DAMPING, nativeDamping * 0.98 + d * 0.02));
        }

        void applied(Vector3d dvWorld, Vector3d vWorld) {
            expected.set(vWorld).add(dvWorld);
            have = true;
        }
    }

    // -------------------------------------------------------------- the tick

    public static final class Result {
        public double submergedCells;
        public double forwardSpeed;
    }

    public static Result tick(ShipCoreBlockEntity core, ServerSubLevel sub, RigidBodyHandle handle, double dt) {
        Result res = new Result();
        double[] cells = core.hullCells();
        MassData mass = sub.getMassTracker();
        if (cells.length == 0 || mass.getCenterOfMass() == null || mass.getMass() <= 0) return res;

        ServerLevel level = sub.getLevel();
        Pose3dc pose = sub.logicalPose();
        Quaterniondc q = pose.orientation();
        Vector3d com = new Vector3d(mass.getCenterOfMass());

        Vector3d gWorld = DimensionPhysicsData.getGravity(level, pose.position());
        double g = gWorld.length();
        if (g < 1e-6) return res;
        Vector3d gLocal = q.transformInverse(new Vector3d(gWorld));
        Vector3d upLocal = new Vector3d(gLocal).negate().div(g);

        Vector3d vWorld = handle.getLinearVelocity(new Vector3d());
        core.physics.observe(vWorld, dt);
        Vector3d wWorld = handle.getAngularVelocity(new Vector3d());
        Vector3d v = q.transformInverse(new Vector3d(vWorld));
        Vector3d w = q.transformInverse(new Vector3d(wWorld));

        Direction facing = core.shipFacing();
        Vector3d fwd = new Vector3d(facing.getStepX(), 0, facing.getStepZ());
        Vector3d side = new Vector3d(fwd.z, 0, -fwd.x);

        WaterProbe water = new WaterProbe(level);
        Vector3d force = new Vector3d();
        Vector3d torque = new Vector3d();
        Vector3d world = new Vector3d();
        Vector3d r = new Vector3d();
        Vector3d pv = new Vector3d();
        Vector3d f = new Vector3d();
        double buoyPerCell = WATER_DENSITY * g * core.buoyancyScale(mass.getMass());
        double submerged = 0;
        double cellMass = mass.getMass() / Math.max(1, cells.length / 3);
        double[] fracs = new double[cells.length / 3];
        Vector3d buoyForce = new Vector3d(), buoyTorque = new Vector3d();

        for (int i = 0; i < cells.length; i += 3) {
            world.set(cells[i], cells[i + 1], cells[i + 2]);
            pose.transformPosition(world, world);
            double frac = water.submerged(world.x, world.y, world.z);
            if (frac <= 0) continue;
            fracs[i / 3] = frac;
            submerged += frac;
            r.set(cells[i] - com.x, cells[i + 1] - com.y, cells[i + 2] - com.z);

            // buoyancy (straight up in world = against local gravity), summed apart so it can be capped below
            Vector3d bf = new Vector3d(upLocal).mul(buoyPerCell * frac);
            buoyForce.add(bf);
            buoyTorque.add(new Vector3d(r).cross(bf));
            f.set(0, 0, 0);

            // drag on the point velocity, split into heave / lateral / forward
            w.cross(r, pv).add(v);
            double vUp = pv.dot(upLocal);
            double vFwd = pv.dot(fwd);
            double vSide = pv.dot(side);
            double dragScale = testDragScale * frac * Math.max(0.5, cellMass * 2);
            f.fma(-HEAVE_DAMPING * dragScale * vUp, upLocal);
            f.fma(-LATERAL_DAMPING * dragScale * vSide, side);

            force.add(f);
            torque.add(new Vector3d(r).cross(f));
        }

        // A hull that suddenly finds itself deep (a big upgrade built around it in one go) would otherwise be
        // flung out of the water: never push up harder than MAX_LIFT times the ship's weight.
        double lift = buoyForce.length();
        double cap = MAX_LIFT * mass.getMass() * g;
        double k = lift > cap ? cap / lift : 1;
        force.fma(k, buoyForce);
        torque.fma(k, buoyTorque);

        res.submergedCells = submerged;
        res.forwardSpeed = v.dot(fwd);

        if (submerged > 0) {
            ShipStats stats = core.stats();
            double m = mass.getMass();
            // a hull with boosted buoyancy needs proportionally less of itself in the water to count as afloat
            double immersion = Math.min(1, submerged * core.buoyancyScale(m) / Math.max(1, cells.length / 3 * 0.15));

            // water resistance along the keel
            double vF = res.forwardSpeed;
            force.fma(-m * FORWARD_DRAG * testDragScale * vF * immersion, fwd);

            // thrust: full acceleration until close to top speed, plus whatever cancels the water resistance
            double throttle = core.activeThrottle();
            if (throttle != 0) {
                double target = throttle > 0 ? stats.maxSpeed() : -stats.maxSpeed() * 0.4;
                double room = Math.max(0, Math.min(1, 3 * (target - vF) / target));
                double feed = FORWARD_DRAG * vF;
                force.fma(m * (Math.signum(throttle) * stats.accel() * room + (room > 0 ? feed : 0)) * immersion, fwd);
            }

            // rudder: drive the yaw rate toward the requested rate
            Matrix3dc inertia = mass.getInertiaTensor();
            double yawRate = w.dot(upLocal);
            double wantYaw = -core.activeTurn() * stats.turnRate() * Math.min(1, 0.35 + Math.abs(res.forwardSpeed) / 6);
            Vector3d iUp = inertia.transform(new Vector3d(upLocal));
            double iYaw = Math.max(1e-3, iUp.dot(upLocal));
            double yawImpulsePerSec = (wantYaw - yawRate) * iYaw * 3.0 * immersion;
            torque.fma(yawImpulsePerSec, upLocal);

            // cancel Sable's native damping on the horizontal velocity (see MAX_NATIVE_DAMPING). It acts on the
            // submerged hull, below the centre of mass, so the cancelling push goes there too: pushing at the centre
            // of mass alone left a twist that dipped whichever end was leading (bow ahead, stern astern).
            Vector3d vHoriz = new Vector3d(v).fma(-v.dot(upLocal), upLocal);
            Vector3d cancel = new Vector3d(vHoriz).mul(m * core.physics.nativeDamping * immersion);
            force.add(cancel);
            Vector3d part = new Vector3d();
            for (int i = 0; i < fracs.length; i++) {
                if (fracs[i] <= 0) continue;
                r.set(cells[i * 3] - com.x, cells[i * 3 + 1] - com.y, cells[i * 3 + 2] - com.z);
                part.set(cancel).mul(HULL_DAMPING_TRIM * fracs[i] / submerged);
                torque.add(new Vector3d(r).cross(part));
            }

            // stabiliser: a critically damped spring turning the ship's up axis toward world up, scaled by the
            // ship's own inertia so big and small ships right themselves equally briskly
            Vector3d shipUp = new Vector3d(0, 1, 0);
            Vector3d axis = shipUp.cross(upLocal, new Vector3d());
            double sinA = axis.length();
            double omega = STABILISER_FREQ * Math.sqrt(stats.stability());
            if (sinA > 1e-6) {
                Vector3d n = new Vector3d(axis).div(sinA);
                double iAxis = Math.max(1e-3, inertia.transform(new Vector3d(n)).dot(n));
                double angle = Math.atan2(sinA, shipUp.dot(upLocal));
                torque.fma(iAxis * omega * omega * angle * immersion, n);
            }
            Vector3d tiltRate = new Vector3d(w).fma(-w.dot(upLocal), upLocal);
            torque.add(inertia.transform(tiltRate, new Vector3d()).mul(-2 * STABILISER_ZETA * omega * immersion));
        }

        core.physics.applied(q.transform(new Vector3d(force)).mul(dt / mass.getMass()), vWorld);
        force.mul(dt);
        torque.mul(dt);
        handle.applyLinearAndAngularImpulse(force, torque, true);
        return res;
    }
}
