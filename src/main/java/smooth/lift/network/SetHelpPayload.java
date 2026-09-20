package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** 【1.16】客户端 -> 服务端：石斧界面里的「无障碍提示音」开关。 */
public record SetHelpPayload(BlockPos pos, boolean helpEnabled) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "set_help");
    public static final CustomPacketPayload.Type<SetHelpPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, SetHelpPayload> CODEC =
            StreamCodec.of(SetHelpPayload::write, SetHelpPayload::read);

    private static SetHelpPayload read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        boolean helpEnabled = buf.readBoolean();
        return new SetHelpPayload(pos, helpEnabled);
    }

    private static void write(FriendlyByteBuf buf, SetHelpPayload payload) {
        buf.writeBlockPos(payload.pos());
        buf.writeBoolean(payload.helpEnabled());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
