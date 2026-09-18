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
 * 【1.31】服务端 -> 客户端：同步「无障碍提示音的**速率**」（Hz）——进 / 出两套。
 *
 * <p>形状与 {@link VolumeSyncPacket} 一致，只是**一次带两张表**：
 * 上客端（进入扶梯）与落客端（离开扶梯）总是由同一条指令一起改、一起同步，
 * 所以合成一只包（少一次建包/发送，客户端也少一次覆盖）。
 *
 * <p>速率本身很小（一个整数），所以这是**小包**；音频字节完全不经过它。
 */
public class HelpSpeedSyncPacket {
    private final String dimension;
    private final int defaultIn;
    private final Map<BlockPos, Integer> speedsIn;
    private final int defaultOut;
    private final Map<BlockPos, Integer> speedsOut;

    public HelpSpeedSyncPacket(String dimension, int defaultIn, Map<BlockPos, Integer> speedsIn,
                               int defaultOut, Map<BlockPos, Integer> speedsOut) {
        this.dimension = dimension;
        this.defaultIn = defaultIn;
        this.speedsIn = speedsIn;
        this.defaultOut = defaultOut;
        this.speedsOut = speedsOut;
    }

    public static void encode(HelpSpeedSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.dimension, 256);
        buf.writeVarInt(pkt.defaultIn);
        buf.writeVarInt(pkt.speedsIn.size());
        for (Map.Entry<BlockPos, Integer> entry : pkt.speedsIn.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
        buf.writeVarInt(pkt.defaultOut);
        buf.writeVarInt(pkt.speedsOut.size());
        for (Map.Entry<BlockPos, Integer> entry : pkt.speedsOut.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    public static HelpSpeedSyncPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        int defaultIn = buf.readVarInt();
        int countIn = buf.readVarInt();
        Map<BlockPos, Integer> speedsIn = new HashMap<>();
        for (int i = 0; i < countIn; i++) {
            speedsIn.put(buf.readBlockPos(), buf.readVarInt());
        }
        int defaultOut = buf.readVarInt();
        int countOut = buf.readVarInt();
        Map<BlockPos, Integer> speedsOut = new HashMap<>();
        for (int i = 0; i < countOut; i++) {
            speedsOut.put(buf.readBlockPos(), buf.readVarInt());
        }
        return new HelpSpeedSyncPacket(dimension, defaultIn, speedsIn, defaultOut, speedsOut);
    }

    public static void handle(HelpSpeedSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
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
            EscalatorSpeedManager.applyClientHelpSpeeds(dimKey, pkt.defaultIn, pkt.speedsIn,
                    pkt.defaultOut, pkt.speedsOut);
        });
        context.setPacketHandled(true);
    }
}
