package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 【1.42】服务端 -> 客户端：同步**直梯（Lift）开关门提示音**（按维度）。
 *
 * <p>1.20.4 时代这份负载是手写的 {@code buildLiftChimePacket(ServerLevel) -> FriendlyByteBuf}，
 * 字段顺序**就是** {@code EscalatorSpeedManager.applyClientLiftChime} 的入参顺序：
 * {@code dimId → enabled → speed → volume → upEnabled → downEnabled → chimeEnabled
 * → round → volumeUp → volumeDown → volumeChime}。
 * 改成 payload 后顺序不变（协议兼容），两个方法仍必须一起改。
 */
public record LiftChimeSyncPayload(String dimension, boolean enabled, float speed, int volume,
                                   boolean upEnabled, boolean downEnabled, boolean chimeEnabled,
                                   int round, int volumeUp, int volumeDown, int volumeChime)
        implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "lift_chime_sync");
    public static final CustomPacketPayload.Type<LiftChimeSyncPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, LiftChimeSyncPayload> CODEC =
            StreamCodec.of(LiftChimeSyncPayload::write, LiftChimeSyncPayload::read);

    private static LiftChimeSyncPayload read(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        boolean enabled = buf.readBoolean();
        float speed = buf.readFloat();
        // 【1.43】音量
        int volume = buf.readVarInt();
        // 【1.46】三提示音独立子开关（up → down → chime）
        boolean upEnabled = buf.readBoolean();
        boolean downEnabled = buf.readBoolean();
        boolean chimeEnabled = buf.readBoolean();
        // 【1.47】淡入淡出范围（三项共用）
        int round = buf.readVarInt();
        // 【1.48】三项各自音量（-1 = 跟随共用默认）
        int volumeUp = buf.readVarInt();
        int volumeDown = buf.readVarInt();
        int volumeChime = buf.readVarInt();
        return new LiftChimeSyncPayload(dimension, enabled, speed, volume,
                upEnabled, downEnabled, chimeEnabled, round, volumeUp, volumeDown, volumeChime);
    }

    private static void write(FriendlyByteBuf buf, LiftChimeSyncPayload payload) {
        buf.writeUtf(payload.dimension(), 256);
        buf.writeBoolean(payload.enabled());
        buf.writeFloat(payload.speed());
        buf.writeVarInt(payload.volume());
        buf.writeBoolean(payload.upEnabled());
        buf.writeBoolean(payload.downEnabled());
        buf.writeBoolean(payload.chimeEnabled());
        buf.writeVarInt(payload.round());
        buf.writeVarInt(payload.volumeUp());
        buf.writeVarInt(payload.volumeDown());
        buf.writeVarInt(payload.volumeChime());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
