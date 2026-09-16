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
        CHANNEL.messageBuilder(ApplyChainPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ApplyChainPacket::encode)
                .decoder(ApplyChainPacket::decode)
                .consumerMainThread(ApplyChainPacket::handle)
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
        CHANNEL.messageBuilder(SetStepSpeedPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetStepSpeedPacket::encode)
                .decoder(SetStepSpeedPacket::decode)
                .consumerMainThread(SetStepSpeedPacket::handle)
                .add();
        CHANNEL.messageBuilder(AlignStepPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(AlignStepPacket::encode)
                .decoder(AlignStepPacket::decode)
                .consumerMainThread(AlignStepPacket::handle)
                .add();
        CHANNEL.messageBuilder(RestoreStepPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RestoreStepPacket::encode)
                .decoder(RestoreStepPacket::decode)
                .consumerMainThread(RestoreStepPacket::handle)
                .add();
        // 【1.7】自定义扶梯声音：客户端 -> 服务端
        CHANNEL.messageBuilder(UploadAudioPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UploadAudioPacket::encode)
                .decoder(UploadAudioPacket::decode)
                .consumerMainThread(UploadAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(BindAudioPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BindAudioPacket::encode)
                .decoder(BindAudioPacket::decode)
                .consumerMainThread(BindAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(UnbindAudioPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UnbindAudioPacket::encode)
                .decoder(UnbindAudioPacket::decode)
                .consumerMainThread(UnbindAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(DeleteAudioPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DeleteAudioPacket::encode)
                .decoder(DeleteAudioPacket::decode)
                .consumerMainThread(DeleteAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(ImportFolderAudioPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ImportFolderAudioPacket::encode)
                .decoder(ImportFolderAudioPacket::decode)
                .consumerMainThread(ImportFolderAudioPacket::handle)
                .add();
        // 【1.9】声音音量：客户端 -> 服务端
        CHANNEL.messageBuilder(SetVolumePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetVolumePacket::encode)
                .decoder(SetVolumePacket::decode)
                .consumerMainThread(SetVolumePacket::handle)
                .add();
        // 【1.7】服务端 -> 客户端：分块音频同步
        CHANNEL.messageBuilder(AudioSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(AudioSyncPacket::encode)
                .decoder(AudioSyncPacket::decode)
                .consumerMainThread(AudioSyncPacket::handle)
                .add();
        // 【1.9】服务端 -> 客户端：音量表同步
        CHANNEL.messageBuilder(VolumeSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(VolumeSyncPacket::encode)
                .decoder(VolumeSyncPacket::decode)
                .consumerMainThread(VolumeSyncPacket::handle)
                .add();
        // 【1.16】无障碍提示音开关：客户端 -> 服务端 + 服务端 -> 客户端
        CHANNEL.messageBuilder(SetHelpPacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetHelpPacket::encode)
                .decoder(SetHelpPacket::decode)
                .consumerMainThread(SetHelpPacket::handle)
                .add();
        CHANNEL.messageBuilder(HelpSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HelpSyncPacket::encode)
                .decoder(HelpSyncPacket::decode)
                .consumerMainThread(HelpSyncPacket::handle)
                .add();
        // 【1.18】无障碍提示音音量：客户端 -> 服务端 + 服务端 -> 客户端
        CHANNEL.messageBuilder(SetHelpVolumePacket.class, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetHelpVolumePacket::encode)
                .decoder(SetHelpVolumePacket::decode)
                .consumerMainThread(SetHelpVolumePacket::handle)
                .add();
        CHANNEL.messageBuilder(HelpVolumeSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HelpVolumeSyncPacket::encode)
                .decoder(HelpVolumeSyncPacket::decode)
                .consumerMainThread(HelpVolumeSyncPacket::handle)
                .add();
        // 【1.24】两个淡入淡出范围（底噪 / 提示音）：只有服务端 -> 客户端
        CHANNEL.messageBuilder(RoundSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RoundSyncPacket::encode)
                .decoder(RoundSyncPacket::decode)
                .consumerMainThread(RoundSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(HelpRoundSyncPacket.class, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(HelpRoundSyncPacket::encode)
                .decoder(HelpRoundSyncPacket::decode)
                .consumerMainThread(HelpRoundSyncPacket::handle)
                .add();
    }
}