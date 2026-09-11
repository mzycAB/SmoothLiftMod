package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** 服务端 -> 客户端：全量同步所有维度的扶梯速度与阶梯动画数据。 */
public class SyncPacket {
    public record LevelSpeedData(String dimension, double defaultSpeed,
                                 boolean stepEnabled, double stepValue,
                                 Map<BlockPos, Double> speeds, Map<BlockPos, Double> stepSpeeds) {
    }

    private final List<LevelSpeedData> entries;

    public SyncPacket(List<LevelSpeedData> entries) {
        this.entries = entries;
    }

    public static void encode(SyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeVarInt(pkt.entries.size());
        for (LevelSpeedData entry : pkt.entries) {
            buf.writeUtf(entry.dimension(), 256);
            buf.writeDouble(entry.defaultSpeed());
            buf.writeBoolean(entry.stepEnabled());
            buf.writeDouble(entry.stepValue());
            buf.writeVarInt(entry.speeds().size());
            for (Map.Entry<BlockPos, Double> e : entry.speeds().entrySet()) {
                buf.writeBlockPos(e.getKey());
                buf.writeDouble(e.getValue());
            }
            buf.writeVarInt(entry.stepSpeeds().size());
            for (Map.Entry<BlockPos, Double> e : entry.stepSpeeds().entrySet()) {
                buf.writeBlockPos(e.getKey());
                buf.writeDouble(e.getValue());
            }
        }
    }

    public static SyncPacket decode(FriendlyByteBuf buf) {
        int dimCount = buf.readVarInt();
        List<LevelSpeedData> entries = new ArrayList<>();
        for (int i = 0; i < dimCount; i++) {
            String dimension = buf.readUtf(256);
            double defaultSpeed = buf.readDouble();
            boolean stepEnabled = buf.readBoolean();
            double stepValue = buf.readDouble();
            int entryCount = buf.readVarInt();
            Map<BlockPos, Double> speeds = new HashMap<>();
            for (int j = 0; j < entryCount; j++) {
                speeds.put(buf.readBlockPos(), buf.readDouble());
            }
            int stepCount = buf.readVarInt();
            Map<BlockPos, Double> stepSpeeds = new HashMap<>();
            for (int j = 0; j < stepCount; j++) {
                stepSpeeds.put(buf.readBlockPos(), buf.readDouble());
            }
            entries.add(new LevelSpeedData(dimension, defaultSpeed, stepEnabled, stepValue, speeds, stepSpeeds));
        }
        return new SyncPacket(entries);
    }

    public static void handle(SyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            for (LevelSpeedData entry : pkt.entries) {
                try {
                    EscalatorSpeedManager.applyClientData(
                            EscalatorSpeedManager.parseDimensionKey(entry.dimension()),
                            entry.defaultSpeed(),
                            entry.speeds(),
                            entry.stepSpeeds(),
                            entry.stepEnabled(),
                            entry.stepValue()
                    );
                } catch (Exception ignored) {
                }
            }
        });
        context.setPacketHandled(true);
    }
}