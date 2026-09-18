package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** 【1.24】服务端 -> 客户端：同步「扶梯方块 -> 提示音可闻范围」表（含维度默认范围）。 */
public record HelpRoundSyncPayload(String dimension, int defaultHelpRound, Map<BlockPos, Integer> helpRounds) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "help_round_sync");
    public static final CustomPacketPayload.Type<HelpRoundSyncPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, HelpRoundSyncPayload> CODEC =
            StreamCodec.of(HelpRoundSyncPayload::write, HelpRoundSyncPayload::read);

    private static HelpRoundSyncPayload read(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        int defaultHelpRound = buf.readVarInt();
        int count = buf.readVarInt();
        Map<BlockPos, Integer> helpRounds = new HashMap<>();
        for (int i = 0; i < count; i++) {
            helpRounds.put(buf.readBlockPos(), buf.readVarInt());
        }
        return new HelpRoundSyncPayload(dimension, defaultHelpRound, helpRounds);
    }

    private static void write(FriendlyByteBuf buf, HelpRoundSyncPayload payload) {
        buf.writeUtf(payload.dimension(), 256);
        buf.writeVarInt(payload.defaultHelpRound());
        buf.writeVarInt(payload.helpRounds().size());
        for (Map.Entry<BlockPos, Integer> entry : payload.helpRounds().entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
