package smooth.lift;

import net.minecraft.core.BlockPos;
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
 */
public class EscalatorSpeedData extends SavedData {
    public static final String DATA_NAME = "smoothlift_speeds";
    public static final double DEFAULT_SPEED = 1.0;
    public static final double MAX_SPEED = 50.0;

    /** MTR 原版阶梯贴图动画对应的运行速度标定值。阶移动画基准：把速度除以它得到倍率。 */
    public static final double VANILLA_STEP = 0.625;

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

    public static EscalatorSpeedData fromTag(CompoundTag tag) {
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

    @Override
    public CompoundTag save(CompoundTag tag) {
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
        return tag;
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