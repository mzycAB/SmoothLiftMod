package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SetStepSpeedPayload(BlockPos pos, double step) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "set_step_speed");
    public static final CustomPacketPayload.Type<SetStepSpeedPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, SetStepSpeedPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetStepSpeedPayload::pos,
            ByteBufCodecs.DOUBLE, SetStepSpeedPayload::step,
            SetStepSpeedPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}