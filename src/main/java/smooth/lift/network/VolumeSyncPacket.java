package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.NetworkDirection;
import smooth.lift.EscalatorSpeedManager;

import java.util.HashMap;
import java.util.Map;

/**
 * 【1.9】服务端 -> 客户端：同步「扶梯方块 → 声音音量」表（含【1.12】的默认音量）。
 *
 * <p>这是一个**小包**：只带音量，不含音频字节。改音量时只发它，
 * 避免为了一个数字重发整个音频库。
 */
public class VolumeSyncPacket {
    private final String dimension;
    private final int defaultVolume;
    private final Map<BlockPos, Integer> volumes;

    public VolumeSyncPacket(String dimension, int defaultVolume, Map<BlockPos, Integer> volumes) {
        this.dimension = dimension;
        this.defaultVolume = defaultVolume;
        this.volumes = volumes;
    }

    public static void encode(VolumeSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.dimension, 256);
        buf.writeVarInt(pkt.defaultVolume);
        buf.writeVarInt(pkt.volumes.size());
        for (Map.Entry<BlockPos, Integer> entry : pkt.volumes.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    public static VolumeSyncPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        int defaultVolume = buf.readVarInt();
        int count = buf.readVarInt();
        Map<BlockPos, Integer> volumes = new HashMap<>();
        for (int i = 0; i < count; i++) {
            volumes.put(buf.readBlockPos(), buf.readVarInt());
        }
        return new VolumeSyncPacket(dimension, defaultVolume, volumes);
    }

    public static void handle(VolumeSyncPacket pkt, CustomPayloadEvent.Context context) {
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
            EscalatorSpeedManager.applyClientVolumes(dimKey, pkt.volumes, pkt.defaultVolume);
        });
        context.setPacketHandled(true);
    }
}
