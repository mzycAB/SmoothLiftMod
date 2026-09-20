package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** 【1.18】客户端 -> 服务端：石斧界面里的「提示音音量」输入框。 */
public record SetHelpVolumePayload(BlockPos pos, int volume) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "set_help_volume");
    public static final CustomPacketPayload.Type<SetHelpVolumePayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, SetHelpVolumePayload> CODEC =
            StreamCodec.of(SetHelpVolumePayload::write, SetHelpVolumePayload::read);

    private static SetHelpVolumePayload read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int volume = buf.readVarInt();
        return new SetHelpVolumePayload(pos, volume);
    }

    private static void write(FriendlyByteBuf buf, SetHelpVolumePayload payload) {
        buf.writeBlockPos(payload.pos());
        buf.writeVarInt(payload.volume());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
