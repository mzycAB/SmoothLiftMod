package smooth.lift;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import smooth.lift.mixin.ChunkMapAccessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 速度与阶梯动画数据中枢：
 * - 服务端：每个维度一份 EscalatorSpeedData（SavedData，随世界保存）。
 * - 客户端：保存服务端同步过来的镜像，供客户端预测与贴图动画使用（和服务端一致）。
 *
 * <p>1.6 起阶梯动画速度是「每条扶梯单独设置」：设置了就用单独值；
 * 没设置的回退到「维度默认阶梯动画速度」（/jietispeed，仅当它被开启时）；
 * 连维度默认都没开时，**跟随这条扶梯自己的运行速度**。
 *
 * <p><b>贯穿所有指令与界面的统一规则：设置「扶梯速度」时「阶梯速度」一起跟随；
 * 设置「阶梯速度」时绝不动「扶梯速度」。</b>
 * 因此每一个写运行速度的入口（石斧界面、/futispeed 各分支）都会同时把阶梯速度
 * 置成同一个值（清掉单独阶梯设置，或镜像全局阶梯值）；而 /jietispeed 各分支只写阶梯速度。
 */
public final class EscalatorSpeedManager {
    private EscalatorSpeedManager() {
    }

    public static final class ClientDimensionData {
        public double defaultSpeed = EscalatorSpeedData.DEFAULT_SPEED;
        public final Map<BlockPos, Double> speeds = new HashMap<>();
        public final Map<BlockPos, Double> stepSpeeds = new HashMap<>();
        public boolean stepEnabled = false;
        public double stepValue = EscalatorSpeedData.DEFAULT_SPEED;
    }

    private static final Map<ResourceKey<Level>, ClientDimensionData> CLIENT_DATA = new HashMap<>();

    /** 运行速度：客户端读镜像，服务端读 SavedData。 */
    public static double getSpeed(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_SPEED;
            }
            Double speed = data.speeds.get(pos);
            return speed != null ? speed : data.defaultSpeed;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Double speed = data.speeds.get(pos);
        return speed != null ? speed : data.defaultSpeed;
    }

    /**
     * 阶梯动画设定速度（1.6：以「每条扶梯单独设置」为主）：
     * - 该扶梯被单独设置过阶梯动画速度 -> 用它的单独值；
     * - 否则 -> 用「维度默认阶梯动画速度」（/jietispeed 设定，未开启则为 MTR 原版 0.625）。
     */
    public static double getStepAnimationSpeed(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.VANILLA_STEP;
            }
            Double s = data.stepSpeeds.get(pos);
            if (s != null) {
                return s;
            }
            return data.stepEnabled ? data.stepValue : EscalatorSpeedData.VANILLA_STEP;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Double s = data.stepSpeeds.get(pos);
        if (s != null) {
            return s;
        }
        return data.defaultStepSpeed();
    }

    /** 维度默认阶梯动画速度：未单独设置阶梯动画的扶梯使用它。 */
    public static double getDefaultStepSpeed(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.VANILLA_STEP;
            }
            return data.stepEnabled ? data.stepValue : EscalatorSpeedData.VANILLA_STEP;
        }
        return getServerData((ServerLevel) level).defaultStepSpeed();
    }

    /** 查询某条扶梯单独设置的阶梯动画速度；未单独设置返回 null（此时跟随全局）。 */
    public static Double getIndividualStepSpeed(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? null : data.stepSpeeds.get(pos);
        }
        return getServerData((ServerLevel) level).stepSpeeds.get(pos);
    }

    /** 查询某条扶梯单独设置的运行速度；未单独设置返回 null（此时跟随全局运行速度）。 */
    public static Double getIndividualRunSpeed(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? null : data.speeds.get(pos);
        }
        return getServerData((ServerLevel) level).speeds.get(pos);
    }

    /** 维度默认阶梯动画速度当前是否启用（/jietispeed on / X 会开启，off 关闭）。 */
    public static boolean isStepEnabled(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data != null && data.stepEnabled;
        }
        return getServerData((ServerLevel) level).stepEnabled;
    }

    /**
     * 这条扶梯**实际使用**的阶梯动画速度。取值优先级：
     *
     * <ol>
     *   <li>这条扶梯被单独设置过阶梯速度 -&gt; 用单独值（石斧界面「阶梯速度」框）；</li>
     *   <li>否则如果它的**运行速度**被石斧单独改过 -&gt; 跟随它自己的运行速度
     *       （这条扶梯属于「专门调过的」，全局阶梯速度不再影响它
     *       —— 这正是「不会修改与全局不同的扶梯」）；</li>
     *   <li>否则若 /jietispeed 开着全局阶梯速度 -&gt; 用全局值；</li>
     *   <li>否则 -&gt; 跟随自己的运行速度（= 全局运行速度）。</li>
     * </ol>
     *
     * <p>所以默认情况下「改扶梯速度，阶梯速度会跟着变」；而改阶梯速度只写第 1 档，
     * 不会反过来影响运行速度。
     */
    public static double getAnimationSpeed(Level level, BlockPos pos) {
        Double individualStep = getIndividualStepSpeed(level, pos);
        if (individualStep != null) {
            return individualStep;
        }
        Double individualRun = getIndividualRunSpeed(level, pos);
        if (individualRun != null) {
            return individualRun;
        }
        if (isStepEnabled(level)) {
            return getDefaultStepSpeed(level);
        }
        return getSpeed(level, pos);
    }

    /** 输入界面预填：查询单个方块运行速度，未设置返回 null（界面再显示默认值）。 */
    public static Double getClientSpeed(Level level, BlockPos pos) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        if (data == null) {
            return null;
        }
        return data.speeds.get(pos);
    }

    public static double getClientDefault(Level level) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        return data != null ? data.defaultSpeed : EscalatorSpeedData.DEFAULT_SPEED;
    }

    /** 界面预填：维度默认阶梯动画速度（未被单独设置的扶梯使用）。 */
    public static double getClientDefaultStepSpeed(Level level) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        if (data == null) {
            return EscalatorSpeedData.VANILLA_STEP;
        }
        return data.stepEnabled ? data.stepValue : EscalatorSpeedData.VANILLA_STEP;
    }

    public static void applyClientData(ResourceKey<Level> dimension, double defaultSpeed,
                                       Map<BlockPos, Double> speeds, Map<BlockPos, Double> stepSpeeds,
                                       boolean stepEnabled, double stepValue) {
        ClientDimensionData data = new ClientDimensionData();
        data.defaultSpeed = defaultSpeed;
        data.speeds.putAll(speeds);
        data.stepSpeeds.putAll(stepSpeeds);
        data.stepEnabled = stepEnabled;
        data.stepValue = stepValue;
        CLIENT_DATA.put(dimension, data);
    }

    /** 断开连接时清空客户端镜像，避免换世界后残留旧数据。 */
    public static void clearClientData() {
        CLIENT_DATA.clear();
    }

    public static EscalatorSpeedData getServerData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(EscalatorSpeedData::fromTag, EscalatorSpeedData::new,
                EscalatorSpeedData.DATA_NAME);
    }

    /** 对 seed 所在的整条扶梯链设置运行速度，返回实际设置到几个方块。 */
    public static int setSpeed(ServerLevel level, BlockPos seed, double speed) {
        speed = EscalatorSpeedData.clamp(speed);
        EscalatorSpeedData data = getServerData(level);
        Set<BlockPos> chain = EscalatorUtil.collectChain(level, seed);
        for (BlockPos pos : chain) {
            data.speeds.put(pos, speed);
            // 规则：设置运行速度时阶梯速度一起跟随 —— 清掉单独阶梯设置，
            // 没单独设置的阶梯速度本来就跟随运行速度。
            data.stepSpeeds.remove(pos);
            data.axeModified.remove(pos);
        }
        data.setDirty();
        return chain.size();
    }

    /**
     * 石斧设置界面按下 ESC 时一次性应用改动。两个改动合成一个包发过来，
     * 顺序固定、不会出现「先设阶梯又被清掉」的竞态。
     *
     * <ul>
     *   <li>{@code setRun=true}：把这条扶梯（整条链）的运行速度设为 run，
     *       并**清掉它的单独阶梯速度**，于是阶梯速度自动跟随新的运行速度
     *       —— 即「改扶梯速度，阶梯速度也会跟着一起调整」。</li>
     *   <li>{@code setStep=true}：把这条扶梯的阶梯速度单独设为 step，
     *       **完全不动运行速度** —— 即「改阶梯速度，扶梯速度不会跟着调整」。</li>
     * </ul>
     *
     * <p>两个都开时先写运行速度再写阶梯速度，所以最终阶梯速度以 setStep 的值为准。
     *
     * @return 受影响的扶梯方块数；0 表示这个位置没有扶梯
     */
    public static int applyChain(ServerLevel level, BlockPos seed, boolean setRun, double run,
                                 boolean setStep, double step) {
        Set<BlockPos> chain = EscalatorUtil.collectChain(level, seed);
        if (chain.isEmpty()) {
            return 0;
        }
        if (!setRun && !setStep) {
            return chain.size();
        }
        EscalatorSpeedData data = getServerData(level);
        if (setRun) {
            double value = EscalatorSpeedData.clamp(run);
            for (BlockPos pos : chain) {
                data.speeds.put(pos, value);
                // 阶梯速度跟随运行速度：清掉单独设置（没单独设置时本来就跟随运行速度）。
                data.stepSpeeds.remove(pos);
                data.axeModified.remove(pos);
            }
        }
        if (setStep) {
            double value = EscalatorSpeedData.clamp(step);
            for (BlockPos pos : chain) {
                data.stepSpeeds.put(pos, value);
                data.axeModified.add(pos);
            }
        }
        data.setDirty();
        return chain.size();
    }

    /** 石斧：给【这一条】扶梯单独设置阶梯动画速度（只影响这条扶梯）。 */
    public static int setStepSpeed(ServerLevel level, BlockPos seed, double step) {
        step = EscalatorSpeedData.clamp(step);
        EscalatorSpeedData data = getServerData(level);
        Set<BlockPos> chain = EscalatorUtil.collectChain(level, seed);
        for (BlockPos pos : chain) {
            data.stepSpeeds.put(pos, step);
        }
        data.axeModified.addAll(chain);
        data.setDirty();
        return chain.size();
    }

    /** 石斧：对齐——【这一条】扶梯的阶梯动画速度 = 它自己的运行速度。 */
    public static int alignStepToRunning(ServerLevel level, BlockPos seed) {
        EscalatorSpeedData data = getServerData(level);
        Set<BlockPos> chain = EscalatorUtil.collectChain(level, seed);
        for (BlockPos pos : chain) {
            Double run = data.speeds.get(pos);
            data.stepSpeeds.put(pos, run != null ? run : data.defaultSpeed);
        }
        data.axeModified.addAll(chain);
        data.setDirty();
        return chain.size();
    }

    /**
     * 石斧：清除【这一条】扶梯的单独阶梯动画设置，让它重新跟随维度默认阶梯动画速度。
     * 只改动画，不动运行速度。
     */
    public static int clearStepSpeed(ServerLevel level, BlockPos seed) {
        EscalatorSpeedData data = getServerData(level);
        Set<BlockPos> chain = EscalatorUtil.collectChain(level, seed);
        int cleared = 0;
        for (BlockPos pos : chain) {
            boolean removed = data.stepSpeeds.remove(pos) != null;
            data.axeModified.remove(pos);
            if (removed) {
                cleared++;
            }
        }
        if (cleared > 0) {
            data.setDirty();
        }
        return chain.size();
    }

    // ------------------------------------------------------------------
    // 全局 / 批量指令
    //
    // 统一规则：**改运行速度 ⇒ 阶梯速度一起跟随；改阶梯速度 ⇒ 运行速度不动。**
    //
    // 数据模型：defaultSpeed = 全局扶梯速度；stepEnabled/stepValue = 全局阶梯速度
    //          （未开启时全局扶梯的阶梯速度 = 自己的运行速度）；
    //          speeds / stepSpeeds 里有记录的才是「被石斧单独改过」的扶梯。
    //
    // 因为 getAnimationSpeed 的优先级里「单独运行速度」高于「全局阶梯值」，
    // 改全局运行速度时要把 stepValue 镜像成新的 defaultSpeed（否则设了 /jietispeed
    // 之后阶梯速度不会跟着运行速度走）；反过来 -f 强制阶梯速度时，要给这些
    // 「只单独改过运行速度」的扶梯显式补一条阶梯设置，否则覆盖不到它们。
    //
    // 不带 -f 的指令只改「全局扶梯」：直接改全局值，并把那些
    //   **运行速度与阶梯速度都仍和全局一致**的、曾被改过的扶梯恢复成跟随全局
    //   （值不一致的扶梯原封不动）。
    // 带 -f 的指令强制改所有扶梯：改全局值并清掉所有单独设置。
    // ------------------------------------------------------------------

    /** 数值比较用的容差。 */
    public static final double EPSILON = 1.0E-6;

    public static boolean same(double a, double b) {
        return Math.abs(a - b) < EPSILON;
    }

    /** 全局扶梯的运行速度（= 维度默认运行速度）。 */
    public static double getGlobalRunSpeed(ServerLevel level) {
        return getServerData(level).defaultSpeed;
    }

    /** 全局扶梯的阶梯速度：/jietispeed 开着用维度默认值，关着就是跟随运行速度。 */
    public static double getGlobalStepSpeed(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        return data.stepEnabled ? data.stepValue : data.defaultSpeed;
    }

    /** /jietispeed X 设定的维度默认阶梯值（与是否开启无关）。 */
    public static double getStepValue(ServerLevel level) {
        return getServerData(level).stepValue;
    }

    /** 一条扶梯当前的运行速度（没有单独设置就是全局值）。 */
    private static double runSpeedOf(EscalatorSpeedData data, BlockPos pos) {
        Double v = data.speeds.get(pos);
        return v != null ? v : data.defaultSpeed;
    }

    /**
     * 一条扶梯当前的阶梯速度，优先级与 {@link #getAnimationSpeed} 一致：
     * 单独阶梯设置 &gt; 单独运行速度 &gt; 全局阶梯速度 &gt; 全局运行速度。
     */
    private static double stepSpeedOf(EscalatorSpeedData data, BlockPos pos) {
        Double step = data.stepSpeeds.get(pos);
        if (step != null) {
            return step;
        }
        Double run = data.speeds.get(pos);
        if (run != null) {
            return run;
        }
        return data.stepEnabled ? data.stepValue : data.defaultSpeed;
    }

    /**
     * 把「运行速度与阶梯速度都仍等于给定全局值」的、曾被单独改过的扶梯恢复为跟随全局
     * （删掉它们的单独记录）。有一条不一致就跳过 —— 这正是
     * 「不会修改扶梯速度或阶梯速度与全局不同的扶梯」。
     *
     * @return 恢复（记录被删除）的扶梯方块数
     */
    private static int releaseMatchingOverrides(EscalatorSpeedData data, double run, double step) {
        Set<BlockPos> candidates = new HashSet<>();
        candidates.addAll(data.speeds.keySet());
        candidates.addAll(data.stepSpeeds.keySet());
        candidates.addAll(data.axeModified);
        int released = 0;
        for (BlockPos pos : candidates) {
            // 注意：必须在改动全局值**之前**调用，这里读到的才是旧全局值。
            if (!same(runSpeedOf(data, pos), run) || !same(stepSpeedOf(data, pos), step)) {
                continue;
            }
            boolean changed = data.speeds.remove(pos) != null
                    | data.stepSpeeds.remove(pos) != null
                    | data.axeModified.remove(pos);
            if (changed) {
                released++;
            }
        }
        return released;
    }

    /**
     * /futispeed X：只改**全局扶梯**的运行速度。
     * 值仍与全局一致的（含曾被改过但值一致的）会自动回到全局并跟随新值；
     * 运行速度或阶梯速度与全局不同的扶梯原封不动。
     *
     * <p>规则：**设置扶梯速度时阶梯速度一起跟随** —— 全局阶梯值会被镜像成新的全局运行速度，
     * 所以全局扶梯的阶梯速度也会变成 X。
     *
     * @return 被恢复为跟随全局的扶梯方块数
     */
    public static int setGlobalRunSpeed(ServerLevel level, double speed) {
        EscalatorSpeedData data = getServerData(level);
        double oldRun = data.defaultSpeed;
        double oldStep = data.stepEnabled ? data.stepValue : data.defaultSpeed;
        // 必须先按旧全局值判定/释放，再改全局值。
        int released = releaseMatchingOverrides(data, oldRun, oldStep);
        data.defaultSpeed = EscalatorSpeedData.clamp(speed);
        // 阶梯速度跟随运行速度：全局阶梯值镜像到新的全局运行速度。
        data.stepValue = data.defaultSpeed;
        data.setDirty();
        return released;
    }

    /**
     * /futispeed -f X：强制所有扶梯运行速度 = X（不管有没有被改过）。
     * 规则：「设置扶梯速度时阶梯速度一起跟随」—— 清掉所有单独阶梯设置，
     * 并把全局阶梯值镜像成 X，于是**所有**扶梯的阶梯速度也都变成 X。
     *
     * @return 被清掉的单独记录数（运行速度 + 阶梯速度条目）
     */
    public static int forceGlobalRunSpeed(ServerLevel level, double speed) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.speeds.size() + data.stepSpeeds.size();
        data.defaultSpeed = EscalatorSpeedData.clamp(speed);
        // 阶梯速度一起跟随：全局阶梯值镜像到新的全局运行速度。
        data.stepValue = data.defaultSpeed;
        data.speeds.clear();
        data.stepSpeeds.clear();
        data.axeModified.clear();
        data.setDirty();
        return cleared;
    }

    /**
     * /futispeed -f X to Y：把运行速度为 X 的**所有**扶梯（含被改过的）改成 Y；
     * 运行速度不为 X 的扶梯保持不变。
     * 被改到运行速度 Y 的扶梯，其阶梯速度也一起跟随变成 Y（规则：设运行速度 ⇒ 阶梯跟随）。
     *
     * @return 被改动的扶梯方块数
     */
    public static int forceRunFromTo(ServerLevel level, double from, double to) {
        EscalatorSpeedData data = getServerData(level);
        double target = EscalatorSpeedData.clamp(to);
        boolean dirty = false;
        if (same(data.defaultSpeed, from)) {
            data.defaultSpeed = target;
            // 阶梯速度一起跟随。
            data.stepValue = target;
            dirty = true;
        }
        int changed = 0;
        for (Map.Entry<BlockPos, Double> entry : data.speeds.entrySet()) {
            if (!same(entry.getValue(), from)) {
                continue;
            }
            entry.setValue(target);
            // 运行速度变了，清掉单独阶梯设置让阶梯速度跟随新的运行速度。
            data.stepSpeeds.remove(entry.getKey());
            data.axeModified.remove(entry.getKey());
            changed++;
        }
        if (dirty || changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /**
     * /jietispeed X：只改**全局扶梯**的阶梯速度（不动任何运行速度）。
     *
     * @return 被恢复为跟随全局的扶梯方块数
     */
    public static int setGlobalStepSpeed(ServerLevel level, double value) {
        EscalatorSpeedData data = getServerData(level);
        double oldRun = data.defaultSpeed;
        double oldStep = data.stepEnabled ? data.stepValue : data.defaultSpeed;
        int released = releaseMatchingOverrides(data, oldRun, oldStep);
        data.stepValue = EscalatorSpeedData.clamp(value);
        data.stepEnabled = true;
        data.setDirty();
        return released;
    }

    /**
     * /jietispeed -f X：强制所有扶梯的阶梯速度 = X（不管有没有被改过）。
     * **完全不动运行速度**。
     *
     * <p>注意：阶梯速度取值里「单独运行速度」优先于「全局阶梯值」，所以只设全局值
     * 盖不住那些被石斧单独改过运行速度的扶梯。这里额外给它们显式补一条阶梯设置，
     * 让 -f 真正覆盖**所有**扶梯。
     *
     * @return 被清掉的单独阶梯设置数
     */
    public static int forceGlobalStepSpeed(ServerLevel level, double value) {
        EscalatorSpeedData data = getServerData(level);
        double target = EscalatorSpeedData.clamp(value);
        int cleared = data.stepSpeeds.size() + data.axeModified.size();
        data.stepValue = target;
        data.stepEnabled = true;
        data.stepSpeeds.clear();
        data.axeModified.clear();
        // 运行速度被单独改过的扶梯：显式补阶梯设置 = X（只写阶梯，运行速度原封不动）。
        for (BlockPos pos : data.speeds.keySet()) {
            data.stepSpeeds.put(pos, target);
            data.axeModified.add(pos);
        }
        data.setDirty();
        return cleared + data.speeds.size();
    }

    /**
     * /jietispeed -f X to Y：把阶梯速度为 X 的**所有**扶梯（含被改过的）改成 Y，
     * 阶梯速度不为 X 的保持不变；**不动任何运行速度**。
     *
     * <p>「阶梯速度为 X」要按 {@link #getAnimationSpeed} 的优先级链逐一识别：
     * <ol>
     *   <li>单独阶梯设置 == X；</li>
     *   <li>没有单独阶梯设置、但单独运行速度 == X（阶梯跟随运行速度）；</li>
     *   <li>都没有时，全局阶梯值（开启时）或全局运行速度 == X。</li>
     * </ol>
     *
     * @return 被改动的扶梯方块数
     */
    public static int forceStepFromTo(ServerLevel level, double from, double to) {
        EscalatorSpeedData data = getServerData(level);
        double target = EscalatorSpeedData.clamp(to);
        boolean dirty = false;
        // 1) 全局阶梯值正好是 X：连维度默认一起改成 Y（覆盖未加载区块与以后放置的扶梯）。
        if (data.stepEnabled && same(data.stepValue, from)) {
            data.stepValue = target;
            dirty = true;
        }
        // 2) 单独设置过阶梯速度、且正好是 X 的：改成 Y。
        int changed = 0;
        for (Map.Entry<BlockPos, Double> entry : data.stepSpeeds.entrySet()) {
            if (same(entry.getValue(), from)) {
                entry.setValue(target);
                changed++;
            }
        }
        // 3) 只单独改过运行速度、没有单独阶梯设置的扶梯：阶梯速度跟随自己的运行速度
        //    （优先于全局值），所以运行速度 == X 的也要一并改成 Y。
        for (Map.Entry<BlockPos, Double> entry : data.speeds.entrySet()) {
            if (data.stepSpeeds.containsKey(entry.getKey()) || !same(entry.getValue(), from)) {
                continue;
            }
            data.stepSpeeds.put(entry.getKey(), target);
            data.axeModified.add(entry.getKey());
            changed++;
        }
        // 4) 全局阶梯值没开启时，未单独改过的扶梯阶梯速度 = 全局运行速度；
        //    全局运行速度正好是 X 的话，把全局阶梯值整体抬到 Y 并开启。
        if (!data.stepEnabled && same(data.defaultSpeed, from)) {
            data.stepValue = target;
            data.stepEnabled = true;
            dirty = true;
        }
        if (dirty || changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** 枚举一个已加载区块里的所有扶梯方块。 */
    private static List<BlockPos> escalatorBlocksIn(LevelChunk chunk, Level level) {
        List<BlockPos> out = new ArrayList<>();
        ChunkPos cp = chunk.getPos();
        int baseX = cp.getMinBlockX();
        int baseZ = cp.getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        int maxY = minY + chunk.getHeight();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    BlockPos pos = new BlockPos(baseX + x, y, baseZ + z);
                    if (EscalatorUtil.isEscalator(chunk.getBlockState(pos))) {
                        out.add(pos);
                    }
                }
            }
        }
        return out;
    }

    /** 通过访问器拿到服务端已加载区块。 */
    private static List<LevelChunk> lastChunks(ServerLevel level) {
        List<LevelChunk> out = new ArrayList<>();
        ServerChunkCache cache = (ServerChunkCache) level.getChunkSource();
        if (cache.chunkMap == null) {
            return out;
        }
        for (ChunkHolder holder : ((ChunkMapAccessor) (Object) cache.chunkMap).smoothlift_getChunks()) {
            LevelChunk chunk = holder.getFullChunk();
            if (chunk != null) {
                out.add(chunk);
            }
        }
        return out;
    }

    public static void removeSpeed(ServerLevel level, BlockPos pos) {
        EscalatorSpeedData data = getServerData(level);
        boolean removed = data.speeds.remove(pos) != null
                | data.stepSpeeds.remove(pos) != null
                | data.axeModified.remove(pos);
        if (removed) {
            data.setDirty();
        }
    }

    public static void syncToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, SmoothLift.SYNC_CHANNEL, buildSyncPacket(server));
        }
    }

    private static FriendlyByteBuf buildSyncPacket(MinecraftServer server) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        List<ServerLevel> levels = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            levels.add(level);
        }
        buf.writeVarInt(levels.size());
        for (ServerLevel level : levels) {
            EscalatorSpeedData data = getServerData(level);
            buf.writeUtf(level.dimension().location().toString(), 256);
            buf.writeDouble(data.defaultSpeed);
            buf.writeBoolean(data.stepEnabled);
            buf.writeDouble(data.stepValue);
            buf.writeVarInt(data.speeds.size());
            for (Map.Entry<BlockPos, Double> entry : data.speeds.entrySet()) {
                buf.writeBlockPos(entry.getKey());
                buf.writeDouble(entry.getValue());
            }
            buf.writeVarInt(filteredStepSpeeds(data).size());
            for (Map.Entry<BlockPos, Double> entry : filteredStepSpeeds(data).entrySet()) {
                buf.writeBlockPos(entry.getKey());
                buf.writeDouble(entry.getValue());
            }
        }
        return buf;
    }

    /** 只同步石斧自定义过的阶梯动画（旧指令写的、无 axeModified 的条目不发）。 */
    private static Map<BlockPos, Double> filteredStepSpeeds(EscalatorSpeedData data) {
        Map<BlockPos, Double> out = new HashMap<>();
        for (Map.Entry<BlockPos, Double> entry : data.stepSpeeds.entrySet()) {
            if (data.axeModified.contains(entry.getKey())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    public static ResourceKey<Level> parseDimensionKey(String id) {
        return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(id));
    }
}
