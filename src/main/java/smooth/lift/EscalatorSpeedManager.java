package smooth.lift;

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
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.mixin.ChunkMapAccessor;
import smooth.lift.network.AudioSyncPacket;
import smooth.lift.network.HelpAudioSyncPacket;
import smooth.lift.network.HelpRoundSyncPacket;
import smooth.lift.network.HelpSpeedSyncPacket;
import smooth.lift.network.HelpSyncPacket;
import smooth.lift.network.HelpVolumeSyncPacket;
import smooth.lift.network.LiftChimeSyncPacket;
import smooth.lift.network.LiftToneSyncPacket;
import smooth.lift.network.Packets;
import smooth.lift.network.PsdChimeSyncPacket;
import smooth.lift.network.PsdToneSyncPacket;
import smooth.lift.network.RoundSyncPacket;
import smooth.lift.network.SyncPacket;
import smooth.lift.network.VolumeSyncPacket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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
    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    private EscalatorSpeedManager() {
    }

    public static final class ClientDimensionData {
        public double defaultSpeed = EscalatorSpeedData.DEFAULT_SPEED;
        public final Map<BlockPos, Double> speeds = new HashMap<>();
        public final Map<BlockPos, Double> stepSpeeds = new HashMap<>();
        public boolean stepEnabled = false;
        public double stepValue = EscalatorSpeedData.DEFAULT_SPEED;
        /** 【1.7】音频ID → OGG 字节（服务端同步过来的镜像，供客户端播放器注入声音引擎）。 */
        public final Map<String, byte[]> audioLibrary = new HashMap<>();
        /** 【1.7】扶梯方块 → 音频ID（服务端同步过来的镜像）。 */
        public final Map<BlockPos, String> blockAudio = new HashMap<>();
        /** 【1.9】扶梯方块 → 声音音量（1~1000，服务端同步过来的镜像）。 */
        public final Map<BlockPos, Integer> blockVolume = new HashMap<>();
        /** 【1.12】默认扶梯音量（/futiloud 设置，未单独设置音量的扶梯使用）。 */
        public int defaultVolume = EscalatorSpeedData.DEFAULT_AUDIO_VOLUME;
        /** 【1.11】默认扶梯音频 ID（未单独绑定音频的扶梯使用）；null = 无默认（静音）。 */
        public String defaultAudio;
        /** 【1.16】默认无障碍提示音开关（/futihelp 设置，未单独设置的扶梯使用）。 */
        public boolean defaultHelp = true;
        /** 【1.16】扶梯方块 → 提示音开关（服务端同步过来的镜像）。 */
        public final Map<BlockPos, Boolean> blockHelp = new HashMap<>();
        /**
         * 【1.18】默认无障碍提示音音量（/futihelploud 设置，未单独设置的扶梯使用）。
         * 注意与 {@link #defaultVolume}（扶梯**运行底噪**音量）是两套互不影响的数据。
         */
        public int defaultHelpVolume = EscalatorSpeedData.DEFAULT_HELP_VOLUME;
        /** 【1.18】扶梯方块 → 提示音音量（服务端同步过来的镜像）。 */
        public final Map<BlockPos, Integer> blockHelpVolume = new HashMap<>();
        /** 【1.24】默认扶梯运行底噪的可闻范围（/futiround 设置，单位格，初始 16）。 */
        public int defaultRound = EscalatorSpeedData.DEFAULT_ROUND;
        /** 【1.24】扶梯方块 → 运行底噪范围（服务端同步过来的镜像）。 */
        public final Map<BlockPos, Integer> blockRound = new HashMap<>();
        /** 【1.24】默认无障碍提示音的可闻范围（/futihelpround 设置，单位格，初始 4）。 */
        public int defaultHelpRound = EscalatorSpeedData.DEFAULT_HELP_ROUND;
        /** 【1.24】扶梯方块 → 提示音范围（服务端同步过来的镜像）。 */
        public final Map<BlockPos, Integer> blockHelpRound = new HashMap<>();
        /** 【1.31】默认**上客端（进入扶梯）**提示音速率（Hz，初始 10）。 */
        public int defaultHelpSpeedIn = EscalatorSpeedData.DEFAULT_HELP_SPEED_IN;
        /** 【1.31】扶梯方块 → 上客端提示音速率（服务端同步过来的镜像）。 */
        public final Map<BlockPos, Integer> blockHelpSpeedIn = new HashMap<>();
        /** 【1.31】默认**落客端（离开扶梯）**提示音速率（Hz，初始 1）。 */
        public int defaultHelpSpeedOut = EscalatorSpeedData.DEFAULT_HELP_SPEED_OUT;
        /** 【1.31】扶梯方块 → 落客端提示音速率（服务端同步过来的镜像）。 */
        public final Map<BlockPos, Integer> blockHelpSpeedOut = new HashMap<>();
        /**
         * 【1.41】默认无障碍提示音**音乐** ID 的**进入扶梯（上客端）**那一套
         * （{@code /futihelpmusic in} 设置，未单独设置的扶梯使用）。
         * 初始 {@link EscalatorSpeedData#HELP_AUDIO_DEFAULT} = 模组原来的提示音。
         */
        public String defaultHelpAudioIn = EscalatorSpeedData.HELP_AUDIO_DEFAULT;
        /** 【1.41】扶梯方块 → **上客端**无障碍提示音音乐 ID（服务端同步过来的镜像）。 */
        public final Map<BlockPos, String> blockHelpAudioIn = new HashMap<>();
        /** 【1.41】默认无障碍提示音**音乐** ID 的**离开扶梯（落客端）**那一套（{@code /futihelpmusic out}）。 */
        public String defaultHelpAudioOut = EscalatorSpeedData.HELP_AUDIO_DEFAULT;
        /** 【1.41】扶梯方块 → **落客端**无障碍提示音音乐 ID（服务端同步过来的镜像）。 */
        public final Map<BlockPos, String> blockHelpAudioOut = new HashMap<>();
        /**
         * 【1.42】直梯开关门提示音开关（{@code /lifthelp}，服务端同步过来的镜像）。
         * 与扶梯那套提示音（{@link #defaultHelp}）**完全无关**，是独立的另一件事。
         */
        public boolean liftHelp = true;
        /** 【1.42】直梯开关门提示音倍速（{@code /lifthelpspeed}，服务端同步过来的镜像）。 */
        public float liftHelpSpeed = EscalatorSpeedData.DEFAULT_LIFT_HELP_SPEED;
        /** 【1.43】直梯开关门提示音音量（{@code /lifthelploud}，服务端同步过来的镜像）。 */
        public int liftHelpVolume = EscalatorSpeedData.DEFAULT_LIFT_HELP_VOLUME;
        /** 【1.48】三项各自音量镜像（-1 = 跟随共用默认）。 */
        public int liftToneVolumeUp = EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
        public int liftToneVolumeDown = EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
        public int liftToneVolumeChime = EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
        /** 【1.47】直梯提示音（三项共用）淡入淡出范围（{@code /lifthelpround}，服务端同步过来的镜像）。 */
        public int liftHelpRound = EscalatorSpeedData.DEFAULT_LIFT_HELP_ROUND;

        /** 【1.15】三提示音的**维度默认素材**镜像（{@code /lifthelp up|down|door <名字>}）。
         * 单独设置过的那条直梯按 {@link #liftToneAudio} 走；没设置、或者那一项是
         * {@code default}（跟维度默认）时回落到这里。
         */
        public String liftToneAudioUp = EscalatorSpeedData.LIFT_TONE_DEFAULT;
        public String liftToneAudioDown = EscalatorSpeedData.LIFT_TONE_DEFAULT;
        public String liftToneAudioChime = EscalatorSpeedData.LIFT_TONE_DEFAULT;
        /** 【1.46】三提示音独立子开关的镜像（{@code /lifthelpup|down|chime} / 石斧 UI 开关）。 */
        public boolean liftToneUpEnabled = true;
        public boolean liftToneDownEnabled = true;
        public boolean liftToneChimeEnabled = true;
        /** 【1.7】存档<MBM_Audio>文件夹里可选 OGG 文件名（上传来源，未入库的才显示）。 */
        public final Set<String> folderAudio = new HashSet<>();
        /**
         * 【1.45】直梯楼层轨道提示音（竖井列打包坐标 → 三音频 id，服务端同步过来的镜像）。
         * 播放端按「最近直梯的楼层列」查它。
         */
        public final Map<Long, EscalatorSpeedData.LiftToneAudio> liftToneAudio = new HashMap<>();

        // 【1.50】屏蔽门（MTR PSD / APG）开关门提示音的镜像（形状与直梯那一组对称）
        /** 【1.50】屏蔽门提示音总开关镜像（{@code /pbmmusic}）。 */
        public boolean psdHelp = true;
        /** 【1.50】屏蔽门两项各自子开关的镜像（{@code /pbmmusic open|close}）。 */
        public boolean psdToneOpenEnabled = true;
        public boolean psdToneCloseEnabled = true;
        /** 【1.50】屏蔽门提示音**共用默认**音量的镜像（{@code /pbmloud}）。 */
        public int psdHelpVolume = EscalatorSpeedData.DEFAULT_PSD_HELP_VOLUME;
        /** 【1.50】两项各自音量的镜像（-1 = 跟随共用默认）。 */
        public int psdToneVolumeOpen = EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
        public int psdToneVolumeClose = EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
        /** 【1.22】到站播报 / 进站报站各自那一项音量的镜像（-1 = 跟随共用默认）。 */
        public int psdMidiumVolume = EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
        public int psdArriveVolume = EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
        /** 【1.50】屏蔽门提示音可闻范围镜像（{@code /pbmround}，格）。 */
        public int psdHelpRound = EscalatorSpeedData.DEFAULT_PSD_HELP_ROUND;
        /** 【1.23】到站播报 / 进站报站各自的可闻范围镜像（{@code /pbmmidiumround} / {@code /pbmarriveround}，格）。 */
        public int psdMidiumRound = EscalatorSpeedData.DEFAULT_PSD_MIDIUM_ROUND;
        public int psdArriveRound = EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND;
        /** 【1.16】关门提示音强制等待时长镜像（{@code /pbmclosewait}，秒）。 */
        public int psdCloseWaitSeconds = EscalatorSpeedData.DEFAULT_PSD_CLOSE_WAIT_SECONDS;
        /** 【1.15】两项**维度默认素材**的镜像（{@code /pbmmusic open|close <名字>}）。 */
        public String psdToneAudioOpen = EscalatorSpeedData.PSD_TONE_DEFAULT;
        public String psdToneAudioClose = EscalatorSpeedData.PSD_TONE_DEFAULT;
        /** 【1.17】到站播报的镜像：素材 id（{@code off} = 不播）+ 等待秒数。 */
        public String psdMidiumAudio = EscalatorSpeedData.PSD_MIDIUM_OFF;
        public int psdMidiumWaitSeconds = EscalatorSpeedData.DEFAULT_PSD_MIDIUM_WAIT_SECONDS;
        /** 【1.21】进站报站的镜像：素材 id（{@code off} = 不播）+ 秒数（(-∞, 0]：最近一班车还剩 |X| 秒到站时起播）。 */
        public String psdArriveAudio = EscalatorSpeedData.PSD_ARRIVE_OFF;
        public int psdArriveSeconds = EscalatorSpeedData.DEFAULT_PSD_ARRIVE_SECONDS;
        /** 【1.50】每扇门单独设置的素材镜像（门锚点打包坐标 → {open, close}）。 */
        public final Map<Long, EscalatorSpeedData.PsdToneAudio> psdToneAudio = new HashMap<>();
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

    /**
     * 应用服务端同步过来的速度/阶梯动画数据。
     *
     * <p><b>注意</b>：这里必须复用已有的 {@link ClientDimensionData}（只覆盖速度相关字段），
     * 不能 `CLIENT_DATA.put(dimension, new ClientDimensionData())` ——
     * 那会把同一维度里已经收到的**音频库 / 扶梯-音频绑定 / 音量**一起清空。
     * 速度同步（SYNC）和音频同步（AUDIO_SYNC）是两个独立的包，
     * 到达顺序不保证：先收音频再收速度时，旧的写法会让声音数据凭空消失。
     */
    public static void applyClientData(ResourceKey<Level> dimension, double defaultSpeed,
                                       Map<BlockPos, Double> speeds, Map<BlockPos, Double> stepSpeeds,
                                       boolean stepEnabled, double stepValue) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.defaultSpeed = defaultSpeed;
        data.speeds.clear();
        data.speeds.putAll(speeds);
        data.stepSpeeds.clear();
        data.stepSpeeds.putAll(stepSpeeds);
        data.stepEnabled = stepEnabled;
        data.stepValue = stepValue;
    }

    /** 【1.7】应用服务端同步过来的音频库、就绪拾取文件夹名单与扶梯-音频绑定（覆盖式更新）。 */
    public static void applyClientAudioData(ResourceKey<Level> dimension,
                                            Map<String, byte[]> audioLibrary,
                                            Set<String> folderAudio,
                                            Map<BlockPos, String> blockAudio,
                                            String defaultAudio) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.audioLibrary.clear();
        data.audioLibrary.putAll(audioLibrary);
        data.folderAudio.clear();
        data.folderAudio.addAll(folderAudio);
        data.blockAudio.clear();
        data.blockAudio.putAll(blockAudio);
        data.defaultAudio = defaultAudio;
    }

    /** 【1.11】客户端：当前维度的默认扶梯音频 ID；没设置返回 null。 */
    public static String getClientDefaultAudio(ResourceKey<Level> dimension) {
        ClientDimensionData data = CLIENT_DATA.get(dimension);
        return data == null ? null : data.defaultAudio;
    }

    /** 【1.9】该扶梯**实际使用**的声音音量（1~1000，100 = 原始音量）。
     *  优先级：单独设置（石斧界面） &gt; 默认音量（/futiloud） &gt; 100。
     *  客户端读镜像，服务端读 SavedData。 */
    public static int getVolume(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null
                    ? EscalatorSpeedData.DEFAULT_AUDIO_VOLUME
                    : data.blockVolume.getOrDefault(pos, data.defaultVolume);
        }
        return getServerData((ServerLevel) level).getVolume(pos);
    }

    /** 【1.12】该扶梯方块**单独设置**的音量；没单独设置返回 null（表示跟随默认音量）。 */
    public static Integer getIndividualVolume(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? null : data.blockVolume.get(pos);
        }
        return getServerData((ServerLevel) level).getIndividualVolume(pos);
    }

    /** 【1.12】该扶梯方块是否**单独设置**过音量（石斧界面）。 */
    public static boolean hasIndividualVolume(Level level, BlockPos pos) {
        return getIndividualVolume(level, pos) != null;
    }

    /**
     * 【1.9】界面用：取「这条扶梯」的音量。
     *
     * <p>音频绑定与音量都是按**玩家当时点到的那一个方块**存的，而同一条扶梯上不同方块是不同的 key。
     * 所以这里先看自己这块有没有单独记录；没有就顺着扶梯链（{@link EscalatorUtil#collectChain}）
     * 找同一条扶梯上单独设过音量的方块，避免出现「在 A 块设成 50，走到 B 块打开界面却显示默认值」。
     * 整条链都没单独设过 → 用维度默认音量（【1.12】/futiloud 设置的那个）。
     * （只有打开界面时才会调用，链遍历开销可忽略。）
     */
    public static int getVolumeForScreen(Level level, BlockPos pos) {
        Integer own = getIndividualVolume(level, pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            if (p.equals(pos)) {
                continue;
            }
            Integer v = getIndividualVolume(level, p);
            if (v != null) {
                return v;
            }
        }
        return getDefaultVolume(level);
    }

    /** 【1.12】该维度的默认扶梯音量（/futiloud 设置；未设置过就是 100 = 原始音量）。 */
    public static int getDefaultVolume(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_AUDIO_VOLUME : data.defaultVolume;
        }
        return getServerData((ServerLevel) level).defaultVolume;
    }

    /**
     * 【1.9】声音播放器专用：按「音频绑定所在的那个方块」直接取音量。
     * 播放器本来就拿着绑定坐标，所以不做链回退，每 tick 调用也只是一次 HashMap 查询。
     * 【1.12】没单独设过就用维度默认音量。
     */
    public static int getClientBindingVolume(ResourceKey<Level> dimension, BlockPos boundPos) {
        ClientDimensionData data = CLIENT_DATA.get(dimension);
        if (data == null) {
            return EscalatorSpeedData.DEFAULT_AUDIO_VOLUME;
        }
        return data.blockVolume.getOrDefault(boundPos, data.defaultVolume);
    }

    /**
     * 【1.9】设置某条扶梯的声音音量（服务端）。返回夹取到 1~1000 之后的实际值（100 = 原始音量）。
     *
     * <p>会把同一个扶梯链上**已绑定音频**的方块一起设成同一个值 —— 否则
     * 「在 B 块调音量、而音频绑在 A 块」时播放器读的是 A 块的音量，表现就是「调了没反应」。
     */
    public static int setVolume(ServerLevel level, BlockPos pos, int volume) {
        EscalatorSpeedData data = getServerData(level);
        int v = EscalatorSpeedData.clampVolume(volume);
        data.setVolume(pos, v);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            if (data.blockAudio.containsKey(p)) {
                data.setVolume(p, v);
            }
        }
        data.setDirty();
        return v;
    }

    // ------------------------------------------------------------------
    // 【1.12】/futiloud：默认扶梯音量（数据模型与 /futispeed、/futimusic 完全对称）
    // ------------------------------------------------------------------

    /** /futiloud &lt;音量&gt;：只改**默认**音量，单独设置过音量的扶梯不变。 */
    public static void setDefaultVolume(ServerLevel level, int volume) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultVolume = EscalatorSpeedData.clampVolume(volume);
        data.setDirty();
    }

    /**
     * /futiloud -f &lt;音量&gt;：强制**所有**扶梯音量 = 该值。
     * 做法是设默认值 + 清掉所有单独设置（清掉比把每一格都写成同一个值更省存档）。
     *
     * @return 被清掉的单独设置数
     */
    public static int forceDefaultVolume(ServerLevel level, int volume) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockVolume.size();
        data.defaultVolume = EscalatorSpeedData.clampVolume(volume);
        data.blockVolume.clear();
        data.setDirty();
        return cleared;
    }

    /**
     * /futiloud &lt;X&gt; to &lt;Y&gt;：默认音量正好是 X 时才改成 Y；单独设置过的一概不动。
     *
     * @return 是否真的改了
     */
    public static boolean replaceDefaultVolume(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultVolume != from) {
            return false;
        }
        data.defaultVolume = EscalatorSpeedData.clampVolume(to);
        data.setDirty();
        return true;
    }

    /**
     * /futiloud -f &lt;X&gt; to &lt;Y&gt;：把**所有**音量正好是 X 的扶梯（含单独设置的）改成 Y。
     * 「音量正好是 X」按实际生效值判断（单独设置优先，其次默认音量）。
     *
     * @return 被改动的处数（默认层算 1 处）
     */
    public static int forceReplaceVolumeFromTo(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        int target = EscalatorSpeedData.clampVolume(to);
        int changed = 0;
        if (data.defaultVolume == from) {
            data.defaultVolume = target;
            changed++;
        }
        // 先把要改的坐标收集起来再改，避免边遍历边改 Map。
        java.util.List<BlockPos> toChange = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, Integer> entry : data.blockVolume.entrySet()) {
            if (entry.getValue() == from) {
                toChange.add(entry.getKey());
            }
        }
        for (BlockPos pos : toChange) {
            data.setVolume(pos, target);
            changed++;
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** 【1.9】应用服务端同步过来的扶梯音量表（覆盖式更新）。 */
    public static void applyClientVolumes(ResourceKey<Level> dimension, Map<BlockPos, Integer> volumes,
                                          int defaultVolume) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.blockVolume.clear();
        data.blockVolume.putAll(volumes);
        data.defaultVolume = defaultVolume;
    }

    // ------------------------------------------------------------------
    // 【1.16】/futihelp：无障碍提示音开关（数据模型与 /futiloud 完全对称）
    //
    //   两层：维度默认（/futihelp on|off） + 每条扶梯单独设置（石斧界面里的开关）。
    //   单独设置**只存一个方块**（玩家点的那块），但读的时候会顺着扶梯链找 ——
    //   同一条扶梯上任意一块设过，整条都算设过（和 getVolumeForScreen 同一套规则），
    //   这样「在 A 块关掉、走到 B 块打开界面」看到的仍然是关。
    // ------------------------------------------------------------------

    /** 【1.16】这条扶梯**实际生效**是否播放无障碍提示音（单独设置 &gt; 维度默认）。 */
    public static boolean isHelpEnabled(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                // 镜像还没到（刚进世界）：按默认「开」处理，宁可响也不要哑掉。
                return true;
            }
            Boolean own = findChainHelp(data.blockHelp, level, pos);
            return own != null ? own : data.defaultHelp;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Boolean own = findChainHelp(data.blockHelp, level, pos);
        return own != null ? own : data.defaultHelp;
    }

    /** 【1.16】这条扶梯是否被**单独设置**过提示音开关（顺扶梯链找）。 */
    public static boolean hasOwnHelp(Level level, BlockPos pos) {
        Map<BlockPos, Boolean> overrides;
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return false;
            }
            overrides = data.blockHelp;
        } else {
            overrides = getServerData((ServerLevel) level).blockHelp;
        }
        return findChainHelp(overrides, level, pos) != null;
    }

    /** 顺扶梯链找单独设置：自己这块优先，其次链上其它方块；整条链都没有返回 null（用维度默认）。 */
    private static Boolean findChainHelp(Map<BlockPos, Boolean> overrides, Level level, BlockPos pos) {
        if (overrides.isEmpty()) {
            return null;
        }
        Boolean own = overrides.get(pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            Boolean value = overrides.get(p);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 【1.16】该维度的默认提示音开关（/futihelp 设置；未设置过就是 true = 开）。 */
    public static boolean getDefaultHelp(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null || data.defaultHelp;
        }
        return getServerData((ServerLevel) level).defaultHelp;
    }

    /**
     * 【1.16】石斧界面：设置某条扶梯的提示音开关（服务端）。
     *
     * <p>先把整条链上的旧记录清掉，再只在玩家点的那一块记一条（仅在它与默认值不同时），
     * 保证「一条扶梯最多一条记录」，省存档也免得链上多块各说各话。
     */
    public static void setHelp(ServerLevel level, BlockPos pos, boolean enabled) {
        EscalatorSpeedData data = getServerData(level);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            data.blockHelp.remove(p);
        }
        data.blockHelp.remove(pos);
        data.setHelp(pos, enabled);
        data.setDirty();
    }

    /** /futihelp &lt;on|off&gt;：只改**默认**开关，单独设置过的扶梯不变。 */
    public static void setDefaultHelp(ServerLevel level, boolean enabled) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultHelp = enabled;
        data.setDirty();
    }

    /**
     * /futihelp -f &lt;on|off&gt;：强制**所有**扶梯 = 该开关。
     * 做法是设默认值 + 清掉所有单独设置（和 forceDefaultVolume 同一套）。
     *
     * @return 被清掉的单独设置数
     */
    public static int forceDefaultHelp(ServerLevel level, boolean enabled) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockHelp.size();
        data.defaultHelp = enabled;
        data.blockHelp.clear();
        data.setDirty();
        return cleared;
    }

    /**
     * /futihelp &lt;X&gt; to &lt;Y&gt;：默认开关正好是 X 时才改成 Y；单独设置过的一概不动。
     *
     * @return 是否真的改了
     */
    public static boolean replaceDefaultHelp(ServerLevel level, boolean from, boolean to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultHelp != from) {
            return false;
        }
        data.defaultHelp = to;
        data.setDirty();
        return true;
    }

    /**
     * /futihelp -f &lt;X&gt; to &lt;Y&gt;：把所有**生效开关正好是 X** 的扶梯（含单独设置的）改成 Y。
     *
     * @return 被改动的处数（默认层算 1 处）
     */
    public static int forceReplaceHelpFromTo(ServerLevel level, boolean from, boolean to) {
        EscalatorSpeedData data = getServerData(level);
        int changed = 0;
        if (data.defaultHelp == from) {
            data.defaultHelp = to;
            changed++;
        }
        // 先收集再改，避免边遍历边改 Map。
        java.util.List<BlockPos> toChange = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, Boolean> entry : data.blockHelp.entrySet()) {
            if (entry.getValue() == from) {
                toChange.add(entry.getKey());
            }
        }
        for (BlockPos pos : toChange) {
            data.setHelp(pos, to);
            changed++;
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** 【1.16】应用服务端同步过来的提示音开关表（覆盖式更新）。 */
    public static void applyClientHelp(ResourceKey<Level> dimension, boolean defaultHelp,
                                       Map<BlockPos, Boolean> blockHelp) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.blockHelp.clear();
        data.blockHelp.putAll(blockHelp);
        data.defaultHelp = defaultHelp;
        clientHelpGeneration++;
    }

    /**
     * 【1.16】无障碍开关的「代次」：只在客户端镜像被整体替换时（收到 HELP_SYNC、断开连接清镜像）递增。
     *
     * <p>给每 tick 都要判断「现在该不该响」的调用方（{@code EscalatorChimePlayer}）当缓存键用 ——
     * {@link #isHelpEnabled} 在链上有单独设置时要展开整条链，每 tick 都做没必要；
     * 而只要代次变了就说明开关数据变了，必须立刻重查。**千万别用「缓存到下一次方块变化为止」那套**：
     * 玩家坐在扶梯上不动时那种缓存永远不会失效，开关就「关不掉」。
     */
    public static long clientHelpGeneration() {
        return clientHelpGeneration;
    }

    /** 见 {@link #clientHelpGeneration()}。只在客户端线程写。 */
    private static long clientHelpGeneration;

    // ------------------------------------------------------------------
    // 【1.18】/futihelploud：无障碍提示音音量（数据模型与 /futiloud、/futihelp 完全对称）
    //
    //   两层：维度默认（/futihelploud <音量>） + 每条扶梯单独设置（石斧界面里的输入框）。
    //   单独设置**只存一个方块**（玩家点的那块），读的时候顺着扶梯链找 —— 与提示音开关同一套规则。
    //
    //   注意：这是「提示音（香港式视障人士提升音）」的音量，作用在端头**单块**方块上、射程 4 格；
    //   与 /futiloud 管的「扶梯运行底噪」（整条扶梯、射程 16 格）是**两件不同的事**，数据与指令互不影响。
    // ------------------------------------------------------------------

    /**
     * 【1.18】这条扶梯**实际生效**的提示音音量（单独设置 &gt; 维度默认；1~1000，100 = 原始音量）。
     *
     * <p>这里和 {@link #isHelpEnabled} 一样**会顺扶梯链找**：提示音播放器拿的是「离玩家最近的阶梯块」，
     * 而玩家可能在护栏/侧板上打开界面设的音量，所以链上任意一块设过都算。
     */
    public static int getHelpVolume(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                // 镜像还没到（刚进世界）：按默认音量处理。
                return EscalatorSpeedData.DEFAULT_HELP_VOLUME;
            }
            Integer own = findChainHelpVolume(data.blockHelpVolume, level, pos);
            return own != null ? own : data.defaultHelpVolume;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Integer own = findChainHelpVolume(data.blockHelpVolume, level, pos);
        return own != null ? own : data.defaultHelpVolume;
    }

    /** 【1.18】这条扶梯是否被**单独设置**过提示音音量（顺扶梯链找）。 */
    public static boolean hasOwnHelpVolume(Level level, BlockPos pos) {
        Map<BlockPos, Integer> overrides;
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return false;
            }
            overrides = data.blockHelpVolume;
        } else {
            overrides = getServerData((ServerLevel) level).blockHelpVolume;
        }
        return findChainHelpVolume(overrides, level, pos) != null;
    }

    /** 顺扶梯链找单独设置的音量：自己这块优先，其次链上其它方块；整条链都没有返回 null（用维度默认）。 */
    private static Integer findChainHelpVolume(Map<BlockPos, Integer> overrides, Level level, BlockPos pos) {
        if (overrides.isEmpty()) {
            return null;
        }
        Integer own = overrides.get(pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            Integer value = overrides.get(p);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 【1.18】该维度的默认提示音音量（/futihelploud 设置；未设置过就是 100 = 原始音量）。 */
    public static int getDefaultHelpVolume(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_HELP_VOLUME : data.defaultHelpVolume;
        }
        return getServerData((ServerLevel) level).defaultHelpVolume;
    }

    /**
     * 【1.18】石斧界面：设置某条扶梯的提示音音量（服务端）。返回夹取后的实际值。
     *
     * <p>先把整条链上的旧记录清掉，再只在玩家点的那一块记一条（仅在它与默认值不同时），
     * 保证「一条扶梯最多一条记录」。
     */
    public static int setHelpVolume(ServerLevel level, BlockPos pos, int volume) {
        EscalatorSpeedData data = getServerData(level);
        int v = EscalatorSpeedData.clampHelpVolume(volume);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            data.blockHelpVolume.remove(p);
        }
        data.blockHelpVolume.remove(pos);
        data.setHelpVolume(pos, v);
        data.setDirty();
        return v;
    }

    /** /futihelploud &lt;音量&gt;：只改**默认**音量，单独设置过的不变。 */
    public static void setDefaultHelpVolume(ServerLevel level, int volume) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultHelpVolume = EscalatorSpeedData.clampHelpVolume(volume);
        data.setDirty();
    }

    /**
     * /futihelploud -f &lt;音量&gt;：强制**所有**扶梯提示音音量 = 该值。
     * 做法是设默认值 + 清掉所有单独设置（和 forceDefaultVolume 同一套）。
     *
     * @return 被清掉的单独设置数
     */
    public static int forceDefaultHelpVolume(ServerLevel level, int volume) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockHelpVolume.size();
        data.defaultHelpVolume = EscalatorSpeedData.clampHelpVolume(volume);
        data.blockHelpVolume.clear();
        data.setDirty();
        return cleared;
    }

    /**
     * /futihelploud &lt;X&gt; to &lt;Y&gt;：默认音量正好是 X 时才改成 Y；单独设置过的一概不动。
     *
     * @return 是否真的改了
     */
    public static boolean replaceDefaultHelpVolume(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultHelpVolume != from) {
            return false;
        }
        data.defaultHelpVolume = EscalatorSpeedData.clampHelpVolume(to);
        data.setDirty();
        return true;
    }

    /**
     * /futihelploud -f &lt;X&gt; to &lt;Y&gt;：把所有**生效音量正好是 X** 的扶梯（含单独设置的）改成 Y。
     *
     * @return 被改动的处数（默认层算 1 处）
     */
    public static int forceReplaceHelpVolumeFromTo(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        int target = EscalatorSpeedData.clampHelpVolume(to);
        int changed = 0;
        if (data.defaultHelpVolume == from) {
            data.defaultHelpVolume = target;
            changed++;
        }
        // 先收集再改，避免边遍历边改 Map。
        java.util.List<BlockPos> toChange = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, Integer> entry : data.blockHelpVolume.entrySet()) {
            if (entry.getValue() == from) {
                toChange.add(entry.getKey());
            }
        }
        for (BlockPos pos : toChange) {
            data.setHelpVolume(pos, target);
            changed++;
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** 【1.18】应用服务端同步过来的提示音音量表（覆盖式更新）。 */
    public static void applyClientHelpVolume(ResourceKey<Level> dimension, int defaultHelpVolume,
                                             Map<BlockPos, Integer> blockHelpVolume) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.blockHelpVolume.clear();
        data.blockHelpVolume.putAll(blockHelpVolume);
        data.defaultHelpVolume = defaultHelpVolume;
        clientHelpVolumeGeneration++;
    }

    /**
     * 【1.18】提示音音量的「代次」：只在客户端镜像被整体替换时（收到 HELP_VOLUME_SYNC、断开连接清镜像）递增。
     *
     * <p>用途与 {@link #clientHelpGeneration()} 完全相同：给每 tick 都要读音量的
     * {@code EscalatorChimePlayer} 当缓存键，保证「音量一改，下一个 tick 立刻生效」。
     * 同样**绝不能**用「缓存到方块变化为止」那套。
     */
    public static long clientHelpVolumeGeneration() {
        return clientHelpVolumeGeneration;
    }

    /** 见 {@link #clientHelpVolumeGeneration()}。只在客户端线程写。 */
    private static long clientHelpVolumeGeneration;

    // ------------------------------------------------------------------
    // 【1.24】/futiround 与 /futihelpround：两个「淡入淡出范围」（单位格）
    //
    //   底噪（/futiround）默认 16 格、提示音（/futihelpround）默认 4 格 —— 与 1.17 定的
    //   「底噪按整条扶梯 16 格、提示音按端头单块 4 格」完全一致，只是现在可调了。
    //
    //   两层：维度默认（X） + 每条扶梯单独设置。**1.24 没有石斧界面控件**，
    //   所以单独设置这一层目前只能由 `-f <X> to <Y>` 间接产生；数据模型与其它可调项一致。
    //
    //   ★ 坑 17 在此同样适用：范围是「由网络同步驱动、随时会变」的条件，
    //     播放侧必须**逐 tick 现算**（或按代次缓存），绝不能塞进任何「按 anchor 缓存的几何计算」里，
    //     否则玩家站在扶梯上不动时改了范围也永远不生效。
    // ------------------------------------------------------------------

    /** 【1.24】这条扶梯**运行底噪**的生效可闻范围（单独设置 &gt; 维度默认；1~128 格）。 */
    public static int getRound(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_ROUND;
            }
            Integer own = findChainRound(data.blockRound, level, pos);
            return own != null ? own : data.defaultRound;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Integer own = findChainRound(data.blockRound, level, pos);
        return own != null ? own : data.defaultRound;
    }

    /** 【1.24】这条扶梯是否被**单独设置**过底噪范围（顺扶梯链找）。 */
    public static boolean hasOwnRound(Level level, BlockPos pos) {
        Map<BlockPos, Integer> overrides;
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return false;
            }
            overrides = data.blockRound;
        } else {
            overrides = getServerData((ServerLevel) level).blockRound;
        }
        return findChainRound(overrides, level, pos) != null;
    }

    /** 顺扶梯链找单独设置的底噪范围：自己这块优先，其次链上其它方块；整条链都没有返回 null。 */
    private static Integer findChainRound(Map<BlockPos, Integer> overrides, Level level, BlockPos pos) {
        if (overrides.isEmpty()) {
            return null;
        }
        Integer own = overrides.get(pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            Integer value = overrides.get(p);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 【1.24】该维度的默认底噪范围（/futiround 设置；未设置过就是 16 格）。 */
    public static int getDefaultRound(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_ROUND : data.defaultRound;
        }
        return getServerData((ServerLevel) level).defaultRound;
    }

    /** 【1.24】设置某条扶梯的底噪范围（服务端）。返回夹取后的实际值。 */
    public static int setRound(ServerLevel level, BlockPos pos, int round) {
        EscalatorSpeedData data = getServerData(level);
        int v = EscalatorSpeedData.clampRound(round);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            data.blockRound.remove(p);
        }
        data.blockRound.remove(pos);
        data.setRound(pos, v);
        data.setDirty();
        return v;
    }

    /** /futiround &lt;范围&gt;：只改**默认**范围，单独设置过的不变。 */
    public static void setDefaultRound(ServerLevel level, int round) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultRound = EscalatorSpeedData.clampRound(round);
        data.setDirty();
    }

    /**
     * /futiround -f &lt;范围&gt;：强制**所有**扶梯底噪范围 = 该值（设默认 + 清掉所有单独设置）。
     *
     * @return 被清掉的单独设置数
     */
    public static int forceDefaultRound(ServerLevel level, int round) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockRound.size();
        data.defaultRound = EscalatorSpeedData.clampRound(round);
        data.blockRound.clear();
        data.setDirty();
        return cleared;
    }

    /** /futiround &lt;X&gt; to &lt;Y&gt;：默认范围正好是 X 时才改成 Y；单独设置过的一概不动。 */
    public static boolean replaceDefaultRound(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultRound != from) {
            return false;
        }
        data.defaultRound = EscalatorSpeedData.clampRound(to);
        data.setDirty();
        return true;
    }

    /** /futiround -f &lt;X&gt; to &lt;Y&gt;：把所有**生效范围正好是 X** 的扶梯（含单独设置的）改成 Y。 */
    public static int forceReplaceRoundFromTo(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        int target = EscalatorSpeedData.clampRound(to);
        int changed = 0;
        if (data.defaultRound == from) {
            data.defaultRound = target;
            changed++;
        }
        java.util.List<BlockPos> toChange = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, Integer> entry : data.blockRound.entrySet()) {
            if (entry.getValue() == from) {
                toChange.add(entry.getKey());
            }
        }
        for (BlockPos pos : toChange) {
            data.setRound(pos, target);
            changed++;
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /**
     * 【1.24】该维度里**可能的最大**底噪范围 = max(默认范围, 所有单独设置的最大值)。
     *
     * <p>只用来定「去多远找扶梯」的扫描半径 —— 真正判定「听不听得见」时用的是
     * {@link #getRound} 给出的**那条扶梯自己的**范围。单独设置表平时是空的，所以这就是默认值本身。
     */
    public static int getMaxRound(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_ROUND;
            }
            return maxOf(data.defaultRound, data.blockRound);
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        return maxOf(data.defaultRound, data.blockRound);
    }

    /** 【1.24】应用服务端同步过来的底噪范围表（覆盖式更新）。 */
    public static void applyClientRounds(ResourceKey<Level> dimension, int defaultRound,
                                        Map<BlockPos, Integer> blockRound) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.blockRound.clear();
        data.blockRound.putAll(blockRound);
        data.defaultRound = defaultRound;
        clientRoundGeneration++;
    }

    /**
     * 【1.24】底噪范围的「代次」：只在客户端镜像被整体替换时（收到 ROUND_SYNC、断开清镜像）递增。
     *
     * <p>给每 tick 都要读范围的播放器当缓存键，保证「范围一改，下一个 tick 立刻生效」。
     */
    public static long clientRoundGeneration() {
        return clientRoundGeneration;
    }

    /** 见 {@link #clientRoundGeneration()}。只在客户端线程写。 */
    private static long clientRoundGeneration;

    /** 【1.24】这条扶梯**无障碍提示音**的生效可闻范围（单独设置 &gt; 维度默认；1~128 格）。 */
    public static int getHelpRound(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_HELP_ROUND;
            }
            Integer own = findChainRound(data.blockHelpRound, level, pos);
            return own != null ? own : data.defaultHelpRound;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Integer own = findChainRound(data.blockHelpRound, level, pos);
        return own != null ? own : data.defaultHelpRound;
    }

    /** 【1.24】这条扶梯是否被**单独设置**过提示音范围（顺扶梯链找）。 */
    public static boolean hasOwnHelpRound(Level level, BlockPos pos) {
        Map<BlockPos, Integer> overrides;
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return false;
            }
            overrides = data.blockHelpRound;
        } else {
            overrides = getServerData((ServerLevel) level).blockHelpRound;
        }
        return findChainRound(overrides, level, pos) != null;
    }

    /** 【1.24】该维度的默认提示音范围（/futihelpround 设置；未设置过就是 4 格）。 */
    public static int getDefaultHelpRound(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_HELP_ROUND : data.defaultHelpRound;
        }
        return getServerData((ServerLevel) level).defaultHelpRound;
    }

    /** 【1.24】设置某条扶梯的提示音范围（服务端）。返回夹取后的实际值。 */
    public static int setHelpRound(ServerLevel level, BlockPos pos, int round) {
        EscalatorSpeedData data = getServerData(level);
        int v = EscalatorSpeedData.clampHelpRound(round);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            data.blockHelpRound.remove(p);
        }
        data.blockHelpRound.remove(pos);
        data.setHelpRound(pos, v);
        data.setDirty();
        return v;
    }

    /** /futihelpround &lt;范围&gt;：只改**默认**范围，单独设置过的不变。 */
    public static void setDefaultHelpRound(ServerLevel level, int round) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultHelpRound = EscalatorSpeedData.clampHelpRound(round);
        data.setDirty();
    }

    /** /futihelpround -f &lt;范围&gt;：强制所有扶梯提示音范围 = 该值（设默认 + 清单独设置）。 */
    public static int forceDefaultHelpRound(ServerLevel level, int round) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockHelpRound.size();
        data.defaultHelpRound = EscalatorSpeedData.clampHelpRound(round);
        data.blockHelpRound.clear();
        data.setDirty();
        return cleared;
    }

    /** /futihelpround &lt;X&gt; to &lt;Y&gt;：默认范围正好是 X 时才改成 Y；单独设置的不动。 */
    public static boolean replaceDefaultHelpRound(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultHelpRound != from) {
            return false;
        }
        data.defaultHelpRound = EscalatorSpeedData.clampHelpRound(to);
        data.setDirty();
        return true;
    }

    /** /futihelpround -f &lt;X&gt; to &lt;Y&gt;：把所有生效范围正好是 X 的扶梯（含单独设置的）改成 Y。 */
    public static int forceReplaceHelpRoundFromTo(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        int target = EscalatorSpeedData.clampHelpRound(to);
        int changed = 0;
        if (data.defaultHelpRound == from) {
            data.defaultHelpRound = target;
            changed++;
        }
        java.util.List<BlockPos> toChange = new java.util.ArrayList<>();
        for (Map.Entry<BlockPos, Integer> entry : data.blockHelpRound.entrySet()) {
            if (entry.getValue() == from) {
                toChange.add(entry.getKey());
            }
        }
        for (BlockPos pos : toChange) {
            data.setHelpRound(pos, target);
            changed++;
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** 【1.24】该维度里**可能的最大**提示音范围（见 {@link #getMaxRound}）。 */
    public static int getMaxHelpRound(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_HELP_ROUND;
            }
            return maxOf(data.defaultHelpRound, data.blockHelpRound);
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        return maxOf(data.defaultHelpRound, data.blockHelpRound);
    }

    /** 【1.24】应用服务端同步过来的提示音范围表（覆盖式更新）。 */
    public static void applyClientHelpRounds(ResourceKey<Level> dimension, int defaultHelpRound,
                                             Map<BlockPos, Integer> blockHelpRound) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.blockHelpRound.clear();
        data.blockHelpRound.putAll(blockHelpRound);
        data.defaultHelpRound = defaultHelpRound;
        clientHelpRoundGeneration++;
    }

    /** 【1.24】提示音范围的「代次」，用途同 {@link #clientRoundGeneration()}。 */
    public static long clientHelpRoundGeneration() {
        return clientHelpRoundGeneration;
    }

    /** 见 {@link #clientHelpRoundGeneration()}。只在客户端线程写。 */
    private static long clientHelpRoundGeneration;

    /** 「默认值 + 单独设置表」里可能的最大值（表为空时就是默认值）。 */
    private static int maxOf(int defaultRound, Map<BlockPos, Integer> overrides) {
        int max = defaultRound;
        for (int v : overrides.values()) {
            if (v > max) {
                max = v;
            }
        }
        return max;
    }

    /** 【1.7】该扶梯方块绑定的音频ID；无绑定返回 null。客户端读镜像，服务端读 SavedData。 */
    public static String getBlockAudioId(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? null : data.blockAudio.get(pos);
        }
        return getServerData((ServerLevel) level).getAudioId(pos);
    }

    /** 【1.7】取音频字节；不存在返回 null。客户端读镜像，服务端读 SavedData。 */
    public static byte[] getAudioBytes(Level level, String audioId) {
        if (audioId == null) {
            return null;
        }
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? null : data.audioLibrary.get(audioId);
        }
        return getServerData((ServerLevel) level).audioLibrary.get(audioId);
    }

    /** 【1.7】客户端：当前维度全部扶梯-音频绑定（只读视图），供声音播放器每 tick 遍历候选；无数据返回空 Map。 */
    public static Map<BlockPos, String> getClientAudioBindings(ResourceKey<Level> dimension) {
        ClientDimensionData data = CLIENT_DATA.get(dimension);
        return data == null ? Map.of() : java.util.Collections.unmodifiableMap(data.blockAudio);
    }

    /**
     * 【1.7】客户端：当前维度已存入存档的音频 ID（文件名）列表。
     * 供音乐选择界面列出可绑定/可删除的音频；空则返回空集合。
     */
    public static Set<String> getClientAudioLibraryKeys(Level level) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        return data == null ? Set.of() : java.util.Collections.unmodifiableSet(data.audioLibrary.keySet());
    }

    /** 断开连接时清空客户端镜像，避免换世界后残留旧数据。 */
    public static void clearClientData() {
        CLIENT_DATA.clear();
        clientHelpGeneration++;
        clientHelpVolumeGeneration++;
        // 【1.24】两个范围的代次也要 ++：镜像被清空同样是一次「整体替换」，
        // 否则播放器会继续用上一个世界缓存下来的范围。
        clientRoundGeneration++;
        clientHelpRoundGeneration++;
        // 【1.39】提示音音乐的代次同样要 ++：镜像被清空是一次「整体替换」，
        // 否则播放器会继续用上一个世界缓存下来的提示音。
        clientHelpAudioGeneration++;
        // 【1.42】直梯提示音设置也同理：不断代的话，断线重连后会沿用上一个世界的开关/倍速。
        clientLiftChimeGeneration++;
    }

    // ------------------------------------------------------------------
    // 【1.8】内置音频：随模组 jar 一起分发（assets/smoothlift/sounds/audio/*.ogg，
    // 并在 assets/smoothlift/sounds.json 里注册成 smoothlift:audio/* 声音事件）。
    // 好处：玩家装好模组就能直接绑定、开箱即用 —— 不需要自己准备 .ogg 文件，
    // 也不需要任何转码工具（ffmpeg 只在"作者制作音频"时用得到，与玩家无关）。
    // 绑定到扶梯时存的是带前缀的 ID（builtin:<key>），不会和存档音频库里的文件名撞车。
    //
    // 目前只内置 1 段（按需求精简）：
    //   subway_escalator.ogg（约 23.6 秒）—— 素材 = Freesound "4_Escalator.wav"
    //   https://freesound.org/people/14G_Panska_Hoskovcova_Eliska/sounds/419482/
    //   作者 14G_Panska_Hoskovcova_Eliska，许可 CC0 1.0（公共领域奉献，无需署名，
    //   可商用/可修改/可再分发）。出处与许可见 jar 内 AUDIO-CREDITS.txt。
    // ------------------------------------------------------------------

    /** 内置音频绑定 ID 的前缀。 */
    public static final String BUILTIN_PREFIX = "builtin:";

    /**
     * 内置音频：key（= 资源文件名，同时也是 sounds.json 里 audio/&lt;key&gt; 的名字）-&gt; 界面显示名。
     *
     * <p><b>不要随意改 key</b>：玩家的存档里存的是 {@code builtin:<key>},
     * 改名会让已经绑定过的扶梯静音。
     */
    private static final Map<String, String> BUILTIN_AUDIO = new LinkedHashMap<>();

    static {
        BUILTIN_AUDIO.put("subway_escalator", "内置 · 地铁自动扶梯");
    }

    /** 全部内置音频的绑定 ID（已带 {@link #BUILTIN_PREFIX} 前缀），顺序固定，供界面列出。 */
    public static List<String> builtinAudioIds() {
        List<String> out = new ArrayList<>(BUILTIN_AUDIO.size());
        for (String key : BUILTIN_AUDIO.keySet()) {
            out.add(BUILTIN_PREFIX + key);
        }
        return out;
    }

    /** 这个绑定 ID 是不是内置音频。 */
    public static boolean isBuiltinAudio(String audioId) {
        return audioId != null && audioId.startsWith(BUILTIN_PREFIX)
                && BUILTIN_AUDIO.containsKey(audioId.substring(BUILTIN_PREFIX.length()));
    }

    /** 内置音频的 key（去掉前缀，= 资源文件名）；不是内置时返回 null。 */
    public static String builtinKey(String audioId) {
        if (!isBuiltinAudio(audioId)) {
            return null;
        }
        return audioId.substring(BUILTIN_PREFIX.length());
    }

    /** 内置音频在界面上的中文显示名；不是内置时原样返回 ID。 */
    public static String displayName(String audioId) {
        String key = builtinKey(audioId);
        if (key == null) {
            return audioId;
        }
        String name = BUILTIN_AUDIO.get(key);
        return name != null ? name : key;
    }

    // ------------------------------------------------------------------
    // 【1.53】存档音频来源文件夹：<存档>/MBM_Audio/（原 smoothlift_audio，用户点名改名）。该文件夹只是"上传来源"：
    // 选中一个文件后会把内容拷进 SavedData（融入存档），之后删掉原文件仍可播放。
    // ------------------------------------------------------------------

    /** 存档目录下存放待导入 OGG 的文件夹名。 */
    public static final String AUDIO_FOLDER = "MBM_Audio";

    /** 该世界存档的音频来源文件夹路径。 */
    public static Path audioFolder(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve(AUDIO_FOLDER);
    }

    /** 确保存档音频来源文件夹存在；不存在则自动创建（服务端启动时调用）。 */
    public static void ensureAudioFolder(ServerLevel level) {
        try {
            Files.createDirectories(audioFolder(level));
        } catch (IOException ignored) {
            // 无法创建时忽略：后续扫描遇到不可读文件夹会当作无音频处理。
        }
    }

    /** 扫描存档音频来源文件夹里的 .ogg 文件，返回 文件名 → 字节。文件夹不存在/不可读/超限的文件忽略。 */
    public static Map<String, byte[]> scanAudioFiles(ServerLevel level) {
        Map<String, byte[]> out = new HashMap<>();
        Path dir = audioFolder(level);
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
                    .forEach(path -> {
                        try {
                            byte[] bytes = Files.readAllBytes(path);
                            if (bytes.length > 0 && bytes.length <= EscalatorSpeedData.MAX_AUDIO_BYTES) {
                                out.put(path.getFileName().toString(), bytes);
                            }
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
            // 文件夹尚未创建或不可读：视作无可用来源。
        }
        return out;
    }

    /**
     * 【1.7 诊断】判断这段字节是不是 Minecraft 能解码的「Ogg Vorbis」，不是就返回中文原因。
     *
     * <p>客户端是用 stb_vorbis（{@code com.mojang.blaze3d.audio.OggAudioStream}）解码的，
     * 只有 **Ogg 容器 + Vorbis 编码** 能解。常见踩坑：
     * <ul>
     *   <li>MP3 直接改扩展名为 .ogg —— 容器都不是 Ogg，解码时抛
     *       {@code IOException("Failed to find Ogg header")}；</li>
     *   <li>在线转换器/新版工具导出成 Ogg <b>Opus</b> —— stb_vorbis 不认 Opus；</li>
     *   <li>Ogg FLAC / Ogg Speex —— 同样不认。</li>
     * </ul>
     * 这些文件在客户端一律只能「静音」（解码异常被捕获），玩家完全看不出原因，
     * 所以入库前先在这里拦下来并给出明确提示。
     *
     * @return {@code null} 表示是合法的 Ogg Vorbis；否则返回可直接显示给玩家的原因
     */
    public static String describeOggProblem(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return "文件是空的（0 字节）或太小";
        }
        if (bytes[0] != 'O' || bytes[1] != 'g' || bytes[2] != 'g' || bytes[3] != 'S') {
            int n = Math.min(bytes.length, 16);
            String head = new String(bytes, 0, n, StandardCharsets.ISO_8859_1);
            if (head.startsWith("ID3")) {
                return "内容其实是 MP3（只是把扩展名改成了 .ogg）；请真正转成 Ogg Vorbis";
            }
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0) {
                return "内容是 MPEG 音频帧（MP3），不是 Ogg；请真正转成 Ogg Vorbis";
            }
            if (head.startsWith("fLaC")) {
                return "内容是 FLAC，不是 Ogg；请转成 Ogg Vorbis";
            }
            if (head.startsWith("RIFF")) {
                return "内容是 WAV，不是 Ogg；请转成 Ogg Vorbis";
            }
            return "不是 Ogg 容器（文件头不是 OggS）";
        }
        // 容器对了，再确认编码是 Vorbis（stb_vorbis 不认 Opus / FLAC / Speex）
        String body = new String(bytes, 0, Math.min(bytes.length, 64 * 1024), StandardCharsets.ISO_8859_1);
        if (body.contains("OpusHead") || body.contains("OpusTags")) {
            return "是 Ogg Opus，MC 只支持 Ogg Vorbis；请用 Vorbis 编码重新导出";
        }
        if (!body.contains("vorbis")) {
            return "没有 Vorbis 编码头（可能是 Ogg FLAC/Speex 等 MC 不支持的内容）";
        }
        return null;
    }

    /**
     * 从存档音频文件夹把指定文件拷入存档音频库（上传来源 -&gt; 融入存档）。
     * 入库前校验内容确实是 MC 能播的 Ogg Vorbis。
     *
     * @return {@code null} 表示导入成功；否则返回失败原因（可直接显示给玩家）
     */
    public static String importAudioToStore(ServerLevel level, String fileName) {
        byte[] bytes = scanAudioFiles(level).get(fileName);
        if (bytes == null) {
            return "存档文件夹 " + AUDIO_FOLDER + " 里没有这个文件（或超过 "
                    + (EscalatorSpeedData.MAX_AUDIO_BYTES / 1024 / 1024) + "MB）";
        }
        String problem = describeOggProblem(bytes);
        if (problem != null) {
            LOGGER.warn("[SmoothLift/Audio] 拒绝导入 {}（{} 字节）：{}", fileName, bytes.length, problem);
            return problem;
        }
        EscalatorSpeedData data = getServerData(level);
        data.audioLibrary.put(fileName, bytes);
        data.setDirty();
        LOGGER.info("[SmoothLift/Audio] 已导入 {}（{} 字节）到存档音频库", fileName, bytes.length);
        return null;
    }

    /** 客户端：存档文件夹里尚未入库、可"选中即导入并绑定"的 OGG 文件名；空返回空集合。 */
    public static Set<String> getClientFolderAudioKeys(Level level) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        if (data == null) {
            return Set.of();
        }
        java.util.Collections.synchronizedSet(data.folderAudio).removeAll(data.audioLibrary.keySet());
        return java.util.Collections.unmodifiableSet(data.folderAudio);
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

    /**
     * 清除某个扶梯方块上的全部记录（速度 / 阶梯速度 / 斧头标记 / 音量 / 音频绑定）。
     * 返回是否真的有记录被清除，供调用方决定要不要广播同步包。
     * <p>注意：音频绑定（blockAudio）也必须一并清除，否则拆掉已绑声音的扶梯后，
     * 那个坐标仍会在客户端继续播放，直到下一次音频同步才消失。
     */
    public static boolean removeSpeed(ServerLevel level, BlockPos pos) {
        EscalatorSpeedData data = getServerData(level);
        boolean removed = data.speeds.remove(pos) != null
                | data.stepSpeeds.remove(pos) != null
                | data.axeModified.remove(pos)
                | data.blockVolume.remove(pos) != null
                | data.blockHelp.remove(pos) != null
                | data.blockHelpVolume.remove(pos) != null
                | data.blockRound.remove(pos) != null
                | data.blockHelpRound.remove(pos) != null
                | data.blockHelpSpeedIn.remove(pos) != null
                | data.blockHelpSpeedOut.remove(pos) != null
                // 【1.41】提示音音乐的两套单独设置（顺便补上 1.39 漏掉的这一处：
                //   拆掉已单独设过提示音的扶梯时，旧坐标会一直留在存档里，并可能被后来的
                //   同步包带着走 —— 表现是「拆掉的那条扶梯还在响」）
                | data.blockHelpAudioIn.remove(pos) != null
                | data.blockHelpAudioOut.remove(pos) != null
                | data.blockAudio.remove(pos) != null;
        if (removed) {
            data.setDirty();
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 【1.30】「拆掉一部分再放回去」之后把整条链的单独设置补齐
    //
    // 症状：扶梯阶梯分成左右两半、两半的动画速度不一样，/futispeed 重写一遍才正常。
    // 两个成因都在这里补齐：
    //   ① EscalatorUtil.collectChain 可能只收半边（已单独修，见那边的【1.30】）；
    //   ② 被拆掉的那一段记录在 onBlockBreak 里被清掉了，玩家再放回去时**没有任何东西**
    //      会把这段补回来 —— 于是新放的那段跟随全局、留着的那段还是单独设置，两段动画不同步。
    //      MTR 是自己在 ItemEscalator#useOnBlock 里 setBlockState 的，不触发 Forge / Fabric 的
    //      放置事件，所以这里改成「玩家用扶梯物品右键 → 下一个服务端刻去重扫一遍」。
    // ------------------------------------------------------------------

    /** 待重扫的坐标：维度 → 右键点过的位置。下一个服务端刻统一处理。 */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> PENDING_CHAIN_RECONCILE = new HashMap<>();

    /** 找扶梯方块时扫描的半径（右键点在扶梯下方，实际方块落在附近几格内）。 */
    private static final int RECONCILE_RADIUS = 3;

    /**
     * 记下「玩家刚用扶梯物品右键了 around 附近」，下一个服务端刻再去看实际落了哪些方块。
     *
     * <p>调用方是各平台入口的右键钩子（它们手上只有「玩家点了哪一格」，而 MTR 的方块是这次
     * 右键**之后**才被放下去的，所以不能当场处理）。
     */
    public static void scheduleChainReconcile(Level level, BlockPos around) {
        if (level == null || around == null || level.isClientSide()) {
            return;
        }
        PENDING_CHAIN_RECONCILE
                .computeIfAbsent(level.dimension(), key -> new HashSet<>())
                .add(around.immutable());
    }

    /**
     * 每服务端刻调用：把上一刻记下的右键点重扫一遍，把整条扶梯的单独设置补齐。
     *
     * <p>只在真的补齐了东西时才广播同步包；已经一致时 {@link #reconcileChain} 返回 false，
     * 所以「连续右键延长扶梯」的常见操作不会反复发包。
     */
    public static void tickPendingReconcile(MinecraftServer server) {
        if (server == null || PENDING_CHAIN_RECONCILE.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (ResourceKey<Level> key : new ArrayList<>(PENDING_CHAIN_RECONCILE.keySet())) {
            Set<BlockPos> pending = PENDING_CHAIN_RECONCILE.remove(key);
            ServerLevel level = server.getLevel(key);
            if (pending == null || level == null) {
                continue;
            }
            for (BlockPos around : pending) {
                BlockPos seed = findStepNear(level, around);
                if (seed != null && reconcileChain(level, seed)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            syncToAll(server);
        }
    }

    /** 在 around 附近（半径 {@link #RECONCILE_RADIUS} 的立方体，按由近到远）找第一个扶梯**阶梯**方块。 */
    private static BlockPos findStepNear(ServerLevel level, BlockPos around) {
        for (int ring = 0; ring <= RECONCILE_RADIUS; ring++) {
            for (int dy = -ring; dy <= ring; dy++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        if (Math.max(Math.max(Math.abs(dx), Math.abs(dz)), Math.abs(dy)) != ring) {
                            continue;
                        }
                        BlockPos pos = around.offset(dx, dy, dz);
                        if (EscalatorUtil.isEscalatorStep(level.getBlockState(pos))) {
                            return pos;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 把 seed 所在那条扶梯的「单独设置」补齐到整条链上：链上任意一格有单独设置，
     * 就让整条链都用它 —— 于是左右两半、以及刚放回去的那一段，动画速度完全一致。
     *
     * <p>**只搬运已经在链上的值，绝不发明新设置**：整条链都没有单独设置（纯跟随全局）时直接返回
     * false，不动任何数据 —— 所以没被石斧调过的扶梯不受影响。
     *
     * @return 是否有数据被改动（调用方据此决定要不要广播）
     */
    private static boolean reconcileChain(ServerLevel level, BlockPos seed) {
        EscalatorSpeedData data = getServerData(level);
        Set<BlockPos> chain = EscalatorUtil.collectChain(level, seed);
        if (chain.isEmpty()) {
            return false;
        }
        Double repRun = null;
        Double repStep = null;
        for (BlockPos pos : chain) {
            if (repRun == null) {
                repRun = data.speeds.get(pos);
            }
            if (repStep == null) {
                repStep = data.stepSpeeds.get(pos);
            }
            if (repRun != null && repStep != null) {
                break;
            }
        }
        if (repRun == null && repStep == null) {
            return false;
        }
        boolean changed = false;
        for (BlockPos pos : chain) {
            changed |= putOrRemove(data.speeds, pos, repRun);
            changed |= putOrRemove(data.stepSpeeds, pos, repStep);
            // axeModified 决定 stepSpeeds 会不会被同步给客户端（见 filteredStepSpeeds），
            // 所以它必须与「这一格有没有单独阶梯设置」严格对应。
            if (repStep == null) {
                changed |= data.axeModified.remove(pos);
            } else {
                changed |= data.axeModified.add(pos);
            }
        }
        if (changed) {
            data.setDirty();
        }
        return changed;
    }

    /** 把 map 里 pos 的值设成 value；value 为 null 表示删掉。返回是否真的改动了。 */
    private static boolean putOrRemove(Map<BlockPos, Double> map, BlockPos pos, Double value) {
        if (value == null) {
            return map.remove(pos) != null;
        }
        Double old = map.put(pos, value);
        return old == null || !same(old, value);
    }

    public static void syncToAll(MinecraftServer server) {
        List<SyncPacket.LevelSpeedData> entries = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            entries.add(new SyncPacket.LevelSpeedData(
                    level.dimension().location().toString(),
                    data.defaultSpeed,
                    data.stepEnabled,
                    data.stepValue,
                    new HashMap<>(data.speeds),
                    filteredStepSpeeds(data)
            ));
        }
        SyncPacket packet = new SyncPacket(entries);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

    // ------------------------------------------------------------------
    // 【1.7】自定义扶梯声音：网络处理
    // ------------------------------------------------------------------

    /** 音频传输分块大小。MC 网络包上限 32767 字节，这里留足协议头余量。 */
    public static final int AUDIO_CHUNK_SIZE = 16 * 1024;

    /** 音频 ID（文件名）的最大字符数，超过则拒绝上传，避免同步包超限。 */
    public static final int MAX_AUDIO_NAME = 48;

    /** 服务端拼接中的音频上传：音频ID → (块索引 → 数据)。 */
    private static final Map<String, Map<Integer, byte[]>> PENDING_UPLOADS = new HashMap<>();

    /**
     * 收到客户端上传的一个音频块。全部块到齐后拼出完整 OGG 字节并返回；
     * 未到齐返回 null（继续等待）；块非法或缺块时丢弃并返回 null。
     * 音频ID为文件名：同名再上传即覆盖（一个音频可被多条扶梯复用，都是同一个 ID）。
     */
    public static byte[] handleAudioUploadChunk(String audioId, int totalChunks, int chunkIndex, byte[] chunk) {
        if (audioId == null || audioId.isEmpty() || audioId.length() > MAX_AUDIO_NAME
                || totalChunks <= 0
                || chunkIndex < 0 || chunkIndex >= totalChunks
                || chunk == null || chunk.length == 0 || chunk.length > AUDIO_CHUNK_SIZE) {
            return null;
        }
        Map<Integer, byte[]> chunks = PENDING_UPLOADS.computeIfAbsent(audioId, k -> new HashMap<>());
        chunks.put(chunkIndex, chunk);
        if (chunks.size() < totalChunks) {
            return null;
        }
        PENDING_UPLOADS.remove(audioId);
        int total = 0;
        for (byte[] part : chunks.values()) {
            total += part.length;
        }
        if (total <= 0 || total > EscalatorSpeedData.MAX_AUDIO_BYTES) {
            return null;
        }
        byte[] bytes = new byte[total];
        int offset = 0;
        for (int i = 0; i < totalChunks; i++) {
            byte[] part = chunks.get(i);
            if (part == null) {
                return null;
            }
            System.arraycopy(part, 0, bytes, offset, part.length);
            offset += part.length;
        }
        // OGG 魔数校验（'OggS'）
        if (bytes.length < 4 || bytes[0] != 'O' || bytes[1] != 'g' || bytes[2] != 'g' || bytes[3] != 'S') {
            return null;
        }
        return bytes;
    }

    /** 把已上传的音频写入存档。返回是否成功（音频ID格式非法、字节超限或不是 Ogg Vorbis 则拒绝）。 */
    public static boolean storeAudio(ServerLevel level, String audioId, byte[] bytes) {
        if (audioId == null || audioId.isEmpty() || audioId.length() > MAX_AUDIO_NAME
                || bytes == null || bytes.length == 0 || bytes.length > EscalatorSpeedData.MAX_AUDIO_BYTES) {
            return false;
        }
        String problem = describeOggProblem(bytes);
        if (problem != null) {
            LOGGER.warn("[SmoothLift/Audio] 拒绝上传的音频 {}（{} 字节）：{}", audioId, bytes.length, problem);
            return false;
        }
        EscalatorSpeedData data = getServerData(level);
        data.audioLibrary.put(audioId, bytes);
        data.setDirty();
        return true;
    }

    /** 把音频绑定到扶梯方块（音频必须已入库，或是内置音频）。返回是否绑定成功。 */
    public static boolean bindAudio(ServerLevel level, BlockPos pos, String audioId) {
        EscalatorSpeedData data = getServerData(level);
        if (audioId == null
                || (!isBuiltinAudio(audioId) && !data.audioLibrary.containsKey(audioId))) {
            return false;
        }
        data.bindAudio(pos, audioId);
        if (!data.hasAudio(pos)) {
            return false;
        }
        data.setDirty();
        return true;
    }

    /** 解绑扶梯方块的音频（之后该扶梯静音）。返回是否真的有绑定被解除。 */
    public static boolean unbindAudio(ServerLevel level, BlockPos pos) {
        EscalatorSpeedData data = getServerData(level);
        if (!data.hasAudio(pos)) {
            return false;
        }
        data.unbindAudio(pos);
        data.setDirty();
        return true;
    }

    /** 从存档删除一段音频，同时解绑所有引用它的扶梯。返回是否真的删除了。 */
    public static boolean deleteAudio(ServerLevel level, String audioId) {
        EscalatorSpeedData data = getServerData(level);
        if (!data.audioLibrary.containsKey(audioId)) {
            return false;
        }
        data.removeAudio(audioId);
        data.setDirty();
        return true;
    }

    // ------------------------------------------------------------------
    // 【1.11】默认扶梯音频（/futimusic）
    //
    // 数据模型和速度完全对称：
    //   defaultAudio = 「全局/默认」层 —— 没单独绑定音频的扶梯都用它；
    //   blockAudio   = 「被单独设置过」的扶梯（石斧界面绑定的）。
    //
    // 不带 -f 的指令只改**默认层**（已单独绑定音频的扶梯原封不动）；
    // 带 -f 的指令改**所有**扶梯（含单独绑定的）。
    // ------------------------------------------------------------------

    /** 【1.11】默认扶梯音频 ID；没设置返回 null。 */
    public static String getDefaultAudio(ServerLevel level) {
        return getServerData(level).defaultAudio;
    }

    /**
     * 【1.11】/futimusic &lt;名字&gt;：只改**默认**扶梯音频。
     * 已经单独绑定过音频的扶梯不受影响（这正是「已经添加了其他音乐的扶梯除外」）。
     */
    public static void setDefaultAudio(ServerLevel level, String audioId) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultAudio = audioId;
        data.setDirty();
    }

    /**
     * 【1.11】/futimusic -f &lt;名字&gt;：强制**所有**扶梯都用这个音频。
     * 做法是设默认值 + 清掉所有单独绑定（清掉比把每一格都写成同一个值更省存档）。
     *
     * @return 被清掉的单独绑定数
     */
    public static int forceDefaultAudio(ServerLevel level, String audioId) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockAudio.size();
        data.defaultAudio = audioId;
        data.blockAudio.clear();
        data.setDirty();
        return cleared;
    }

    /**
     * 【1.11】/futimusic &lt;X&gt; to &lt;Y&gt;：默认音频正好是 X 时才改成 Y；
     * 已经单独绑定音频的扶梯一概不动（音频不是「默认的 X」的就不变）。
     *
     * @return 是否真的改了
     */
    public static boolean replaceDefaultAudio(ServerLevel level, String from, String to) {
        EscalatorSpeedData data = getServerData(level);
        if (from == null || !from.equals(data.defaultAudio)) {
            return false;
        }
        data.defaultAudio = to;
        data.setDirty();
        return true;
    }

    /**
     * 【1.11】/futimusic -f &lt;X&gt; to &lt;Y&gt;：把**所有**音频为 X 的扶梯（含单独绑定的）改成 Y。
     *
     * @return 被改动的扶梯数（默认层算 1 条）
     */
    public static int forceReplaceAudioFromTo(ServerLevel level, String from, String to) {
        EscalatorSpeedData data = getServerData(level);
        int changed = 0;
        if (from != null && from.equals(data.defaultAudio)) {
            data.defaultAudio = to;
            changed++;
        }
        if (to == null) {
            changed += data.blockAudio.size();
            data.blockAudio.clear();
        } else {
            for (Map.Entry<BlockPos, String> entry : data.blockAudio.entrySet()) {
                if (from != null && from.equals(entry.getValue())) {
                    entry.setValue(to);
                    changed++;
                }
            }
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /**
     * 【1.11】命令行音频名字 → 绑定 ID。
     *
     * <ul>
     *   <li>{@code default} -&gt; 模组内置音频（目前只有 1 段）；</li>
     *   <li>{@code off} / {@code none} -&gt; {@code off=true}（清除默认音频）；</li>
     *   <li>其他 -&gt; 存档音频库里同名的文件（找不到时再试「名字 + .ogg」）。</li>
     * </ul>
     *
     * @param id    解析出的绑定 ID（{@code off} 时为 null）
     * @param off   是否是「关闭默认音频」
     * @param error 解析失败的原因（可直接显示给玩家）；成功时为 null
     */
    public record AudioArg(String id, boolean off, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    public static AudioArg resolveAudioName(ServerLevel level, String name) {
        if (name == null || name.isEmpty()) {
            return new AudioArg(null, false, "音频名字不能为空");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if ("default".equals(lower)) {
            return new AudioArg(builtinAudioIds().get(0), false, null);
        }
        if ("off".equals(lower) || "none".equals(lower)) {
            return new AudioArg(null, true, null);
        }
        if (isBuiltinAudio(name)) {
            return new AudioArg(name, false, null);
        }
        EscalatorSpeedData data = getServerData(level);
        if (data.audioLibrary.containsKey(name)) {
            return new AudioArg(name, false, null);
        }
        // 玩家少打后缀名时兜底
        if (!lower.endsWith(".ogg") && data.audioLibrary.containsKey(name + ".ogg")) {
            return new AudioArg(name + ".ogg", false, null);
        }
        return new AudioArg(null, false, "存档里没有叫「" + name + "」的音频"
                + (data.audioLibrary.isEmpty()
                        ? "（还没有上传过音频，玩家需要在石斧界面里上传/导入 .ogg）"
                        : "（已有的：" + previewNames(data) + "；也可以用 default）"));
    }

    /** 列几个已有音频名，拼进「找不到音频」的提示里。 */
    private static String previewNames(EscalatorSpeedData data) {
        List<String> names = new ArrayList<>(data.audioLibrary.keySet());
        names.sort(String::compareTo);
        boolean more = names.size() > 6;
        if (more) {
            names = names.subList(0, 6);
        }
        return String.join("、", names) + (more ? " 等" : "");
    }

    /** 【1.11】某条扶梯**实际使用**的音频 ID：链上任意方块单独绑定过就用它，否则用默认音频。 */
    public static String effectiveAudioId(Level level, BlockPos pos) {
        if (pos == null) {
            return null;
        }
        String own = findIndividualAudio(level, pos);
        if (own != null) {
            return own;
        }
        if (level.isClientSide()) {
            return getClientDefaultAudio(level.dimension());
        }
        return getServerData((ServerLevel) level).defaultAudio;
    }

    /** 【1.11】这条扶梯是否有单独绑定的音频（链上任意方块有绑定就算）。 */
    public static boolean hasIndividualAudio(Level level, BlockPos pos) {
        return pos != null && findIndividualAudio(level, pos) != null;
    }

    /** 在「这条扶梯」的整条链上找单独绑定的音频 ID；没有返回 null。 */
    private static String findIndividualAudio(Level level, BlockPos pos) {
        String own = getBlockAudioId(level, pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            String id = getBlockAudioId(level, p);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /**
     * 【1.11】服务端：玩家「当前所在的扶梯」，供 /futispeed、/jietispeed、/futimusic 显示用。
     * 优先级与客户端动画驱动一致：脚下/身上 → 准星指向（64 格）→ 附近最近（16 格）。
     * 找不到返回 null（此时指令显示全局默认值）。
     */
    public static BlockPos currentEscalator(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos feet = player.blockPosition();
        if (EscalatorUtil.isEscalator(level.getBlockState(feet))) {
            return feet;
        }
        if (EscalatorUtil.isEscalator(level.getBlockState(feet.below()))) {
            return feet.below();
        }
        if (EscalatorUtil.isEscalator(level.getBlockState(feet.above()))) {
            return feet.above();
        }
        HitResult hit = player.pick(64.0, 1.0F, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = blockHit.getBlockPos();
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos candidate = hitPos.offset(0, dy, 0);
                if (EscalatorUtil.isEscalator(level.getBlockState(candidate))) {
                    return candidate;
                }
            }
        }
        // 附近最近（指令调用频率极低，直接三重循环足够，不必做球壳优化）
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -NEARBY_SEARCH; dx <= NEARBY_SEARCH; dx++) {
            for (int dy = -NEARBY_SEARCH; dy <= NEARBY_SEARCH; dy++) {
                for (int dz = -NEARBY_SEARCH; dz <= NEARBY_SEARCH; dz++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (!EscalatorUtil.isEscalator(level.getBlockState(candidate))) {
                        continue;
                    }
                    double dist = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /** 「当前扶梯」兜底搜索半径（格）。 */
    private static final int NEARBY_SEARCH = 16;

    /** 构建某维度的完整音频同步负载（音频库 + 来源文件夹名单 + 扶梯-音频绑定）。 */
    private static byte[] buildAudioSyncPayload(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(data.audioLibrary.size());
        for (Map.Entry<String, byte[]> entry : data.audioLibrary.entrySet()) {
            buf.writeUtf(entry.getKey(), 128);
            buf.writeByteArray(entry.getValue());
        }
        Map<String, byte[]> folder = scanAudioFiles(level);
        buf.writeVarInt(folder.size());
        for (String name : folder.keySet()) {
            buf.writeUtf(name, 128);
        }
        buf.writeVarInt(data.blockAudio.size());
        for (Map.Entry<BlockPos, String> entry : data.blockAudio.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeUtf(entry.getValue(), 128);
        }
        // 【1.11】默认扶梯音频（空串表示没有默认音频）
        buf.writeUtf(data.defaultAudio == null ? "" : data.defaultAudio, 128);
        byte[] payload = new byte[buf.readableBytes()];
        buf.readBytes(payload);
        return payload;
    }

    /** 把一个维度的音频数据分块发给单个玩家（Forge SimpleChannel，一包一块）。 */
    public static void sendAudioSyncTo(ServerPlayer player, ServerLevel level) {
        byte[] payload = buildAudioSyncPayload(level);
        String dimId = level.dimension().location().toString();
        int totalChunks = Math.max(1, (payload.length + AUDIO_CHUNK_SIZE - 1) / AUDIO_CHUNK_SIZE);
        for (int i = 0; i < totalChunks; i++) {
            int from = i * AUDIO_CHUNK_SIZE;
            int len = Math.min(AUDIO_CHUNK_SIZE, payload.length - from);
            byte[] chunk = new byte[len];
            System.arraycopy(payload, from, chunk, 0, len);
            Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new AudioSyncPacket(dimId, totalChunks, i, chunk));
        }
    }

    /** 把全部维度的音频数据分块同步给所有在线玩家。 */
    public static void syncAudioToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendAudioSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 【1.9】扶梯声音音量：同步（单独一个小包，避免为了改音量重发整个音频库）
    // ------------------------------------------------------------------

    /** 把一个维度的扶梯音量表发给单个玩家。 */
    public static void sendVolumeSyncTo(ServerPlayer player, ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new VolumeSyncPacket(level.dimension().location().toString(),
                        data.defaultVolume, new HashMap<>(data.blockVolume)));
    }

    /** 把全部维度的扶梯音量表同步给所有在线玩家。 */
    public static void syncVolumeToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendVolumeSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 【1.16】无障碍提示音开关：同步（和音量一样单独一个小包，开关数据极小）
    // ------------------------------------------------------------------

    /** 把一个维度的提示音开关表发给单个玩家。 */
    public static void sendHelpSyncTo(ServerPlayer player, ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new HelpSyncPacket(level.dimension().location().toString(),
                        data.defaultHelp, new HashMap<>(data.blockHelp)));
    }

    /** 把全部维度的提示音开关表同步给所有在线玩家。 */
    public static void syncHelpToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendHelpSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 【1.18】无障碍提示音音量：同步（同样单独一个小包）
    // ------------------------------------------------------------------

    /** 把一个维度的提示音音量表发给单个玩家。 */
    public static void sendHelpVolumeSyncTo(ServerPlayer player, ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new HelpVolumeSyncPacket(level.dimension().location().toString(),
                        data.defaultHelpVolume, new HashMap<>(data.blockHelpVolume)));
    }

    /** 把全部维度的提示音音量表同步给所有在线玩家。 */
    public static void syncHelpVolumeToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendHelpVolumeSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 【1.24】两个「淡入淡出范围」：同步（同样各一个小包）
    //   ★ 1.24 没有界面控件，所以只有 S→C 的 SYNC，没有 C→S 的 SET。
    // ------------------------------------------------------------------

    /** 把一个维度的底噪范围表发给单个玩家。 */
    public static void sendRoundSyncTo(ServerPlayer player, ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new RoundSyncPacket(level.dimension().location().toString(),
                        data.defaultRound, new HashMap<>(data.blockRound)));
    }

    /** 把全部维度的底噪范围表同步给所有在线玩家。 */
    public static void syncRoundToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendRoundSyncTo(player, level);
            }
        }
    }

    /** 把一个维度的提示音范围表发给单个玩家。 */
    public static void sendHelpRoundSyncTo(ServerPlayer player, ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new HelpRoundSyncPacket(level.dimension().location().toString(),
                        data.defaultHelpRound, new HashMap<>(data.blockHelpRound)));
    }

    /** 把全部维度的提示音范围表同步给所有在线玩家。 */
    public static void syncHelpRoundToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendHelpRoundSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 【1.31】无障碍提示音的**速率**（/futihelpspeed in|out）
    // 【1.41】无障碍提示音的**音乐**（/futihelpmusic in|out + 提示音选择界面）
    //
    // 这两块是从 Fabric 1.20.1(1.9) 同步过来的**纯数据层 + 同步层**：
    //   · 速率：进 / 出两套（defaultHelpSpeedIn|Out + blockHelpSpeedIn|Out），单位 Hz；
    //   · 音乐：进 / 出两套（defaultHelpAudioIn|Out + blockHelpAudioIn|Out），
    //     与运行底噪共用同一个 audioLibrary，但「哪段用在哪儿」是独立数据。
    // 数据模型与 /futiloud、/futihelpround 完全对称，只记录与默认**不同**的项。
    // ★ Forge 与 Fabric 的唯一差别只在最下面两个 send*SyncTo：这里走 SimpleChannel
    //   （Packets.CHANNEL.send + PacketDistributor.PLAYER），不收 FriendlyByteBuf。
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 【1.31】无障碍提示音的**速率**（每秒响几次，单位 Hz）
    //
    //   ★ 这里管的是**端头那一路提示音**「响得多快」，和上面四个维度凑成完整的一套：
    //     /futihelp（开关）、/futihelploud（音量）、/futihelpround（范围）、
    //     /futihelpspeed in|out（速率）。四套数据与四条指令互不影响。
    //   ★ 入口（上客端）与出口（落客端）是**两套**数据：同一个指令的两个子命令。
    //   ★ 与 1.24 的两个范围一样**没有石斧界面控件**，只有 S→C 同步、没有 SET 通道。
    //
    //   速率是「换素材 + 调 pitch」实现的（原版 SoundEngine 把 pitch 夹在 [0.5,2.0]），
    //   细节见 EscalatorChimePlayer#chimeEventFor / #chimePitchFor。
    //   两套速率总是同时设置、同时同步，所以**共用一只同步包和一个代次**（见 applyClientHelpSpeeds）。
    // ------------------------------------------------------------------

    /** 【1.31】这条扶梯**上客端（进入扶梯）**提示音的生效速率（Hz，单独设置 &gt; 维度默认；1~100）。 */
    public static int getHelpSpeedIn(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_HELP_SPEED_IN;
            }
            Integer own = findChainHelpSpeed(data.blockHelpSpeedIn, level, pos);
            return own != null ? own : data.defaultHelpSpeedIn;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Integer own = findChainHelpSpeed(data.blockHelpSpeedIn, level, pos);
        return own != null ? own : data.defaultHelpSpeedIn;
    }

    /** 【1.31】这条扶梯**落客端（离开扶梯）**提示音的生效速率（Hz）。 */
    public static int getHelpSpeedOut(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.DEFAULT_HELP_SPEED_OUT;
            }
            Integer own = findChainHelpSpeed(data.blockHelpSpeedOut, level, pos);
            return own != null ? own : data.defaultHelpSpeedOut;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        Integer own = findChainHelpSpeed(data.blockHelpSpeedOut, level, pos);
        return own != null ? own : data.defaultHelpSpeedOut;
    }

    /** 【1.31】这条扶梯是否被**单独设置**过上客端速率（顺扶梯链找）。 */
    public static boolean hasOwnHelpSpeedIn(Level level, BlockPos pos) {
        return findChainHelpSpeed(helpSpeedMap(level, true), level, pos) != null;
    }

    /** 【1.31】这条扶梯是否被**单独设置**过落客端速率（顺扶梯链找）。 */
    public static boolean hasOwnHelpSpeedOut(Level level, BlockPos pos) {
        return findChainHelpSpeed(helpSpeedMap(level, false), level, pos) != null;
    }

    /** 取（客户端镜像 / 服务端存档的）速率「单独设置」表；{@code in} 为 true 取上客端那套。 */
    private static Map<BlockPos, Integer> helpSpeedMap(Level level, boolean in) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return java.util.Collections.emptyMap();
            }
            return in ? data.blockHelpSpeedIn : data.blockHelpSpeedOut;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        return in ? data.blockHelpSpeedIn : data.blockHelpSpeedOut;
    }

    /**
     * 顺扶梯链找单独设置的提示音速率：自己这块优先，其次链上其它方块；整条链都没有返回 null。
     *
     * <p>与 {@link #findChainRound} 同一套逻辑 —— 单独设置是按「整条扶梯」写的，
     * 所以从链上任意一格读都要能读到（否则「在 A 块设过、走到 B 块读出来是默认值」）。
     */
    private static Integer findChainHelpSpeed(Map<BlockPos, Integer> overrides, Level level, BlockPos pos) {
        if (overrides.isEmpty()) {
            return null;
        }
        Integer own = overrides.get(pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            Integer value = overrides.get(p);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** 【1.31】该维度的默认上客端提示音速率（/futihelpspeed in 设置；未设置过就是 10 Hz）。 */
    public static int getDefaultHelpSpeedIn(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_HELP_SPEED_IN : data.defaultHelpSpeedIn;
        }
        return getServerData((ServerLevel) level).defaultHelpSpeedIn;
    }

    /** 【1.31】该维度的默认落客端提示音速率（/futihelpspeed out 设置；未设置过就是 1 Hz）。 */
    public static int getDefaultHelpSpeedOut(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_HELP_SPEED_OUT : data.defaultHelpSpeedOut;
        }
        return getServerData((ServerLevel) level).defaultHelpSpeedOut;
    }

    /** 【1.31】设置某条扶梯的上客端提示音速率（服务端）。返回夹取后的实际值。 */
    public static int setHelpSpeedIn(ServerLevel level, BlockPos pos, int speed) {
        EscalatorSpeedData data = getServerData(level);
        int v = EscalatorSpeedData.clampHelpSpeed(speed);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            data.blockHelpSpeedIn.remove(p);
        }
        data.blockHelpSpeedIn.remove(pos);
        data.setHelpSpeedIn(pos, v);
        data.setDirty();
        return v;
    }

    /** 【1.31】设置某条扶梯的落客端提示音速率（服务端）。返回夹取后的实际值。 */
    public static int setHelpSpeedOut(ServerLevel level, BlockPos pos, int speed) {
        EscalatorSpeedData data = getServerData(level);
        int v = EscalatorSpeedData.clampHelpSpeed(speed);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            data.blockHelpSpeedOut.remove(p);
        }
        data.blockHelpSpeedOut.remove(pos);
        data.setHelpSpeedOut(pos, v);
        data.setDirty();
        return v;
    }

    /** /futihelpspeed in &lt;Hz&gt;：只改**默认**上客端速率，单独设置过的不变。 */
    public static void setDefaultHelpSpeedIn(ServerLevel level, int speed) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultHelpSpeedIn = EscalatorSpeedData.clampHelpSpeed(speed);
        data.setDirty();
    }

    /** /futihelpspeed out &lt;Hz&gt;：只改**默认**落客端速率，单独设置过的不变。 */
    public static void setDefaultHelpSpeedOut(ServerLevel level, int speed) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultHelpSpeedOut = EscalatorSpeedData.clampHelpSpeed(speed);
        data.setDirty();
    }

    /**
     * /futihelpspeed -f in &lt;Hz&gt;：强制**所有**扶梯上客端速率 = 该值（设默认 + 清掉所有单独设置）。
     *
     * @return 被清掉的单独设置数
     */
    public static int forceDefaultHelpSpeedIn(ServerLevel level, int speed) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockHelpSpeedIn.size();
        data.defaultHelpSpeedIn = EscalatorSpeedData.clampHelpSpeed(speed);
        data.blockHelpSpeedIn.clear();
        data.setDirty();
        return cleared;
    }

    /** /futihelpspeed -f out &lt;Hz&gt;：强制所有扶梯落客端速率 = 该值（设默认 + 清单独设置）。 */
    public static int forceDefaultHelpSpeedOut(ServerLevel level, int speed) {
        EscalatorSpeedData data = getServerData(level);
        int cleared = data.blockHelpSpeedOut.size();
        data.defaultHelpSpeedOut = EscalatorSpeedData.clampHelpSpeed(speed);
        data.blockHelpSpeedOut.clear();
        data.setDirty();
        return cleared;
    }

    /** /futihelpspeed in &lt;X&gt; to &lt;Y&gt;：默认上客端速率正好是 X 时才改成 Y；单独设置的不动。 */
    public static boolean replaceDefaultHelpSpeedIn(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultHelpSpeedIn != from) {
            return false;
        }
        data.defaultHelpSpeedIn = EscalatorSpeedData.clampHelpSpeed(to);
        data.setDirty();
        return true;
    }

    /** /futihelpspeed out &lt;X&gt; to &lt;Y&gt;：默认落客端速率正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultHelpSpeedOut(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultHelpSpeedOut != from) {
            return false;
        }
        data.defaultHelpSpeedOut = EscalatorSpeedData.clampHelpSpeed(to);
        data.setDirty();
        return true;
    }

    /** /futihelpspeed -f in &lt;X&gt; to &lt;Y&gt;：把所有**生效上客端速率正好是 X** 的（含单独设置的）改成 Y。 */
    public static int forceReplaceHelpSpeedInFromTo(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        int target = EscalatorSpeedData.clampHelpSpeed(to);
        int changed = 0;
        if (data.defaultHelpSpeedIn == from) {
            data.defaultHelpSpeedIn = target;
            changed++;
        }
        for (BlockPos pos : matchingKeys(data.blockHelpSpeedIn, from)) {
            data.setHelpSpeedIn(pos, target);
            changed++;
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** /futihelpspeed -f out &lt;X&gt; to &lt;Y&gt;：把所有生效落客端速率正好是 X 的（含单独设置的）改成 Y。 */
    public static int forceReplaceHelpSpeedOutFromTo(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        int target = EscalatorSpeedData.clampHelpSpeed(to);
        int changed = 0;
        if (data.defaultHelpSpeedOut == from) {
            data.defaultHelpSpeedOut = target;
            changed++;
        }
        for (BlockPos pos : matchingKeys(data.blockHelpSpeedOut, from)) {
            data.setHelpSpeedOut(pos, target);
            changed++;
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /** 先收集「值正好是 from」的键再改，避免边遍历边改 map（和 round 那两处同一手法）。 */
    private static List<BlockPos> matchingKeys(Map<BlockPos, Integer> overrides, int from) {
        List<BlockPos> out = new ArrayList<>();
        for (Map.Entry<BlockPos, Integer> entry : overrides.entrySet()) {
            if (entry.getValue() == from) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    /**
     * 【1.31】应用服务端同步过来的两套提示音速率（覆盖式更新）。
     *
     * <p>入口与出口总是同一条指令一起设置、一起同步，所以共用一只包 → 一次覆盖两张表、代次只 +1。
     */
    public static void applyClientHelpSpeeds(ResourceKey<Level> dimension,
                                             int defaultIn, Map<BlockPos, Integer> blockIn,
                                             int defaultOut, Map<BlockPos, Integer> blockOut) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.blockHelpSpeedIn.clear();
        data.blockHelpSpeedIn.putAll(blockIn);
        data.defaultHelpSpeedIn = defaultIn;
        data.blockHelpSpeedOut.clear();
        data.blockHelpSpeedOut.putAll(blockOut);
        data.defaultHelpSpeedOut = defaultOut;
        clientHelpSpeedGeneration++;
    }

    /**
     * 【1.31】提示音速率的「代次」，用途同 {@link #clientRoundGeneration()} ——
     * 给每 tick 现算速率的播放器当缓存键，保证「指令一改，下一个 tick 立刻换速度」。
     */
    public static long clientHelpSpeedGeneration() {
        return clientHelpSpeedGeneration;
    }

    /** 见 {@link #clientHelpSpeedGeneration()}。只在客户端线程写。 */
    private static long clientHelpSpeedGeneration;

    // ------------------------------------------------------------------
    // 【1.41】无障碍提示音**音乐**（/futihelpmusic in|out + 提示音选择界面）
    //
    // 数据模型与 /futimusic 完全对称：
    //   defaultHelpAudioIn/Out = 「默认」层（没单独设置的扶梯都用它，初始 = 模组原来的提示音）；
    //   blockHelpAudioIn/Out   = 「被单独设置过」的扶梯（界面上点的那一条）。
    // 但**音频字节共用同一份 audioLibrary**（与运行底噪同一个导入文件夹 / 同一个库）。
    // ★【1.41】「进入扶梯（上客端）」与「离开扶梯（落客端）」是**两套独立数据**
    //   （形状同 /futihelpspeed 的 in|out），两头一起同步、共用一只包与一个代次
    //   （见 applyClientHelpAudio / clientHelpAudioGeneration）。
    // ★ 这是「可变条件」：不能塞进按 anchor 缓存的几何结果里（见 EscalatorChimePlayer 的
    //   helpAudioIds，与 helpEnabled 同一套代次缓存）。
    // ------------------------------------------------------------------

    /** 【1.41】应用服务端同步过来的提示音音乐（进 / 出两套：默认层 + 单独设置层，覆盖式更新）。 */
    public static void applyClientHelpAudio(ResourceKey<Level> dimension,
                                            String defaultIn, Map<BlockPos, String> blockIn,
                                            String defaultOut, Map<BlockPos, String> blockOut) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.defaultHelpAudioIn = normaliseHelpAudio(defaultIn);
        data.blockHelpAudioIn.clear();
        data.blockHelpAudioIn.putAll(blockIn);
        data.defaultHelpAudioOut = normaliseHelpAudio(defaultOut);
        data.blockHelpAudioOut.clear();
        data.blockHelpAudioOut.putAll(blockOut);
        clientHelpAudioGeneration++;
    }

    /**
     * 【1.41】提示音音乐的「代次」，用途同 {@link #clientHelpSpeedGeneration()} ——
     * 给每 tick 现算「这条扶梯该播哪段提示音」的播放器当缓存键。
     */
    public static long clientHelpAudioGeneration() {
        return clientHelpAudioGeneration;
    }

    /** 见 {@link #clientHelpAudioGeneration()}。只在客户端线程写。 */
    private static long clientHelpAudioGeneration;

    /** 空 / null 一律归到「默认」（= 模组原来的提示音），别让 null 漏进播放器。 */
    private static String normaliseHelpAudio(String audioId) {
        return audioId == null || audioId.isEmpty() ? EscalatorSpeedData.HELP_AUDIO_DEFAULT : audioId;
    }

    /**
     * 【1.41】客户端：当前维度默认的提示音音乐 ID；镜像还没到时返回 default。
     * {@code in} 为 true = 进入扶梯（上客端）那一头。
     */
    public static String getClientDefaultHelpAudio(ResourceKey<Level> dimension, boolean in) {
        ClientDimensionData data = CLIENT_DATA.get(dimension);
        if (data == null) {
            return EscalatorSpeedData.HELP_AUDIO_DEFAULT;
        }
        return normaliseHelpAudio(in ? data.defaultHelpAudioIn : data.defaultHelpAudioOut);
    }

    /**
     * 【1.41】该扶梯方块**单独设置**的提示音音乐 ID；没单独设置返回 null。
     * 客户端读镜像，服务端读 SavedData。{@code in} 为 true = 上客端。
     */
    public static String getBlockHelpAudioId(Level level, BlockPos pos, boolean in) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return null;
            }
            return (in ? data.blockHelpAudioIn : data.blockHelpAudioOut).get(pos);
        }
        return getServerData((ServerLevel) level).getHelpAudioId(pos, in);
    }

    // ------------------------------------------------------------------
    // 【1.41】无障碍提示音「音乐」（/futihelpmusic in|out）
    //
    // 与 /futimusic 的运行底噪**完全对称**的第二套音频绑定，但有三处不同：
    //   ① 共用同一份 audioLibrary（同一个导入文件夹，导入一次两边都能选）；
    //   ② `default` 的含义不同 —— 这里是「模组原来的提示音」（五档素材 + 速率分档），
    //      不是内置运行底噪 subway_escalator；
    //   ③ 多一个 HELP_AUDIO_OFF：可以把**某一条扶梯的这一头**（或整个默认层）单独设成不播提示音。
    // ★【1.41】「进入扶梯（上客端）」与「离开扶梯（落客端）」是**两套独立数据**：
    //   下面每个方法都带一个 `in` 参数（true = 上客端），形状与 /futihelpspeed 的 in|out 完全一致，
    //   指令也照它写成 `/futihelpmusic in|out <名字>`（详见 SmoothLift 里的注册段）。
    // ------------------------------------------------------------------

    /** 【1.41】默认提示音音乐 ID（永远非 null，初始 = {@code default} = 模组原来的提示音）。 */
    public static String getDefaultHelpAudio(ServerLevel level, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        return normaliseHelpAudio(in ? data.defaultHelpAudioIn : data.defaultHelpAudioOut);
    }

    /** 【1.41】/futihelpmusic in|out &lt;名字&gt;：只改**默认**层（已单独设置过的扶梯不变）。 */
    public static void setDefaultHelpAudio(ServerLevel level, String audioId, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        String id = normaliseHelpAudio(audioId);
        if (in) {
            data.defaultHelpAudioIn = id;
        } else {
            data.defaultHelpAudioOut = id;
        }
        data.setDirty();
    }

    /**
     * 【1.41】/futihelpmusic -f in|out &lt;名字&gt;：设默认值 + 清掉**这一头**的所有单独设置。
     *
     * <p>注意只清 `in`（或只清 `out`）那一张表：另一头的单独设置原地不动
     * —— 与 /futihelpspeed -f in|out 的语义完全一致。
     *
     * @return 被清掉的单独设置数
     */
    public static int forceDefaultHelpAudio(ServerLevel level, String audioId, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        Map<BlockPos, String> overrides = data.helpAudioOverrides(in);
        int cleared = overrides.size();
        String id = normaliseHelpAudio(audioId);
        if (in) {
            data.defaultHelpAudioIn = id;
        } else {
            data.defaultHelpAudioOut = id;
        }
        overrides.clear();
        data.setDirty();
        return cleared;
    }

    /** 【1.41】/futihelpmusic in|out &lt;X&gt; to &lt;Y&gt;：默认层正好是 X 时才改成 Y。@return 是否真的改了 */
    public static boolean replaceDefaultHelpAudio(ServerLevel level, String from, String to, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        String current = normaliseHelpAudio(in ? data.defaultHelpAudioIn : data.defaultHelpAudioOut);
        if (from == null || !from.equals(current)) {
            return false;
        }
        String id = normaliseHelpAudio(to);
        if (in) {
            data.defaultHelpAudioIn = id;
        } else {
            data.defaultHelpAudioOut = id;
        }
        data.setDirty();
        return true;
    }

    /**
     * 【1.41】/futihelpmusic -f in|out &lt;X&gt; to &lt;Y&gt;：把这一头音乐为 X 的扶梯（含单独设置的）改成 Y。
     *
     * @return 被改动的扶梯数（默认层算 1 条）
     */
    public static int forceReplaceHelpAudioFromTo(ServerLevel level, String from, String to, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        int changed = 0;
        String id = normaliseHelpAudio(to);
        String current = normaliseHelpAudio(in ? data.defaultHelpAudioIn : data.defaultHelpAudioOut);
        if (from != null && from.equals(current)) {
            if (in) {
                data.defaultHelpAudioIn = id;
            } else {
                data.defaultHelpAudioOut = id;
            }
            changed++;
        }
        for (Map.Entry<BlockPos, String> entry : data.helpAudioOverrides(in).entrySet()) {
            if (from != null && from.equals(entry.getValue())) {
                entry.setValue(id);
                changed++;
            }
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    /**
     * 【1.39】命令行「提示音音乐」名字 → ID。
     *
     * <ul>
     *   <li>{@code default} -&gt; 模组原来的提示音（{@link EscalatorSpeedData#HELP_AUDIO_DEFAULT}）；</li>
     *   <li>{@code off} / {@code none} -&gt; 不播提示音（{@link EscalatorSpeedData#HELP_AUDIO_OFF}，off=true）；</li>
     *   <li>其他 -&gt; 存档音频库里同名的文件（找不到时再试「名字 + .ogg」）。</li>
     * </ul>
     *
     * <p>★ **不接受内置运行底噪**（{@code builtin:...}）：那是整条扶梯 23 秒的环境音，
     * 而提示音要的是端头短促循环的定位音；「模组自带的那一个」已经被 {@code default} 占用，
     * 再允许 builtin 只会让两个 default 的语义打架。
     */
    public static AudioArg resolveHelpAudioName(ServerLevel level, String name) {
        if (name == null || name.isEmpty()) {
            return new AudioArg(null, false, "提示音名字不能为空");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if ("default".equals(lower)) {
            return new AudioArg(EscalatorSpeedData.HELP_AUDIO_DEFAULT, false, null);
        }
        if ("off".equals(lower) || "none".equals(lower)) {
            return new AudioArg(EscalatorSpeedData.HELP_AUDIO_OFF, true, null);
        }
        if (isBuiltinAudio(name)) {
            return new AudioArg(null, false, "无障碍提示音不能用内置运行底噪（那是整条扶梯的环境音）；"
                    + "这里请用 default（模组原来的提示音）或自己导入的文件名");
        }
        EscalatorSpeedData data = getServerData(level);
        if (data.audioLibrary.containsKey(name)) {
            return new AudioArg(name, false, null);
        }
        if (!lower.endsWith(".ogg") && data.audioLibrary.containsKey(name + ".ogg")) {
            return new AudioArg(name + ".ogg", false, null);
        }
        return new AudioArg(null, false, "存档里没有叫「" + name + "」的音频"
                + (data.audioLibrary.isEmpty()
                        ? "（还没有上传过音频，需要在提示音选择界面里导入 .ogg）"
                        : "（已有的：" + previewNames(data) + "；也可以用 default）"));
    }

    /**
     * 【1.41】这条扶梯**实际生效**的提示音音乐 ID：单独设置 &gt; 维度默认。**永远非 null**
     * （没设过就是 {@code default} = 模组原来的提示音）。
     *
     * <p>{@code in} 为 true = **进入扶梯（上客端）**那一头，false = 离开扶梯（落客端）。
     */
    public static String effectiveHelpAudioId(Level level, BlockPos pos, boolean in) {
        if (pos == null) {
            return EscalatorSpeedData.HELP_AUDIO_DEFAULT;
        }
        String own = findChainHelpAudio(level, pos, in);
        if (own != null) {
            return own;
        }
        if (level.isClientSide()) {
            return getClientDefaultHelpAudio(level.dimension(), in);
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        return normaliseHelpAudio(in ? data.defaultHelpAudioIn : data.defaultHelpAudioOut);
    }

    /** 【1.41】这条扶梯**这一头**是否被**单独设置**过提示音音乐（顺扶梯链找）。 */
    public static boolean hasIndividualHelpAudio(Level level, BlockPos pos, boolean in) {
        return pos != null && findChainHelpAudio(level, pos, in) != null;
    }

    /** 【1.41】界面用：这条扶梯单独设置的提示音音乐 ID（顺链找）；没设过返回 null（= 跟随默认）。 */
    public static String getHelpAudioForScreen(Level level, BlockPos pos, boolean in) {
        return findChainHelpAudio(level, pos, in);
    }

    /** 顺扶梯链找单独设置：自己这块优先，其次链上其它方块；整条链都没有返回 null（用维度默认）。 */
    private static String findChainHelpAudio(Level level, BlockPos pos, boolean in) {
        Map<BlockPos, String> overrides = helpAudioOverrides(level, in);
        if (overrides.isEmpty()) {
            return null;
        }
        String own = overrides.get(pos);
        if (own != null) {
            return own;
        }
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            String value = overrides.get(p);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 【1.41】当前维度的「单独设置」表（客户端读镜像、服务端读 SavedData）。
     * {@code in} 为 true = 进入扶梯（上客端）那一头。
     */
    private static Map<BlockPos, String> helpAudioOverrides(Level level, boolean in) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return Map.of();
            }
            return in ? data.blockHelpAudioIn : data.blockHelpAudioOut;
        }
        return getServerData((ServerLevel) level).helpAudioOverrides(in);
    }

    /**
     * 【1.41】界面：把提示音音乐绑定到这条扶梯的**某一头**。
     *
     * <p>与 {@link #bindAudio} 不同，这里**先清掉整条链上的旧记录、再只记玩家点的那一块**
     * （同 {@link #setHelp} 的做法），保证「一条扶梯每一头最多一条记录」，
     * 免得链上多块各说各话、界面上来回跳。
     *
     * <p>★ 只动 {@code in}（或只动 {@code out}）那一张表：另一头的单独设置原地不动
     * —— 这正是「进 / 出各设各的」的关键。
     */
    public static boolean bindHelpAudio(ServerLevel level, BlockPos pos, String audioId, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        String id = normaliseHelpAudio(audioId);
        if (!EscalatorSpeedData.HELP_AUDIO_DEFAULT.equals(id)
                && !EscalatorSpeedData.HELP_AUDIO_OFF.equals(id)
                && !data.audioLibrary.containsKey(id)) {
            return false;
        }
        Map<BlockPos, String> overrides = data.helpAudioOverrides(in);
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            overrides.remove(p);
        }
        overrides.remove(pos);
        // 与默认层相同就不必记：省存档，界面上也会老老实实显示成「使用默认」。
        String defaultId = normaliseHelpAudio(in ? data.defaultHelpAudioIn : data.defaultHelpAudioOut);
        if (!id.equals(defaultId)) {
            data.bindHelpAudio(pos, id, in);
        }
        data.setDirty();
        return true;
    }

    /** 【1.41】清掉这条扶梯**这一头**的提示音音乐单独设置（回到维度默认）。@return 是否真的有记录被清掉 */
    public static boolean unbindHelpAudio(ServerLevel level, BlockPos pos, boolean in) {
        EscalatorSpeedData data = getServerData(level);
        Map<BlockPos, String> overrides = data.helpAudioOverrides(in);
        boolean removed = false;
        for (BlockPos p : EscalatorUtil.collectChain(level, pos)) {
            removed |= overrides.remove(p) != null;
        }
        removed |= overrides.remove(pos) != null;
        if (removed) {
            data.setDirty();
        }
        return removed;
    }

    /** 把一个维度的提示音速率表发给单个玩家。 */
    public static void sendHelpSpeedSyncTo(ServerPlayer player, ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new HelpSpeedSyncPacket(level.dimension().location().toString(),
                        data.defaultHelpSpeedIn, new HashMap<>(data.blockHelpSpeedIn),
                        data.defaultHelpSpeedOut, new HashMap<>(data.blockHelpSpeedOut)));
    }

    /** 把全部维度的提示音速率表同步给所有在线玩家。 */
    public static void syncHelpSpeedToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendHelpSpeedSyncTo(player, level);
            }
        }
    }

    /** 把一个维度的提示音音乐表发给单个玩家。 */
    public static void sendHelpAudioSyncTo(ServerPlayer player, ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new HelpAudioSyncPacket(level.dimension().location().toString(),
                        normaliseHelpAudio(data.defaultHelpAudioIn), new HashMap<>(data.blockHelpAudioIn),
                        normaliseHelpAudio(data.defaultHelpAudioOut), new HashMap<>(data.blockHelpAudioOut)));
    }

    /** 把全部维度的提示音音乐表同步给所有在线玩家。 */
    public static void syncHelpAudioToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendHelpAudioSyncTo(player, level);
            }
        }
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

    // ==================================================================
    // 【1.42】直梯（Lift）开关门提示音 liftmusic.ogg
    //
    //   数据只有「维度默认」一层（见 EscalatorSpeedData 里那一段的说明），所以这里的
    //   访问器比其它设置短得多：没有 blockXxx 表、没有「单独设置」概念。
    //
    //   ★ 这里说的「维度默认」是**按维度存的**（每个 ServerLevel 一份 SavedData），
    //     所以指令里的 `-f` 取「**对所有维度**强制」的含义 —— 那才是这套数据里
    //     唯一能被「强制」的东西。别照抄扶梯那边的「清掉单独设置」文案。
    // ==================================================================

    /** 【1.42】这个维度**生效**的直梯提示音开关（客户端读镜像，服务端读 SavedData）。 */
    public static boolean isLiftHelpEnabled(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null || data.liftHelp;
        }
        return getServerData((ServerLevel) level).defaultLiftHelp;
    }

    // ------------------------------------------------------------------
    // 【1.46】三提示音（up / down / chime）的**独立子开关**：总开关（/lifthelp）开着时，
    //   这三个还能各自再关一层。which 一律是 "up" / "down" / "chime"。
    //   客户端读镜像、服务端读 SavedData；指令与石斧 UI 都走这一组。
    // ------------------------------------------------------------------

    private static boolean serverLiftToneEnabled(EscalatorSpeedData data, String which) {
        return switch (which) {
            case "up" -> data.defaultLiftToneUpEnabled;
            case "down" -> data.defaultLiftToneDownEnabled;
            case "chime" -> data.defaultLiftToneChimeEnabled;
            default -> true;
        };
    }

    private static void setServerLiftToneEnabled(EscalatorSpeedData data, String which, boolean enabled) {
        switch (which) {
            case "up" -> data.defaultLiftToneUpEnabled = enabled;
            case "down" -> data.defaultLiftToneDownEnabled = enabled;
            case "chime" -> data.defaultLiftToneChimeEnabled = enabled;
            default -> {
            }
        }
    }

    /** 【1.46】这个维度**生效**的某项子开关（客户端读镜像，服务端读 SavedData）。 */
    public static boolean isLiftToneEnabled(Level level, String which) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return switch (which) {
                case "up" -> data == null || data.liftToneUpEnabled;
                case "down" -> data == null || data.liftToneDownEnabled;
                case "chime" -> data == null || data.liftToneChimeEnabled;
                default -> true;
            };
        }
        return serverLiftToneEnabled(getServerData((ServerLevel) level), which);
    }

    /** {@code /lifthelpup|down|chime <on|off>}：只改**本维度**的对应子开关。 */
    public static void setDefaultLiftToneEnabled(ServerLevel level, String which, boolean enabled) {
        EscalatorSpeedData data = getServerData(level);
        setServerLiftToneEnabled(data, which, enabled);
        data.setDirty();
    }

    /** {@code /lifthelpup|down|chime <X> to <Y>}：本维度对应子开关正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultLiftToneEnabled(ServerLevel level, String which, boolean from, boolean to) {
        EscalatorSpeedData data = getServerData(level);
        if (serverLiftToneEnabled(data, which) != from) {
            return false;
        }
        setServerLiftToneEnabled(data, which, to);
        data.setDirty();
        return true;
    }

    /** {@code /lifthelpup|down|chime -f <on|off>}：把**所有维度**的对应子开关都设成该值。 */
    public static int setDefaultLiftToneEnabledAll(MinecraftServer server, String which, boolean enabled) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (serverLiftToneEnabled(data, which) != enabled) {
                setServerLiftToneEnabled(data, which, enabled);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /lifthelpup|down|chime -f <X> to <Y>}：所有维度里对应子开关正好是 X 的改成 Y。 */
    public static int replaceDefaultLiftToneEnabledAll(MinecraftServer server, String which, boolean from, boolean to) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (serverLiftToneEnabled(data, which) == from) {
                setServerLiftToneEnabled(data, which, to);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** 【1.46】某项子开关在界面 / 指令反馈里的中文名。 */
    public static String liftToneEnabledLabel(String which) {
        return switch (which) {
            case "up" -> "上楼提示音";
            case "down" -> "下楼提示音";
            case "chime" -> "开关门提示音";
            default -> "提示音";
        };
    }

    /**
     * 【1.42】这个维度**生效**的直梯提示音倍速（客户端读镜像，服务端读 SavedData）。
     * 始终落在 [{@link EscalatorSpeedData#LIFT_HELP_SPEED_MIN}, {@link EscalatorSpeedData#LIFT_HELP_SPEED_MAX}]。
     */
    public static float getLiftHelpSpeed(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_LIFT_HELP_SPEED : data.liftHelpSpeed;
        }
        return getServerData((ServerLevel) level).defaultLiftHelpSpeed;
    }

    /**
     * 【1.43】这个维度**生效**的直梯提示音音量（客户端读镜像，服务端读 SavedData）。
     * 始终落在 [{@link EscalatorSpeedData#HELP_VOLUME_MIN}, {@link EscalatorSpeedData#HELP_VOLUME_MAX}]
     * （1~1000，100 = 原始音量、1000 = 10× 放大）。
     */
    public static int getLiftHelpVolume(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_LIFT_HELP_VOLUME : data.liftHelpVolume;
        }
        return getServerData((ServerLevel) level).defaultLiftHelpVolume;
    }

    // ------------------------------------------------------------------
    // 【1.48】三项提示音（up / down / chime）**各自的音量**：-1 = 该项没单独调过 → 跟随共用默认。
    //   which 一律是 "up" / "down" / "chime"（指令子命令用 door = chime 的别名，见 SmoothLift）。
    //   客户端读镜像、服务端读 SavedData；石斧 UI 每个列表里的音量输入框走这一组。
    // ------------------------------------------------------------------

    private static int serverLiftToneVolume(EscalatorSpeedData data, String which) {
        return switch (which) {
            case "up" -> data.defaultLiftToneVolumeUp;
            case "down" -> data.defaultLiftToneVolumeDown;
            case "chime" -> data.defaultLiftToneVolumeChime;
            default -> EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
        };
    }

    private static void setServerLiftToneVolume(EscalatorSpeedData data, String which, int volume) {
        switch (which) {
            case "up" -> data.defaultLiftToneVolumeUp = EscalatorSpeedData.clampLiftToneVolume(volume);
            case "down" -> data.defaultLiftToneVolumeDown = EscalatorSpeedData.clampLiftToneVolume(volume);
            case "chime" -> data.defaultLiftToneVolumeChime = EscalatorSpeedData.clampLiftToneVolume(volume);
            default -> {
            }
        }
    }

    /** 【1.48】这项提示音**生效**的音量（单项 -1 → 跟随共用默认）。客户端读镜像，服务端读 SavedData。 */
    public static int getLiftToneVolume(Level level, String which) {
        int own;
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            own = switch (which) {
                case "up" -> data == null ? EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET : data.liftToneVolumeUp;
                case "down" -> data == null ? EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET : data.liftToneVolumeDown;
                case "chime" -> data == null ? EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET : data.liftToneVolumeChime;
                default -> EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
            };
        } else {
            own = serverLiftToneVolume(getServerData((ServerLevel) level), which);
        }
        if (own == EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET) {
            return getLiftHelpVolume(level); // 跟随共用默认
        }
        return own;
    }

    /** 【1.48】这项提示音有没有**单独调过**音量（true = 有自己的值；false = 跟随共用默认）。 */
    public static boolean hasOwnLiftToneVolume(Level level, String which) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return switch (which) {
                case "up" -> data != null && data.liftToneVolumeUp != EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
                case "down" -> data != null && data.liftToneVolumeDown != EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
                case "chime" -> data != null && data.liftToneVolumeChime != EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
                default -> false;
            };
        }
        return serverLiftToneVolume(getServerData((ServerLevel) level), which)
                != EscalatorSpeedData.LIFT_TONE_VOLUME_UNSET;
    }

    /** {@code /lifthelploud up|down|chime <音量>}：只改**本维度**这项的音量。 */
    public static void setDefaultLiftToneVolume(ServerLevel level, String which, int volume) {
        EscalatorSpeedData data = getServerData(level);
        setServerLiftToneVolume(data, which, volume);
        data.setDirty();
    }

    /** {@code /lifthelploud up|down|chime <X> to <Y>}：本维度这项音量正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultLiftToneVolume(ServerLevel level, String which, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (serverLiftToneVolume(data, which) != from) {
            return false;
        }
        setServerLiftToneVolume(data, which, to);
        data.setDirty();
        return true;
    }

    /** {@code /lifthelploud -f up|down|chime <音量>}：把**所有维度**这项的音量都设成该值。 */
    public static int setDefaultLiftToneVolumeAll(MinecraftServer server, String which, int volume) {
        int changed = 0;
        int clamped = EscalatorSpeedData.clampLiftToneVolume(volume);
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (serverLiftToneVolume(data, which) != clamped) {
                setServerLiftToneVolume(data, which, clamped);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /lifthelploud -f up|down|chime <X> to <Y>}：所有维度里这项音量正好是 X 的改成 Y。 */
    public static int replaceDefaultLiftToneVolumeAll(MinecraftServer server, String which, int from, int to) {
        int changed = 0;
        int clamped = EscalatorSpeedData.clampLiftToneVolume(to);
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (serverLiftToneVolume(data, which) == from) {
                setServerLiftToneVolume(data, which, clamped);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * 【1.47】这个维度**生效**的直梯提示音淡入淡出范围（客户端读镜像，服务端读 SavedData）。
     * 三项提示音（上楼 / 下楼 / 开关门）共用这一份；始终落在
     * [{@link EscalatorSpeedData#LIFT_HELP_ROUND_MIN}, {@link EscalatorSpeedData#LIFT_HELP_ROUND_MAX}]。
     */
    public static int getLiftHelpRound(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            return data == null ? EscalatorSpeedData.DEFAULT_LIFT_HELP_ROUND : data.liftHelpRound;
        }
        return getServerData((ServerLevel) level).defaultLiftHelpRound;
    }

    /** {@code /lifthelpround <范围>}：只改**本维度**的默认范围。 */
    public static void setDefaultLiftHelpRound(ServerLevel level, int round) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultLiftHelpRound = EscalatorSpeedData.clampLiftHelpRound(round);
        data.setDirty();
    }

    /** {@code /lifthelpround <X> to <Y>}：本维度默认范围正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultLiftHelpRound(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultLiftHelpRound != from) {
            return false;
        }
        data.defaultLiftHelpRound = EscalatorSpeedData.clampLiftHelpRound(to);
        data.setDirty();
        return true;
    }

    /** {@code /lifthelpround -f <范围>}：把**所有维度**的默认范围都设成该值。 */
    public static int setDefaultLiftHelpRoundAll(MinecraftServer server, int round) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            int clamped = EscalatorSpeedData.clampLiftHelpRound(round);
            if (data.defaultLiftHelpRound != clamped) {
                data.defaultLiftHelpRound = clamped;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /lifthelpround -f <X> to <Y>}：所有维度里默认范围正好是 X 的改成 Y。 */
    public static int replaceDefaultLiftHelpRoundAll(MinecraftServer server, int from, int to) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelpRound == from) {
                data.defaultLiftHelpRound = EscalatorSpeedData.clampLiftHelpRound(to);
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /** {@code /lifthelp <on|off>}：只改**本维度**的默认开关。 */
    public static void setDefaultLiftHelp(ServerLevel level, boolean enabled) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultLiftHelp = enabled;
        data.setDirty();
    }

    /** {@code /lifthelp <X> to <Y>}：本维度默认开关正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultLiftHelp(ServerLevel level, boolean from, boolean to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultLiftHelp != from) {
            return false;
        }
        data.defaultLiftHelp = to;
        data.setDirty();
        return true;
    }

    /** {@code /lifthelpspeed <倍速>}：只改**本维度**的默认倍速。 */
    public static void setDefaultLiftHelpSpeed(ServerLevel level, float speed) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultLiftHelpSpeed = EscalatorSpeedData.clampLiftHelpSpeed(speed);
        data.setDirty();
    }

    /** {@code /lifthelpspeed <X> to <Y>}：本维度默认倍速正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultLiftHelpSpeed(ServerLevel level, float from, float to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultLiftHelpSpeed != from) {
            return false;
        }
        data.defaultLiftHelpSpeed = EscalatorSpeedData.clampLiftHelpSpeed(to);
        data.setDirty();
        return true;
    }

    /** {@code /lifthelploud <音量>}：只改**本维度**的默认音量。 */
    public static void setDefaultLiftHelpVolume(ServerLevel level, int volume) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultLiftHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(volume);
        data.setDirty();
    }

    /** {@code /lifthelploud <X> to <Y>}：本维度默认音量正好是 X 时才改成 Y。 */
    public static boolean replaceDefaultLiftHelpVolume(ServerLevel level, int from, int to) {
        EscalatorSpeedData data = getServerData(level);
        if (data.defaultLiftHelpVolume != from) {
            return false;
        }
        data.defaultLiftHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(to);
        data.setDirty();
        return true;
    }

    /**
     * {@code /lifthelp -f <on|off>}：把**所有维度**的默认开关都设成该值。
     *
     * @return 实际被改动的维度数
     */
    public static int setDefaultLiftHelpAll(MinecraftServer server, boolean enabled) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelp != enabled) {
                data.defaultLiftHelp = enabled;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * {@code /lifthelpspeed -f <倍速>}：把**所有维度**的默认倍速都设成该值。
     *
     * @return 实际被改动的维度数
     */
    public static int setDefaultLiftHelpSpeedAll(MinecraftServer server, float speed) {
        float target = EscalatorSpeedData.clampLiftHelpSpeed(speed);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelpSpeed != target) {
                data.defaultLiftHelpSpeed = target;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * {@code /lifthelp -f <X> to <Y>}：所有维度里，默认开关正好是 X 的那些改成 Y。
     *
     * @return 实际被改动的维度数
     */
    public static int replaceDefaultLiftHelpAll(MinecraftServer server, boolean from, boolean to) {
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelp == from) {
                data.defaultLiftHelp = to;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * {@code /lifthelpspeed -f <X> to <Y>}：所有维度里，默认倍速正好是 X 的那些改成 Y。
     *
     * @return 实际被改动的维度数
     */
    public static int replaceDefaultLiftHelpSpeedAll(MinecraftServer server, float from, float to) {
        float target = EscalatorSpeedData.clampLiftHelpSpeed(to);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelpSpeed == from) {
                data.defaultLiftHelpSpeed = target;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * {@code /lifthelploud -f <音量>}：把**所有维度**的默认音量都设成该值。
     *
     * @return 实际被改动的维度数
     */
    public static int setDefaultLiftHelpVolumeAll(MinecraftServer server, int volume) {
        int target = EscalatorSpeedData.clampLiftHelpVolume(volume);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelpVolume != target) {
                data.defaultLiftHelpVolume = target;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * {@code /lifthelploud -f <X> to <Y>}：所有维度里，默认音量正好是 X 的那些改成 Y。
     *
     * @return 实际被改动的维度数
     */
    public static int replaceDefaultLiftHelpVolumeAll(MinecraftServer server, int from, int to) {
        int target = EscalatorSpeedData.clampLiftHelpVolume(to);
        int changed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultLiftHelpVolume == from) {
                data.defaultLiftHelpVolume = target;
                data.setDirty();
                changed++;
            }
        }
        return changed;
    }

    /**
     * 【1.42】应用服务端同步过来的直梯提示音设置（覆盖式更新本维度的镜像）。
     *
     * <p>【1.43】多了**音量**一项 —— 和开关 / 倍速同属一套「按维度」的设置，所以共用同一只
     * 同步包、同一个代次，不做第三条频道。
     *
     * <p>代次 +1 是给客户端播放器做「缓存作废」用的（与 {@code clientHelpGeneration} 同一手法）：
     * 播放器每 tick 只在「代次变了」时才真的去查，不然每次读都要过一次 Map。
     */
    public static void applyClientLiftChime(ResourceKey<Level> dimension, boolean enabled, float speed,
                                            int volume, boolean upEnabled, boolean downEnabled,
                                            boolean chimeEnabled, int round,
                                            int toneVolumeUp, int toneVolumeDown, int toneVolumeChime) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.liftHelp = enabled;
        data.liftHelpSpeed = EscalatorSpeedData.clampLiftHelpSpeed(speed);
        data.liftHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(volume);
        data.liftHelpRound = EscalatorSpeedData.clampLiftHelpRound(round);
        data.liftToneUpEnabled = upEnabled;
        data.liftToneDownEnabled = downEnabled;
        data.liftToneChimeEnabled = chimeEnabled;
        data.liftToneVolumeUp = EscalatorSpeedData.clampLiftToneVolume(toneVolumeUp);
        data.liftToneVolumeDown = EscalatorSpeedData.clampLiftToneVolume(toneVolumeDown);
        data.liftToneVolumeChime = EscalatorSpeedData.clampLiftToneVolume(toneVolumeChime);
        clientLiftChimeGeneration++;
    }

    /** 见 {@link #applyClientLiftChime}。只在客户端线程读、在客户端线程写。 */
    public static long clientLiftChimeGeneration() {
        return clientLiftChimeGeneration;
    }

    /** 见 {@link #clientLiftChimeGeneration()}。 */
    private static long clientLiftChimeGeneration;

    /**
     * 【1.42】构建某个维度的「直梯提示音」同步包：维度默认开关 + 维度默认倍速
     * 【1.43】+ 维度默认音量。【1.46】+ 三提示音独立子开关。
     *
     * <p>字段顺序**就是** {@link #applyClientLiftChime} 的入参顺序，两处必须一起改
     * （客户端 {@code SmoothLiftClient} 那边是按同一顺序读的）：
     * {@code dimId → enabled → speed → volume → upEnabled → downEnabled → chimeEnabled}。
     */
    private static LiftChimeSyncPacket buildLiftChimePacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        return new LiftChimeSyncPacket(
                level.dimension().location().toString(),
                data.defaultLiftHelp,
                data.defaultLiftHelpSpeed,
                data.defaultLiftHelpVolume,
                data.defaultLiftToneUpEnabled,
                data.defaultLiftToneDownEnabled,
                data.defaultLiftToneChimeEnabled,
                // 【1.47】淡入淡出范围（三项共用）
                data.defaultLiftHelpRound,
                // 【1.48】三项各自音量（-1 = 跟随共用默认）
                data.defaultLiftToneVolumeUp,
                data.defaultLiftToneVolumeDown,
                data.defaultLiftToneVolumeChime);
    }

    /** 【1.42】把一个维度的直梯提示音设置发给单个玩家。 */
    public static void sendLiftChimeSyncTo(ServerPlayer player, ServerLevel level) {
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), buildLiftChimePacket(level));
    }

    /** 【1.42】把所有维度的直梯提示音设置同步给所有在线玩家。 */
    public static void syncLiftChimeToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendLiftChimeSyncTo(player, level);
            }
        }
    }

    // ------------------------------------------------------------------
    // 【1.45】直梯楼层轨道提示音（石斧右键楼层轨道设置：up / down / chime 三列表）
    // ------------------------------------------------------------------

    /** 竖井列打包坐标：同一条直梯的所有楼层轨道共享 (X, Z)，只有 Y 不同 ⇒ key = asLong(x, 0, z)。 */
    public static long liftToneKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    /** 从任意一个楼层/轿厢坐标（double）算竖井列 key；越界/异常一律退化成「无设置」。 */
    public static long liftToneKeyNear(double x, double z) {
        return liftToneKey((int) Math.floor(x), (int) Math.floor(z));
    }

    /**
     * 服务端：读某条直梯（竖井列）的三项提示音设置；没设置过 → {@link EscalatorSpeedData.LiftToneAudio#NONE}。
     * 值域校验不做在这里（写入时已经夹过）。
     */
    public static EscalatorSpeedData.LiftToneAudio getServerLiftTone(ServerLevel level, long key) {
        EscalatorSpeedData.LiftToneAudio tone = getServerData(level).liftToneAudio.get(key);
        return tone != null ? tone : EscalatorSpeedData.LiftToneAudio.NONE;
    }

    /**
     * 客户端：读某条直梯（竖井列）的三项提示音设置（镜像）；没同步过 → {@code NONE}。
     */
    public static EscalatorSpeedData.LiftToneAudio getClientLiftTone(Level level, long key) {
        ClientDimensionData data = CLIENT_DATA.get(level.dimension());
        if (data == null) {
            return EscalatorSpeedData.LiftToneAudio.NONE;
        }
        EscalatorSpeedData.LiftToneAudio tone = data.liftToneAudio.get(key);
        return tone != null ? tone : EscalatorSpeedData.LiftToneAudio.NONE;
    }

    /**
     * 【1.45】按「哪一项（up/down/chime）」定位值；{@code which} 不属于这三者 → null。
     */
    public static String toneField(EscalatorSpeedData.LiftToneAudio tone, String which) {
        return switch (which) {
            case "up" -> tone.up();
            case "down" -> tone.down();
            case "chime" -> tone.chime();
            default -> null;
        };
    }

    /**
     * 校验并写入一个直梯提示音设置。
     *
     * @return 成功写入了才 true；{@code audioId} 不是 default / off / 音频库里存在的 id → false。
     */
    public static boolean setServerLiftTone(ServerLevel level, long key, String which, String audioId) {
        if (!"up".equals(which) && !"down".equals(which) && !"chime".equals(which)) {
            return false;
        }
        if (!EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(audioId)
                && !EscalatorSpeedData.LIFT_TONE_OFF.equals(audioId)
                && !getServerData(level).audioLibrary.containsKey(audioId)) {
            return false;
        }
        EscalatorSpeedData data = getServerData(level);
        EscalatorSpeedData.LiftToneAudio old = data.liftToneAudio.get(key);
        if (old == null) {
            old = EscalatorSpeedData.LiftToneAudio.NONE;
        }
        String up = "up".equals(which) ? audioId : old.up();
        String down = "down".equals(which) ? audioId : old.down();
        String chime = "chime".equals(which) ? audioId : old.chime();
        if (EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(up)
                && EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(down)
                && EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(chime)) {
            // 三项全默认 = 等于没设置，直接删掉这条记录（表越干净越好查）。
            data.liftToneAudio.remove(key);
        } else {
            data.liftToneAudio.put(key, new EscalatorSpeedData.LiftToneAudio(up, down, chime));
        }
        data.setDirty();
        return true;
    }

    /**
     * 【1.45】构建某个维度的「直梯楼层轨道提示音」同步包：
     * {@code dimId → 条数 → (key, up, down, chime) × N}。
     */
    private static LiftToneSyncPacket buildLiftTonePacket(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        return new LiftToneSyncPacket(level.dimension().location().toString(),
                new HashMap<>(data.liftToneAudio));
    }

    /** 【1.45】把一个维度的直梯楼层轨道提示音设置发给单个玩家。 */
    public static void sendLiftToneSyncTo(ServerPlayer player, ServerLevel level) {
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), buildLiftTonePacket(level));
    }

    /** 【1.45】把所有维度的直梯楼层轨道提示音设置同步给所有在线玩家。 */
    public static void syncLiftToneToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendLiftToneSyncTo(player, level);
            }
        }
    }

    /** 【1.45】客户端接收器：把同步包的直梯楼层轨道提示音写进镜像（包已在接收器里解析好）。 */
    public static void applyClientLiftTone(ResourceKey<Level> dimension,
                                           Map<Long, EscalatorSpeedData.LiftToneAudio> tones) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.liftToneAudio.clear();
        data.liftToneAudio.putAll(tones);
        clientLiftToneGeneration++;
    }

    /**
     * 【1.45】客户端：点完石斧界面某一行后**本地立即**改镜像一个竖井列的设置
     * （服务端的权威值随后会通过 {@link #applyClientLiftTone} 整表覆盖回来，所以只是临时加速回显）。
     */
    public static void applyClientLiftToneLocal(ResourceKey<Level> dimension, long key,
                                                EscalatorSpeedData.LiftToneAudio tone) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        if (EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(tone.up())
                && EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(tone.down())
                && EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(tone.chime())) {
            data.liftToneAudio.remove(key);
        } else {
            data.liftToneAudio.put(key, tone);
        }
        clientLiftToneGeneration++;
    }

    /** 【1.46】客户端：石斧 UI 点完开关后**本地立即**翻镜像（服务端权威值随后整表覆盖回来）。 */
    public static void applyClientLiftToneSwitchLocal(ResourceKey<Level> dimension, String which, boolean enabled) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        switch (which) {
            case "up" -> data.liftToneUpEnabled = enabled;
            case "down" -> data.liftToneDownEnabled = enabled;
            case "chime" -> data.liftToneChimeEnabled = enabled;
            default -> {
            }
        }
        clientLiftChimeGeneration++;
    }

    /** 【1.48】客户端：石斧 UI 主界面「设置默认音量」后**本地立即**改镜像（服务端随后权威同步覆盖）。 */
    public static void applyClientLiftVolumeLocal(ResourceKey<Level> dimension, int volume) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        data.liftHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(volume);
        clientLiftChimeGeneration++;
    }

    /** 【1.48】客户端：石斧 UI 单项列表「音量」后**本地立即**改镜像（服务端随后权威同步覆盖）。 */
    public static void applyClientLiftToneVolumeLocal(ResourceKey<Level> dimension, String which, int volume) {
        ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
        int v = EscalatorSpeedData.clampLiftToneVolume(volume);
        switch (which) {
            case "up" -> data.liftToneVolumeUp = v;
            case "down" -> data.liftToneVolumeDown = v;
            case "chime" -> data.liftToneVolumeChime = v;
            default -> {
            }
        }
        clientLiftChimeGeneration++;
    }

    /** 直梯楼层轨道提示音镜像的代数（客户端播放端用来刷新缓存）。 */
    public static long clientLiftToneGeneration() {
        return clientLiftToneGeneration;
    }

    private static long clientLiftToneGeneration;

    private static long clientPsdChimeGeneration;
    private static long clientPsdToneGeneration;


    public static void applyClientLiftChime(ResourceKey<Level> dimension, boolean enabled, float speed,
                                            int volume, boolean upEnabled, boolean downEnabled,
                                            boolean chimeEnabled, int round,
                                            int toneVolumeUp, int toneVolumeDown, int toneVolumeChime,
                                            String toneAudioUp, String toneAudioDown, String toneAudioChime) {
            ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
            data.liftHelp = enabled;
            data.liftHelpSpeed = EscalatorSpeedData.clampLiftHelpSpeed(speed);
            data.liftHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(volume);
            data.liftHelpRound = EscalatorSpeedData.clampLiftHelpRound(round);
            data.liftToneUpEnabled = upEnabled;
            data.liftToneDownEnabled = downEnabled;
            data.liftToneChimeEnabled = chimeEnabled;
            data.liftToneVolumeUp = EscalatorSpeedData.clampLiftToneVolume(toneVolumeUp);
            data.liftToneVolumeDown = EscalatorSpeedData.clampLiftToneVolume(toneVolumeDown);
            data.liftToneVolumeChime = EscalatorSpeedData.clampLiftToneVolume(toneVolumeChime);
            // 【1.15】三项的维度默认素材
            data.liftToneAudioUp = EscalatorSpeedData.normalizeLiftToneAudio(toneAudioUp);
            data.liftToneAudioDown = EscalatorSpeedData.normalizeLiftToneAudio(toneAudioDown);
            data.liftToneAudioChime = EscalatorSpeedData.normalizeLiftToneAudio(toneAudioChime);
            clientLiftChimeGeneration++;
        
    }

    public static void applyClientPsdChime(ResourceKey<Level> dimension, boolean enabled, int volume,
                                           boolean openEnabled, boolean closeEnabled, int round,
                                           int toneVolumeOpen, int toneVolumeClose,
                                           String toneAudioOpen, String toneAudioClose,
                                           int closeWaitSeconds,
                                           String midiumAudio, int midiumWaitSeconds,
                                           String arriveAudio, int arriveSeconds,
                                           int midiumVolume, int arriveVolume,
                                           int midiumRound, int arriveRound) {
            ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
            data.psdHelp = enabled;
            data.psdHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(volume);
            data.psdToneOpenEnabled = openEnabled;
            data.psdToneCloseEnabled = closeEnabled;
            data.psdHelpRound = EscalatorSpeedData.clampPsdHelpRound(round);
            data.psdToneVolumeOpen = EscalatorSpeedData.clampPsdToneVolume(toneVolumeOpen);
            data.psdToneVolumeClose = EscalatorSpeedData.clampPsdToneVolume(toneVolumeClose);
            data.psdToneAudioOpen = EscalatorSpeedData.normalizePsdToneAudio(toneAudioOpen);
            data.psdToneAudioClose = EscalatorSpeedData.normalizePsdToneAudio(toneAudioClose);
            data.psdCloseWaitSeconds = EscalatorSpeedData.clampPsdCloseWaitSeconds(closeWaitSeconds);
            data.psdMidiumAudio = EscalatorSpeedData.normalizePsdMidiumAudio(midiumAudio);
            data.psdMidiumWaitSeconds = EscalatorSpeedData.clampPsdMidiumWaitSeconds(midiumWaitSeconds);
            data.psdArriveAudio = EscalatorSpeedData.normalizePsdArriveAudio(arriveAudio);
            data.psdArriveSeconds = EscalatorSpeedData.clampPsdArriveSeconds(arriveSeconds);
            data.psdMidiumVolume = EscalatorSpeedData.clampPsdToneVolume(midiumVolume);
            data.psdArriveVolume = EscalatorSpeedData.clampPsdToneVolume(arriveVolume);
            data.psdMidiumRound = EscalatorSpeedData.clampPsdMidiumRound(midiumRound);
            data.psdArriveRound = EscalatorSpeedData.clampPsdArriveRound(arriveRound);
            clientPsdChimeGeneration++;
        
    }

    public static void applyClientPsdDoorLocal(ResourceKey<Level> dimension, long key,
                                               java.util.function.UnaryOperator<EscalatorSpeedData.PsdToneAudio> fn) {
            ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
            EscalatorSpeedData.PsdToneAudio old = data.psdToneAudio.get(key);
            if (old == null) {
                old = EscalatorSpeedData.PsdToneAudio.NONE;
            }
            applyClientPsdToneLocal(dimension, key, fn.apply(old));
        
    }

    public static void applyClientPsdTone(ResourceKey<Level> dimension,
                                          Map<Long, EscalatorSpeedData.PsdToneAudio> tones) {
            ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
            data.psdToneAudio.clear();
            data.psdToneAudio.putAll(tones);
            clientPsdToneGeneration++;
        
    }

    public static void applyClientPsdToneLocal(ResourceKey<Level> dimension, long key,
                                               EscalatorSpeedData.PsdToneAudio tone) {
            ClientDimensionData data = CLIENT_DATA.computeIfAbsent(dimension, k -> new ClientDimensionData());
            if (tone.isEmpty()) {
                data.psdToneAudio.remove(key);
            } else {
                data.psdToneAudio.put(key, tone);
            }
            clientPsdToneGeneration++;
        
    }

    private static boolean clearLiftToneOverrides(EscalatorSpeedData data, String which) {
            if (data.liftToneAudio.isEmpty()) {
                return false;
            }
            Map<Long, EscalatorSpeedData.LiftToneAudio> next = new HashMap<>();
            boolean touched = false;
            for (Map.Entry<Long, EscalatorSpeedData.LiftToneAudio> e : data.liftToneAudio.entrySet()) {
                EscalatorSpeedData.LiftToneAudio t = e.getValue();
                String up = "up".equals(which) ? EscalatorSpeedData.LIFT_TONE_DEFAULT : t.up();
                String down = "down".equals(which) ? EscalatorSpeedData.LIFT_TONE_DEFAULT : t.down();
                String chime = "chime".equals(which) ? EscalatorSpeedData.LIFT_TONE_DEFAULT : t.chime();
                if (!up.equals(t.up()) || !down.equals(t.down()) || !chime.equals(t.chime())) {
                    touched = true;
                }
                if (!EscalatorSpeedData.isLiftToneAllDefault(up, down, chime)) {
                    next.put(e.getKey(), new EscalatorSpeedData.LiftToneAudio(up, down, chime));
                }
            }
            if (!touched) {
                return false;
            }
            data.liftToneAudio.clear();
            data.liftToneAudio.putAll(next);
            return true;
        
    }

    public static int clearPsdArriveIfRemoved(MinecraftServer server, String audioId) {
            if (audioId == null || audioId.isEmpty()) {
                return 0;
            }
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                if (audioId.equals(data.defaultPsdArriveAudio)) {
                    data.defaultPsdArriveAudio = EscalatorSpeedData.PSD_ARRIVE_OFF;
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static int clearPsdMidiumIfRemoved(MinecraftServer server, String audioId) {
            if (audioId == null || audioId.isEmpty()) {
                return 0;
            }
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                if (audioId.equals(data.defaultPsdMidiumAudio)) {
                    data.defaultPsdMidiumAudio = EscalatorSpeedData.PSD_MIDIUM_OFF;
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    private static boolean clearPsdToneOverrides(EscalatorSpeedData data, String which) {
            if (data.psdToneAudio.isEmpty()) {
                return false;
            }
            Map<Long, EscalatorSpeedData.PsdToneAudio> next = new HashMap<>();
            boolean touched = false;
            for (Map.Entry<Long, EscalatorSpeedData.PsdToneAudio> e : data.psdToneAudio.entrySet()) {
                EscalatorSpeedData.PsdToneAudio t = e.getValue();
                // ★【1.20】只把**这一项的素材**清回「跟维度默认」，其余覆盖项原样保留
                //   （老写法用 new PsdToneAudio(open, close) 重建 ⇒ 顺手抹掉这扇门的音量等设置）。
                if (EscalatorSpeedData.PSD_TONE_DEFAULT.equals("open".equals(which) ? t.open() : t.close())) {
                    next.put(e.getKey(), t);
                    continue;
                }
                t = t.withTone(which, EscalatorSpeedData.PSD_TONE_DEFAULT);
                touched = true;
                if (!t.isEmpty()) {
                    next.put(e.getKey(), t);
                }
            }
            if (!touched) {
                return false;
            }
            data.psdToneAudio.clear();
            data.psdToneAudio.putAll(next);
            return true;
        
    }

    public static long clientPsdChimeGeneration() {
            return clientPsdChimeGeneration;
        
    }

    public static long clientPsdToneGeneration() {
            return clientPsdToneGeneration;
        
    }

    public static EscalatorSpeedData.PsdToneAudio getClientPsdTone(Level level, long key) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.PsdToneAudio.NONE;
            }
            EscalatorSpeedData.PsdToneAudio tone = data.psdToneAudio.get(key);
            return tone != null ? tone : EscalatorSpeedData.PsdToneAudio.NONE;
        
    }

    public static String getDoorPsdArriveAudio(Level level, long key) {
            String own = psdDoorRecord(level, key).arrive();
            return own != null ? own : getPsdArriveAudio(level);
        
    }

    public static int getDoorPsdArriveSeconds(Level level, long key) {
            Integer own = psdDoorRecord(level, key).arriveSeconds();
            return own != null ? own : getPsdArriveSeconds(level);
        
    }

    public static int getDoorPsdArriveVolume(Level level, long key) {
            Integer own = psdDoorRecord(level, key).arriveVolume();
            return own != null ? own : getPsdArriveVolume(level);
        
    }

    public static int getDoorPsdCloseWaitSeconds(Level level, long key) {
            Integer own = psdDoorRecord(level, key).closeWaitSeconds();
            return own != null ? own : getPsdCloseWaitSeconds(level);
        
    }

    public static int getDoorPsdHelpVolume(Level level, long key) {
            Integer own = psdDoorRecord(level, key).volume();
            return own != null ? own : getPsdHelpVolume(level);
        
    }

    public static String getDoorPsdMidiumAudio(Level level, long key) {
            String own = psdDoorRecord(level, key).midium();
            return own != null ? own : getPsdMidiumAudio(level);
        
    }

    public static int getDoorPsdMidiumVolume(Level level, long key) {
            Integer own = psdDoorRecord(level, key).midiumVolume();
            return own != null ? own : getPsdMidiumVolume(level);
        
    }

    public static int getDoorPsdMidiumWaitSeconds(Level level, long key) {
            Integer own = psdDoorRecord(level, key).midiumWaitSeconds();
            return own != null ? own : getPsdMidiumWaitSeconds(level);
        
    }

    public static int getDoorPsdOpenWaitSeconds(Level level, long key) {
            Integer own = psdDoorRecord(level, key).openWaitSeconds();
            return own != null ? own : EscalatorSpeedData.DEFAULT_PSD_OPEN_WAIT_SECONDS;
        
    }

    public static int getDoorPsdToneVolume(Level level, long key, String which) {
            Integer own = "open".equals(which)
                    ? psdDoorRecord(level, key).openVolume()
                    : psdDoorRecord(level, key).closeVolume();
            return own != null ? own : getDoorPsdHelpVolume(level, key);
        
    }

    public static String getLiftToneAudio(Level level, String which) {
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                return EscalatorSpeedData.normalizeLiftToneAudio(switch (which) {
                    case "up" -> data == null ? null : data.liftToneAudioUp;
                    case "down" -> data == null ? null : data.liftToneAudioDown;
                    case "chime" -> data == null ? null : data.liftToneAudioChime;
                    default -> null;
                });
            }
            return serverLiftToneAudio(getServerData((ServerLevel) level), which);
        
    }

    public static String getPsdArriveAudio(Level level) {
            if (level == null) {
                return EscalatorSpeedData.PSD_ARRIVE_OFF;
            }
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                return data == null ? EscalatorSpeedData.PSD_ARRIVE_OFF : data.psdArriveAudio;
            }
            return getServerData((ServerLevel) level).defaultPsdArriveAudio;
        
    }

    public static int getPsdArriveRound(Level level) {
            if (level == null) {
                return EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND;
            }
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                return data == null ? EscalatorSpeedData.DEFAULT_PSD_ARRIVE_ROUND : data.psdArriveRound;
            }
            return getServerData((ServerLevel) level).defaultPsdArriveRound;
        
    }

    public static int getPsdArriveSeconds(Level level) {
            if (level == null) {
                return EscalatorSpeedData.DEFAULT_PSD_ARRIVE_SECONDS;
            }
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                return data == null
                        ? EscalatorSpeedData.DEFAULT_PSD_ARRIVE_SECONDS
                        : data.psdArriveSeconds;
            }
            return getServerData((ServerLevel) level).defaultPsdArriveSeconds;
        
    }

    public static int getPsdArriveVolume(Level level) {
            return getPsdItemVolume(level, "arrive");
        
    }

    public static int getPsdCloseWaitSeconds(Level level) {
            if (level == null) {
                return EscalatorSpeedData.DEFAULT_PSD_CLOSE_WAIT_SECONDS;
            }
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                return data == null
                        ? EscalatorSpeedData.DEFAULT_PSD_CLOSE_WAIT_SECONDS
                        : data.psdCloseWaitSeconds;
            }
            return getServerData((ServerLevel) level).defaultPsdCloseWaitSeconds;
        
    }

    public static int getPsdHelpRound(Level level) {
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                return data == null ? EscalatorSpeedData.DEFAULT_PSD_HELP_ROUND : data.psdHelpRound;
            }
            return getServerData((ServerLevel) level).defaultPsdHelpRound;
        
    }

    public static int getPsdHelpVolume(Level level) {
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                return data == null ? EscalatorSpeedData.DEFAULT_PSD_HELP_VOLUME : data.psdHelpVolume;
            }
            return getServerData((ServerLevel) level).defaultPsdHelpVolume;
        
    }

    public static int getPsdItemVolume(Level level, String which) {
            int own;
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                if (data == null) {
                    own = EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
                } else {
                    own = "midium".equals(which) ? data.psdMidiumVolume : data.psdArriveVolume;
                }
            } else {
                own = serverPsdItemVolume(getServerData((ServerLevel) level), which);
            }
            return own == EscalatorSpeedData.PSD_TONE_VOLUME_UNSET ? getPsdHelpVolume(level) : own;
        
    }

    public static String getPsdMidiumAudio(Level level) {
            if (level == null) {
                return EscalatorSpeedData.PSD_MIDIUM_OFF;
            }
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                return data == null ? EscalatorSpeedData.PSD_MIDIUM_OFF : data.psdMidiumAudio;
            }
            return getServerData((ServerLevel) level).defaultPsdMidiumAudio;
        
    }

    public static int getPsdMidiumRound(Level level) {
            if (level == null) {
                return EscalatorSpeedData.DEFAULT_PSD_MIDIUM_ROUND;
            }
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                return data == null ? EscalatorSpeedData.DEFAULT_PSD_MIDIUM_ROUND : data.psdMidiumRound;
            }
            return getServerData((ServerLevel) level).defaultPsdMidiumRound;
        
    }

    public static int getPsdMidiumVolume(Level level) {
            return getPsdItemVolume(level, "midium");
        
    }

    public static int getPsdMidiumWaitSeconds(Level level) {
            if (level == null) {
                return EscalatorSpeedData.DEFAULT_PSD_MIDIUM_WAIT_SECONDS;
            }
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                return data == null
                        ? EscalatorSpeedData.DEFAULT_PSD_MIDIUM_WAIT_SECONDS
                        : data.psdMidiumWaitSeconds;
            }
            return getServerData((ServerLevel) level).defaultPsdMidiumWaitSeconds;
        
    }

    public static String getPsdToneAudio(Level level, String which) {
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                return EscalatorSpeedData.normalizePsdToneAudio(switch (which) {
                    case "open" -> data == null ? null : data.psdToneAudioOpen;
                    case "close" -> data == null ? null : data.psdToneAudioClose;
                    default -> null;
                });
            }
            return serverPsdToneAudio(getServerData((ServerLevel) level), which);
        
    }

    public static int getPsdToneVolume(Level level, String which) {
            int own;
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                if (data == null) {
                    own = EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
                } else {
                    own = "open".equals(which) ? data.psdToneVolumeOpen : data.psdToneVolumeClose;
                }
            } else {
                own = serverPsdToneVolume(getServerData((ServerLevel) level), which);
            }
            if (own == EscalatorSpeedData.PSD_TONE_VOLUME_UNSET) {
                return getPsdHelpVolume(level); // 跟随共用默认
            }
            return own;
        
    }

    public static Set<String> getServerAudioLibraryKeys(ServerLevel level) {
            return java.util.Collections.unmodifiableSet(getServerData(level).audioLibrary.keySet());
        
    }

    public static EscalatorSpeedData.PsdToneAudio getServerPsdTone(ServerLevel level, long key) {
            EscalatorSpeedData.PsdToneAudio tone = getServerData(level).psdToneAudio.get(key);
            return tone != null ? tone : EscalatorSpeedData.PsdToneAudio.NONE;
        
    }

    public static boolean hasAnyDoorPsdHelpOn(Level level) {
            if (level == null) {
                return false;
            }
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                if (data == null) {
                    return false;
                }
                for (EscalatorSpeedData.PsdToneAudio t : data.psdToneAudio.values()) {
                    if (Boolean.TRUE.equals(t.help())) {
                        return true;
                    }
                }
                return false;
            }
            for (EscalatorSpeedData.PsdToneAudio t : getServerData((ServerLevel) level).psdToneAudio.values()) {
                if (Boolean.TRUE.equals(t.help())) {
                    return true;
                }
            }
            return false;
        
    }

    public static boolean hasOwnDoorPsdToneVolume(Level level, long key, String which) {
            EscalatorSpeedData.PsdToneAudio t = psdDoorRecord(level, key);
            Integer own = "open".equals(which) ? t.openVolume() : t.closeVolume();
            return own != null || hasOwnPsdToneVolume(level, which);
        
    }

    public static boolean hasOwnPsdItemVolume(Level level, String which) {
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                if (data == null) {
                    return false;
                }
                return ("midium".equals(which) ? data.psdMidiumVolume : data.psdArriveVolume)
                        != EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
            }
            return serverPsdItemVolume(getServerData((ServerLevel) level), which)
                    != EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
        
    }

    public static boolean hasOwnPsdToneVolume(Level level, String which) {
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                if (data == null) {
                    return false;
                }
                return "open".equals(which)
                        ? data.psdToneVolumeOpen != EscalatorSpeedData.PSD_TONE_VOLUME_UNSET
                        : data.psdToneVolumeClose != EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
            }
            return serverPsdToneVolume(getServerData((ServerLevel) level), which)
                    != EscalatorSpeedData.PSD_TONE_VOLUME_UNSET;
        
    }

    public static boolean isDoorPsdHelpEnabled(Level level, long key) {
            Boolean own = psdDoorRecord(level, key).help();
            return own != null ? own : isPsdHelpEnabled(level);
        
    }

    public static boolean isDoorPsdToneEnabled(Level level, long key, String which) {
            EscalatorSpeedData.PsdToneAudio t = psdDoorRecord(level, key);
            Boolean own = "open".equals(which) ? t.openEnabled() : t.closeEnabled();
            return own != null ? own : isPsdToneEnabled(level, which);
        
    }

    public static boolean isPsdHelpEnabled(Level level) {
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                return data == null || data.psdHelp;
            }
            return getServerData((ServerLevel) level).defaultPsdHelp;
        
    }

    public static boolean isPsdToneEnabled(Level level, String which) {
            if (!"open".equals(which) && !"close".equals(which)) {
                return false;
            }
            if (level.isClientSide()) {
                ClientDimensionData data = CLIENT_DATA.get(level.dimension());
                return switch (which) {
                    case "open" -> data == null || data.psdToneOpenEnabled;
                    default -> data == null || data.psdToneCloseEnabled;
                };
            }
            return serverPsdToneEnabled(getServerData((ServerLevel) level), which);
        
    }

    public static List<String> liftToneNameCandidates(ServerLevel level) {
            List<String> names = new ArrayList<>(getServerAudioLibraryKeys(level));
            names.sort(String::compareTo);
            List<String> out = new ArrayList<>(names.size() + 2);
            out.add(EscalatorSpeedData.LIFT_TONE_DEFAULT);
            out.add("none");
            out.addAll(names);
            return out;
        
    }

    public static List<String> psdArriveSuggestions(ServerLevel level) {
            List<String> out = new ArrayList<>();
            out.add(EscalatorSpeedData.PSD_ARRIVE_OFF);
            if (level != null) {
                List<String> names = new ArrayList<>(getServerData(level).audioLibrary.keySet());
                names.sort(String::compareTo);
                out.addAll(names);
            }
            return out;
        
    }

    public static EscalatorSpeedData.PsdToneAudio psdDoorRecord(Level level, long key) {
            if (level == null) {
                return EscalatorSpeedData.PsdToneAudio.NONE;
            }
            return level.isClientSide()
                    ? getClientPsdTone(level, key)
                    : getServerPsdTone((ServerLevel) level, key);
        
    }

    public static List<String> psdMidiumSuggestions(ServerLevel level) {
            List<String> out = new ArrayList<>();
            out.add(EscalatorSpeedData.PSD_MIDIUM_OFF);
            if (level != null) {
                List<String> names = new ArrayList<>(getServerData(level).audioLibrary.keySet());
                names.sort(String::compareTo);
                out.addAll(names);
            }
            return out;
        
    }

    public static List<String> psdNameCandidates(ServerLevel level) {
            List<String> names = new ArrayList<>(getServerAudioLibraryKeys(level));
            names.sort(String::compareTo);
            List<String> out = new ArrayList<>(names.size() + 5);
            out.add(EscalatorSpeedData.PSD_TONE_BUILTIN_OPEN);
            out.add(EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE);
            out.add(EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M);
            out.add(EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S);
            out.add("none");
            out.addAll(names);
            return out;
        
    }

    public static String psdToneField(EscalatorSpeedData.PsdToneAudio tone, String which) {
            if (tone == null) {
                return EscalatorSpeedData.PSD_TONE_DEFAULT;
            }
            return "open".equals(which) ? tone.open() : tone.close();
        
    }

    public static long psdToneKey(int x, int y, int z) {
            return BlockPos.asLong(x, y, z);
        
    }

    private static boolean remapPsdDoorOverrides(EscalatorSpeedData data,
                                                 java.util.function.Function<EscalatorSpeedData.PsdToneAudio,
                                                         EscalatorSpeedData.PsdToneAudio> fn) {
            if (data.psdToneAudio.isEmpty()) {
                return false;
            }
            Map<Long, EscalatorSpeedData.PsdToneAudio> next = new HashMap<>();
            boolean touched = false;
            for (Map.Entry<Long, EscalatorSpeedData.PsdToneAudio> e : data.psdToneAudio.entrySet()) {
                EscalatorSpeedData.PsdToneAudio changed = fn.apply(e.getValue());
                if (changed == null) {
                    next.put(e.getKey(), e.getValue());
                    continue;
                }
                touched = true;
                if (!changed.isEmpty()) {
                    next.put(e.getKey(), changed);
                }
            }
            if (!touched) {
                return false;
            }
            data.psdToneAudio.clear();
            data.psdToneAudio.putAll(next);
            return true;
        
    }

    public static boolean replaceDefaultLiftToneAudio(ServerLevel level, String which, String from, String to) {
            EscalatorSpeedData data = getServerData(level);
            if (!java.util.Objects.equals(serverLiftToneAudio(data, which), from)) {
                return false;
            }
            setServerLiftToneAudio(data, which, to);
            data.setDirty();
            return true;
        
    }

    public static int replaceDefaultLiftToneAudioAll(MinecraftServer server, String which, String from, String to) {
            String id = EscalatorSpeedData.normalizeLiftToneAudio(to);
            String src = EscalatorSpeedData.normalizeLiftToneAudio(from);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (src.equals(serverLiftToneAudio(data, which))) {
                    setServerLiftToneAudio(data, which, id);
                    touched = true;
                }
                // 单独设置里那一项正好是 X 的也一起换掉（-f = 「含单独设置的」）
                if (!data.liftToneAudio.isEmpty()) {
                    Map<Long, EscalatorSpeedData.LiftToneAudio> next = new HashMap<>();
                    for (Map.Entry<Long, EscalatorSpeedData.LiftToneAudio> e : data.liftToneAudio.entrySet()) {
                        EscalatorSpeedData.LiftToneAudio t = e.getValue();
                        String up = "up".equals(which) && src.equals(t.up()) ? id : t.up();
                        String down = "down".equals(which) && src.equals(t.down()) ? id : t.down();
                        String chime = "chime".equals(which) && src.equals(t.chime()) ? id : t.chime();
                        if (!up.equals(t.up()) || !down.equals(t.down()) || !chime.equals(t.chime())) {
                            touched = true;
                        }
                        if (!EscalatorSpeedData.isLiftToneAllDefault(up, down, chime)) {
                            next.put(e.getKey(), new EscalatorSpeedData.LiftToneAudio(up, down, chime));
                        }
                    }
                    if (touched) {
                        data.liftToneAudio.clear();
                        data.liftToneAudio.putAll(next);
                    }
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static boolean replaceDefaultPsdArriveRound(ServerLevel level, int from, int to) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultPsdArriveRound != from) {
                return false;
            }
            data.defaultPsdArriveRound = EscalatorSpeedData.clampPsdArriveRound(to);
            data.setDirty();
            return true;
        
    }

    public static int replaceDefaultPsdArriveRoundAll(MinecraftServer server, int from, int to) {
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                if (data.defaultPsdArriveRound == from) {
                    data.defaultPsdArriveRound = EscalatorSpeedData.clampPsdArriveRound(to);
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static boolean replaceDefaultPsdArriveVolume(ServerLevel level, int from, int to) {
            return replaceDefaultPsdItemVolume(level, "arrive", from, to);
        
    }

    public static int replaceDefaultPsdArriveVolumeAll(MinecraftServer server, int from, int to) {
            int target = EscalatorSpeedData.clampPsdToneVolume(to);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (data.defaultPsdArriveVolume == from) {
                    data.defaultPsdArriveVolume = target;
                    touched = true;
                }
                if (remapPsdDoorOverrides(data, t -> t.arriveVolume() == null || t.arriveVolume() != from
                        ? null : t.withArriveVolume(target))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static boolean replaceDefaultPsdCloseWaitSeconds(ServerLevel level, int from, int to) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultPsdCloseWaitSeconds != from) {
                return false;
            }
            data.defaultPsdCloseWaitSeconds = EscalatorSpeedData.clampPsdCloseWaitSeconds(to);
            data.setDirty();
            return true;
        
    }

    public static int replaceDefaultPsdCloseWaitSecondsAll(MinecraftServer server, int from, int to) {
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (data.defaultPsdCloseWaitSeconds == from) {
                    data.defaultPsdCloseWaitSeconds = EscalatorSpeedData.clampPsdCloseWaitSeconds(to);
                    touched = true;
                }
                // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
                //   条件：强制等待秒数正好是 X 的改成 Y（clamp）（见 remapPsdDoorOverrides）。
                if (remapPsdDoorOverrides(data, t -> t.closeWaitSeconds() == null || t.closeWaitSeconds() != from
                              ? null
                              : t.withCloseWaitSeconds(EscalatorSpeedData.clampPsdCloseWaitSeconds(to)))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static boolean replaceDefaultPsdHelp(ServerLevel level, boolean from, boolean to) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultPsdHelp != from) {
                return false;
            }
            data.defaultPsdHelp = to;
            data.setDirty();
            return true;
        
    }

    public static int replaceDefaultPsdHelpAll(MinecraftServer server, boolean from, boolean to) {
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (data.defaultPsdHelp == from) {
                    data.defaultPsdHelp = to;
                    touched = true;
                }
                // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
                //   条件：正好是 X 的改成 Y（见 remapPsdDoorOverrides）。
                if (remapPsdDoorOverrides(data, t -> t.help() == null || t.help() != from ? null : t.withHelp(to))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static boolean replaceDefaultPsdHelpRound(ServerLevel level, int from, int to) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultPsdHelpRound != from) {
                return false;
            }
            data.defaultPsdHelpRound = EscalatorSpeedData.clampPsdHelpRound(to);
            data.setDirty();
            return true;
        
    }

    public static int replaceDefaultPsdHelpRoundAll(MinecraftServer server, int from, int to) {
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                if (data.defaultPsdHelpRound == from) {
                    data.defaultPsdHelpRound = EscalatorSpeedData.clampPsdHelpRound(to);
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static boolean replaceDefaultPsdHelpVolume(ServerLevel level, int from, int to) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultPsdHelpVolume != from) {
                return false;
            }
            data.defaultPsdHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(to);
            data.setDirty();
            return true;
        
    }

    public static int replaceDefaultPsdHelpVolumeAll(MinecraftServer server, int from, int to) {
            int target = EscalatorSpeedData.clampLiftHelpVolume(to);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (data.defaultPsdHelpVolume == from) {
                    data.defaultPsdHelpVolume = target;
                    touched = true;
                }
                // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
                //   条件：音量正好是 X 的改成 Y（已 clamp 的 target）（见 remapPsdDoorOverrides）。
                if (remapPsdDoorOverrides(data, t -> t.volume() == null || t.volume() != from ? null : t.withVolume(target))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    private static boolean replaceDefaultPsdItemVolume(ServerLevel level, String which, int from, int to) {
            EscalatorSpeedData data = getServerData(level);
            if (serverPsdItemVolume(data, which) != from) {
                return false;
            }
            setServerPsdItemVolume(data, which, to);
            data.setDirty();
            return true;
        
    }

    public static boolean replaceDefaultPsdMidiumRound(ServerLevel level, int from, int to) {
            EscalatorSpeedData data = getServerData(level);
            if (data.defaultPsdMidiumRound != from) {
                return false;
            }
            data.defaultPsdMidiumRound = EscalatorSpeedData.clampPsdMidiumRound(to);
            data.setDirty();
            return true;
        
    }

    public static int replaceDefaultPsdMidiumRoundAll(MinecraftServer server, int from, int to) {
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                if (data.defaultPsdMidiumRound == from) {
                    data.defaultPsdMidiumRound = EscalatorSpeedData.clampPsdMidiumRound(to);
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static boolean replaceDefaultPsdMidiumVolume(ServerLevel level, int from, int to) {
            return replaceDefaultPsdItemVolume(level, "midium", from, to);
        
    }

    public static int replaceDefaultPsdMidiumVolumeAll(MinecraftServer server, int from, int to) {
            int target = EscalatorSpeedData.clampPsdToneVolume(to);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (data.defaultPsdMidiumVolume == from) {
                    data.defaultPsdMidiumVolume = target;
                    touched = true;
                }
                if (remapPsdDoorOverrides(data, t -> t.midiumVolume() == null || t.midiumVolume() != from
                        ? null : t.withMidiumVolume(target))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static boolean replaceDefaultPsdToneAudio(ServerLevel level, String which, String from, String to) {
            EscalatorSpeedData data = getServerData(level);
            if (!java.util.Objects.equals(serverPsdToneAudio(data, which), from)) {
                return false;
            }
            setServerPsdToneAudio(data, which, to);
            data.setDirty();
            return true;
        
    }

    public static int replaceDefaultPsdToneAudioAll(MinecraftServer server, String which, String from, String to) {
            String id = EscalatorSpeedData.normalizePsdToneAudio(to);
            String src = EscalatorSpeedData.normalizePsdToneAudio(from);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (src.equals(serverPsdToneAudio(data, which))) {
                    setServerPsdToneAudio(data, which, id);
                    touched = true;
                }
                // 单独设置里那一项正好是 X 的也一起换掉（-f = 「含单独设置的」）
                if (!data.psdToneAudio.isEmpty()) {
                    Map<Long, EscalatorSpeedData.PsdToneAudio> next = new HashMap<>();
                    for (Map.Entry<Long, EscalatorSpeedData.PsdToneAudio> e : data.psdToneAudio.entrySet()) {
                        EscalatorSpeedData.PsdToneAudio t = e.getValue();
                        // ★【1.20】只换**素材那一项**，其余覆盖项（开关 / 音量 / 强制等待 / 到站播报）
                        //   原样保留 —— 这里原来用 new PsdToneAudio(open, close) 重建，会顺手把
                        //   这扇门的音量设置抹掉（「改个素材，音量回默认」）。
                        if (src.equals("open".equals(which) ? t.open() : t.close())) {
                            t = t.withTone(which, id);
                            touched = true;
                        }
                        if (!t.isEmpty()) {
                            next.put(e.getKey(), t);
                        }
                    }
                    if (touched) {
                        data.psdToneAudio.clear();
                        data.psdToneAudio.putAll(next);
                    }
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static boolean replaceDefaultPsdToneEnabled(ServerLevel level, String which, boolean from, boolean to) {
            if (!"open".equals(which) && !"close".equals(which)) {
                return false;
            }
            EscalatorSpeedData data = getServerData(level);
            if (serverPsdToneEnabled(data, which) != from) {
                return false;
            }
            setServerPsdToneEnabled(data, which, to);
            data.setDirty();
            return true;
        
    }

    public static int replaceDefaultPsdToneEnabledAll(MinecraftServer server, String which,
                                                      boolean from, boolean to) {
            if (!"open".equals(which) && !"close".equals(which)) {
                return 0;
            }
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (serverPsdToneEnabled(data, which) == from) {
                    setServerPsdToneEnabled(data, which, to);
                    touched = true;
                }
                // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
                //   条件：这一项子开关正好是 X 的改成 Y（见 remapPsdDoorOverrides）。
                if (remapPsdDoorOverrides(data, t -> ("open".equals(which) ? t.openEnabled() : t.closeEnabled()) == null
                              || ("open".equals(which) ? t.openEnabled() : t.closeEnabled()) != from
                              ? null
                              : t.withToneEnabled(which, to))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static boolean replaceDefaultPsdToneVolume(ServerLevel level, String which, int from, int to) {
            if (!"open".equals(which) && !"close".equals(which)) {
                return false;
            }
            EscalatorSpeedData data = getServerData(level);
            if (serverPsdToneVolume(data, which) != from) {
                return false;
            }
            setServerPsdToneVolume(data, which, to);
            data.setDirty();
            return true;
        
    }

    public static int replaceDefaultPsdToneVolumeAll(MinecraftServer server, String which, int from, int to) {
            if (!"open".equals(which) && !"close".equals(which)) {
                return 0;
            }
            int clamped = EscalatorSpeedData.clampPsdToneVolume(to);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (serverPsdToneVolume(data, which) == from) {
                    setServerPsdToneVolume(data, which, clamped);
                    touched = true;
                }
                // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
                //   条件：这一项音量正好是 X 的改成 Y（已 clamp 的 clamped）（见 remapPsdDoorOverrides）。
                if (remapPsdDoorOverrides(data, t -> ("open".equals(which) ? t.openVolume() : t.closeVolume()) == null
                              || ("open".equals(which) ? t.openVolume() : t.closeVolume()) != from
                              ? null
                              : t.withToneVolume(which, clamped))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static AudioArg resolveLiftToneName(ServerLevel level, String name) {
            if (name == null || name.isEmpty()) {
                return new AudioArg(null, false, "音频名字不能为空");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(lower)) {
                return new AudioArg(EscalatorSpeedData.LIFT_TONE_DEFAULT, false, null);
            }
            if (EscalatorSpeedData.LIFT_TONE_OFF.equals(lower) || "none".equals(lower) || "mute".equals(lower)) {
                return new AudioArg(EscalatorSpeedData.LIFT_TONE_OFF, true, null);
            }
            EscalatorSpeedData data = getServerData(level);
            if (data.audioLibrary.containsKey(name)) {
                return new AudioArg(name, false, null);
            }
            // 玩家少打后缀名时兜底（与 /futimusic 同一手法）
            if (!lower.endsWith(".ogg") && data.audioLibrary.containsKey(name + ".ogg")) {
                return new AudioArg(name + ".ogg", false, null);
            }
            return new AudioArg(null, false, "存档里没有叫「" + name + "」的音频。直梯提示音可以用 "
                    + EscalatorSpeedData.LIFT_TONE_DEFAULT + " 内置素材、none 不播，或用导入过的 .ogg；"
                    + (data.audioLibrary.isEmpty()
                            ? "现在还没有导入过任何音频，玩家需要在石斧界面里上传或导入 .ogg"
                            : "已有的：" + previewNames(data)));
        
    }

    public static String resolvePsdArriveName(ServerLevel level, String name) {
            if (EscalatorSpeedData.isPsdArriveOff(name)) {
                return EscalatorSpeedData.PSD_ARRIVE_OFF;
            }
            EscalatorSpeedData data = getServerData(level);
            if (data.audioLibrary.containsKey(name)) {
                return name;
            }
            if (!name.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
                String withExt = name + ".ogg";
                if (data.audioLibrary.containsKey(withExt)) {
                    return withExt;
                }
                if (importAudioToStore(level, withExt) == null && data.audioLibrary.containsKey(withExt)) {
                    return withExt;
                }
            }
            if (importAudioToStore(level, name) == null && data.audioLibrary.containsKey(name)) {
                return name;
            }
            return null;
        
    }

    public static String resolvePsdMidiumName(ServerLevel level, String name) {
            if (EscalatorSpeedData.isPsdMidiumOff(name)) {
                return EscalatorSpeedData.PSD_MIDIUM_OFF;
            }
            EscalatorSpeedData data = getServerData(level);
            if (data.audioLibrary.containsKey(name)) {
                return name;
            }
            if (!name.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
                String withExt = name + ".ogg";
                if (data.audioLibrary.containsKey(withExt)) {
                    return withExt;
                }
                if (importAudioToStore(level, withExt) == null && data.audioLibrary.containsKey(withExt)) {
                    return withExt;
                }
            }
            if (importAudioToStore(level, name) == null && data.audioLibrary.containsKey(name)) {
                return name;
            }
            return null;
        
    }

    public static AudioArg resolvePsdToneName(ServerLevel level, String name) {
            if (name == null || name.isEmpty()) {
                return new AudioArg(null, false, "音频名字不能为空");
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (EscalatorSpeedData.PSD_TONE_BUILTIN_OPEN.equals(lower)) {
                return new AudioArg(EscalatorSpeedData.PSD_TONE_BUILTIN_OPEN, false, null);
            }
            if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE.equals(lower)) {
                return new AudioArg(EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE, false, null);
            }
            if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M.equals(lower)) {
                return new AudioArg(EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M, false, null);
            }
            if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S.equals(lower)) {
                return new AudioArg(EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S, false, null);
            }
            if (EscalatorSpeedData.PSD_TONE_OFF.equals(lower) || "none".equals(lower) || "mute".equals(lower)) {
                return new AudioArg(EscalatorSpeedData.PSD_TONE_OFF, true, null);
            }
            EscalatorSpeedData data = getServerData(level);
            if (data.audioLibrary.containsKey(name)) {
                return new AudioArg(name, false, null);
            }
            if (!lower.endsWith(".ogg") && data.audioLibrary.containsKey(name + ".ogg")) {
                return new AudioArg(name + ".ogg", false, null);
            }
            return new AudioArg(null, false, "存档里没有叫「" + name + "」的音频。屏蔽门提示音可以用 "
                    + EscalatorSpeedData.PSD_TONE_BUILTIN_OPEN + " / "
                    + EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE + " / "
                    + EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M + " / "
                    + EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S + " 四段内置素材、none 不播，"
                    + "或用导入过的 .ogg；"
                    + (data.audioLibrary.isEmpty()
                            ? "现在还没有导入过任何音频，玩家需要在石斧界面里上传或导入 .ogg"
                            : "已有的：" + previewNames(data)));
        
    }

    private static String serverLiftToneAudio(EscalatorSpeedData data, String which) {
            return switch (which) {
                case "up" -> data.defaultLiftToneAudioUp;
                case "down" -> data.defaultLiftToneAudioDown;
                case "chime" -> data.defaultLiftToneAudioChime;
                default -> EscalatorSpeedData.LIFT_TONE_DEFAULT;
            };
        
    }

    private static int serverPsdItemVolume(EscalatorSpeedData data, String which) {
            return "midium".equals(which) ? data.defaultPsdMidiumVolume : data.defaultPsdArriveVolume;
        
    }

    private static String serverPsdToneAudio(EscalatorSpeedData data, String which) {
            return switch (which) {
                case "open" -> EscalatorSpeedData.normalizePsdToneAudio(data.defaultPsdToneAudioOpen);
                case "close" -> EscalatorSpeedData.normalizePsdToneAudio(data.defaultPsdToneAudioClose);
                default -> EscalatorSpeedData.PSD_TONE_DEFAULT;
            };
        
    }

    private static boolean serverPsdToneEnabled(EscalatorSpeedData data, String which) {
            return "open".equals(which) ? data.defaultPsdToneOpenEnabled : data.defaultPsdToneCloseEnabled;
        
    }

    private static int serverPsdToneVolume(EscalatorSpeedData data, String which) {
            return "open".equals(which) ? data.defaultPsdToneVolumeOpen : data.defaultPsdToneVolumeClose;
        
    }

    public static void setDefaultLiftToneAudio(ServerLevel level, String which, String audioId) {
            EscalatorSpeedData data = getServerData(level);
            setServerLiftToneAudio(data, which, audioId);
            data.setDirty();
        
    }

    public static int setDefaultLiftToneAudioAll(MinecraftServer server, String which, String audioId) {
            String id = EscalatorSpeedData.normalizeLiftToneAudio(audioId);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (!id.equals(serverLiftToneAudio(data, which))) {
                    setServerLiftToneAudio(data, which, id);
                    touched = true;
                }
                if (clearLiftToneOverrides(data, which)) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDefaultPsdArrive(ServerLevel level, String audioId, int seconds) {
            EscalatorSpeedData data = getServerData(level);
            data.defaultPsdArriveAudio = EscalatorSpeedData.normalizePsdArriveAudio(audioId);
            data.defaultPsdArriveSeconds = EscalatorSpeedData.clampPsdArriveSeconds(seconds);
            data.setDirty();
        
    }

    public static int setDefaultPsdArriveAll(MinecraftServer server, String audioId, int seconds) {
            String id = EscalatorSpeedData.normalizePsdArriveAudio(audioId);
            int sec = EscalatorSpeedData.clampPsdArriveSeconds(seconds);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (!id.equals(data.defaultPsdArriveAudio) || data.defaultPsdArriveSeconds != sec) {
                    data.defaultPsdArriveAudio = id;
                    data.defaultPsdArriveSeconds = sec;
                    touched = true;
                }
                // 【1.21】-f = 「修改全部」：这一项的「按串单独设置」也一起抹回跟维度默认。
                if (remapPsdDoorOverrides(data, t -> (t.arrive() == null && t.arriveSeconds() == null)
                              ? null
                              : t.withArrive(null).withArriveSeconds(null))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDefaultPsdArriveRound(ServerLevel level, int round) {
            EscalatorSpeedData data = getServerData(level);
            data.defaultPsdArriveRound = EscalatorSpeedData.clampPsdArriveRound(round);
            data.setDirty();
        
    }

    public static int setDefaultPsdArriveRoundAll(MinecraftServer server, int round) {
            int clamped = EscalatorSpeedData.clampPsdArriveRound(round);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                if (data.defaultPsdArriveRound != clamped) {
                    data.defaultPsdArriveRound = clamped;
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDefaultPsdArriveVolume(ServerLevel level, int volume) {
            EscalatorSpeedData data = getServerData(level);
            setServerPsdItemVolume(data, "arrive", volume);
            data.setDirty();
        
    }

    public static int setDefaultPsdArriveVolumeAll(MinecraftServer server, int volume) {
            int target = EscalatorSpeedData.clampPsdToneVolume(volume);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (data.defaultPsdArriveVolume != target) {
                    data.defaultPsdArriveVolume = target;
                    touched = true;
                }
                if (remapPsdDoorOverrides(data, t -> t.arriveVolume() == null ? null : t.withArriveVolume(null))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDefaultPsdCloseWaitSeconds(ServerLevel level, int seconds) {
            EscalatorSpeedData data = getServerData(level);
            data.defaultPsdCloseWaitSeconds = EscalatorSpeedData.clampPsdCloseWaitSeconds(seconds);
            data.setDirty();
        
    }

    public static int setDefaultPsdCloseWaitSecondsAll(MinecraftServer server, int seconds) {
            int clamped = EscalatorSpeedData.clampPsdCloseWaitSeconds(seconds);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (data.defaultPsdCloseWaitSeconds != clamped) {
                    data.defaultPsdCloseWaitSeconds = clamped;
                    touched = true;
                }
                // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
                //   设值：强制等待秒数抹回跟维度默认（见 remapPsdDoorOverrides）。
                if (remapPsdDoorOverrides(data, t -> t.closeWaitSeconds() == null ? null : t.withCloseWaitSeconds(null))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDefaultPsdHelp(ServerLevel level, boolean enabled) {
            EscalatorSpeedData data = getServerData(level);
            data.defaultPsdHelp = enabled;
            data.setDirty();
        
    }

    public static int setDefaultPsdHelpAll(MinecraftServer server, boolean enabled) {
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (data.defaultPsdHelp != enabled) {
                    data.defaultPsdHelp = enabled;
                    touched = true;
                }
                // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
                //   设值：抹回跟维度默认（见 remapPsdDoorOverrides）。
                if (remapPsdDoorOverrides(data, t -> t.help() == null ? null : t.withHelp(null))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDefaultPsdHelpRound(ServerLevel level, int round) {
            EscalatorSpeedData data = getServerData(level);
            data.defaultPsdHelpRound = EscalatorSpeedData.clampPsdHelpRound(round);
            data.setDirty();
        
    }

    public static int setDefaultPsdHelpRoundAll(MinecraftServer server, int round) {
            int clamped = EscalatorSpeedData.clampPsdHelpRound(round);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                if (data.defaultPsdHelpRound != clamped) {
                    data.defaultPsdHelpRound = clamped;
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDefaultPsdHelpVolume(ServerLevel level, int volume) {
            EscalatorSpeedData data = getServerData(level);
            data.defaultPsdHelpVolume = EscalatorSpeedData.clampLiftHelpVolume(volume);
            data.setDirty();
        
    }

    public static int setDefaultPsdHelpVolumeAll(MinecraftServer server, int volume) {
            int target = EscalatorSpeedData.clampLiftHelpVolume(volume);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (data.defaultPsdHelpVolume != target) {
                    data.defaultPsdHelpVolume = target;
                    touched = true;
                }
                // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
                //   设值：音量抹回跟维度默认（见 remapPsdDoorOverrides）。
                if (remapPsdDoorOverrides(data, t -> t.volume() == null ? null : t.withVolume(null))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDefaultPsdMidium(ServerLevel level, String audioId, int seconds) {
            EscalatorSpeedData data = getServerData(level);
            data.defaultPsdMidiumAudio = EscalatorSpeedData.normalizePsdMidiumAudio(audioId);
            data.defaultPsdMidiumWaitSeconds = EscalatorSpeedData.clampPsdMidiumWaitSeconds(seconds);
            data.setDirty();
        
    }

    public static int setDefaultPsdMidiumAll(MinecraftServer server, String audioId, int seconds) {
            String id = EscalatorSpeedData.normalizePsdMidiumAudio(audioId);
            int sec = EscalatorSpeedData.clampPsdMidiumWaitSeconds(seconds);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (!id.equals(data.defaultPsdMidiumAudio) || data.defaultPsdMidiumWaitSeconds != sec) {
                    data.defaultPsdMidiumAudio = id;
                    data.defaultPsdMidiumWaitSeconds = sec;
                    touched = true;
                }
                // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
                //   设值：到站播报素材 + 等待秒数一起抹回跟维度默认（见 remapPsdDoorOverrides）。
                if (remapPsdDoorOverrides(data, t -> (t.midium() == null && t.midiumWaitSeconds() == null)
                              ? null
                              : t.withMidium(null).withMidiumWaitSeconds(null))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDefaultPsdMidiumRound(ServerLevel level, int round) {
            EscalatorSpeedData data = getServerData(level);
            data.defaultPsdMidiumRound = EscalatorSpeedData.clampPsdMidiumRound(round);
            data.setDirty();
        
    }

    public static int setDefaultPsdMidiumRoundAll(MinecraftServer server, int round) {
            int clamped = EscalatorSpeedData.clampPsdMidiumRound(round);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                if (data.defaultPsdMidiumRound != clamped) {
                    data.defaultPsdMidiumRound = clamped;
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDefaultPsdMidiumVolume(ServerLevel level, int volume) {
            EscalatorSpeedData data = getServerData(level);
            setServerPsdItemVolume(data, "midium", volume);
            data.setDirty();
        
    }

    public static int setDefaultPsdMidiumVolumeAll(MinecraftServer server, int volume) {
            int target = EscalatorSpeedData.clampPsdToneVolume(volume);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (data.defaultPsdMidiumVolume != target) {
                    data.defaultPsdMidiumVolume = target;
                    touched = true;
                }
                // -f = 「修改全部」：这一项的「按扇门单独设置」也一起抹掉（见 remapPsdDoorOverrides）。
                if (remapPsdDoorOverrides(data, t -> t.midiumVolume() == null ? null : t.withMidiumVolume(null))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDefaultPsdToneAudio(ServerLevel level, String which, String audioId) {
            EscalatorSpeedData data = getServerData(level);
            setServerPsdToneAudio(data, which, audioId);
            data.setDirty();
        
    }

    public static int setDefaultPsdToneAudioAll(MinecraftServer server, String which, String audioId) {
            String id = EscalatorSpeedData.normalizePsdToneAudio(audioId);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (!id.equals(serverPsdToneAudio(data, which))) {
                    setServerPsdToneAudio(data, which, id);
                    touched = true;
                }
                if (clearPsdToneOverrides(data, which)) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDefaultPsdToneEnabled(ServerLevel level, String which, boolean enabled) {
            if (!"open".equals(which) && !"close".equals(which)) {
                return;
            }
            EscalatorSpeedData data = getServerData(level);
            setServerPsdToneEnabled(data, which, enabled);
            data.setDirty();
        
    }

    public static int setDefaultPsdToneEnabledAll(MinecraftServer server, String which, boolean enabled) {
            if (!"open".equals(which) && !"close".equals(which)) {
                return 0;
            }
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (serverPsdToneEnabled(data, which) != enabled) {
                    setServerPsdToneEnabled(data, which, enabled);
                    touched = true;
                }
                // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
                //   设值：这一项子开关抹回跟维度默认（见 remapPsdDoorOverrides）。
                if (remapPsdDoorOverrides(data, t -> ("open".equals(which) ? t.openEnabled() : t.closeEnabled()) == null
                              ? null
                              : t.withToneEnabled(which, null))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDefaultPsdToneVolume(ServerLevel level, String which, int volume) {
            if (!"open".equals(which) && !"close".equals(which)) {
                return;
            }
            EscalatorSpeedData data = getServerData(level);
            setServerPsdToneVolume(data, which, volume);
            data.setDirty();
        
    }

    public static int setDefaultPsdToneVolumeAll(MinecraftServer server, String which, int volume) {
            if (!"open".equals(which) && !"close".equals(which)) {
                return 0;
            }
            int clamped = EscalatorSpeedData.clampPsdToneVolume(volume);
            int changed = 0;
            for (ServerLevel level : server.getAllLevels()) {
                EscalatorSpeedData data = getServerData(level);
                boolean touched = false;
                if (serverPsdToneVolume(data, which) != clamped) {
                    setServerPsdToneVolume(data, which, clamped);
                    touched = true;
                }
                // 【1.20】-f = 「修改全部」：这一项的「按扇门单独设置」也一起处理掉——
                //   设值：这一项音量抹回跟维度默认（见 remapPsdDoorOverrides）。
                if (remapPsdDoorOverrides(data, t -> ("open".equals(which) ? t.openVolume() : t.closeVolume()) == null
                              ? null
                              : t.withToneVolume(which, null))) {
                    touched = true;
                }
                if (touched) {
                    data.setDirty();
                    changed++;
                }
            }
            return changed;
        
    }

    public static void setDoorPsdArrive(ServerLevel level, long key, String audioId, int seconds) {
            updateDoor(level, key, t -> t.withArrive(EscalatorSpeedData.normalizePsdArriveAudio(audioId))
                    .withArriveSeconds(EscalatorSpeedData.clampPsdArriveSeconds(seconds)));
        
    }

    public static void setDoorPsdArriveVolume(ServerLevel level, long key, int volume) {
            updateDoor(level, key, t -> t.withArriveVolume(EscalatorSpeedData.clampPsdToneVolume(volume)));
        
    }

    public static void setDoorPsdCloseWaitSeconds(ServerLevel level, long key, int seconds) {
            updateDoor(level, key, t -> t.withCloseWaitSeconds(EscalatorSpeedData.clampPsdCloseWaitSeconds(seconds)));
        
    }

    public static void setDoorPsdHelp(ServerLevel level, long key, boolean enabled) {
            updateDoor(level, key, t -> t.withHelp(enabled));
        
    }

    public static void setDoorPsdHelpVolume(ServerLevel level, long key, int volume) {
            updateDoor(level, key, t -> t.withVolume(EscalatorSpeedData.clampLiftHelpVolume(volume)));
        
    }

    public static void setDoorPsdMidium(ServerLevel level, long key, String audioId, int seconds) {
            updateDoor(level, key, t -> t.withMidium(EscalatorSpeedData.normalizePsdMidiumAudio(audioId))
                    .withMidiumWaitSeconds(EscalatorSpeedData.clampPsdMidiumWaitSeconds(seconds)));
        
    }

    public static void setDoorPsdMidiumVolume(ServerLevel level, long key, int volume) {
            updateDoor(level, key, t -> t.withMidiumVolume(EscalatorSpeedData.clampPsdToneVolume(volume)));
        
    }

    public static void setDoorPsdOpenWaitSeconds(ServerLevel level, long key, int seconds) {
            updateDoor(level, key, t -> t.withOpenWaitSeconds(EscalatorSpeedData.clampPsdOpenWaitSeconds(seconds)));
        
    }

    public static void setDoorPsdToneEnabled(ServerLevel level, long key, String which, boolean enabled) {
            updateDoor(level, key, t -> t.withToneEnabled(which, enabled));
        
    }

    public static void setDoorPsdToneVolume(ServerLevel level, long key, String which, int volume) {
            updateDoor(level, key, t -> t.withToneVolume(which, EscalatorSpeedData.clampPsdToneVolume(volume)));
        
    }

    private static void setServerLiftToneAudio(EscalatorSpeedData data, String which, String audioId) {
            String id = EscalatorSpeedData.normalizeLiftToneAudio(audioId);
            switch (which) {
                case "up" -> data.defaultLiftToneAudioUp = id;
                case "down" -> data.defaultLiftToneAudioDown = id;
                case "chime" -> data.defaultLiftToneAudioChime = id;
                default -> {
                }
            }
        
    }

    private static void setServerPsdItemVolume(EscalatorSpeedData data, String which, int volume) {
            if ("midium".equals(which)) {
                data.defaultPsdMidiumVolume = EscalatorSpeedData.clampPsdToneVolume(volume);
            } else {
                data.defaultPsdArriveVolume = EscalatorSpeedData.clampPsdToneVolume(volume);
            }
        
    }

    public static boolean setServerPsdTone(ServerLevel level, long key, String which, String audioId) {
            if (!"open".equals(which) && !"close".equals(which)) {
                return false;
            }
            EscalatorSpeedData data = getServerData(level);
            String id = EscalatorSpeedData.normalizePsdToneAudio(audioId);
            if (!EscalatorSpeedData.isPsdBuiltinName(id)
                    && !EscalatorSpeedData.PSD_TONE_OFF.equals(id)
                    && !data.audioLibrary.containsKey(id)) {
                return false;
            }
            EscalatorSpeedData.PsdToneAudio old = data.psdToneAudio.get(key);
            if (old == null) {
                old = EscalatorSpeedData.PsdToneAudio.NONE;
            }
            // ★【1.20】用 withTone 只换这一端的素材：这条记录现在还带着这扇门的开关 / 音量 /
            //   强制等待 / 到站播报（石斧 UI 改的就是它们），重建整条会把那些一起抹掉。
            EscalatorSpeedData.PsdToneAudio now = old.withTone(which, id);
            if (now.isEmpty()) {
                // 全部覆盖项都回到默认 = 等于没设置，删掉这条记录（表越干净越好查）。
                data.psdToneAudio.remove(key);
            } else {
                data.psdToneAudio.put(key, now);
            }
            data.setDirty();
            return true;
        
    }

    private static void setServerPsdToneAudio(EscalatorSpeedData data, String which, String audioId) {
            String id = EscalatorSpeedData.normalizePsdToneAudio(audioId);
            switch (which) {
                case "open" -> data.defaultPsdToneAudioOpen = id;
                case "close" -> data.defaultPsdToneAudioClose = id;
                default -> {
                }
            }
        
    }

    private static void setServerPsdToneEnabled(EscalatorSpeedData data, String which, boolean enabled) {
            if ("open".equals(which)) {
                data.defaultPsdToneOpenEnabled = enabled;
            } else {
                data.defaultPsdToneCloseEnabled = enabled;
            }
        
    }

    private static void setServerPsdToneVolume(EscalatorSpeedData data, String which, int volume) {
            if ("open".equals(which)) {
                data.defaultPsdToneVolumeOpen = EscalatorSpeedData.clampPsdToneVolume(volume);
            } else {
                data.defaultPsdToneVolumeClose = EscalatorSpeedData.clampPsdToneVolume(volume);
            }
        
    }

    public static void syncPsdChimeToAll(MinecraftServer server) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                for (ServerLevel level : server.getAllLevels()) {
                    sendPsdChimeSyncTo(player, level);
                }
            }
        
    }

    public static void syncPsdToneToAll(MinecraftServer server) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                for (ServerLevel level : server.getAllLevels()) {
                    sendPsdToneSyncTo(player, level);
                }
            }
        
    }

    private static void updateDoor(ServerLevel level, long key,
                                   java.util.function.UnaryOperator<EscalatorSpeedData.PsdToneAudio> fn) {
            EscalatorSpeedData data = getServerData(level);
            EscalatorSpeedData.PsdToneAudio old = data.psdToneAudio.get(key);
            if (old == null) {
                old = EscalatorSpeedData.PsdToneAudio.NONE;
            }
            EscalatorSpeedData.PsdToneAudio now = fn.apply(old);
            if (now.isEmpty()) {
                data.psdToneAudio.remove(key);
            } else {
                data.psdToneAudio.put(key, now);
            }
            data.setDirty();
        
    }


    /** 【1.50】把一个维度的屏蔽门提示音设置发给单个玩家（Forge SimpleChannel 版）。 */
    public static void sendPsdChimeSyncTo(ServerPlayer player, ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PsdChimeSyncPacket(
                        level.dimension().location().toString(),
                        data.defaultPsdHelp,
                        data.defaultPsdHelpVolume,
                        data.defaultPsdToneOpenEnabled,
                        data.defaultPsdToneCloseEnabled,
                        data.defaultPsdHelpRound,
                        data.defaultPsdToneVolumeOpen,
                        data.defaultPsdToneVolumeClose,
                        EscalatorSpeedData.normalizePsdToneAudio(data.defaultPsdToneAudioOpen),
                        EscalatorSpeedData.normalizePsdToneAudio(data.defaultPsdToneAudioClose),
                        data.defaultPsdCloseWaitSeconds,
                        EscalatorSpeedData.normalizePsdMidiumAudio(data.defaultPsdMidiumAudio),
                        data.defaultPsdMidiumWaitSeconds,
                        EscalatorSpeedData.normalizePsdArriveAudio(data.defaultPsdArriveAudio),
                        data.defaultPsdArriveSeconds,
                        data.defaultPsdMidiumVolume,
                        data.defaultPsdArriveVolume,
                        data.defaultPsdMidiumRound,
                        data.defaultPsdArriveRound));
    }

    /** 【1.50】把所有维度的屏蔽门提示音设置同步给所有在线玩家。 */



    // ---- 【1.20】「可选值」的包读写：与 PsdToneSyncPacket 的 encode/decode 成对使用 ----

    public static void writeDoorOptBool(FriendlyByteBuf buf, Boolean v) {
        buf.writeBoolean(v != null);
        if (v != null) {
            buf.writeBoolean(v);
        }
    }

    public static void writeDoorOptInt(FriendlyByteBuf buf, Integer v) {
        buf.writeBoolean(v != null);
        if (v != null) {
            buf.writeVarInt(v);
        }
    }

    public static void writeDoorOptString(FriendlyByteBuf buf, String v) {
        buf.writeBoolean(v != null);
        if (v != null) {
            buf.writeUtf(v, 128);
        }
    }

    /** 客户端读一个「可选 boolean」；与 {@link #writeDoorOptBool} 成对。 */
    public static Boolean readDoorOptBool(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readBoolean() : null;
    }

    /** 客户端读一个「可选 int」；与 {@link #writeDoorOptInt} 成对。 */
    public static Integer readDoorOptInt(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readVarInt() : null;
    }

    /** 客户端读一个「可选字符串」；与 {@link #writeDoorOptString} 成对。 */
    public static String readDoorOptString(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUtf(128) : null;
    }

    /** 【1.50】把一个维度的每扇门单独素材发给单个玩家（Forge SimpleChannel 版）。 */
    public static void sendPsdToneSyncTo(ServerPlayer player, ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PsdToneSyncPacket(level.dimension().location().toString(),
                        new java.util.HashMap<>(data.psdToneAudio)));
    }

}

