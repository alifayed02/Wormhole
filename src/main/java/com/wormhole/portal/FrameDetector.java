package com.wormhole.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Detects a completed diamond-block frame after a diamond block is placed.
 *
 * <p>Frame rules (spec): vertical rectangle in an X- or Z-aligned plane, interior all air,
 * interior 2..21 wide and 3..21 tall, bottom/top rows and side columns all diamond, corners
 * not required.
 */
public final class FrameDetector {
    public static final int MIN_WIDTH = 2;
    public static final int MIN_HEIGHT = 3;
    public static final int MAX_SIZE = 21;

    private FrameDetector() {
    }

    /** Returns the portal end completed by the diamond block at {@code framePos}, or null. */
    public static PortalEnd detect(ServerLevel level, BlockPos framePos) {
        for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            for (Direction dir : candidateDirections(axis)) {
                PortalEnd end = scanFrom(level, framePos.relative(dir), axis);
                if (end != null) {
                    return end;
                }
            }
        }
        return null;
    }

    /** The placed block can border the interior from below, above, or either side (in-plane). */
    private static Direction[] candidateDirections(Direction.Axis axis) {
        return axis == Direction.Axis.X
            ? new Direction[]{Direction.UP, Direction.DOWN, Direction.EAST, Direction.WEST}
            : new Direction[]{Direction.UP, Direction.DOWN, Direction.SOUTH, Direction.NORTH};
    }

    /** Tries to discover a complete frame whose interior contains {@code start}. */
    private static PortalEnd scanFrom(ServerLevel level, BlockPos start, Direction.Axis axis) {
        if (!isEmpty(level, start)) {
            return null;
        }
        Direction left = axis == Direction.Axis.X ? Direction.WEST : Direction.NORTH;
        Direction right = left.getOpposite();

        // 1. Descend to the bottom interior row; the block below it must be diamond.
        BlockPos cursor = start;
        int descended = 0;
        while (isEmpty(level, cursor.below())) {
            cursor = cursor.below();
            if (++descended > MAX_SIZE) {
                return null;
            }
        }
        if (!isFrame(level, cursor.below())) {
            return null;
        }

        // 2. Walk to the left edge along the bottom row (bottom must be diamond all along).
        BlockPos bottomLeft = cursor;
        int steps = 0;
        while (isEmpty(level, bottomLeft.relative(left))) {
            bottomLeft = bottomLeft.relative(left);
            if (++steps > MAX_SIZE || !isFrame(level, bottomLeft.below())) {
                return null;
            }
        }
        if (!isFrame(level, bottomLeft.relative(left))) {
            return null;
        }

        // 3. Measure the interior width along the bottom row.
        int width = 1;
        BlockPos p = bottomLeft;
        while (isEmpty(level, p.relative(right))) {
            p = p.relative(right);
            if (++width > MAX_SIZE || !isFrame(level, p.below())) {
                return null;
            }
        }
        if (!isFrame(level, p.relative(right)) || width < MIN_WIDTH) {
            return null;
        }

        // 4. Measure the interior height: full-width air rows with diamond side columns.
        int height = 0;
        while (true) {
            if (height > MAX_SIZE) {
                return null;
            }
            BlockPos rowLeft = bottomLeft.above(height);
            boolean rowAir = true;
            for (int w = 0; w < width; w++) {
                if (!isEmpty(level, rowLeft.relative(right, w))) {
                    rowAir = false;
                    break;
                }
            }
            if (!rowAir) {
                break; // candidate top-border row
            }
            if (!isFrame(level, rowLeft.relative(left)) || !isFrame(level, rowLeft.relative(right, width))) {
                return null;
            }
            height++;
        }
        if (height < MIN_HEIGHT) {
            return null;
        }

        // 5. The top border must be diamond across every interior column.
        BlockPos topRow = bottomLeft.above(height);
        for (int w = 0; w < width; w++) {
            if (!isFrame(level, topRow.relative(right, w))) {
                return null;
            }
        }

        return new PortalEnd(level.dimension(), bottomLeft, axis, width, height);
    }

    private static boolean isEmpty(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir();
    }

    private static boolean isFrame(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.DIAMOND_BLOCK);
    }
}
