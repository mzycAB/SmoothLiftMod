package smooth.lift.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.SimpleChannel;

/** 1.20.4 网络层：使用新的 ChannelBuilder + CustomPayloadEvent.Context API。 */
public final class Packets {
    private static final int PROTOCOL_VERSION = 1;

    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named(new ResourceLocation("smoothlift", "main"))
            .networkProtocolVersion(PROTOCOL_VERSION)
            .simpleChannel();

    private Packets() {
    }

    public static void register() {
        CHANNEL.messageBuilder(SetSpeedPacket.class)
                .encoder(SetSpeedPacket::encode)
                .decoder(SetSpeedPacket::decode)
                .consumerMainThread(SetSpeedPacket::handle)
                .add();
        CHANNEL.messageBuilder(RequestSyncPacket.class)
                .encoder(RequestSyncPacket::encode)
                .decoder(RequestSyncPacket::decode)
                .consumerMainThread(RequestSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(SyncPacket.class)
                .encoder(SyncPacket::encode)
                .decoder(SyncPacket::decode)
                .consumerMainThread(SyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetStepSpeedPacket.class)
                .encoder(SetStepSpeedPacket::encode)
                .decoder(SetStepSpeedPacket::decode)
                .consumerMainThread(SetStepSpeedPacket::handle)
                .add();
        CHANNEL.messageBuilder(AlignStepPacket.class)
                .encoder(AlignStepPacket::encode)
                .decoder(AlignStepPacket::decode)
                .consumerMainThread(AlignStepPacket::handle)
                .add();
        CHANNEL.messageBuilder(RestoreStepPacket.class)
                .encoder(RestoreStepPacket::encode)
                .decoder(RestoreStepPacket::decode)
                .consumerMainThread(RestoreStepPacket::handle)
                .add();
    }

    /** 客户端 -> 服务端：1.20.4 起 SimpleChannel 不再自带 sendToServer，需通过当前客户端连接发送。 */
    public static <MSG> void sendToServer(MSG msg) {
        Connection conn = Minecraft.getInstance().getConnection().getConnection();
        CHANNEL.send(msg, conn);
    }
}