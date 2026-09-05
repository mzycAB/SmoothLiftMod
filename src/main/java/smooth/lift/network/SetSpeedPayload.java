package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SetSpeedPayload(BlockPos pos, double speed) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "set_speed");
    public static final CustomPacketPayload.Type<SetSpeedPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, SetSpeedPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetSpeedPayload::pos,
            ByteBufCodecs.DOUBLE, SetSpeedPayload::speed,
            SetSpeedPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
