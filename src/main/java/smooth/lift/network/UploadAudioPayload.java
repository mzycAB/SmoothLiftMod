package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 -> 服务端：分块上传一段 OGG 音频（音频ID = 文件名，同名覆盖、可复用）。
 *
 * <p>一段音频最大 {@code EscalatorSpeedData#MAX_AUDIO_BYTES}（12MB），远超单包上限，
 * 所以按 {@code EscalatorSpeedManager#AUDIO_CHUNK_SIZE} 切块逐包发送；
 * 服务端在 {@code PENDING_UPLOADS} 里按「音频ID → 块索引」暂存，到齐后校验并入库。
 */
public record UploadAudioPayload(String audioId, int totalChunks, int chunkIndex, byte[] chunk)
        implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "upload_audio");
    public static final CustomPacketPayload.Type<UploadAudioPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, UploadAudioPayload> CODEC =
            StreamCodec.of(UploadAudioPayload::write, UploadAudioPayload::read);

    private static UploadAudioPayload read(FriendlyByteBuf buf) {
        return new UploadAudioPayload(buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
    }

    private static void write(FriendlyByteBuf buf, UploadAudioPayload payload) {
        buf.writeUtf(payload.audioId(), 128);
        buf.writeVarInt(payload.totalChunks());
        buf.writeVarInt(payload.chunkIndex());
        buf.writeByteArray(payload.chunk());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
