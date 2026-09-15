package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.NetworkDirection;
import smooth.lift.EscalatorSpeedManager;

/** 客户端 -> 服务端：从存档删除一段音频（同时解绑所有引用它的扶梯，并向所有玩家重发同步）。 */
public class DeleteAudioPacket {
    private final String audioId;

    public DeleteAudioPacket(String audioId) {
        this.audioId = audioId;
    }

    public static void encode(DeleteAudioPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.audioId, 128);
    }

    public static DeleteAudioPacket decode(FriendlyByteBuf buf) {
        return new DeleteAudioPacket(buf.readUtf(128));
    }

    public static void handle(DeleteAudioPacket pkt, CustomPayloadEvent.Context context) {
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ServerLevel level = player.serverLevel();
            if (EscalatorSpeedManager.deleteAudio(level, pkt.audioId)) {
                player.displayClientMessage(Component.literal("已从存档删除音频（引用它的扶梯已静音）"), true);
                EscalatorSpeedManager.syncAudioToAll(player.server);
            }
        });
        context.setPacketHandled(true);
    }
}
