package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.client.PsdToneSetupScreen;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 【1.50】服务端 -> 客户端：一个维度里每扇屏蔽门的单独素材。
 * buf 顺序：{@code dimId → count → (key, open, close, 13 个 opt) × N}，
 * 与 fabric 版 {@code buildPsdTonePacket} 的写序逐格一致。
 */
public class PsdToneSyncPacket {
    private final String dimId;
    private final Map<Long, EscalatorSpeedData.PsdToneAudio> tones;

    public PsdToneSyncPacket(String dimId, Map<Long, EscalatorSpeedData.PsdToneAudio> tones) {
        this.dimId = dimId;
        this.tones = tones;
    }

    public static void encode(PsdToneSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.dimId, 256);
        buf.writeVarInt(pkt.tones.size());
        for (Map.Entry<Long, EscalatorSpeedData.PsdToneAudio> e : pkt.tones.entrySet()) {
            buf.writeLong(e.getKey());
            EscalatorSpeedData.PsdToneAudio tone = e.getValue();
            buf.writeUtf(tone.open(), 128);
            buf.writeUtf(tone.close(), 128);
            EscalatorSpeedManager.writeDoorOptBool(buf, tone.help());
            EscalatorSpeedManager.writeDoorOptBool(buf, tone.openEnabled());
            EscalatorSpeedManager.writeDoorOptBool(buf, tone.closeEnabled());
            EscalatorSpeedManager.writeDoorOptInt(buf, tone.volume());
            EscalatorSpeedManager.writeDoorOptInt(buf, tone.openVolume());
            EscalatorSpeedManager.writeDoorOptInt(buf, tone.closeVolume());
            EscalatorSpeedManager.writeDoorOptInt(buf, tone.openWaitSeconds());
            EscalatorSpeedManager.writeDoorOptInt(buf, tone.closeWaitSeconds());
            EscalatorSpeedManager.writeDoorOptString(buf, tone.midium());
            EscalatorSpeedManager.writeDoorOptInt(buf, tone.midiumWaitSeconds());
            EscalatorSpeedManager.writeDoorOptString(buf, tone.arrive());
            EscalatorSpeedManager.writeDoorOptInt(buf, tone.arriveSeconds());
            EscalatorSpeedManager.writeDoorOptInt(buf, tone.midiumVolume());
            EscalatorSpeedManager.writeDoorOptInt(buf, tone.arriveVolume());
        }
    }

    public static PsdToneSyncPacket decode(FriendlyByteBuf buf) {
        String dimId = buf.readUtf(256);
        int n = buf.readVarInt();
        Map<Long, EscalatorSpeedData.PsdToneAudio> tones = new HashMap<>();
        for (int i = 0; i < n; i++) {
            long key = buf.readLong();
            String open = buf.readUtf(128);
            String close = buf.readUtf(128);
            Boolean help = EscalatorSpeedManager.readDoorOptBool(buf);
            Boolean openEnabled = EscalatorSpeedManager.readDoorOptBool(buf);
            Boolean closeEnabled = EscalatorSpeedManager.readDoorOptBool(buf);
            Integer volume = EscalatorSpeedManager.readDoorOptInt(buf);
            Integer openVolume = EscalatorSpeedManager.readDoorOptInt(buf);
            Integer closeVolume = EscalatorSpeedManager.readDoorOptInt(buf);
            Integer openWaitSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
            Integer closeWaitSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
            String midium = EscalatorSpeedManager.readDoorOptString(buf);
            Integer midiumWaitSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
            String arrive = EscalatorSpeedManager.readDoorOptString(buf);
            Integer arriveSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
            Integer midiumVolume = EscalatorSpeedManager.readDoorOptInt(buf);
            Integer arriveVolume = EscalatorSpeedManager.readDoorOptInt(buf);
            tones.put(key, new EscalatorSpeedData.PsdToneAudio(open, close,
                    help, openEnabled, closeEnabled,
                    volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds,
                    midium, midiumWaitSeconds, midiumVolume,
                    arrive, arriveSeconds, arriveVolume));
        }
        return new PsdToneSyncPacket(dimId, tones);
    }

    public static void handle(PsdToneSyncPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            try {
                ResourceKey<Level> dimKey = EscalatorSpeedManager.parseDimensionKey(pkt.dimId);
                EscalatorSpeedManager.applyClientPsdTone(dimKey, pkt.tones);
                PsdToneSetupScreen.notifyToneDataChanged();
            } catch (Exception ignored) {
            }
        });
        context.setPacketHandled(true);
    }
}
