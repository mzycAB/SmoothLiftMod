package smooth.lift;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * 每个维度一份，随世界存档自动保存/加载（存在 <世界>/<维度>/data/smoothlift_speeds.dat）。
 * defaultSpeed：本维度未单独调速的扶梯使用的默认速度；speeds：每个扶梯方块的速度。
 */
public class EscalatorSpeedData extends SavedData {
    public static final String DATA_NAME = "smoothlift_speeds";
    public static final double DEFAULT_SPEED = 1.0;
    public static final double MAX_SPEED = 50.0;

    public double defaultSpeed = DEFAULT_SPEED;
    public final Map<BlockPos, Double> speeds = new HashMap<>();

    public static final SavedData.Factory<EscalatorSpeedData> FACTORY =
            new SavedData.Factory<>(EscalatorSpeedData::new, EscalatorSpeedData::fromTag, DataFixTypes.SAVED_DATA_MAP_DATA);

    public static EscalatorSpeedData fromTag(CompoundTag tag, HolderLookup.Provider provider) {
        EscalatorSpeedData data = new EscalatorSpeedData();
        if (tag.contains("default")) {
            data.defaultSpeed = clamp(tag.getDouble("default"));
        }
        CompoundTag speeds = tag.getCompound("speeds");
        for (String key : speeds.getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                data.speeds.put(pos, clamp(speeds.getDouble(key)));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putDouble("default", defaultSpeed);
        CompoundTag speeds = new CompoundTag();
        for (Map.Entry<BlockPos, Double> entry : this.speeds.entrySet()) {
            BlockPos pos = entry.getKey();
            speeds.putDouble(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        tag.put("speeds", speeds);
        return tag;
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
