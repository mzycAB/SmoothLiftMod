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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;

public final class EscalatorUtil {
    private static final int MAX_CHAIN_BLOCKS = 4096;
    private static final int MAX_WALK_STEPS = 512;
    /** 并入「另一半」列时最多迭代几轮（通常 2 轮就收敛）。 */
    private static final int MAX_PARTNER_PASSES = 4;
    /** 找伙伴块时允许的竖直错位范围（坡段/过渡段或两列没对齐时用得上）。 */
    private static final int PARTNER_DY = 2;
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
     * 是否是「侧板」方块（MTR 的 BlockEscalatorSide）。
     *
     * <p>MTR 放一格扶梯会同时生成台阶块与其正上方的侧板块；石斧调速要对两者一起生效，
     * 因为乘坐/动画取速度时可能读到其中任意一块。判定同样按类名做，避免编译期依赖 MTR。
     */
    public static boolean isEscalatorSide(BlockState state) {
        return state != null
                && state.getBlock().getClass().getName().toLowerCase(Locale.ROOT).contains("escalatorside");
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
     * 一格的位置，见 MTR 的 ItemEscalator 放置逻辑与 IBlock.getSideDirection），
     * 人看到的是左右各半边。所以石斧调速时只走 seed 所在的一条列，就会出现
     * 「只能半边半边地调」——必须把「另一半」列也并进来，才能一下调整条扶梯。
     *
     * <p>做法：先沿 facing 轴走完 seed 所在那一列（含坡段/过渡段的上下错位）；
     * 再从**链上每一格**出发，朝垂直于 facing 的方向找「另一半」列上的对应块，
     * 找到就把它那一列整条也收进来；反复几轮直到不再出现新的伙伴列。
     * 从每一格出发（而不只是 seed 那一块）能覆盖 seed 落在平台/过渡段的边角情况。
     *
     * <p>判定伙伴块要求 facing 相同、side 相反，避免把并排的另一条扶梯
     * （例如相邻的另一组左右双列、或上下行并排的那一条）误并入同一条。
     *
     * <p>没有 facing 属性时退化为 6 向泛洪。
     */
    public static Set<BlockPos> collectChain(Level level, BlockPos seed) {
        Set<BlockPos> chain = new LinkedHashSet<>();
        if (!isEscalator(level.getBlockState(seed))) {
            return chain;
        }

        Direction facing = getFacing(level.getBlockState(seed));
        if (facing == null) {
            floodFill(level, seed, chain);
            return chain;
        }

        // 1) seed 所在的那一整列。
        walkAxis(level, seed, facing, chain);
        walkAxis(level, seed, facing.getOpposite(), chain);

        // 2) 并入相邻的「另一半」列，直到不再出现新的伙伴列。
        for (int pass = 0; pass < MAX_PARTNER_PASSES; pass++) {
            int before = chain.size();
            for (BlockPos base : new ArrayList<>(chain)) {
                BlockPos partnerSeed = findPartnerSeed(level, base, facing);
                if (partnerSeed == null || chain.contains(partnerSeed)) {
                    continue;
                }
                walkAxis(level, partnerSeed, facing, chain);
                walkAxis(level, partnerSeed, facing.getOpposite(), chain);
            }
            if (chain.size() == before) {
                break;
            }
        }
        return chain;
    }

    /**
     * 找出 base 这块所在的扶梯「另一半」列上、与它对应（允许上下错位）的那块。
     *
     * <p>MTR 的 right 列 = left 列沿 facing 顺时针一格；因此左半边往顺时针找、
     * 右半边往逆时针找（side 读不到时按左半边处理）。首选方向找不到、或候选块的
     * side 不满足条件时，逐步放宽兜底（放开 side 限制 -&gt; 换相反方向），
     * 保证即使两列没对齐、或 side 属性异常也仍能配到伙伴列。
     */
    private static BlockPos findPartnerSeed(Level level, BlockPos base, Direction facing) {
        String side = getSideName(level.getBlockState(base));
        Direction primary = "right".equals(side)
                ? facing.getCounterClockWise()
                : facing.getClockWise();
        Direction secondary = primary.getOpposite();

        // 严格匹配（同 facing + side 相反）优先，避免误并并排的另一条扶梯。
        BlockPos found = matchPartner(level, base, primary, facing, side, true);
        if (found == null) {
            found = matchPartner(level, base, primary, facing, side, false);
        }
        if (found == null) {
            found = matchPartner(level, base, secondary, facing, side, true);
        }
        if (found == null) {
            found = matchPartner(level, base, secondary, facing, side, false);
        }
        return found;
    }

    /**
     * 在 base 沿 dir 方向、竖直 ±{@link #PARTNER_DY} 格范围内找「另一半」列上的块。
     *
     * @param requireOppositeSide true 时要求候选块的 side 与 base 相反
     *                            （用于严格匹配，避免误并并排的同向扶梯）
     */
    private static BlockPos matchPartner(Level level, BlockPos base, Direction dir, Direction facing,
                                         String seedSide, boolean requireOppositeSide) {
        // 优先同高（dy=0），再依次试上下各 1、2 格；同高命中时不会串到别的高度。
        for (int ring = 0; ring <= PARTNER_DY; ring++) {
            for (int sign = 0; sign < (ring == 0 ? 1 : 2); sign++) {
                int dy = ring == 0 ? 0 : (sign == 0 ? -ring : ring);
                if (Math.abs(dy) > PARTNER_DY) {
                    continue;
                }
                BlockPos pos = base.relative(dir).offset(0, dy, 0);
                BlockState state = level.getBlockState(pos);
                if (!isEscalator(state)) {
                    continue;
                }
                Direction otherFacing = getFacing(state);
                if (otherFacing != null && otherFacing != facing) {
                    continue;
                }
                if (requireOppositeSide && seedSide != null) {
                    String otherSide = getSideName(state);
                    if (otherSide != null && otherSide.equals(seedSide)) {
                        continue;
                    }
                }
                return pos;
            }
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
            addVerticalMate(level, current, out);

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

    /**
     * 把 current 这一格「竖直方向上的另一块」也收进来：台阶 ↔ 正上方的侧板。
     *
     * <p>MTR 放扶梯时台阶块与侧板块是同 x/z、上下相邻的一对；石斧点台阶还是点侧板
     * 都应该整条生效。只按「台阶↔侧板」这种**不同种类**的竖直邻居来配对，
     * 因此不会把上下层叠的其它扶梯（同种类方块）串进来。
     */
    private static void addVerticalMate(Level level, BlockPos current, Set<BlockPos> out) {
        BlockState state = level.getBlockState(current);
        BlockPos matePos;
        if (isEscalatorStep(state)) {
            matePos = current.above();
            if (!isEscalatorSide(level.getBlockState(matePos))) {
                return;
            }
        } else if (isEscalatorSide(state)) {
            matePos = current.below();
            if (!isEscalatorStep(level.getBlockState(matePos))) {
                return;
            }
        } else {
            return;
        }
        out.add(matePos);
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
