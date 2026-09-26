package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;

import java.util.function.Supplier;

/**
 * 【1.50】服务端 -> 客户端：一个维度的屏蔽门提示音设置。
 * buf 顺序与 fabric 版 {@code buildPsdChimePacket} 的写序逐格一致。
 */
public class PsdChimeSyncPacket {
    private final String dimId;
    private final boolean enabled;
    private final int volume;
    private final boolean openEnabled;
    private final boolean closeEnabled;
    private final int round;
    private final int toneVolumeOpen;
    private final int toneVolumeClose;
    private final String toneAudioOpen;
    private final String toneAudioClose;
    private final int closeWaitSeconds;
    private final String midiumAudio;
    private final int midiumWaitSeconds;
    private final String arriveAudio;
    private final int arriveSeconds;
    private final int midiumVolume;
    private final int arriveVolume;
    private final int midiumRound;
    private final int arriveRound;

    public PsdChimeSyncPacket(String dimId, boolean enabled, int volume, boolean openEnabled,
                              boolean closeEnabled, int round, int toneVolumeOpen, int toneVolumeClose,
                              String toneAudioOpen, String toneAudioClose, int closeWaitSeconds,
                              String midiumAudio, int midiumWaitSeconds, String arriveAudio,
                              int arriveSeconds, int midiumVolume, int arriveVolume,
                              int midiumRound, int arriveRound) {
        this.dimId = dimId;
        this.enabled = enabled;
        this.volume = volume;
        this.openEnabled = openEnabled;
        this.closeEnabled = closeEnabled;
        this.round = round;
        this.toneVolumeOpen = toneVolumeOpen;
        this.toneVolumeClose = toneVolumeClose;
        this.toneAudioOpen = toneAudioOpen;
        this.toneAudioClose = toneAudioClose;
        this.closeWaitSeconds = closeWaitSeconds;
        this.midiumAudio = midiumAudio;
        this.midiumWaitSeconds = midiumWaitSeconds;
        this.arriveAudio = arriveAudio;
        this.arriveSeconds = arriveSeconds;
        this.midiumVolume = midiumVolume;
        this.arriveVolume = arriveVolume;
        this.midiumRound = midiumRound;
        this.arriveRound = arriveRound;
    }

    public static void encode(PsdChimeSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.dimId, 256);
        buf.writeBoolean(pkt.enabled);
        buf.writeVarInt(pkt.volume);
        buf.writeBoolean(pkt.openEnabled);
        buf.writeBoolean(pkt.closeEnabled);
        buf.writeVarInt(pkt.round);
        buf.writeVarInt(pkt.toneVolumeOpen);
        buf.writeVarInt(pkt.toneVolumeClose);
        buf.writeUtf(pkt.toneAudioOpen, 128);
        buf.writeUtf(pkt.toneAudioClose, 128);
        buf.writeVarInt(pkt.closeWaitSeconds);
        buf.writeUtf(pkt.midiumAudio, 128);
        buf.writeVarInt(pkt.midiumWaitSeconds);
        buf.writeUtf(pkt.arriveAudio, 128);
        buf.writeVarInt(pkt.arriveSeconds);
        buf.writeVarInt(pkt.midiumVolume);
        buf.writeVarInt(pkt.arriveVolume);
        buf.writeVarInt(pkt.midiumRound);
        buf.writeVarInt(pkt.arriveRound);
    }

    public static PsdChimeSyncPacket decode(FriendlyByteBuf buf) {
        return new PsdChimeSyncPacket(
                buf.readUtf(256), buf.readBoolean(), buf.readVarInt(), buf.readBoolean(),
                buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readUtf(128), buf.readUtf(128), buf.readVarInt(),
                buf.readUtf(128), buf.readVarInt(), buf.readUtf(128), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(PsdChimeSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(pkt.dimId);
                EscalatorSpeedManager.applyClientPsdChime(dimKey, pkt.enabled, pkt.volume,
                        pkt.openEnabled, pkt.closeEnabled, pkt.round,
                        pkt.toneVolumeOpen, pkt.toneVolumeClose,
                        pkt.toneAudioOpen, pkt.toneAudioClose, pkt.closeWaitSeconds,
                        pkt.midiumAudio, pkt.midiumWaitSeconds, pkt.arriveAudio, pkt.arriveSeconds,
                        pkt.midiumVolume, pkt.arriveVolume, pkt.midiumRound, pkt.arriveRound);
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}