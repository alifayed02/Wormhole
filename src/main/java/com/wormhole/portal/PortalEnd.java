package com.wormhole.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One end of a portal: a rectangular plane in the world, axis-aligned to X or Z.
 *
 * <p>Same-dimension port of SeamlessPortals' {@code PortalInfo} (dimension/type/coordinate-scale
 * fields removed). Geometry: an axis-X end is a Z-facing plane spanning {@code [x, x+width]} by
 * {@code [y, y+height]} at one block of depth; an axis-Z end is the X-facing mirror.
 */
public final class PortalEnd {
    private final BlockPos origin;
    private final Direction.Axis axis;
    private final int width;
    private final int height;
    private final AABB boundingBox;
    private final Vec3 center;
    private final Vec3 normal;

    public PortalEnd(BlockPos origin, Direction.Axis axis, int width, int height) {
        if (axis != Direction.Axis.X && axis != Direction.Axis.Z) {
            throw new IllegalArgumentException("Portal axis must be X or Z, got " + axis);
        }
        this.origin = origin.immutable();
        this.axis = axis;
        this.width = width;
        this.height = height;
        this.boundingBox = this.computeBoundingBox();
        this.center = this.boundingBox.getCenter();
        this.normal = axis == Direction.Axis.X ? new Vec3(0.0, 0.0, 1.0) : new Vec3(1.0, 0.0, 0.0);
    }

    private AABB computeBoundingBox() {
        return this.axis == Direction.Axis.X
            ? new AABB(origin.getX(), origin.getY(), origin.getZ(),
                       origin.getX() + width, origin.getY() + height, origin.getZ() + 1)
            : new AABB(origin.getX(), origin.getY(), origin.getZ(),
                       origin.getX() + 1, origin.getY() + height, origin.getZ() + width);
    }

    public boolean containsPoint(Vec3 point) {
        return this.boundingBox.inflate(0.1).contains(point);
    }

    /**
     * Radius of the spherical mouth: the largest sphere inscribed in the {@code width x height}
     * opening, so the orb fits cleanly within the declared footprint.
     */
    public double getRadius() {
        return Math.min(this.width, this.height) / 2.0;
    }

    /** True if the segment {@code from -> to} crosses this end's plane within the mouth's radius. */
    public boolean intersectsMovement(Vec3 from, Vec3 to) {
        double planeCoord = this.axis == Direction.Axis.X ? this.center.z : this.center.x;
        double fromCoord = this.axis == Direction.Axis.X ? from.z : from.x;
        double toCoord = this.axis == Direction.Axis.X ? to.z : to.x;
        // Both points on the same side of the plane -> no crossing.
        if ((fromCoord - planeCoord) * (toCoord - planeCoord) > 0.0) {
            return false;
        }
        double denom = toCoord - fromCoord;
        if (denom == 0.0) {
            return false;
        }
        double t = (planeCoord - fromCoord) / denom;
        if (t < 0.0 || t > 1.0) {
            return false;
        }
        Vec3 hit = from.lerp(to, t);
        return this.isWithinMouth(hit);
    }

    /** True if {@code point} lies within the circular silhouette of the mouth, in the portal plane. */
    private boolean isWithinMouth(Vec3 point) {
        double dy = point.y - this.center.y;
        double dLat = this.axis == Direction.Axis.X ? point.x - this.center.x : point.z - this.center.z;
        double r = this.getRadius();
        return dy * dy + dLat * dLat <= r * r;
    }

    public Vec3 getIntersectionPoint(Vec3 from, Vec3 to) {
        double planeCoord = this.axis == Direction.Axis.X ? this.center.z : this.center.x;
        double fromCoord = this.axis == Direction.Axis.X ? from.z : from.x;
        double toCoord = this.axis == Direction.Axis.X ? to.z : to.x;
        double t = (planeCoord - fromCoord) / (toCoord - fromCoord);
        return from.lerp(to, t);
    }

    public BlockPos getOrigin() {
        return this.origin;
    }

    public Direction.Axis getAxis() {
        return this.axis;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public AABB getBoundingBox() {
        return this.boundingBox;
    }

    public Vec3 getCenter() {
        return this.center;
    }

    public Vec3 getNormal() {
        return this.normal;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.origin);
        buf.writeBoolean(this.axis == Direction.Axis.X);
        buf.writeVarInt(this.width);
        buf.writeVarInt(this.height);
    }

    public static PortalEnd read(FriendlyByteBuf buf) {
        BlockPos origin = buf.readBlockPos();
        Direction.Axis axis = buf.readBoolean() ? Direction.Axis.X : Direction.Axis.Z;
        int width = buf.readVarInt();
        int height = buf.readVarInt();
        return new PortalEnd(origin, axis, width, height);
    }
}
