package com.wormhole.portal;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Coordinate transforms between two linked portal ends in the SAME dimension.
 *
 * <p>Same-dimension port of SeamlessPortals' {@code PortalTransform} (the nether coordinate-scale
 * path is removed). Works in a local frame {@code (depth, width, height)} where depth is the
 * portal-normal axis. The render transform keeps depth; the teleport transform negates it (so you
 * come out the far side facing forward).
 */
public final class PortalTransform {
    private PortalTransform() {
    }

    /** Camera/render transform: where a source-side point maps to for rendering the destination. */
    public static Vec3 transformPoint(PortalEnd source, PortalEnd destination, Vec3 sourcePos) {
        Vec3 offset = sourcePos.subtract(source.getCenter());
        LocalCoords local = toLocal(source.getAxis(), offset);
        Vec3 horizontal = fromLocal(destination.getAxis(), new LocalCoords(local.depth(), local.width(), 0.0));
        double heightFromFloor = sourcePos.y() - source.getOrigin().getY();
        Vec3 destCenter = destination.getCenter();
        return new Vec3(destCenter.x() + horizontal.x(),
                        destination.getOrigin().getY() + heightFromFloor,
                        destCenter.z() + horizontal.z());
    }

    /**
     * Teleport transform (port of SeamlessPortals' {@code transformTeleportPoint}). The depth is
     * NEGATED: the entity emerges on the mirror side of the destination, which is consistent with
     * what the portal showed (the render keeps +depth, but at the crossing the entity is on the
     * plane so depth ~ 0 and the two agree). Ping-pong is prevented by the volume-exit suppression in
     * {@code ClientPortalTeleport}, not by dropping the negation.
     */
    public static Vec3 transformTeleportPoint(PortalEnd source, PortalEnd destination, Vec3 sourcePos) {
        Vec3 offset = sourcePos.subtract(source.getCenter());
        LocalCoords local = toLocal(source.getAxis(), offset);
        Vec3 horizontal = fromLocal(destination.getAxis(), new LocalCoords(-local.depth(), local.width(), 0.0));
        double heightFromFloor = sourcePos.y() - source.getOrigin().getY();
        Vec3 destCenter = destination.getCenter();
        return new Vec3(destCenter.x() + horizontal.x(),
                        destination.getOrigin().getY() + heightFromFloor,
                        destCenter.z() + horizontal.z());
    }

    /** Velocity/direction transform (port of SeamlessPortals' {@code transformVector}): depth negated
     *  so motion stays consistent with the negated position transform and the render camera. */
    public static Vec3 transformVector(PortalEnd source, PortalEnd destination, Vec3 vector) {
        LocalCoords local = toLocal(source.getAxis(), vector);
        return fromLocal(destination.getAxis(), new LocalCoords(-local.depth(), local.width(), local.height()));
    }

    /** Adjusts yaw by the axis difference between the two ends (0 or +/-90 degrees). */
    public static float transformYaw(PortalEnd source, PortalEnd destination, float sourceYaw) {
        return sourceYaw + yawDelta(source, destination);
    }

    public static float yawDelta(PortalEnd source, PortalEnd destination) {
        if (source.getAxis() == destination.getAxis()) {
            return 0.0F;
        }
        return source.getAxis() == Direction.Axis.Z ? 90.0F : -90.0F;
    }

    private static LocalCoords toLocal(Direction.Axis axis, Vec3 vector) {
        return axis == Direction.Axis.X
            ? new LocalCoords(vector.z, vector.x, vector.y)
            : new LocalCoords(vector.x, vector.z, vector.y);
    }

    private static Vec3 fromLocal(Direction.Axis axis, LocalCoords local) {
        return axis == Direction.Axis.X
            ? new Vec3(local.width(), local.height(), local.depth())
            : new Vec3(local.depth(), local.height(), local.width());
    }

    private record LocalCoords(double depth, double width, double height) {
    }
}
