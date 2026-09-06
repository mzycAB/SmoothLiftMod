package smooth.lift.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class Packets {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("smoothlift", "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int nextId = 0;

    private Packets() {
    }

    public static void register() {
        CHANNEL.messageBuilder(SetSpeedPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetSpeedPacket::encode)
                .decoder(SetSpeedPacket::decode)
                .consumerMainThread(SetSpeedPacket::handle)
                .add();
        CHANNEL.messageBuilder(RequestSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RequestSyncPacket::encode)
                .decoder(RequestSyncPacket::decode)
                .consumerMainThread(RequestSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(SyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncPacket::encode)
                .decoder(SyncPacket::decode)
                .consumerMainThread(SyncPacket::handle)
                .add();
    }
}
