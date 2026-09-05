package smooth.lift;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 速度数据中枢：
 * - 服务端：每个维度一份 EscalatorSpeedData（SavedData，随世界保存）。
 * - 客户端：保存服务端同步过来的镜像，供客户端预测移动使用（和服务
 *   端速度一致，避免拉扯）。多人联机也能正常工作。
 */
public final class EscalatorSpeedManager {
    private EscalatorSpeedManager() {
    }

    public static final class ClientDimensionData {
        public double defaultSpeed = EscalatorSpeedData.DEFAULT_SPEED;
        public final Map<BlockPos, Double> speeds = new HashMap<>();
    }

    private static final Map<ResourceKey<Level>, ClientDimensionData> CLIENT_DATA = new HashMap<>();

    /** Mixin 每 tick 查询：客户端读镜像，服务端读 SavedData。 */
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

    /** 输入界面预填：查询单个方块速度，未设置返回 null（界面再显示默认值）。 */
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

    public static void applyClientData(ResourceKey<Level> dimension, double defaultSpeed, Map<BlockPos, Double> speeds) {
        ClientDimensionData data = new ClientDimensionData();
        data.defaultSpeed = defaultSpeed;
        data.speeds.putAll(speeds);
        CLIENT_DATA.put(dimension, data);
    }

    /** 断开连接时清空客户端镜像，避免换世界后残留旧数据。 */
    public static void clearClientData() {
        CLIENT_DATA.clear();
    }

    public static EscalatorSpeedData getServerData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(EscalatorSpeedData.FACTORY, EscalatorSpeedData.DATA_NAME);
    }

    /** 对 seed 所在的整条扶梯链设置速度，返回实际设置到几个方块。 */
    public static int setSpeed(ServerLevel level, BlockPos seed, double speed) {
        speed = EscalatorSpeedData.clamp(speed);
        EscalatorSpeedData data = getServerData(level);
        int count = 0;
        for (BlockPos pos : EscalatorUtil.collectChain(level, seed)) {
            data.speeds.put(pos, speed);
            count++;
        }
        if (count > 0) {
            data.setDirty();
        }
        return count;
    }

    public static void setDefault(ServerLevel level, double speed) {
        EscalatorSpeedData data = getServerData(level);
        data.defaultSpeed = EscalatorSpeedData.clamp(speed);
        data.setDirty();
    }

    public static void removeSpeed(ServerLevel level, BlockPos pos) {
        EscalatorSpeedData data = getServerData(level);
        if (data.speeds.remove(pos) != null) {
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
            buf.writeVarInt(data.speeds.size());
            for (Map.Entry<BlockPos, Double> entry : data.speeds.entrySet()) {
                buf.writeBlockPos(entry.getKey());
                buf.writeDouble(entry.getValue());
            }
        }
        return buf;
    }

    public static ResourceKey<Level> parseDimensionKey(String id) {
        return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(id));
    }
}
