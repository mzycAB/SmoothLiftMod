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
 * 【1.55】客户端 -> 服务端：「同步所有」弹窗。buf: domain(utf16)→scope(varInt)→force(boolean)→key(long)
 */
public class SyncSettingsPacket {
    private final String domain;
    private final int scope;
    private final boolean force;
    private final long key;

    public SyncSettingsPacket(String domain, int scope, boolean force, long key) {
        this.domain = domain;
        this.scope = scope;
        this.force = force;
        this.key = key;
    }

    public static void encode(SyncSettingsPacket pkt, FriendlyByteBuf buf) {
        buf.writeUtf(pkt.domain, 16);
        buf.writeVarInt(pkt.scope);
        buf.writeBoolean(pkt.force);
        buf.writeLong(pkt.key);
    }

    public static SyncSettingsPacket decode(FriendlyByteBuf buf) {
        return new SyncSettingsPacket(buf.readUtf(16), buf.readVarInt(), buf.readBoolean(), buf.readLong());
    }

    public static void handle(SyncSettingsPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            ServerLevel level = player.serverLevel();
            String answer = SmoothLift.syncSettings(player.server, level, pkt.domain, pkt.scope, pkt.force, pkt.key);
            player.displayClientMessage(Component.literal(answer), false);

        });
        context.setPacketHandled(true);
    }
}
