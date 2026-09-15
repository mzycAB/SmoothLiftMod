package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 客户端 -> 服务端：设置一条扶梯的声音音量（1~1000，100 = 原始音量）。 */
public record SetVolumePayload(BlockPos pos, int volume) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "set_volume");
    public static final CustomPacketPayload.Type<SetVolumePayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, SetVolumePayload> CODEC =
            StreamCodec.of(SetVolumePayload::write, SetVolumePayload::read);

    private static SetVolumePayload read(FriendlyByteBuf buf) {
        return new SetVolumePayload(buf.readBlockPos(), buf.readVarInt());
    }

    private static void write(FriendlyByteBuf buf, SetVolumePayload payload) {
        buf.writeBlockPos(payload.pos());
        buf.writeVarInt(payload.volume());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
