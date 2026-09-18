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
 * 【1.24】服务端 -> 客户端：同步「扶梯方块 → 运行底噪可闻范围（格）」表（含维度默认范围）。
 * 小包：只带范围数字，不含音频字节。
 */
public class RoundSyncPacket {
    private final String dimension;
    private final int defaultRound;
    private final Map<BlockPos, Integer> rounds;

    public RoundSyncPacket(String dimension, int defaultRound, Map<BlockPos, Integer> rounds) {
        this.dimension = dimension;
        this.defaultRound = defaultRound;
        this.rounds = rounds;
    }

    public static void encode(RoundSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.dimension, 256);
        buf.writeVarInt(pkt.defaultRound);
        buf.writeVarInt(pkt.rounds.size());
        for (Map.Entry<BlockPos, Integer> entry : pkt.rounds.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    public static RoundSyncPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        int defaultRound = buf.readVarInt();
        int count = buf.readVarInt();
        Map<BlockPos, Integer> rounds = new HashMap<>();
        for (int i = 0; i < count; i++) {
            rounds.put(buf.readBlockPos(), buf.readVarInt());
        }
        return new RoundSyncPacket(dimension, defaultRound, rounds);
    }

    public static void handle(RoundSyncPacket pkt, CustomPayloadEvent.Context context) {
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
            EscalatorSpeedManager.applyClientRounds(dimKey, pkt.defaultRound, pkt.rounds);
        });
        context.setPacketHandled(true);
    }
}
