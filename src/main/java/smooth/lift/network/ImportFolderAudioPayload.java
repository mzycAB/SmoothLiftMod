package smooth.lift.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 -> 服务端：把「扶梯文件夹」里的某个文件导入存档音频库，并直接绑定到这条扶梯。
 *
 * <p>只在客户端本地存在、还没进过存档的音频会走这条路（界面上点「导入并绑定」）。
 */
public record ImportFolderAudioPayload(BlockPos pos, String fileName) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL =
            ResourceLocation.fromNamespaceAndPath("smoothlift", "import_folder_audio");
    public static final CustomPacketPayload.Type<ImportFolderAudioPayload> TYPE =
            new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, ImportFolderAudioPayload> CODEC =
            StreamCodec.of(ImportFolderAudioPayload::write, ImportFolderAudioPayload::read);

    private static ImportFolderAudioPayload read(FriendlyByteBuf buf) {
        return new ImportFolderAudioPayload(buf.readBlockPos(), buf.readUtf(128));
    }

    private static void write(FriendlyByteBuf buf, ImportFolderAudioPayload payload) {
        buf.writeBlockPos(payload.pos());
        buf.writeUtf(payload.fileName(), 128);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
