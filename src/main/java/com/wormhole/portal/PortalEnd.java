package com.wormhole.portal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One mouth of a wormhole: a sphere of {@code radius} centered at {@code center} in a single
 * dimension. Walking into the sphere teleports you to the partner mouth.
 *
 * <p>Mouths are orientation-less, so the crossing transform (in {@link PortalPair}) is a pure
 * translation between centers. The solid-color sphere drawn now is a placeholder for the
 * light-bending visuals planned later.
 */
public final class PortalEnd {
    public static final Codec<PortalEnd> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(PortalEnd::getDimension),
        Vec3.CODEC.fieldOf("center").forGetter(PortalEnd::getCenter),
        Codec.DOUBLE.fieldOf("radius").forGetter(PortalEnd::getRadius)
    ).apply(instance, PortalEnd::new));

    private final ResourceKey<Level> dimension;
    private final Vec3 center;
    private final double radius;

    public PortalEnd(ResourceKey<Level> dimension, Vec3 center, double radius) {
        this.dimension = dimension;
        this.center = center;
        this.radius = radius;
    }

    /** True if {@code point} lies inside the sphere. This is the teleport trigger volume. */
    public boolean containsPoint(Vec3 point) {
        return point.distanceToSqr(this.center) < this.radius * this.radius;
    }

    /** Axis-aligned box enclosing the sphere — broad-phase only (for the server entity query). */
    public AABB getBoundingBox() {
        return new AABB(
            center.x - radius, center.y - radius, center.z - radius,
            center.x + radius, center.y + radius, center.z + radius);
    }

    /** True if the two mouths overlap in the same dimension (used to reject duplicate creation). */
    public boolean overlaps(PortalEnd other) {
        if (!this.dimension.equals(other.dimension)) {
            return false;
        }
        double reach = this.radius + other.radius;
        return this.center.distanceToSqr(other.center) < reach * reach;
    }

    // ----- accessors -----

    public ResourceKey<Level> getDimension() {
        return this.dimension;
    }

    public Vec3 getCenter() {
        return this.center;
    }

    public double getRadius() {
        return this.radius;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PortalEnd other
            && this.dimension.equals(other.dimension)
            && this.center.equals(other.center)
            && this.radius == other.radius;
    }

    @Override
    public int hashCode() {
        return this.center.hashCode() * 31 + Double.hashCode(this.radius);
    }

    // ----- network -----

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceKey(this.dimension);
        buf.writeDouble(this.center.x);
        buf.writeDouble(this.center.y);
        buf.writeDouble(this.center.z);
        buf.writeDouble(this.radius);
    }

    public static PortalEnd read(FriendlyByteBuf buf) {
        ResourceKey<Level> dimension = buf.readResourceKey(Registries.DIMENSION);
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        double radius = buf.readDouble();
        return new PortalEnd(dimension, new Vec3(x, y, z), radius);
    }
}
