package smooth.lift;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;

public final class EscalatorUtil {
    private static final int MAX_CHAIN_BLOCKS = 4096;
    private static final int MAX_WALK_STEPS = 512;
    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 疑似扶梯阶梯方块的缓存（类型判断按类名做，避免编译期依赖 MTR）。 */
    private static Set<Block> escalatorStepBlocks;

    private EscalatorUtil() {
    }

    public static boolean isEscalator(BlockState state) {
        return state.getBlock().getClass().getName().toLowerCase().contains("escalator");
    }

    /**
     * 是否是「阶梯」方块（MTR 的 BlockEscalatorStep）。
     *
     * <p>只有阶梯方块才有会动的阶梯贴图；两边的侧板（BlockEscalatorSide）用的是静态贴图，
     * 不需要 SmoothLift 逐条渲染，所以这里要和 {@link #isEscalator} 区分开。
     */
    public static boolean isEscalatorStep(BlockState state) {
        return state != null && isEscalatorStepBlock(state.getBlock());
    }

    public static boolean isEscalatorStepBlock(Block block) {
        return escalatorStepBlocks().contains(block);
    }

    /**
     * 遍历方块注册表，缓存所有类名里带 escalatorstep 的方块。
     * 用 HashSet&lt;Block&gt; 做 O(1) 判定 —— 区块扫描时每格都要判断，不能每次去 toLowerCase()。
     */
    private static Set<Block> escalatorStepBlocks() {
        Set<Block> cached = escalatorStepBlocks;
        if (cached == null) {
            Set<Block> found = new HashSet<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block.getClass().getName().toLowerCase(Locale.ROOT).contains("escalatorstep")) {
                    found.add(block);
                }
            }
            if (found.isEmpty()) {
                LOGGER.warn("[SmoothLift] 没有在方块注册表里找到扶梯阶梯方块（MTR 未安装或版本不匹配？）");
            }
            escalatorStepBlocks = cached = found;
        }
        return cached;
    }

    /** 读取指定名字的布尔属性；不存在时返回 fallback。MTR 的属性靠名字访问，避免编译期依赖。 */
    public static boolean getBooleanProperty(BlockState state, String name, boolean fallback) {
        for (Property<?> prop : state.getProperties()) {
            if (name.equals(prop.getName()) && prop.getValueClass() == Boolean.class) {
                Object value = state.getValue(prop);
                if (value instanceof Boolean bool) {
                    return bool;
                }
            }
        }
        return fallback;
    }

    /**
     * 收集与 seed 相连的同一条扶梯链上的所有方块。
     *
     * <p>MTR 的一条扶梯其实是**两列并排**的阶梯：放一个扶梯会生成
     * {@code side=left} 与 {@code side=right} 两列（right 列永远在 left 列沿 facing 顺时针
     * 一格的位置，见 MTR 的 ItemEscalator 放置逻辑），人看到的是左右各半边。
     * 所以石斧调速时只走一条列会出现「只能半边半边地调」。
     *
     * <p>这里在沿 facing 轴走完 seed 所在那一列之后，再找到相邻的「另一半」列并把它整条也收进来，
     * 于是**调一边就同步两边**。判定伙伴列时要求 facing 相同、side 相反，避免把并排的另一条
     * 扶梯（例如相邻的另一组左右双列）误并入同一条。
     *
     * <p>没有 facing 属性时退化为 6 向泛洪。
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

            BlockPos partnerSeed = findPartnerSeed(level, seed, facing);
            if (partnerSeed != null && !chain.contains(partnerSeed)) {
                walkAxis(level, partnerSeed, facing, chain);
                walkAxis(level, partnerSeed, facing.getOpposite(), chain);
            }
        } else {
            floodFill(level, seed, chain);
        }
        return chain;
    }

    /**
     * 找出 seed 这块所在的扶梯「另一半」列上、与它同高（或高/低一格）的那块。
     *
     * <p>MTR 的 right 列 = left 列沿 facing 顺时针一格；因此左半边往顺时针找、右半边往逆时针找。
     * 找到的块必须 facing 相同且 side 相反，否则视为不相干的扶梯，返回 null。
     */
    private static BlockPos findPartnerSeed(Level level, BlockPos seed, Direction facing) {
        String side = getSideName(level.getBlockState(seed));
        Direction partnerDir;
        if ("right".equals(side)) {
            partnerDir = facing.getCounterClockWise();
        } else if ("left".equals(side)) {
            partnerDir = facing.getClockWise();
        } else {
            // 没有 side 属性：两条垂直方向都试一下，谁匹配用谁。
            BlockPos cw = matchPartner(level, seed.relative(facing.getClockWise()), facing, null);
            if (cw != null) {
                return cw;
            }
            return matchPartner(level, seed.relative(facing.getCounterClockWise()), facing, null);
        }
        return matchPartner(level, seed.relative(partnerDir), facing, side);
    }

    /** 校验候选块是不是「另一半」：同 facing、（可选）side 相反；同高找不到就试上下各一格（斜面错位）。 */
    private static BlockPos matchPartner(Level level, BlockPos candidate, Direction facing, String seedSide) {
        for (int dy = 0; dy >= -1; dy--) {
            BlockPos pos = dy == 0 ? candidate : candidate.below();
            BlockState state = level.getBlockState(pos);
            if (!isEscalator(state)) {
                continue;
            }
            Direction otherFacing = getFacing(state);
            if (otherFacing != null && otherFacing != facing) {
                continue;
            }
            if (seedSide != null) {
                String otherSide = getSideName(state);
                if (otherSide != null && otherSide.equals(seedSide)) {
                    continue;
                }
            }
            return pos;
        }
        return null;
    }

    /** 读取名为 side 的属性值（LEFT / RIGHT），小写返回；没有返回 null。 */
    private static String getSideName(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if ("side".equals(prop.getName())) {
                Object value = state.getValue(prop);
                if (value != null) {
                    return value.toString().toLowerCase(Locale.ROOT);
                }
            }
        }
        return null;
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
