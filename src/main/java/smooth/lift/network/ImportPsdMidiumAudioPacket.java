package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;
import java.util.function.Supplier;

/**
 * 【1.53】客户端 -> 服务端：从 MBM_Audio 文件夹导入 OGG 进音频库（到站播报用）。buf: name(utf128)
 */
public class ImportPsdMidiumAudioPacket {
    private final String name;

    public ImportPsdMidiumAudioPacket(String name) {
        this.name = name;
    }

    public static void encode(ImportPsdMidiumAudioPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.name, 128);
    }

    public static ImportPsdMidiumAudioPacket decode(FriendlyByteBuf buf) {
        return new ImportPsdMidiumAudioPacket(buf.readUtf(128));
    }

    public static void handle(ImportPsdMidiumAudioPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            String problem = EscalatorSpeedManager.importAudioToStore(level, pkt.name);
            if (problem == null) {
                player.displayClientMessage(Component.literal(
                        "已导入存档音频库：「" + pkt.name + "」"), true);
                EscalatorSpeedManager.sendAudioSyncTo(player, level);
            } else {
                player.displayClientMessage(Component.literal("导入失败：" + problem), true);
            }

        });
        context.setPacketHandled(true);
    }
}
