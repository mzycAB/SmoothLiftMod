package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 服务端 -&gt; 客户端：同步某个维度的「扶梯方块 → 声音音量」表 + 默认音量。
 *
 * <p>这是个小包（只有坐标与 1~1000 的整数，不含音频字节），改一次音量就单独发它，
 * 不必为了调音量重发整个音频库（{@link AudioSyncPayload}）。
 */
public record VolumeSyncPayload(String dimension, int defaultVolume, Map<BlockPos, Integer> volumes)
        implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "volume_sync");
    public static final CustomPacketPayload.Type<VolumeSyncPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, VolumeSyncPayload> CODEC =
            StreamCodec.of(VolumeSyncPayload::write, VolumeSyncPayload::read);

    private static VolumeSyncPayload read(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        int defaultVolume = buf.readVarInt();
        int count = buf.readVarInt();
        Map<BlockPos, Integer> volumes = new HashMap<>();
        for (int i = 0; i < count; i++) {
            volumes.put(buf.readBlockPos(), buf.readVarInt());
        }
        return new VolumeSyncPayload(dimension, defaultVolume, volumes);
    }

    private static void write(FriendlyByteBuf buf, VolumeSyncPayload payload) {
        buf.writeUtf(payload.dimension(), 256);
        buf.writeVarInt(payload.defaultVolume());
        buf.writeVarInt(payload.volumes().size());
        for (Map.Entry<BlockPos, Integer> entry : payload.volumes().entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
