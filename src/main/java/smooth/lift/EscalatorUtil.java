package smooth.lift;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.Set;

public final class EscalatorUtil {
    private static final int MAX_CHAIN_BLOCKS = 4096;
    private static final int MAX_WALK_STEPS = 512;

    private EscalatorUtil() {
    }

    public static boolean isEscalator(BlockState state) {
        return state.getBlock().getClass().getName().toLowerCase().contains("escalator");
    }

    /**
     * 收集与 seed 相连的同一条扶梯链上的所有方块。
     * 优先沿 facing 轴行走（与乘坐逻辑一致），避免把并排的上下行扶梯算进同一条链；
     * 没有 facing 属性时退化为 6 向泛洪。
     */
    public static Set<BlockPos> collectChain(Level level, BlockPos seed) {
        Set<BlockPos> chain = new LinkedHashSet<>();
        if (!isEscalator(level.getBlockState(seed))) {
            return chain;
        }

        Direction facing = getFacing(level.getBlockState(seed));
        if (facing != null) {
            walkAxis(level, seed, facing, chain);
            walkAxis(level, seed, facing.getOpposite(), chain);
        } else {
            floodFill(level, seed, chain);
        }
        return chain;
    }

    private static void walkAxis(Level level, BlockPos start, Direction dir, Set<BlockPos> out) {
        BlockPos current = start;
        for (int i = 0; i < MAX_WALK_STEPS && out.size() < MAX_CHAIN_BLOCKS; i++) {
            out.add(current);

            BlockPos ahead = current.relative(dir);
            BlockPos next;
            if (isEscalator(level.getBlockState(ahead))) {
                next = ahead;
            } else if (isEscalator(level.getBlockState(ahead.above()))) {
                next = ahead.above();
            } else if (isEscalator(level.getBlockState(ahead.below()))) {
                next = ahead.below();
            } else {
                break;
            }
            current = next;
        }
    }

    private static void floodFill(Level level, BlockPos seed, Set<BlockPos> out) {
        Set<BlockPos> visited = new LinkedHashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed);

        while (!queue.isEmpty() && visited.size() < MAX_CHAIN_BLOCKS) {
            BlockPos pos = queue.poll();
            out.add(pos);
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                if (visited.add(neighbor) && isEscalator(level.getBlockState(neighbor))) {
                    queue.add(neighbor);
                }
            }
        }
    }

    private static Direction getFacing(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if ("facing".equals(prop.getName()) && prop.getValueClass() == Direction.class) {
                return (Direction) state.getValue(prop);
            }
        }
        return null;
    }
}
