package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** 【1.16】服务端 -> 客户端：同步「扶梯方块 -> 提示音开关」表（含维度默认开关）。 */
public record HelpSyncPayload(String dimension, boolean defaultHelp, Map<BlockPos, Boolean> help) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "help_sync");
    public static final CustomPacketPayload.Type<HelpSyncPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, HelpSyncPayload> CODEC =
            StreamCodec.of(HelpSyncPayload::write, HelpSyncPayload::read);

    private static HelpSyncPayload read(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        boolean defaultHelp = buf.readBoolean();
        int count = buf.readVarInt();
        Map<BlockPos, Boolean> help = new HashMap<>();
        for (int i = 0; i < count; i++) {
            help.put(buf.readBlockPos(), buf.readBoolean());
        }
        return new HelpSyncPayload(dimension, defaultHelp, help);
    }

    private static void write(FriendlyByteBuf buf, HelpSyncPayload payload) {
        buf.writeUtf(payload.dimension(), 256);
        buf.writeBoolean(payload.defaultHelp());
        buf.writeVarInt(payload.help().size());
        for (Map.Entry<BlockPos, Boolean> entry : payload.help().entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeBoolean(entry.getValue());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
