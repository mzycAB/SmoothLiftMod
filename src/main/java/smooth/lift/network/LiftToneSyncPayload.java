package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import smooth.lift.EscalatorSpeedData;

import java.util.HashMap;
import java.util.Map;

/**
 * 【1.45】服务端 -> 客户端：同步「竖井列 → 直梯提示音」表（小包，不含音频字节）。
 *
 * <p>字段顺序照 1.20.4 的 {@code buildLiftTonePacket}：
 * {@code dimId → 条数 → (key, up, down, chime) × N}。
 */
public record LiftToneSyncPayload(String dimension, Map<Long, EscalatorSpeedData.LiftToneAudio> tones)
        implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "lift_tone_sync");
    public static final CustomPacketPayload.Type<LiftToneSyncPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, LiftToneSyncPayload> CODEC =
            StreamCodec.of(LiftToneSyncPayload::write, LiftToneSyncPayload::read);

    private static LiftToneSyncPayload read(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        int n = buf.readVarInt();
        Map<Long, EscalatorSpeedData.LiftToneAudio> tones = new HashMap<>();
        for (int i = 0; i < n; i++) {
            long key = buf.readLong();
            String up = buf.readUtf(128);
            String down = buf.readUtf(128);
            String chime = buf.readUtf(128);
            tones.put(key, new EscalatorSpeedData.LiftToneAudio(up, down, chime));
        }
        return new LiftToneSyncPayload(dimension, tones);
    }

    private static void write(FriendlyByteBuf buf, LiftToneSyncPayload payload) {
        buf.writeUtf(payload.dimension(), 256);
        buf.writeVarInt(payload.tones().size());
        for (Map.Entry<Long, EscalatorSpeedData.LiftToneAudio> e : payload.tones().entrySet()) {
            buf.writeLong(e.getKey());
            EscalatorSpeedData.LiftToneAudio tone = e.getValue();
            buf.writeUtf(tone.up(), 128);
            buf.writeUtf(tone.down(), 128);
            buf.writeUtf(tone.chime(), 128);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
