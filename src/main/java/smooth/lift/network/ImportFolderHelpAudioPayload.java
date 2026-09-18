package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** 【1.41】客户端 -> 服务端：把存档 smoothlift_audio 文件夹里的一个 OGG 导入存档并设为提示音音乐。 */
public record ImportFolderHelpAudioPayload(BlockPos pos, String fileName, boolean in) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "import_folder_help_audio");
    public static final CustomPacketPayload.Type<ImportFolderHelpAudioPayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, ImportFolderHelpAudioPayload> CODEC =
            StreamCodec.of(ImportFolderHelpAudioPayload::write, ImportFolderHelpAudioPayload::read);

    private static ImportFolderHelpAudioPayload read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String fileName = buf.readUtf(128);
        boolean in = buf.readBoolean();
        return new ImportFolderHelpAudioPayload(pos, fileName, in);
    }

    private static void write(FriendlyByteBuf buf, ImportFolderHelpAudioPayload payload) {
        buf.writeBlockPos(payload.pos());
        buf.writeUtf(payload.fileName(), 128);
        buf.writeBoolean(payload.in());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
