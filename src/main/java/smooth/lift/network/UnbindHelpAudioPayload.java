package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** 【1.41】客户端 -> 服务端：清掉这条扶梯某一头的提示音音乐单独设置（回到维度默认）。 */
public record UnbindHelpAudioPayload(BlockPos pos, boolean in) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "unbind_help_audio");
    public static final CustomPacketPayload.Type<UnbindHelpAudioPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, UnbindHelpAudioPayload> CODEC =
            StreamCodec.of(UnbindHelpAudioPayload::write, UnbindHelpAudioPayload::read);

    private static UnbindHelpAudioPayload read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean in = buf.readBoolean();
        return new UnbindHelpAudioPayload(pos, in);
    }

    private static void write(FriendlyByteBuf buf, UnbindHelpAudioPayload payload) {
        buf.writeBlockPos(payload.pos());
        buf.writeBoolean(payload.in());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
