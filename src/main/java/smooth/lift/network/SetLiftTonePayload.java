package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 【1.45】客户端 -> 服务端：设置某条直梯的某一项提示音（up / down / chime）。
 *
 * <p>直梯没有稳定 ID，所以客户端在右键楼层轨道时已经把「竖井列 key」算好发过来。
 */
public record SetLiftTonePayload(long key, String which, String audioId) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "set_lift_tone");
    public static final CustomPacketPayload.Type<SetLiftTonePayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, SetLiftTonePayload> CODEC =
            StreamCodec.of(SetLiftTonePayload::write, SetLiftTonePayload::read);

    private static SetLiftTonePayload read(FriendlyByteBuf buf) {
        return new SetLiftTonePayload(buf.readLong(), buf.readUtf(32), buf.readUtf(128));
    }

    private static void write(FriendlyByteBuf buf, SetLiftTonePayload payload) {
        buf.writeLong(payload.key());
        buf.writeUtf(payload.which(), 32);
        buf.writeUtf(payload.audioId(), 128);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
