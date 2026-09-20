package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 【1.48】客户端 -> 服务端：设置**共用默认音量**（石斧 UI 主界面输入框 = /lifthelploud &lt;音量&gt;）。
 */
public record SetLiftChimeVolumePayload(int volume) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "set_lift_chime_volume");
    public static final CustomPacketPayload.Type<SetLiftChimeVolumePayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, SetLiftChimeVolumePayload> CODEC =
            StreamCodec.of(SetLiftChimeVolumePayload::write, SetLiftChimeVolumePayload::read);

    private static SetLiftChimeVolumePayload read(FriendlyByteBuf buf) {
        return new SetLiftChimeVolumePayload(buf.readVarInt());
    }

    private static void write(FriendlyByteBuf buf, SetLiftChimeVolumePayload payload) {
        buf.writeVarInt(payload.volume());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
