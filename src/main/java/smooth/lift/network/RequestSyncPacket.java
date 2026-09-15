package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.NetworkDirection;
import smooth.lift.EscalatorSpeedManager;

/** 客户端 -> 服务端：客户端完全进世界后主动请求全量同步（速度 + 音频 + 音量）。 */
public class RequestSyncPacket {
    public RequestSyncPacket() {
    }

    public static void encode(RequestSyncPacket pkt, FriendlyByteBuf buf) {
    }

    public static RequestSyncPacket decode(FriendlyByteBuf buf) {
        return new RequestSyncPacket();
    }

    public static void handle(RequestSyncPacket pkt, CustomPayloadEvent.Context context) {
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            EscalatorSpeedManager.syncToAll(player.server);
            // 【1.7/1.9】音频与音量是独立的小/大包，这里一并回发，
            // 否则刚进世界时客户端只有速度、音频绑定为空（表现为"明明绑着却静音"）。
            EscalatorSpeedManager.sendAudioSyncTo(player, player.serverLevel());
            EscalatorSpeedManager.sendVolumeSyncTo(player, player.serverLevel());
        });
        context.setPacketHandled(true);
    }
}
