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
        CHANNEL.messageBuilder(ApplyChainPacket.class)
                .encoder(ApplyChainPacket::encode)
                .decoder(ApplyChainPacket::decode)
                .consumerMainThread(ApplyChainPacket::handle)
                .add();
        // ---- 【1.7~1.14】自定义扶梯声音 ----
        // 客户端 -> 服务端
        CHANNEL.messageBuilder(UploadAudioPacket.class)
                .encoder(UploadAudioPacket::encode)
                .decoder(UploadAudioPacket::decode)
                .consumerMainThread(UploadAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(BindAudioPacket.class)
                .encoder(BindAudioPacket::encode)
                .decoder(BindAudioPacket::decode)
                .consumerMainThread(BindAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(UnbindAudioPacket.class)
                .encoder(UnbindAudioPacket::encode)
                .decoder(UnbindAudioPacket::decode)
                .consumerMainThread(UnbindAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(DeleteAudioPacket.class)
                .encoder(DeleteAudioPacket::encode)
                .decoder(DeleteAudioPacket::decode)
                .consumerMainThread(DeleteAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(ImportFolderAudioPacket.class)
                .encoder(ImportFolderAudioPacket::encode)
                .decoder(ImportFolderAudioPacket::decode)
                .consumerMainThread(ImportFolderAudioPacket::handle)
                .add();
        // 【1.9/1.12】音量
        CHANNEL.messageBuilder(SetVolumePacket.class)
                .encoder(SetVolumePacket::encode)
                .decoder(SetVolumePacket::decode)
                .consumerMainThread(SetVolumePacket::handle)
                .add();
        // 服务端 -> 客户端
        CHANNEL.messageBuilder(AudioSyncPacket.class)
                .encoder(AudioSyncPacket::encode)
                .decoder(AudioSyncPacket::decode)
                .consumerMainThread(AudioSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(VolumeSyncPacket.class)
                .encoder(VolumeSyncPacket::encode)
                .decoder(VolumeSyncPacket::decode)
                .consumerMainThread(VolumeSyncPacket::handle)
                .add();
        // 【1.16】无障碍提示音开关：客户端 -> 服务端 + 服务端 -> 客户端
        CHANNEL.messageBuilder(SetHelpPacket.class)
                .encoder(SetHelpPacket::encode)
                .decoder(SetHelpPacket::decode)
                .consumerMainThread(SetHelpPacket::handle)
                .add();
        CHANNEL.messageBuilder(HelpSyncPacket.class)
                .encoder(HelpSyncPacket::encode)
                .decoder(HelpSyncPacket::decode)
                .consumerMainThread(HelpSyncPacket::handle)
                .add();
        // 【1.18】无障碍提示音音量：客户端 -> 服务端 + 服务端 -> 客户端
        CHANNEL.messageBuilder(SetHelpVolumePacket.class)
                .encoder(SetHelpVolumePacket::encode)
                .decoder(SetHelpVolumePacket::decode)
                .consumerMainThread(SetHelpVolumePacket::handle)
                .add();
        CHANNEL.messageBuilder(HelpVolumeSyncPacket.class)
                .encoder(HelpVolumeSyncPacket::encode)
                .decoder(HelpVolumeSyncPacket::decode)
                .consumerMainThread(HelpVolumeSyncPacket::handle)
                .add();
        // 【1.24】两个淡入淡出范围（底噪 / 提示音）：只有服务端 -> 客户端
        CHANNEL.messageBuilder(RoundSyncPacket.class)
                .encoder(RoundSyncPacket::encode)
                .decoder(RoundSyncPacket::decode)
                .consumerMainThread(RoundSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(HelpRoundSyncPacket.class)
                .encoder(HelpRoundSyncPacket::encode)
                .decoder(HelpRoundSyncPacket::decode)
                .consumerMainThread(HelpRoundSyncPacket::handle)
                .add();
        // 【1.31】无障碍提示音速率（进 / 出两套合成一只包）：只有服务端 -> 客户端
        CHANNEL.messageBuilder(HelpSpeedSyncPacket.class)
                .encoder(HelpSpeedSyncPacket::encode)
                .decoder(HelpSpeedSyncPacket::decode)
                .consumerMainThread(HelpSpeedSyncPacket::handle)
                .add();
        // 【1.41】无障碍提示音音乐（进 / 出两套合成一只包）：服务端 -> 客户端 + 客户端 -> 服务端
        CHANNEL.messageBuilder(HelpAudioSyncPacket.class)
                .encoder(HelpAudioSyncPacket::encode)
                .decoder(HelpAudioSyncPacket::decode)
                .consumerMainThread(HelpAudioSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(BindHelpAudioPacket.class)
                .encoder(BindHelpAudioPacket::encode)
                .decoder(BindHelpAudioPacket::decode)
                .consumerMainThread(BindHelpAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(UnbindHelpAudioPacket.class)
                .encoder(UnbindHelpAudioPacket::encode)
                .decoder(UnbindHelpAudioPacket::decode)
                .consumerMainThread(UnbindHelpAudioPacket::handle)
                .add();
        CHANNEL.messageBuilder(ImportFolderHelpAudioPacket.class)
                .encoder(ImportFolderHelpAudioPacket::encode)
                .decoder(ImportFolderHelpAudioPacket::decode)
                .consumerMainThread(ImportFolderHelpAudioPacket::handle)
                .add();
        // 【1.42/1.43/1.46/1.47/1.48】直梯开关门提示音设置：服务端 -> 客户端
        CHANNEL.messageBuilder(LiftChimeSyncPacket.class)
                .encoder(LiftChimeSyncPacket::encode)
                .decoder(LiftChimeSyncPacket::decode)
                .consumerMainThread(LiftChimeSyncPacket::handle)
                .add();
        // 【1.45】直梯楼层轨道提示音（石斧界面设置）：服务端 -> 客户端 + 客户端 -> 服务端
        CHANNEL.messageBuilder(LiftToneSyncPacket.class)
                .encoder(LiftToneSyncPacket::encode)
                .decoder(LiftToneSyncPacket::decode)
                .consumerMainThread(LiftToneSyncPacket::handle)
                .add();
        CHANNEL.messageBuilder(SetLiftTonePacket.class)
                .encoder(SetLiftTonePacket::encode)
                .decoder(SetLiftTonePacket::decode)
                .consumerMainThread(SetLiftTonePacket::handle)
                .add();
        // 【1.46】三提示音独立子开关：客户端 -> 服务端
        CHANNEL.messageBuilder(SetLiftToneSwitchPacket.class)
                .encoder(SetLiftToneSwitchPacket::encode)
                .decoder(SetLiftToneSwitchPacket::decode)
                .consumerMainThread(SetLiftToneSwitchPacket::handle)
                .add();
        // 【1.48】直梯提示音音量（共用默认 + 三项各自）：客户端 -> 服务端
        CHANNEL.messageBuilder(SetLiftChimeVolumePacket.class)
                .encoder(SetLiftChimeVolumePacket::encode)
                .decoder(SetLiftChimeVolumePacket::decode)
                .consumerMainThread(SetLiftChimeVolumePacket::handle)
                .add();
        CHANNEL.messageBuilder(SetLiftToneVolumePacket.class)
                .encoder(SetLiftToneVolumePacket::encode)
                .decoder(SetLiftToneVolumePacket::decode)
                .consumerMainThread(SetLiftToneVolumePacket::handle)
                .add();
        // 【1.45】从文件夹导入 OGG 并设为直梯提示音：客户端 -> 服务端
        CHANNEL.messageBuilder(ImportFolderLiftTonePacket.class)
                .encoder(ImportFolderLiftTonePacket::encode)
                .decoder(ImportFolderLiftTonePacket::decode)
                .consumerMainThread(ImportFolderLiftTonePacket::handle)
                .add();
    }

    /** 客户端 -> 服务端：1.20.4 起 SimpleChannel 不再自带 sendToServer，需通过当前客户端连接发送。 */
    public static <MSG> void sendToServer(MSG msg) {
        Connection conn = Minecraft.getInstance().getConnection().getConnection();
        CHANNEL.send(msg, conn);
    }
}
