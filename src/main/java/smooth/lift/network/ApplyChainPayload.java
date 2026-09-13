package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 -> 服务端：石斧设置界面按 ESC 退出时，一次性应用「扶梯速度 + 阶梯速度」的改动。
 * <ul>
 *   <li>setRun=true  -> 设运行速度，并让阶梯速度跟随运行速度；</li>
 *   <li>setStep=true -> 只设阶梯速度，运行速度不动。</li>
 * </ul>
 */
public record ApplyChainPayload(BlockPos pos, boolean setRun, double run, boolean setStep, double step)
        implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "apply_chain");
    public static final CustomPacketPayload.Type<ApplyChainPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, ApplyChainPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ApplyChainPayload::pos,
            ByteBufCodecs.BOOL, ApplyChainPayload::setRun,
            ByteBufCodecs.DOUBLE, ApplyChainPayload::run,
            ByteBufCodecs.BOOL, ApplyChainPayload::setStep,
            ByteBufCodecs.DOUBLE, ApplyChainPayload::step,
            ApplyChainPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}