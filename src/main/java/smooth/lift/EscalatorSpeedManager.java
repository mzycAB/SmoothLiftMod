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
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.mixin.ChunkMapAccessor;
import smooth.lift.network.AudioSyncPayload;
import smooth.lift.network.SyncPayload;
import smooth.lift.network.VolumeSyncPayload;

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
        /** 【1.7】存档<smoothlift_audio>文件夹里可选 OGG 文件名（上传来源，未入库的才显示）。 */
        public final Set<String> folderAudio = new HashSet<>();
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
    // 【1.7】存档音频来源文件夹：<存档>/smoothlift_audio/。该文件夹只是"上传来源"：
    // 选中一个文件后会把内容拷进 SavedData（融入存档），之后删掉原文件仍可播放。
    // ------------------------------------------------------------------

    /** 存档目录下存放待导入 OGG 的文件夹名。 */
    public static final String AUDIO_FOLDER = "smoothlift_audio";

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
        return level.getDataStorage().computeIfAbsent(EscalatorSpeedData.FACTORY, EscalatorSpeedData.DATA_NAME);
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
        int minY = chunk.getMinY();
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
            LevelChunk chunk = holder.getTickingChunk();
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
                | data.blockAudio.remove(pos) != null;
        if (removed) {
            data.setDirty();
        }
        return removed;
    }

    public static void syncToAll(MinecraftServer server) {
        SyncPayload payload = buildSyncPayload(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerPlayNetworking.send(player, payload);
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
        FriendlyByteBuf buf = PacketByteBufs.create();
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

    /** 把一个维度的音频数据分块发给单个玩家。 */
    public static void sendAudioSyncTo(ServerPlayer player, ServerLevel level) {
        byte[] payload = buildAudioSyncPayload(level);
        String dimId = level.dimension().location().toString();
        int totalChunks = Math.max(1, (payload.length + AUDIO_CHUNK_SIZE - 1) / AUDIO_CHUNK_SIZE);
        for (int i = 0; i < totalChunks; i++) {
            int from = i * AUDIO_CHUNK_SIZE;
            int len = Math.min(AUDIO_CHUNK_SIZE, payload.length - from);
            byte[] chunk = new byte[len];
            System.arraycopy(payload, from, chunk, 0, len);
            ServerPlayNetworking.send(player, new AudioSyncPayload(dimId, totalChunks, i, chunk));
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

    /** 构建某个维度的「方块 → 音量」同步负载（【1.12】含默认音量）。 */
    private static VolumeSyncPayload buildVolumePayload(ServerLevel level) {
        EscalatorSpeedData data = getServerData(level);
        Map<BlockPos, Integer> volumes = new HashMap<>(data.blockVolume);
        return new VolumeSyncPayload(level.dimension().location().toString(), data.defaultVolume, volumes);
    }

    /** 把一个维度的扶梯音量表发给单个玩家。 */
    public static void sendVolumeSyncTo(ServerPlayer player, ServerLevel level) {
        ServerPlayNetworking.send(player, buildVolumePayload(level));
    }

    /** 把全部维度的扶梯音量表同步给所有在线玩家。 */
    public static void syncVolumeToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (ServerLevel level : server.getAllLevels()) {
                sendVolumeSyncTo(player, level);
            }
        }
    }

    private static SyncPayload buildSyncPayload(MinecraftServer server) {
        List<SyncPayload.DimensionEntry> dimensions = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            EscalatorSpeedData data = getServerData(level);
            dimensions.add(new SyncPayload.DimensionEntry(
                    level.dimension().location().toString(),
                    data.defaultSpeed,
                    data.stepEnabled,
                    data.stepValue,
                    new HashMap<>(data.speeds),
                    filteredStepSpeeds(data)));
        }
        return new SyncPayload(dimensions);
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
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
    }
}