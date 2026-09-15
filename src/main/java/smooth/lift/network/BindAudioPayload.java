package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 -> 服务端：把某个音频绑定到一条扶梯（界面里点「使用」）。 */
public record BindAudioPayload(BlockPos pos, String audioId) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "bind_audio");
    public static final CustomPacketPayload.Type<BindAudioPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, BindAudioPayload> CODEC =
            StreamCodec.of(BindAudioPayload::write, BindAudioPayload::read);

    private static BindAudioPayload read(FriendlyByteBuf buf) {
        return new BindAudioPayload(buf.readBlockPos(), buf.readUtf(128));
    }

    private static void write(FriendlyByteBuf buf, BindAudioPayload payload) {
        buf.writeBlockPos(payload.pos());
        buf.writeUtf(payload.audioId(), 128);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
