package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.event.network.CustomPayloadEvent;
import smooth.lift.EscalatorSpeedManager;

import java.util.HashMap;
import java.util.Map;

/**
 * 【1.16】服务端 -> 客户端：同步「扶梯方块 → 无障碍提示音开关」表（含维度默认开关）。
 * 小包：只带开关，不含音频字节。
 */
public class HelpSyncPacket {
    private final String dimension;
    private final boolean defaultHelp;
    private final Map<BlockPos, Boolean> help;

    public HelpSyncPacket(String dimension, boolean defaultHelp, Map<BlockPos, Boolean> help) {
        this.dimension = dimension;
        this.defaultHelp = defaultHelp;
        this.help = help;
    }

    public static void encode(HelpSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.dimension, 256);
        buf.writeBoolean(pkt.defaultHelp);
        buf.writeVarInt(pkt.help.size());
        for (Map.Entry<BlockPos, Boolean> entry : pkt.help.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeBoolean(entry.getValue());
        }
    }

    public static HelpSyncPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        boolean defaultHelp = buf.readBoolean();
        int count = buf.readVarInt();
        Map<BlockPos, Boolean> help = new HashMap<>();
        for (int i = 0; i < count; i++) {
            help.put(buf.readBlockPos(), buf.readBoolean());
        }
        return new HelpSyncPacket(dimension, defaultHelp, help);
    }

    public static void handle(HelpSyncPacket pkt, CustomPayloadEvent.Context context) {
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            final ResourceKey<Level> dimKey;
            try {
                dimKey = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(pkt.dimension));
            } catch (Exception e) {
                return;
            }
            EscalatorSpeedManager.applyClientHelp(dimKey, pkt.defaultHelp, pkt.help);
        });
        context.setPacketHandled(true);
    }
}
