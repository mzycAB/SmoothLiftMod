package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 【1.48】客户端 -> 服务端：设置某一项（up/down/chime）的**单项音量**
 * （石斧 UI 列表输入框 = /lifthelploud up|down|door）。
 */
public record SetLiftToneVolumePayload(String which, int volume) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "set_lift_tone_volume");
    public static final CustomPacketPayload.Type<SetLiftToneVolumePayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, SetLiftToneVolumePayload> CODEC =
            StreamCodec.of(SetLiftToneVolumePayload::write, SetLiftToneVolumePayload::read);

    private static SetLiftToneVolumePayload read(FriendlyByteBuf buf) {
        return new SetLiftToneVolumePayload(buf.readUtf(32), buf.readVarInt());
    }

    private static void write(FriendlyByteBuf buf, SetLiftToneVolumePayload payload) {
        buf.writeUtf(payload.which(), 32);
        buf.writeVarInt(payload.volume());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
