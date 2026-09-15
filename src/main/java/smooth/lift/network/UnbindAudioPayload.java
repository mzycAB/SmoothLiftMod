package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 -> 服务端：解绑一条扶梯的音频（之后该扶梯静音）。 */
public record UnbindAudioPayload(BlockPos pos) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "unbind_audio");
    public static final CustomPacketPayload.Type<UnbindAudioPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, UnbindAudioPayload> CODEC =
            StreamCodec.of(UnbindAudioPayload::write, UnbindAudioPayload::read);

    private static UnbindAudioPayload read(FriendlyByteBuf buf) {
        return new UnbindAudioPayload(buf.readBlockPos());
    }

    private static void write(FriendlyByteBuf buf, UnbindAudioPayload payload) {
        buf.writeBlockPos(payload.pos());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
