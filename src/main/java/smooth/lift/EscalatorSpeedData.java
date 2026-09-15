package smooth.lift;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 每个维度一份，随世界存档自动加载/保存（存在 <世界>/<维度>/data/smoothlift_speeds.dat）。
 *
 * defaultSpeed：本维度未单独调速的扶梯使用的默认运行速度。
 * speeds：每个扶梯方块的运行速度（石斧设置）。
 *
 * 【1.6 起】阶梯动画速度以「每条扶梯单独设置」为主：
 * stepSpeeds：每条扶梯（链上每个方块）单独设置的阶梯动画速度（石斧设置）。
 *             没有单独设置的扶梯回退到「维度默认阶梯动画速度」。
 * axeModified：石斧设置过阶梯动画的扶梯方块集合（用于同步与 /jietispeed 的 f 覆盖）。
 * stepEnabled / stepValue：维度默认阶梯动画速度（未单独设置的扶梯使用）；
 *             stepEnabled=false 表示用 MTR 原版动画（{@link #VANILLA_STEP}）。
 *
 * 【1.7 起】自定义扶梯声音：
 * audioLibrary：音频ID → OGG 文件字节。音频按内容哈希去重存一份，天然支持"一个音频复用多条扶梯"。
 * blockAudio：扶梯方块（x,y,z）→ 音频ID。未绑定的扶梯不播放声音（静音）。
 *             受 NBT 读取大小限制（NbtIo.readCompressed 带 NbtSizeTracker），单音频不超过
 *             {@link #MAX_AUDIO_BYTES}（12MB），超出时服务端拒绝接收。
 *
 * 【1.9 起】扶梯声音音量：
 * blockVolume：扶梯方块（x,y,z）→ 音量（1~1000；【1.12】100 = 原始音量，1000 = 10× 放大）。
 *             只记录被调过的（≠100）方块，未记录的按默认 100 处理，旧存档缺这一段也能正常读。
 *
 * 【1.11 起】默认扶梯音频：
 * defaultAudio：未单独绑定音频的扶梯使用的默认音频 ID（/futimusic 设置）。
 *              null 表示不发声（未绑定的扶梯静音，与 1.10 之前的行为一致）。
 */
public class EscalatorSpeedData extends SavedData {
    public static final String DATA_NAME = "smoothlift_speeds";
    public static final double DEFAULT_SPEED = 1.0;
    public static final double MAX_SPEED = 50.0;

    /** MTR 原版阶梯贴图动画对应的运行速度标定值。阶移动画基准：把速度除以它得到倍率。 */
    public static final double VANILLA_STEP = 0.625;

    /** 单个自定义音频的大小上限（字节）。防止存档 NBT 超限，且避免拖慢声音解码。 */
    public static final int MAX_AUDIO_BYTES = 12 * 1024 * 1024;

    /**
     * 【1.9】扶梯声音音量：界面输入范围 1~1000。
     * <p>【1.12】100 = **原始音量（1.0×）**，1000 = **10× 放大**。
     * 原版 {@code SoundEngine.calculateVolume} 会把增益夹到 [0,1]，所以 &gt;100 的放大由
     * 客户端 Mixin（{@code SoundEngineVolumeMixin}）放开上限实现，详见 {@code EscalatorAudioPlayer}。
     */
    public static final int AUDIO_VOLUME_MIN = 1;
    public static final int AUDIO_VOLUME_MAX = 1000;
    /** 默认（= 原始 1.0×）音量。只记录与该值不同的项，未记录即按 100 处理。 */
    public static final int DEFAULT_AUDIO_VOLUME = 100;

    public double defaultSpeed = DEFAULT_SPEED;
    public final Map<BlockPos, Double> speeds = new HashMap<>();

    /** 石斧自定义过阶梯动画的扶梯方块集合（/jietispeed on|off 默认忽略它们）。 */
    public final Set<BlockPos> axeModified = new HashSet<>();
    /** 石斧给这些扶梯单独设置的阶梯动画速度（仅 stepSpeeds 里的方块有效）。 */
    public final Map<BlockPos, Double> stepSpeeds = new HashMap<>();
    /** 维度默认阶梯动画速度开关：false = MTR 原版动画；true = 使用 stepValue。 */
    public boolean stepEnabled = false;
    /** 维度默认阶梯动画速度值（最后一次 /jietispeed X 设定的值）。 */
    public double stepValue = DEFAULT_SPEED;

    /** 音频ID → OGG 文件字节（按内容哈希去重，一个音频可被多条扶梯复用）。 */
    public final Map<String, byte[]> audioLibrary = new HashMap<>();
    /** 扶梯方块（x,y,z）→ 音频ID。未绑定音频的扶梯不播放声音。 */
    public final Map<BlockPos, String> blockAudio = new HashMap<>();

    /**
     * 【1.9】扶梯方块（x,y,z）→ 声音音量（1~1000，100 = 原始音量，可放大到 1000 = 10×）。
     * 只记录与 {@link #defaultVolume} **不同**的项（默认情况即 100），未记录的按默认音量处理，
     * 这样 NBT 不会膨胀。
     */
    public final Map<BlockPos, Integer> blockVolume = new HashMap<>();

    /**
     * 【1.12】默认扶梯音量（/futiloud 设置）：**没有单独设置音量**的扶梯使用它。
     * 默认 100（= 原始音量）。与 /futispeed 的 defaultSpeed、/futimusic 的 defaultAudio 完全对称。
     */
    public int defaultVolume = DEFAULT_AUDIO_VOLUME;

    /**
     * 【1.11】默认扶梯音频 ID（/futimusic 设置）：**没有单独绑定音频**的扶梯使用它。
     * 内置音频存 {@code builtin:<key>}，玩家上传的存文件名（如 {@code example.ogg}）。
     * {@code null} = 没有默认音频，未绑定的扶梯保持静音（与 1.10 之前一致，旧存档也兼容）。
     */
    public String defaultAudio;

    /** 维度默认阶梯动画速度：未单独设置阶梯动画的扶梯使用它。 */
    public double defaultStepSpeed() {
        return stepEnabled ? stepValue : VANILLA_STEP;
    }

    /** 该方块所在的扶梯是否被单独设置了阶梯动画速度。 */
    public boolean hasIndividualStep(BlockPos pos) {
        return stepSpeeds.containsKey(pos);
    }

    public static final SavedData.Factory<EscalatorSpeedData> FACTORY =
            new SavedData.Factory<>(EscalatorSpeedData::new, EscalatorSpeedData::fromTag, DataFixTypes.SAVED_DATA_MAP_DATA);

    public static EscalatorSpeedData fromTag(CompoundTag tag, HolderLookup.Provider provider) {
        EscalatorSpeedData data = new EscalatorSpeedData();
        if (tag.contains("default")) {
            data.defaultSpeed = clamp(tag.getDouble("default"));
        }
        data.speeds.putAll(readDoubleMap(tag.getCompound("speeds")));
        data.stepSpeeds.putAll(readDoubleMap(tag.getCompound("step")));
        for (String key : tag.getCompound("axe").getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                data.axeModified.add(pos);
            }
        }
        if (tag.contains("stepEnabled")) {
            data.stepEnabled = tag.getBoolean("stepEnabled");
        }
        if (tag.contains("stepValue")) {
            data.stepValue = clamp(tag.getDouble("stepValue"));
        }
        // 【1.7】音频库与扶梯-音频绑定
        CompoundTag audioTag = tag.getCompound("audioLibrary");
        for (String key : audioTag.getAllKeys()) {
            byte[] bytes = audioTag.getByteArray(key);
            if (bytes.length > 0 && bytes.length <= MAX_AUDIO_BYTES) {
                data.audioLibrary.put(key, bytes);
            }
        }
        for (Map.Entry<String, String> entry : readStringMap(tag.getCompound("blockAudio")).entrySet()) {
            BlockPos pos = parsePos(entry.getKey());
            if (pos != null) {
                data.blockAudio.put(pos, entry.getValue());
            }
        }
        // 【1.9】扶梯声音音量（旧存档没有这一段 → 全部按默认 100 处理）
        data.blockVolume.putAll(readIntMap(tag.getCompound("blockVolume")));
        // 【1.12】默认扶梯音量（旧存档没有这一段 → 100 = 原始音量）
        if (tag.contains("defaultVolume")) {
            data.defaultVolume = clampVolume(tag.getInt("defaultVolume"));
        }
        // 【1.11】默认扶梯音频（旧存档没有这一段 → null = 未绑定的扶梯静音）
        if (tag.contains("defaultAudio")) {
            String id = tag.getString("defaultAudio");
            if (!id.isEmpty()) {
                data.defaultAudio = id;
            }
        }
        return data;
    }

    private static Map<BlockPos, Double> readDoubleMap(CompoundTag compound) {
        Map<BlockPos, Double> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                out.put(pos, clamp(compound.getDouble(key)));
            }
        }
        return out;
    }

    private static Map<String, String> readStringMap(CompoundTag compound) {
        Map<String, String> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            out.put(key, compound.getString(key));
        }
        return out;
    }

    /** 【1.9】读「方块 → 音量」，顺手把越界值夹回 1~1000（防止手改 NBT 后出怪值）。 */
    private static Map<BlockPos, Integer> readIntMap(CompoundTag compound) {
        Map<BlockPos, Integer> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                out.put(pos, clampVolume(compound.getInt(key)));
            }
        }
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putDouble("default", defaultSpeed);
        tag.put("speeds", writeDoubleMap(speeds));
        CompoundTag axe = new CompoundTag();
        for (BlockPos pos : axeModified) {
            axe.putString(pos.getX() + "," + pos.getY() + "," + pos.getZ(), "1");
        }
        tag.put("axe", axe);
        tag.putBoolean("stepEnabled", stepEnabled);
        tag.putDouble("stepValue", stepValue);
        tag.put("step", writeDoubleMap(stepSpeeds));
        // 【1.7】音频库与扶梯-音频绑定
        CompoundTag audioTag = new CompoundTag();
        for (Map.Entry<String, byte[]> entry : audioLibrary.entrySet()) {
            audioTag.putByteArray(entry.getKey(), entry.getValue());
        }
        tag.put("audioLibrary", audioTag);
        CompoundTag bindTag = new CompoundTag();
        for (Map.Entry<BlockPos, String> entry : blockAudio.entrySet()) {
            BlockPos pos = entry.getKey();
            bindTag.putString(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        tag.put("blockAudio", bindTag);
        // 【1.9】扶梯声音音量
        tag.put("blockVolume", writeIntMap(blockVolume));
        // 【1.12】默认扶梯音量
        tag.putInt("defaultVolume", defaultVolume);
        // 【1.11】默认扶梯音频
        if (defaultAudio != null && !defaultAudio.isEmpty()) {
            tag.putString("defaultAudio", defaultAudio);
        }
        return tag;
    }

    /** 该扶梯方块是否绑定了自定义音频。 */
    public boolean hasAudio(BlockPos pos) {
        return blockAudio.containsKey(pos);
    }

    /** 返回该扶梯方块绑定的音频ID；未绑定时返回 null。 */
    public String getAudioId(BlockPos pos) {
        return blockAudio.get(pos);
    }

    /** 把音频ID绑定到扶梯方块。存在性校验由调用方（EscalatorSpeedManager）负责。 */
    public void bindAudio(BlockPos pos, String audioId) {
        if (audioId != null) {
            blockAudio.put(pos, audioId);
        }
    }

    /** 解绑扶梯方块的音频（之后该扶梯静音）。 */
    public void unbindAudio(BlockPos pos) {
        blockAudio.remove(pos);
    }

    /** 删除音频库中的一份音频，同时解绑所有引用它的扶梯。 */
    public void removeAudio(String audioId) {
        audioLibrary.remove(audioId);
        blockAudio.entrySet().removeIf(entry -> entry.getValue().equals(audioId));
    }

    // ------------------------------------------------------------------
    // 【1.9】扶梯声音音量（1~1000，100 = 原始音量）
    // ------------------------------------------------------------------

    /** 把任意输入夹到合法音量范围 1~1000。 */
    public static int clampVolume(int volume) {
        return Math.max(AUDIO_VOLUME_MIN, Math.min(AUDIO_VOLUME_MAX, volume));
    }

    /** 该扶梯方块的声音音量；没单独设置过就是维度默认音量（{@link #defaultVolume}，初始 100）。 */
    public int getVolume(BlockPos pos) {
        Integer v = blockVolume.get(pos);
        return v != null ? v : defaultVolume;
    }

    /** 该扶梯方块**单独设置**的音量；没单独设置返回 null（用维度默认音量）。 */
    public Integer getIndividualVolume(BlockPos pos) {
        return blockVolume.get(pos);
    }

    /**
     * 设置某个扶梯方块的声音音量。
     * 值等于**维度默认音量**（{@link #defaultVolume}）时**删掉记录**，让 NBT 只保留真正被调过的扶梯
     * （含放大 &gt;100 的）。
     */
    public void setVolume(BlockPos pos, int volume) {
        int v = clampVolume(volume);
        if (v == defaultVolume) {
            blockVolume.remove(pos);
        } else {
            blockVolume.put(pos, v);
        }
    }

    private static CompoundTag writeIntMap(Map<BlockPos, Integer> map) {
        CompoundTag compound = new CompoundTag();
        for (Map.Entry<BlockPos, Integer> entry : map.entrySet()) {
            BlockPos pos = entry.getKey();
            compound.putInt(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        return compound;
    }

    private static CompoundTag writeDoubleMap(Map<BlockPos, Double> map) {
        CompoundTag compound = new CompoundTag();
        for (Map.Entry<BlockPos, Double> entry : map.entrySet()) {
            BlockPos pos = entry.getKey();
            compound.putDouble(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        return compound;
    }

    public static double clamp(double speed) {
        return Math.max(0.0, Math.min(MAX_SPEED, speed));
    }

    /** 把 2.0 显示成 2、1.5 显示成 1.5。 */
    public static String format(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static BlockPos parsePos(String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}