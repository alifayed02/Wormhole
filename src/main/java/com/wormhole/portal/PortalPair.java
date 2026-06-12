package com.wormhole.portal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/**
 * A pair of portal ends sharing one UUID. {@code b == null} means the pair is PENDING: its
 * first frame exists and is waiting for the next frame built (FIFO) to link with it.
 * Crossing either end of a linked pair teleports you to the other; the transform is derived
 * from the ends' geometry (no stored matrix). Instances are immutable; the manager swaps them.
 */
public final class PortalPair {
    public static final Codec<PortalPair> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        UUIDUtil.CODEC.fieldOf("id").forGetter(PortalPair::getId),
        PortalEnd.CODEC.fieldOf("a").forGetter(PortalPair::getA),
        PortalEnd.CODEC.optionalFieldOf("b").forGetter(p -> Optional.ofNullable(p.b))
    ).apply(instance, (id, a, b) -> new PortalPair(id, a, b.orElse(null))));

    private final UUID id;
    private final PortalEnd a;
    private final PortalEnd b; // null = pending

    public PortalPair(UUID id, PortalEnd a, PortalEnd b) {
        this.id = id;
        this.a = a;
        this.b = b;
    }

    public static PortalPair pending(UUID id, PortalEnd a) {
        return new PortalPair(id, a, null);
    }

    public boolean isLinked() {
        return this.b != null;
    }

    /** This pair with its second end filled in (same UUID). */
    public PortalPair linkedWith(PortalEnd newB) {
        return new PortalPair(this.id, this.a, newB);
    }

    /** This pair reduced to one surviving end, back in pending state (same UUID). */
    public PortalPair asPendingSurvivor(PortalEnd survivor) {
        return new PortalPair(this.id, survivor, null);
    }

    public UUID getId() {
        return this.id;
    }

    public PortalEnd getA() {
        return this.a;
    }

    /** Null while pending. */
    public PortalEnd getB() {
        return this.b;
    }

    /** The opposite end from the one given (the destination when crossing {@code source}). */
    public PortalEnd linkFor(PortalEnd source) {
        return source.equals(this.a) ? this.b : this.a;
    }

    /** Tolerance below which the partner mouth counts as coplanar with this end's plane. */
    private static final double COPLANAR_EPS = 1.0e-4;

    /**
     * True if {@code observerPos} is on {@code source}'s ACTIVE (outer) side — the half-space
     * facing away from the partner mouth. The inner side (facing the partner, i.e. the space
     * between the two mouths) is inert: it neither renders the see-through view nor teleports, so
     * each end behaves as a one-way wormhole mouth. Requires this pair to be linked.
     *
     * <p>The partner's center defines the inner side. If the partner is coplanar with this plane
     * (a degenerate side-by-side layout, no well-defined inner side), the end stays active so it
     * never silently vanishes.
     */
    public boolean isActiveSideFor(PortalEnd source, Vec3 observerPos) {
        PortalEnd partner = linkFor(source);
        Vec3 n = source.getNormal();
        Vec3 c = source.getCenter();
        double observerSide = n.dot(observerPos.subtract(c));
        double partnerSide = n.dot(partner.getCenter().subtract(c));
        if (Math.abs(partnerSide) < COPLANAR_EPS) {
            return true;
        }
        return observerSide * partnerSide < 0.0;
    }

    public Vec3 transformTeleportPosition(PortalEnd source, Vec3 pos) {
        return PortalTransform.transformTeleportPoint(source, linkFor(source), pos);
    }

    public Vec3 transformVelocity(PortalEnd source, Vec3 velocity) {
        return PortalTransform.transformVector(source, linkFor(source), velocity);
    }

    public float transformYaw(PortalEnd source, float yaw) {
        return PortalTransform.transformYaw(source, linkFor(source), yaw);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(this.id);
        this.a.write(buf);
        buf.writeBoolean(this.b != null);
        if (this.b != null) {
            this.b.write(buf);
        }
    }

    public static PortalPair read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        PortalEnd a = PortalEnd.read(buf);
        PortalEnd b = buf.readBoolean() ? PortalEnd.read(buf) : null;
        return new PortalPair(id, a, b);
    }
}
