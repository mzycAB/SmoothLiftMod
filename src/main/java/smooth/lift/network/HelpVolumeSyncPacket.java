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
 * 【1.18】服务端 -> 客户端：同步「扶梯方块 → 无障碍提示音音量」表（含维度默认音量）。
 * 小包：只带音量，不含音频字节。
 */
public class HelpVolumeSyncPacket {
    private final String dimension;
    private final int defaultHelpVolume;
    private final Map<BlockPos, Integer> helpVolume;

    public HelpVolumeSyncPacket(String dimension, int defaultHelpVolume, Map<BlockPos, Integer> helpVolume) {
        this.dimension = dimension;
        this.defaultHelpVolume = defaultHelpVolume;
        this.helpVolume = helpVolume;
    }

    public static void encode(HelpVolumeSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.dimension, 256);
        buf.writeVarInt(pkt.defaultHelpVolume);
        buf.writeVarInt(pkt.helpVolume.size());
        for (Map.Entry<BlockPos, Integer> entry : pkt.helpVolume.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeVarInt(entry.getValue());
        }
    }

    public static HelpVolumeSyncPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        int defaultHelpVolume = buf.readVarInt();
        int count = buf.readVarInt();
        Map<BlockPos, Integer> helpVolume = new HashMap<>();
        for (int i = 0; i < count; i++) {
            helpVolume.put(buf.readBlockPos(), buf.readVarInt());
        }
        return new HelpVolumeSyncPacket(dimension, defaultHelpVolume, helpVolume);
    }

    public static void handle(HelpVolumeSyncPacket pkt, CustomPayloadEvent.Context context) {
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
            EscalatorSpeedManager.applyClientHelpVolume(dimKey, pkt.defaultHelpVolume, pkt.helpVolume);
        });
        context.setPacketHandled(true);
    }
}
