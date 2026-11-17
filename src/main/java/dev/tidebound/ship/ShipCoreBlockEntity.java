package dev.tidebound.ship;

import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.tidebound.Tidebound;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The heart of a ship. Lives on the ship's Sable sub-level, drives its water physics every physics substep,
 * takes helm input from the pilot and remembers which premade upgrades are installed.
 */
public class ShipCoreBlockEntity extends BlockEntity implements BlockEntitySubLevelActor {
    private final Set<String> upgrades = new LinkedHashSet<>();
    private ShipStats stats = ShipStats.of(Set.of());
    private double[] hull = new double[0];
    private int hullAge = 1000;

    // helm state (server only, not saved)
    private float throttle, turn;
    private long lastInputTick = -1000;
    @Nullable private UUID pilot;

    public final ShipPhysics.DampingProbe physics = new ShipPhysics.DampingProbe();

    // the ship this core belongs to (server only): shared upgrades, layout anchor, forward direction, helm
    @Nullable private ShipRegistry registry;
    @Nullable private ShipRegistry.Ship ship;
    private boolean isHelm;
    private boolean cleaned;

    // last physics result, for the HUD / tests
    public volatile double lastForwardSpeed;
    public volatile double lastSubmerged;
    /** Ticks the pilot has been pushing the throttle without the ship moving (aground). */
    public int stuckTicks;

    public ShipCoreBlockEntity(BlockPos pos, BlockState state) {
        super(Tidebound.SHIP_CORE_BE.get(), pos, state);
    }

    public Direction facing() {
        return getBlockState().hasProperty(ShipCoreBlock.FACING) ? getBlockState().getValue(ShipCoreBlock.FACING) : Direction.NORTH;
    }

    public Set<String> upgrades() {
        return upgrades;
    }

    /** This core's own upgrade list (what it brought along before it joined a ship's record). */
    Set<String> ownUpgrades() {
        return upgrades;
    }

    /** Where the ship's premade layouts are built around: where its first core stood. */
    public BlockPos anchor() {
        return ship != null ? ship.anchor : worldPosition;
    }

    /** The ship's forward direction (the first core's facing), whichever way this core was placed. */
    public Direction shipFacing() {
        return ship != null ? ship.facing : facing();
    }

    /** Whether this core drives the ship. A ship has one helm; other cores on it are spares. */
    public boolean isHelm() {
        return ship == null || isHelm;
    }

    /** Make this core the ship's helm (taking the helm here moves it). */
    public void claimHelm() {
        if (ship != null && registry != null && !worldPosition.equals(ship.helm)) {
            ship.helm = worldPosition.immutable();
            registry.setDirty();
        }
        isHelm = true;
    }

    public boolean has(ShipUpgrade u) {
        return upgrades.contains(u.id);
    }

    public void install(ShipUpgrade u) {
        upgrades.add(u.id);
        if (ship != null && registry != null && ship.upgrades.add(u.id)) registry.setDirty();
        stats = ShipStats.of(upgrades);
        hullAge = 1000;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public ShipStats stats() {
        return stats;
    }

    public double[] hullCells() {
        return hull;
    }

    public void markHullDirty() {
        hullAge = 1000;
    }

    // ------------------------------------------------------------------ helm

    public void setInput(UUID player, float throttle, float turn) {
        this.pilot = player;
        this.throttle = Math.max(-1, Math.min(1, throttle));
        this.turn = Math.max(-1, Math.min(1, turn));
        this.lastInputTick = level == null ? 0 : level.getGameTime();
    }

    public void releaseHelm(UUID player) {
        if (player.equals(pilot)) {
            pilot = null;
            throttle = 0;
            turn = 0;
        }
    }

    @Nullable
    public UUID pilot() {
        return pilot;
    }

    private boolean inputFresh() {
        return level != null && pilot != null && level.getGameTime() - lastInputTick < 30;
    }

    public float activeThrottle() {
        return inputFresh() ? throttle : 0;
    }

    public float activeTurn() {
        return inputFresh() ? turn : 0;
    }

    // ----------------------------------------------------------------- sable

    private void joinShip(ServerSubLevel subLevel) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server) || subLevel.getUniqueId() == null) return;
        registry = ShipRegistry.get(server);
        ship = registry.getOrCreate(subLevel.getUniqueId(), this);
        if (!subLevel.getPlot().contains(new net.minecraft.world.level.ChunkPos(ship.anchor))) {
            // stale record pointing into another plot: start over from this core
            ship.anchor = worldPosition.immutable();
            ship.facing = facing();
            ship.helm = ship.anchor;
            registry.setDirty();
        }
        // the helm moves to this core if the old one is gone
        if (!worldPosition.equals(ship.helm) && !(level.getBlockEntity(ship.helm) instanceof ShipCoreBlockEntity)) {
            ship.helm = worldPosition.immutable();
            registry.setDirty();
        }
        isHelm = worldPosition.equals(ship.helm);
        if (!upgrades.equals(ship.upgrades)) {
            upgrades.clear();
            upgrades.addAll(ship.upgrades);
            stats = ShipStats.of(upgrades);
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        if (isHelm && !cleaned) {
            cleaned = true;
            ShipBuilder.removeStrayWater(server, subLevel);
        }
    }

    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        joinShip(subLevel);
        if (++hullAge >= 40) {
            hullAge = 0;
            hull = ShipPhysics.scanHull(subLevel);
        }
        if (pilot != null && !inputFresh()) {
            pilot = null;
        }
        stuckTicks = Math.abs(activeThrottle()) > 0.5f && Math.abs(lastForwardSpeed) < 0.25 ? stuckTicks + 1 : 0;
    }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep) {
        if (ship == null || !isHelm) return; // only the helm floats and drives the ship
        if (hull.length == 0) hull = ShipPhysics.scanHull(subLevel);
        ShipPhysics.Result r = ShipPhysics.tick(this, subLevel, handle, timeStep);
        lastForwardSpeed = r.forwardSpeed;
        lastSubmerged = r.submergedCells;
    }

    // ------------------------------------------------------------------- nbt

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (String u : upgrades) list.add(StringTag.valueOf(u));
        tag.put("upgrades", list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        upgrades.clear();
        ListTag list = tag.getList("upgrades", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) upgrades.add(list.getString(i));
        stats = ShipStats.of(upgrades);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
