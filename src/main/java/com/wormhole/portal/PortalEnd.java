package com.wormhole.portal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One end of a portal: a rectangular opening in a vertical plane, axis-aligned to X or Z,
 * bounded by a diamond-block frame. {@code origin} is the interior's minimum corner;
 * width/height describe the interior (air) rectangle, not the frame.
 *
 * <p>Same-dimension port of SeamlessPortals' {@code PortalInfo}. An axis-X end is a Z-facing
 * plane spanning {@code [x, x+width]} by {@code [y, y+height]} at one block of depth; an
 * axis-Z end is the X-facing mirror.
 */
public final class PortalEnd {
    public static final Codec<PortalEnd> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(PortalEnd::getDimension),
        BlockPos.CODEC.fieldOf("origin").forGetter(PortalEnd::getOrigin),
        Direction.Axis.CODEC.fieldOf("axis").forGetter(PortalEnd::getAxis),
        Codec.intRange(1, 21).fieldOf("width").forGetter(PortalEnd::getWidth),
        Codec.intRange(1, 21).fieldOf("height").forGetter(PortalEnd::getHeight)
    ).apply(instance, PortalEnd::new));

    private final ResourceKey<Level> dimension;
    private final BlockPos origin;
    private final Direction.Axis axis;
    private final int width;
    private final int height;
    private final AABB boundingBox;
    private final Vec3 center;
    private final Vec3 normal;

    public PortalEnd(ResourceKey<Level> dimension, BlockPos origin, Direction.Axis axis, int width, int height) {
        if (axis != Direction.Axis.X && axis != Direction.Axis.Z) {
            throw new IllegalArgumentException("Portal axis must be X or Z, got " + axis);
        }
        this.dimension = dimension;
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
     * True if the segment {@code from -> to} crosses this end's center plane within the opening
     * rectangle (small lateral/vertical slack so a body brushing the edge still counts). This is
     * the teleport trigger: segment-based, so it cannot tunnel at any speed, and it fires exactly
     * at the visual plane — where the thickness-protected portal surface fills the screen and the
     * world swap is invisible.
     */
    public boolean crossesPlane(Vec3 from, Vec3 to) {
        double planeCoord = this.axis == Direction.Axis.X ? this.center.z : this.center.x;
        double fromCoord = this.axis == Direction.Axis.X ? from.z : from.x;
        double toCoord = this.axis == Direction.Axis.X ? to.z : to.x;
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
        double slack = 0.35;
        double lat = this.axis == Direction.Axis.X ? hit.x : hit.z;
        double latOrigin = this.axis == Direction.Axis.X ? this.origin.getX() : this.origin.getZ();
        return lat >= latOrigin - slack && lat <= latOrigin + this.width + slack
            && hit.y >= this.origin.getY() - 0.3 && hit.y <= this.origin.getY() + this.height;
    }

    // ----- frame geometry (used by detection and destruction) -----

    /** True if {@code pos} is one of this end's interior (air) cells. */
    public boolean interiorContains(BlockPos pos) {
        int lat = this.axis == Direction.Axis.X ? pos.getX() : pos.getZ();
        int plane = this.axis == Direction.Axis.X ? pos.getZ() : pos.getX();
        int latOrigin = this.axis == Direction.Axis.X ? origin.getX() : origin.getZ();
        int planeOrigin = this.axis == Direction.Axis.X ? origin.getZ() : origin.getX();
        return plane == planeOrigin
            && lat >= latOrigin && lat < latOrigin + this.width
            && pos.getY() >= origin.getY() && pos.getY() < origin.getY() + this.height;
    }

    /** True if {@code pos} is a structural frame block (bottom/top rows over the interior columns,
     *  or side columns over the interior rows; corners are not structural). */
    public boolean isFrameBlock(BlockPos pos) {
        int lat = this.axis == Direction.Axis.X ? pos.getX() : pos.getZ();
        int plane = this.axis == Direction.Axis.X ? pos.getZ() : pos.getX();
        int latOrigin = this.axis == Direction.Axis.X ? origin.getX() : origin.getZ();
        int planeOrigin = this.axis == Direction.Axis.X ? origin.getZ() : origin.getX();
        if (plane != planeOrigin) {
            return false;
        }
        boolean inLat = lat >= latOrigin && lat < latOrigin + this.width;
        boolean inY = pos.getY() >= origin.getY() && pos.getY() < origin.getY() + this.height;
        boolean bottomOrTop = inLat && (pos.getY() == origin.getY() - 1 || pos.getY() == origin.getY() + this.height);
        boolean side = inY && (lat == latOrigin - 1 || lat == latOrigin + this.width);
        return bottomOrTop || side;
    }

    /** True if the two ends occupy overlapping space in the same dimension (used to reject duplicates). */
    public boolean overlaps(PortalEnd other) {
        return this.dimension.equals(other.dimension)
            && this.boundingBox.inflate(0.5).intersects(other.boundingBox);
    }

    // ----- accessors -----

    public ResourceKey<Level> getDimension() {
        return this.dimension;
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

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PortalEnd other
            && this.dimension.equals(other.dimension)
            && this.origin.equals(other.origin)
            && this.axis == other.axis
            && this.width == other.width
            && this.height == other.height;
    }

    @Override
    public int hashCode() {
        return this.origin.hashCode() * 31 + this.axis.hashCode();
    }

    // ----- network -----

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceKey(this.dimension);
        buf.writeBlockPos(this.origin);
        buf.writeBoolean(this.axis == Direction.Axis.X);
        buf.writeVarInt(this.width);
        buf.writeVarInt(this.height);
    }

    public static PortalEnd read(FriendlyByteBuf buf) {
        ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
        BlockPos origin = buf.readBlockPos();
        Direction.Axis axis = buf.readBoolean() ? Direction.Axis.X : Direction.Axis.Z;
        int width = buf.readVarInt();
        int height = buf.readVarInt();
        return new PortalEnd(dimension, origin, axis, width, height);
    }
}
