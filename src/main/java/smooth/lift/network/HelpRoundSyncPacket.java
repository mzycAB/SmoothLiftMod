package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 【1.24】服务端 -> 客户端：同步「扶梯方块 → 无障碍提示音可闻范围（格）」表（含维度默认范围）。
 * 小包：只带范围数字，不含音频字节。
 */
public class HelpRoundSyncPacket {
    private final String dimension;
    private final int defaultHelpRound;
    private final Map<BlockPos, Integer> helpRounds;

    public HelpRoundSyncPacket(String dimension, int defaultHelpRound, Map<BlockPos, Integer> helpRounds) {
        this.dimension = dimension;
        this.defaultHelpRound = defaultHelpRound;
        this.helpRounds = helpRounds;
    }

    public static void encode(HelpRoundSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.dimension, 256);
        buf.writeVarInt(pkt.defaultHelpRound);
        buf.writeVarInt(pkt.helpRounds.size());
        for (Map.Entry<BlockPos, Integer> entry : pkt.helpRounds.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    public static HelpRoundSyncPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        int defaultHelpRound = buf.readVarInt();
        int count = buf.readVarInt();
        Map<BlockPos, Integer> helpRounds = new HashMap<>();
        for (int i = 0; i < count; i++) {
            helpRounds.put(buf.readBlockPos(), buf.readVarInt());
        }
        return new HelpRoundSyncPacket(dimension, defaultHelpRound, helpRounds);
    }

    public static void handle(HelpRoundSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
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
            EscalatorSpeedManager.applyClientHelpRounds(dimKey, pkt.defaultHelpRound, pkt.helpRounds);
        });
        context.setPacketHandled(true);
    }
}
