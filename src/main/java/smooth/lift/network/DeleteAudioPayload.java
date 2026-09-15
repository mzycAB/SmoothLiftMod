package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 -> 服务端：从存档音频库里删除一份音频（服务端会同时解绑引用它的扶梯）。 */
public record DeleteAudioPayload(String audioId) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "delete_audio");
    public static final CustomPacketPayload.Type<DeleteAudioPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, DeleteAudioPayload> CODEC =
            StreamCodec.of(DeleteAudioPayload::write, DeleteAudioPayload::read);

    private static DeleteAudioPayload read(FriendlyByteBuf buf) {
        return new DeleteAudioPayload(buf.readUtf(128));
    }

    private static void write(FriendlyByteBuf buf, DeleteAudioPayload payload) {
        buf.writeUtf(payload.audioId(), 128);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
