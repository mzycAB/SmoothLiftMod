package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 【1.46】客户端 -> 服务端：设置某类提示音（up/down/chime）的**维度默认子开关**（石斧 UI 开关）。
 */
public record SetLiftToneSwitchPayload(String which, boolean enabled) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "set_lift_tone_switch");
    public static final CustomPacketPayload.Type<SetLiftToneSwitchPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, SetLiftToneSwitchPayload> CODEC =
            StreamCodec.of(SetLiftToneSwitchPayload::write, SetLiftToneSwitchPayload::read);

    private static SetLiftToneSwitchPayload read(FriendlyByteBuf buf) {
        return new SetLiftToneSwitchPayload(buf.readUtf(32), buf.readBoolean());
    }

    private static void write(FriendlyByteBuf buf, SetLiftToneSwitchPayload payload) {
        buf.writeUtf(payload.which(), 32);
        buf.writeBoolean(payload.enabled());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
