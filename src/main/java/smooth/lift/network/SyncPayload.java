package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record SyncPayload(List<DimensionEntry> dimensions) implements CustomPacketPayload {
    public record DimensionEntry(String dimensionId, double defaultSpeed, boolean stepEnabled, double stepValue,
                                 Map<BlockPos, Double> speeds, Map<BlockPos, Double> stepSpeeds) {
    }

    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "sync");
    public static final CustomPacketPayload.Type<SyncPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, SyncPayload> CODEC = StreamCodec.of(SyncPayload::write, SyncPayload::read);

    private static SyncPayload read(FriendlyByteBuf buf) {
        int dimCount = buf.readVarInt();
        List<DimensionEntry> dimensions = new ArrayList<>();
        for (int i = 0; i < dimCount; i++) {
            String dimId = buf.readUtf(256);
            double defaultSpeed = buf.readDouble();
            boolean stepEnabled = buf.readBoolean();
            double stepValue = buf.readDouble();
            int speedCount = buf.readVarInt();
            Map<BlockPos, Double> speeds = new HashMap<>();
            for (int j = 0; j < speedCount; j++) {
                speeds.put(buf.readBlockPos(), buf.readDouble());
            }
            int stepCount = buf.readVarInt();
            Map<BlockPos, Double> stepSpeeds = new HashMap<>();
            for (int j = 0; j < stepCount; j++) {
                stepSpeeds.put(buf.readBlockPos(), buf.readDouble());
            }
            dimensions.add(new DimensionEntry(dimId, defaultSpeed, stepEnabled, stepValue, speeds, stepSpeeds));
        }
        return new SyncPayload(dimensions);
    }

    private static void write(FriendlyByteBuf buf, SyncPayload payload) {
        buf.writeVarInt(payload.dimensions().size());
        for (DimensionEntry entry : payload.dimensions()) {
            buf.writeUtf(entry.dimensionId(), 256);
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

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}