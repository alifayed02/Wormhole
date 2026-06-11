package com.wormhole.portal;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/**
 * A linked pair of portal ends in the same dimension. Crossing either end teleports you to the other.
 * The transform between the ends is derived from their origin/center/axis -- no stored matrix.
 */
public final class PortalPair {
    private final UUID id;
    private final PortalEnd a;
    private final PortalEnd b;

    public PortalPair(UUID id, PortalEnd a, PortalEnd b) {
        this.id = id;
        this.a = a;
        this.b = b;
    }

    public UUID getId() {
        return this.id;
    }

    public PortalEnd getA() {
        return this.a;
    }

    public PortalEnd getB() {
        return this.b;
    }

    /** The opposite end from the one given (the destination when crossing {@code source}). */
    public PortalEnd linkFor(PortalEnd source) {
        return source == this.a ? this.b : this.a;
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
        this.b.write(buf);
    }

    public static PortalPair read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        PortalEnd a = PortalEnd.read(buf);
        PortalEnd b = PortalEnd.read(buf);
        return new PortalPair(id, a, b);
    }
}
