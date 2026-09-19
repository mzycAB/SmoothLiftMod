package smooth.lift.network;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.client.LiftToneSetupScreen;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 【1.45】服务端 -> 客户端：同步**直梯楼层轨道提示音**（竖井列 → up/down/chime 三音频 id）。
 *
 * <p>顺序：{@code dimId → 条数 → (key, up, down, chime) × N}。
 * 只发 id 字符串，音频字节仍走 {@link AudioSyncPacket} 那一份（同一个库）。
 */
public class LiftToneSyncPacket {
    private final String dimension;
    private final Map<Long, EscalatorSpeedData.LiftToneAudio> tones;

    public LiftToneSyncPacket(String dimension, Map<Long, EscalatorSpeedData.LiftToneAudio> tones) {
        this.dimension = dimension;
        this.tones = tones;
    }

    public static void encode(LiftToneSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.dimension, 256);
        buf.writeVarInt(pkt.tones.size());
        for (Map.Entry<Long, EscalatorSpeedData.LiftToneAudio> entry : pkt.tones.entrySet()) {
            buf.writeLong(entry.getKey());
            EscalatorSpeedData.LiftToneAudio v = entry.getValue();
            buf.writeUtf(v.up(), 128);
            buf.writeUtf(v.down(), 128);
            buf.writeUtf(v.chime(), 128);
        }
    }

    public static LiftToneSyncPacket decode(FriendlyByteBuf buf) {
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
        return new LiftToneSyncPacket(dimension, tones);
    }

    public static void handle(LiftToneSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        final String dimension = pkt.dimension;
        final Map<Long, EscalatorSpeedData.LiftToneAudio> tones = pkt.tones;
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            final ResourceKey<Level> dimKey;
            try {
                dimKey = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dimension));
            } catch (Exception e) {
                return;
            }
            EscalatorSpeedManager.applyClientLiftTone(dimKey, tones);
            // 界面开着的时候实时刷新（与 Fabric 版 client.execute 里的行为一致）。
            LiftToneSetupScreen.notifyToneDataChanged();
        }));
        context.setPacketHandled(true);
    }
}
