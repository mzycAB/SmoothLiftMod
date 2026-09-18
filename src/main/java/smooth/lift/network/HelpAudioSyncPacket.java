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
import smooth.lift.client.HelpAudioSetupScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 【1.41】服务端 -> 客户端：同步「无障碍提示音的**音乐**」（进 / 出两套：默认层 + 单独设置层）。
 *
 * <p>这是一个**小包**：只发「选了哪一段」（ID 字符串），音频字节本身仍然只走
 * {@link AudioSyncPacket} 那一份（与运行底噪共用同一个 audioLibrary），绝不重复发。
 *
 * <p>进 / 出两套总是由同一条指令一起改、一起同步，所以合成一只包 —— 与
 * {@link HelpSpeedSyncPacket} 的处理方式完全一致。
 */
public class HelpAudioSyncPacket {
    private final String dimension;
    private final String defaultIn;
    private final Map<BlockPos, String> audioIn;
    private final String defaultOut;
    private final Map<BlockPos, String> audioOut;

    public HelpAudioSyncPacket(String dimension, String defaultIn, Map<BlockPos, String> audioIn,
                               String defaultOut, Map<BlockPos, String> audioOut) {
        this.dimension = dimension;
        this.defaultIn = defaultIn;
        this.audioIn = audioIn;
        this.defaultOut = defaultOut;
        this.audioOut = audioOut;
    }

    public static void encode(HelpAudioSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.dimension, 256);
        buf.writeUtf(pkt.defaultIn, 128);
        buf.writeVarInt(pkt.audioIn.size());
        for (Map.Entry<BlockPos, String> entry : pkt.audioIn.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeUtf(entry.getValue(), 128);
        }
        buf.writeUtf(pkt.defaultOut, 128);
        buf.writeVarInt(pkt.audioOut.size());
        for (Map.Entry<BlockPos, String> entry : pkt.audioOut.entrySet()) {
            buf.writeBlockPos(entry.getKey());
            buf.writeUtf(entry.getValue(), 128);
        }
    }

    public static HelpAudioSyncPacket decode(FriendlyByteBuf buf) {
        String dimension = buf.readUtf(256);
        String defaultIn = buf.readUtf(128);
        int countIn = buf.readVarInt();
        Map<BlockPos, String> audioIn = new HashMap<>();
        for (int i = 0; i < countIn; i++) {
            audioIn.put(buf.readBlockPos(), buf.readUtf(128));
        }
        String defaultOut = buf.readUtf(128);
        int countOut = buf.readVarInt();
        Map<BlockPos, String> audioOut = new HashMap<>();
        for (int i = 0; i < countOut; i++) {
            audioOut.put(buf.readBlockPos(), buf.readUtf(128));
        }
        return new HelpAudioSyncPacket(dimension, defaultIn, audioIn, defaultOut, audioOut);
    }

    public static void handle(HelpAudioSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        final String dimension = pkt.dimension;
        final String defaultIn = pkt.defaultIn;
        final Map<BlockPos, String> audioIn = pkt.audioIn;
        final String defaultOut = pkt.defaultOut;
        final Map<BlockPos, String> audioOut = pkt.audioOut;
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            final ResourceKey<Level> dimKey;
            try {
                dimKey = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(dimension));
            } catch (Exception e) {
                return;
            }
            EscalatorSpeedManager.applyClientHelpAudio(dimKey, defaultIn, audioIn, defaultOut, audioOut);
            // 列表开着的时候实时刷新（与 Fabric 版 client.execute 里的行为一致）。
            HelpAudioSetupScreen.notifyDataChanged();
        }));
        context.setPacketHandled(true);
    }
}
