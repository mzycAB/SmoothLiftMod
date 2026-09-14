package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：分块上传一段 OGG 音频（音频ID = 文件名，同名覆盖、可复用）。
 *
 * <p>一段音频最大 {@link EscalatorSpeedData#MAX_AUDIO_BYTES}（12MB），
 * 远超单包上限，所以按 {@link EscalatorSpeedManager#AUDIO_CHUNK_SIZE} 切块逐包发送；
 * 服务端在 {@code PENDING_UPLOADS} 里按「音频ID → 块索引」暂存，到齐后校验并入库。
 */
public class UploadAudioPacket {
    private final String audioId;
    private final int totalChunks;
    private final int chunkIndex;
    private final byte[] chunk;

    public UploadAudioPacket(String audioId, int totalChunks, int chunkIndex, byte[] chunk) {
        this.audioId = audioId;
        this.totalChunks = totalChunks;
        this.chunkIndex = chunkIndex;
        this.chunk = chunk;
    }

    public static void encode(UploadAudioPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.audioId, 128);
        buf.writeVarInt(pkt.totalChunks);
        buf.writeVarInt(pkt.chunkIndex);
        buf.writeByteArray(pkt.chunk);
    }

    public static UploadAudioPacket decode(FriendlyByteBuf buf) {
        return new UploadAudioPacket(buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readByteArray());
    }

    public static void handle(UploadAudioPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            byte[] complete = EscalatorSpeedManager.handleAudioUploadChunk(
                    pkt.audioId, pkt.totalChunks, pkt.chunkIndex, pkt.chunk);
            if (complete == null) {
                return;
            }
            if (!EscalatorSpeedManager.storeAudio(level, pkt.audioId, complete)) {
                player.displayClientMessage(Component.literal(
                        "音频上传失败：文件过大（最大 "
                                + (EscalatorSpeedData.MAX_AUDIO_BYTES / 1024 / 1024)
                                + "MB）或不是 MC 能播的 Ogg Vorbis（MP3/Opus 都不行）"), true);
                return;
            }
            player.displayClientMessage(Component.literal("音频已上传并存入存档"), true);
            EscalatorSpeedManager.syncAudioToAll(player.server);
        });
        context.setPacketHandled(true);
    }
}
