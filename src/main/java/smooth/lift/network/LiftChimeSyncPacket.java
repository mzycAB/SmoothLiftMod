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
import smooth.lift.EscalatorSpeedManager;

import java.util.function.Supplier;

/**
 * 【1.42】服务端 -> 客户端：同步**直梯开关门提示音**的维度设置。
 *
 * <p>字段顺序**必须**与 {@code EscalatorSpeedManager.buildLiftChimePacket} 的构造顺序、
 * 以及 {@link EscalatorSpeedManager#applyClientLiftChime} 的入参顺序三者一致：
 * {@code dimId → enabled → speed → volume → upEnabled → downEnabled → chimeEnabled
 * → round → toneVolumeUp → toneVolumeDown → toneVolumeChime}。
 *
 * <p>这是**最小的包**：只发「设置」，音频字节本身走 {@link AudioSyncPacket}。
 */
public class LiftChimeSyncPacket {
    private final String dimension;
    private final boolean enabled;
    private final float speed;
    private final int volume;
    private final boolean upEnabled;
    private final boolean downEnabled;
    private final boolean chimeEnabled;
    private final int round;
    private final int toneVolumeUp;
    private final int toneVolumeDown;
    private final int toneVolumeChime;

    public LiftChimeSyncPacket(String dimension, boolean enabled, float speed, int volume,
                               boolean upEnabled, boolean downEnabled, boolean chimeEnabled,
                               int round, int toneVolumeUp, int toneVolumeDown, int toneVolumeChime) {
        this.dimension = dimension;
        this.enabled = enabled;
        this.speed = speed;
        this.volume = volume;
        this.upEnabled = upEnabled;
        this.downEnabled = downEnabled;
        this.chimeEnabled = chimeEnabled;
        this.round = round;
        this.toneVolumeUp = toneVolumeUp;
        this.toneVolumeDown = toneVolumeDown;
        this.toneVolumeChime = toneVolumeChime;
    }

    public static void encode(LiftChimeSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.dimension, 256);
        buf.writeBoolean(pkt.enabled);
        buf.writeFloat(pkt.speed);
        buf.writeVarInt(pkt.volume);
        // 【1.46】三提示音独立子开关（追加在音量后面，读侧顺序必须一致：up → down → chime）
        buf.writeBoolean(pkt.upEnabled);
        buf.writeBoolean(pkt.downEnabled);
        buf.writeBoolean(pkt.chimeEnabled);
        // 【1.47】淡入淡出范围（包尾追加）
        buf.writeVarInt(pkt.round);
        // 【1.48】三项各自音量（-1 = 跟随共用默认）
        buf.writeVarInt(pkt.toneVolumeUp);
        buf.writeVarInt(pkt.toneVolumeDown);
        buf.writeVarInt(pkt.toneVolumeChime);
    }

    public static LiftChimeSyncPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        boolean enabled = buf.readBoolean();
        float speed = buf.readFloat();
        int volume = buf.readVarInt();
        boolean upEnabled = buf.readBoolean();
        boolean downEnabled = buf.readBoolean();
        boolean chimeEnabled = buf.readBoolean();
        int round = buf.readVarInt();
        int toneVolumeUp = buf.readVarInt();
        int toneVolumeDown = buf.readVarInt();
        int toneVolumeChime = buf.readVarInt();
        return new LiftChimeSyncPacket(dimension, enabled, speed, volume,
                upEnabled, downEnabled, chimeEnabled, round,
                toneVolumeUp, toneVolumeDown, toneVolumeChime);
    }

    public static void handle(LiftChimeSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        final String dimension = pkt.dimension;
        final boolean enabled = pkt.enabled;
        final float speed = pkt.speed;
        final int volume = pkt.volume;
        final boolean upEnabled = pkt.upEnabled;
        final boolean downEnabled = pkt.downEnabled;
        final boolean chimeEnabled = pkt.chimeEnabled;
        final int round = pkt.round;
        final int toneVolumeUp = pkt.toneVolumeUp;
        final int toneVolumeDown = pkt.toneVolumeDown;
        final int toneVolumeChime = pkt.toneVolumeChime;
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            final ResourceKey<Level> dimKey;
            try {
                dimKey = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dimension));
            } catch (Exception e) {
                return;
            }
            EscalatorSpeedManager.applyClientLiftChime(dimKey, enabled, speed, volume,
                    upEnabled, downEnabled, chimeEnabled, round,
                    toneVolumeUp, toneVolumeDown, toneVolumeChime);
        }));
        context.setPacketHandled(true);
    }
}
