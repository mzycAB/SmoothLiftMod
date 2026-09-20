package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** 【1.41】服务端 -> 客户端：同步「提示音音乐」表（进 / 出两套：默认层 + 单独设置层；音频字节仍只走 AUDIO_SYNC 一份）。 */
public record HelpAudioSyncPayload(String dimension, String defaultHelpAudioIn, Map<BlockPos, String> helpAudioIn, String defaultHelpAudioOut, Map<BlockPos, String> helpAudioOut) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "help_audio_sync");
    public static final CustomPacketPayload.Type<HelpAudioSyncPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, HelpAudioSyncPayload> CODEC =
            StreamCodec.of(HelpAudioSyncPayload::write, HelpAudioSyncPayload::read);

    private static HelpAudioSyncPayload read(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        String defaultHelpAudioIn = buf.readUtf(128);
        int countIn = buf.readVarInt();
        Map<BlockPos, String> helpAudioIn = new HashMap<>();
        for (int i = 0; i < countIn; i++) {
            helpAudioIn.put(buf.readBlockPos(), buf.readUtf(128));
        }
        String defaultHelpAudioOut = buf.readUtf(128);
        int countOut = buf.readVarInt();
        Map<BlockPos, String> helpAudioOut = new HashMap<>();
        for (int i = 0; i < countOut; i++) {
            helpAudioOut.put(buf.readBlockPos(), buf.readUtf(128));
        }
        return new HelpAudioSyncPayload(dimension, defaultHelpAudioIn, helpAudioIn, defaultHelpAudioOut, helpAudioOut);
    }

    private static void write(FriendlyByteBuf buf, HelpAudioSyncPayload payload) {
        buf.writeUtf(payload.dimension(), 256);
        buf.writeUtf(payload.defaultHelpAudioIn(), 128);
        buf.writeVarInt(payload.helpAudioIn().size());
        for (Map.Entry<BlockPos, String> entry : payload.helpAudioIn().entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeUtf(entry.getValue(), 128);
        }
        buf.writeUtf(payload.defaultHelpAudioOut(), 128);
        buf.writeVarInt(payload.helpAudioOut().size());
        for (Map.Entry<BlockPos, String> entry : payload.helpAudioOut().entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeUtf(entry.getValue(), 128);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
