package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 -&gt; 客户端：分块同步「音频库 + 来源文件夹名单 + 扶梯-音频绑定 + 默认音频」。
 *
 * <p>音频库可能很大（单段可达 {@code EscalatorSpeedData#MAX_AUDIO_BYTES} = 12MB），
 * 所以服务端把整个负载按 {@code EscalatorSpeedManager#AUDIO_CHUNK_SIZE} 切块逐包发送；
 * 客户端在 {@code SmoothLiftClient.PENDING_SYNC_CHUNKS} 里按「维度 → (块索引 → 数据)」暂存，
 * 全部块到齐后拼回一个 {@code FriendlyByteBuf} 再解析入库。
 */
public record AudioSyncPayload(String dimension, int totalChunks, int chunkIndex, byte[] chunk)
        implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "audio_sync");
    public static final CustomPacketPayload.Type<AudioSyncPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, AudioSyncPayload> CODEC =
            StreamCodec.of(AudioSyncPayload::write, AudioSyncPayload::read);

    private static AudioSyncPayload read(FriendlyByteBuf buf) {
        return new AudioSyncPayload(buf.readUtf(256), buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
    }

    private static void write(FriendlyByteBuf buf, AudioSyncPayload payload) {
        buf.writeUtf(payload.dimension(), 256);
        buf.writeVarInt(payload.totalChunks());
        buf.writeVarInt(payload.chunkIndex());
        buf.writeByteArray(payload.chunk());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
