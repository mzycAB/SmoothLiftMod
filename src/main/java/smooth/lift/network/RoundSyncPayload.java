package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** 【1.24】服务端 -> 客户端：同步「扶梯方块 -> 底噪可闻范围」表（含维度默认范围）。 */
public record RoundSyncPayload(String dimension, int defaultRound, Map<BlockPos, Integer> rounds) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "round_sync");
    public static final CustomPacketPayload.Type<RoundSyncPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, RoundSyncPayload> CODEC =
            StreamCodec.of(RoundSyncPayload::write, RoundSyncPayload::read);

    private static RoundSyncPayload read(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        int defaultRound = buf.readVarInt();
        int count = buf.readVarInt();
        Map<BlockPos, Integer> rounds = new HashMap<>();
        for (int i = 0; i < count; i++) {
            rounds.put(buf.readBlockPos(), buf.readVarInt());
        }
        return new RoundSyncPayload(dimension, defaultRound, rounds);
    }

    private static void write(FriendlyByteBuf buf, RoundSyncPayload payload) {
        buf.writeUtf(payload.dimension(), 256);
        buf.writeVarInt(payload.defaultRound());
        buf.writeVarInt(payload.rounds().size());
        for (Map.Entry<BlockPos, Integer> entry : payload.rounds().entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
