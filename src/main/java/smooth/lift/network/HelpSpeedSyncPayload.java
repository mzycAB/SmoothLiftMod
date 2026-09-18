package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** 【1.31】服务端 -> 客户端：同步「提示音速率」表（进 / 出两套：默认值 + 单独设置表）。 */
public record HelpSpeedSyncPayload(String dimension, int defaultHelpSpeedIn, Map<BlockPos, Integer> helpSpeedIn, int defaultHelpSpeedOut, Map<BlockPos, Integer> helpSpeedOut) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "help_speed_sync");
    public static final CustomPacketPayload.Type<HelpSpeedSyncPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, HelpSpeedSyncPayload> CODEC =
            StreamCodec.of(HelpSpeedSyncPayload::write, HelpSpeedSyncPayload::read);

    private static HelpSpeedSyncPayload read(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        int defaultHelpSpeedIn = buf.readVarInt();
        int countIn = buf.readVarInt();
        Map<BlockPos, Integer> helpSpeedIn = new HashMap<>();
        for (int i = 0; i < countIn; i++) {
            helpSpeedIn.put(buf.readBlockPos(), buf.readVarInt());
        }
        int defaultHelpSpeedOut = buf.readVarInt();
        int countOut = buf.readVarInt();
        Map<BlockPos, Integer> helpSpeedOut = new HashMap<>();
        for (int i = 0; i < countOut; i++) {
            helpSpeedOut.put(buf.readBlockPos(), buf.readVarInt());
        }
        return new HelpSpeedSyncPayload(dimension, defaultHelpSpeedIn, helpSpeedIn, defaultHelpSpeedOut, helpSpeedOut);
    }

    private static void write(FriendlyByteBuf buf, HelpSpeedSyncPayload payload) {
        buf.writeUtf(payload.dimension(), 256);
        buf.writeVarInt(payload.defaultHelpSpeedIn());
        buf.writeVarInt(payload.helpSpeedIn().size());
        for (Map.Entry<BlockPos, Integer> entry : payload.helpSpeedIn().entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
        buf.writeVarInt(payload.defaultHelpSpeedOut());
        buf.writeVarInt(payload.helpSpeedOut().size());
        for (Map.Entry<BlockPos, Integer> entry : payload.helpSpeedOut().entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
