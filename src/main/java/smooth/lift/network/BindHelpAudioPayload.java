package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** 【1.41】客户端 -> 服务端：把一段音频设为这条扶梯某一头的无障碍提示音音乐。 */
public record BindHelpAudioPayload(BlockPos pos, String audioId, boolean in) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "bind_help_audio");
    public static final CustomPacketPayload.Type<BindHelpAudioPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, BindHelpAudioPayload> CODEC =
            StreamCodec.of(BindHelpAudioPayload::write, BindHelpAudioPayload::read);

    private static BindHelpAudioPayload read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String audioId = buf.readUtf(128);
        boolean in = buf.readBoolean();
        return new BindHelpAudioPayload(pos, audioId, in);
    }

    private static void write(FriendlyByteBuf buf, BindHelpAudioPayload payload) {
        buf.writeBlockPos(payload.pos());
        buf.writeUtf(payload.audioId(), 128);
        buf.writeBoolean(payload.in());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
