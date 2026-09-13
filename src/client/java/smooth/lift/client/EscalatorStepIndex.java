package smooth.lift.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import smooth.lift.EscalatorUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 「已加载区块里有哪些扶梯阶梯方块」的索引。
 *
 * <p>逐条驱动阶梯动画需要每帧遍历视野内的阶梯方块，不可能每帧去扫区块，
 * 所以这里在区块加载时扫一次建索引，之后每 {@link #RESCAN_INTERVAL} 刻
 * 全量重扫一遍已加载区块来跟上玩家的增删。
 *
 * <p>扫描用 {@link LevelChunkSection#maybeHas} 先按方块调色板做一次廉价的预筛，
 * 只有真的可能含有扶梯阶梯方块的分段才会逐格遍历，代价很小。
 *
 * <p>{@link #positions()} 与 {@link #boxes()} 是一一对应的平行数组，一次重建同时产出。
 * 为什么要把 {@link AABB} 也缓存下来：阶梯面是 SmoothLift 独占绘制的（原版那份已被全透明
 * 底图隐藏），所以视锥剔除不能漏 —— 而 {@code AABB} 的字段是 final，没法复用同一个实例，
 * 每帧现造会在远处造成大量垃圾，所以跟着索引一起建、一直用。
 */
public final class EscalatorStepIndex {

    private static final Predicate<BlockState> IS_STEP = EscalatorUtil::isEscalatorStep;
    /** 全量重扫间隔（刻）。玩家在已加载区块里新建/拆除扶梯后，最多这么久就会出现在索引里。 */
    private static final int RESCAN_INTERVAL = 10;
    /** 方块盒子向外扩一点，避免贴边的方块被视锥误剔（会直接表现为「露空」）。 */
    private static final double BOX_INFLATE = 1.0;

    private static ResourceKey<Level> dimension;
    private static final Map<Long, List<BlockPos>> BY_CHUNK = new HashMap<>();
    private static final List<LevelChunk> LOADED = new ArrayList<>();
    private static List<BlockPos> flattened = List.of();
    private static AABB[] flattenedBoxes = new AABB[0];
    private static boolean dirty = true;
    private static int countdown = RESCAN_INTERVAL;

    private EscalatorStepIndex() {
    }

    public static void register() {
        ClientChunkEvents.CHUNK_LOAD.register(EscalatorStepIndex::onChunkLoad);
        ClientChunkEvents.CHUNK_UNLOAD.register(EscalatorStepIndex::onChunkUnload);
    }

    /** 当前维度已加载区块里的所有扶梯阶梯方块（只在内容变化时重算）。 */
    public static List<BlockPos> positions() {
        rebuildIfDirty();
        return flattened;
    }

    /** 与 {@link #positions()} 一一对应的方块盒子，给视锥剔除用。 */
    public static AABB[] boxes() {
        rebuildIfDirty();
        return flattenedBoxes;
    }

    private static void rebuildIfDirty() {
        if (!dirty) {
            return;
        }
        int total = 0;
        for (List<BlockPos> list : BY_CHUNK.values()) {
            total += list.size();
        }
        List<BlockPos> all = new ArrayList<>(total);
        AABB[] boxes = new AABB[total];
        int index = 0;
        for (List<BlockPos> list : BY_CHUNK.values()) {
            for (BlockPos pos : list) {
                all.add(pos);
                boxes[index++] = new AABB(pos).inflate(BOX_INFLATE);
            }
        }
        flattened = all;
        flattenedBoxes = boxes;
        dirty = false;
    }

    public static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
        if (!acceptDimension(level)) {
            return;
        }
        if (!LOADED.contains(chunk)) {
            LOADED.add(chunk);
        }
        List<BlockPos> found = scan(chunk);
        BY_CHUNK.put(chunk.getPos().toLong(), found);
        dirty = true;
    }

    public static void onChunkUnload(ClientLevel level, LevelChunk chunk) {
        if (dimension != null && !dimension.equals(level.dimension())) {
            return;
        }
        LOADED.remove(chunk);
        if (BY_CHUNK.remove(chunk.getPos().toLong()) != null) {
            dirty = true;
        }
    }

    /** 每客户端刻调用：换维度就清空，并周期性地全量重扫所有已加载区块跟上增删。 */
    public static void tick(ClientLevel level) {
        if (level == null) {
            reset();
            return;
        }
        if (!acceptDimension(level)) {
            reset();
            dimension = level.dimension();
            return;
        }
        if (LOADED.isEmpty()) {
            return;
        }
        if (--countdown > 0) {
            return;
        }
        countdown = RESCAN_INTERVAL;
        rescanAll();
    }

    /**
     * 全量重扫一次所有已加载区块。
     *
     * <p>旧版是「每刻只轮询扫一个区块」，区块多时（城市档几百上千个区块）新建扶梯要等
     * 几分钟才被扫到，期间它的阶梯面无人重绘，看起来就是「贴图消失」。
     * 改成周期全量重扫后，新建/拆除扶梯最多 {@link #RESCAN_INTERVAL} 刻就会反映出来。
     * 扫描成本被 {@code maybeHas} 调色板预筛压得很低，全量重扫的开销可以忽略。
     */
    private static void rescanAll() {
        for (LevelChunk chunk : LOADED) {
            long key = chunk.getPos().toLong();
            List<BlockPos> previous = BY_CHUNK.get(key);
            List<BlockPos> fresh = scan(chunk);
            if (!fresh.equals(previous)) {
                BY_CHUNK.put(key, fresh);
                dirty = true;
            }
        }
    }

    public static void reset() {
        dimension = null;
        BY_CHUNK.clear();
        LOADED.clear();
        flattened = List.of();
        flattenedBoxes = new AABB[0];
        dirty = true;
        countdown = RESCAN_INTERVAL;
    }

    private static boolean acceptDimension(ClientLevel level) {
        if (dimension == null) {
            dimension = level.dimension();
            return true;
        }
        return dimension.equals(level.dimension());
    }

    private static List<BlockPos> scan(LevelChunk chunk) {
        List<BlockPos> found = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        ChunkPos chunkPos = chunk.getPos();
        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();
        int bottom = chunk.getMinBuildHeight();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(IS_STEP)) {
                continue;
            }
            int baseY = bottom + index * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (EscalatorUtil.isEscalatorStep(section.getBlockState(x, y, z))) {
                            found.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
            }
        }
        return found;
    }
}
