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
     * 阶梯动画设定速度：
     * - 被石斧自定义过阶梯动画的扶梯 -> 用它的自定义值；
     * - 否则 -> stepEnabled 开启用 stepValue，关闭用 MTR 原版（0.625）。
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
        if (data.axeModified.contains(pos)) {
            Double s = data.stepSpeeds.get(pos);
            if (s != null) {
                return s;
            }
        }
        return data.stepEnabled ? data.stepValue : EscalatorSpeedData.VANILLA_STEP;
    }

    /** 全局阶梯动画默认：/jietispeed 开启用 stepValue，关闭用 MTR 原版（0.625）。 */
    public static double getGlobalStepDefault(Level level) {
        if (level.isClientSide()) {
            ClientDimensionData data = CLIENT_DATA.get(level.dimension());
            if (data == null) {
                return EscalatorSpeedData.VANILLA_STEP;
            }
            return data.stepEnabled ? data.stepValue : EscalatorSpeedData.VANILLA_STEP;
        }
        EscalatorSpeedData data = getServerData((ServerLevel) level);
        return data.stepEnabled ? data.stepValue : EscalatorSpeedData.VANILLA_STEP;
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
        }
        data.setDirty();
        return chain.size();
    }

    public static void setDefault(ServerLevel level, double speed) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultSpeed = EscalatorSpeedData.clamp(speed);
        data.setDirty();
    }

    /** 石斧：给整条扶梯链设置指定阶梯动画速度（并标记为石斧自定义）。 */
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

    /** 石斧：对齐——整条扶梯链的阶梯动画速度 = 它自己的运行速度。 */
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

    /** 石斧：恢复默认——整条扶梯链的阶梯动画 = MTR 原版（只改动画，不改运行速度）。 */
    public static int restoreStepDefault(ServerLevel level, BlockPos seed) {
        EscalatorSpeedData data = getServerData(level);
        Set<BlockPos> chain = EscalatorUtil.collectChain(level, seed);
        for (BlockPos pos : chain) {
            data.stepSpeeds.put(pos, EscalatorSpeedData.VANILLA_STEP);
        }
        data.axeModified.addAll(chain);
        data.setDirty();
        return chain.size();
    }

    /**
     * /jietispeed 全局开关。includeAdjusted=true 时（带 f）额外把石斧自定义的扶梯也覆盖为目标值。
     *
     * @param enabled  true = 开启（阶梯动画用 stepValue）；false = 关闭（MTR 原版）
     * @param includeAdjusted 是否强制包含石斧自定义过的扶梯
     * @return 被强制覆盖的石斧自定义扶梯方块数（不带 f 时为 0）
     */
    public static int jietiCommand(ServerLevel level, boolean enabled, boolean includeAdjusted) {
        EscalatorSpeedData data = getServerData(level);
        data.stepEnabled = enabled;
        data.setDirty();
        if (!includeAdjusted) {
            return 0;
        }
        double target = enabled ? data.stepValue : EscalatorSpeedData.VANILLA_STEP;
        return forceStepTarget(level, data, target);
    }

    /** 设置全局阶梯动画速度值并开启功能（/jietispeed X）。 */
    public static void setJietiValue(ServerLevel level, double value) {
        EscalatorSpeedData data = getServerData(level);
        data.stepValue = EscalatorSpeedData.clamp(value);
        data.stepEnabled = true;
        data.setDirty();
    }

    /** /futispeed f X：强制所有扶梯运行速度 = X（包括石斧自定义过的）。 */
    public static int forceAllRunningSpeed(ServerLevel level, double speed) {
        speed = EscalatorSpeedData.clamp(speed);
        EscalatorSpeedData data = getServerData(level);
        int changed = 0;
        for (LevelChunk chunk : lastChunks(level)) {
            for (BlockPos pos : escalatorBlocksIn(chunk, level)) {
                data.speeds.put(pos, speed);
                changed++;
            }
        }
        if (changed > 0) {
            data.setDirty();
        }
        return changed;
    }

    private static int forceStepTarget(ServerLevel level, EscalatorSpeedData data, double target) {
        int changed = 0;
        for (LevelChunk chunk : lastChunks(level)) {
            for (BlockPos pos : escalatorBlocksIn(chunk, level)) {
                if (data.axeModified.contains(pos)) {
                    data.stepSpeeds.put(pos, target);
                    changed++;
                }
            }
        }
        if (changed > 0) {
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