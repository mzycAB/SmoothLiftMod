package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import smooth.lift.EscalatorSpeedManager;

import java.util.function.Supplier;

/** 客户端 -> 服务端：客户端完全进世界后主动请求全量速度同步。 */
public class RequestSyncPacket {
    public RequestSyncPacket() {
    }

    public static void encode(RequestSyncPacket pkt, FriendlyByteBuf buf) {
    }

    public static RequestSyncPacket decode(FriendlyByteBuf buf) {
        return new RequestSyncPacket();
    }

    public static void handle(RequestSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            EscalatorSpeedManager.syncToAll(player.getServer());
        });
        context.setPacketHandled(true);
    }
}
