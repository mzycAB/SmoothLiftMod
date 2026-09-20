package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** 【1.18】服务端 -> 客户端：同步「扶梯方块 -> 提示音音量」表（含维度默认音量）。 */
public record HelpVolumeSyncPayload(String dimension, int defaultHelpVolume, Map<BlockPos, Integer> helpVolumes) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "help_volume_sync");
    public static final CustomPacketPayload.Type<HelpVolumeSyncPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, HelpVolumeSyncPayload> CODEC =
            StreamCodec.of(HelpVolumeSyncPayload::write, HelpVolumeSyncPayload::read);

    private static HelpVolumeSyncPayload read(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        int defaultHelpVolume = buf.readVarInt();
        int count = buf.readVarInt();
        Map<BlockPos, Integer> helpVolumes = new HashMap<>();
        for (int i = 0; i < count; i++) {
            helpVolumes.put(buf.readBlockPos(), buf.readVarInt());
        }
        return new HelpVolumeSyncPayload(dimension, defaultHelpVolume, helpVolumes);
    }

    private static void write(FriendlyByteBuf buf, HelpVolumeSyncPayload payload) {
        buf.writeUtf(payload.dimension(), 256);
        buf.writeVarInt(payload.defaultHelpVolume());
        buf.writeVarInt(payload.helpVolumes().size());
        for (Map.Entry<BlockPos, Integer> entry : payload.helpVolumes().entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
