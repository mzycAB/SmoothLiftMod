package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import smooth.lift.client.MbmHelpScreen;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import smooth.lift.client.SmoothLiftClientEvents;
import java.util.function.Supplier;

/**
 * 【1.53】服务端 -> 客户端：请求打开「预设选择」界面（无负载）。
 */
public class MbmHelpOpenPacket {

    public MbmHelpOpenPacket() {
    
    }

    public static void encode(MbmHelpOpenPacket pkt, FriendlyByteBuf buf) {
        // 空包，无负载
    }

    public static MbmHelpOpenPacket decode(FriendlyByteBuf buf) {
        return new MbmHelpOpenPacket();
    }

    public static void handle(MbmHelpOpenPacket pkt, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context context = ctxSupplier.get();
        if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
            context.setPacketHandled(true);
            return;
        }
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new MbmHelpScreen()));

        });
        context.setPacketHandled(true);
    }
}
