package smooth.lift.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 【1.45】客户端 -> 服务端：把 smoothlift_audio 里的一个 OGG 导入并存为某条直梯的某一项提示音。
 */
public record ImportFolderLiftTonePayload(long key, String which, String fileName) implements CustomPacketPayload {
    public static final ResourceLocation CHANNEL = ResourceLocation.fromNamespaceAndPath("smoothlift", "import_folder_lift_tone");
    public static final CustomPacketPayload.Type<ImportFolderLiftTonePayload> TYPE = new CustomPacketPayload.Type<>(CHANNEL);
    public static final StreamCodec<FriendlyByteBuf, ImportFolderLiftTonePayload> CODEC =
            StreamCodec.of(ImportFolderLiftTonePayload::write, ImportFolderLiftTonePayload::read);

    private static ImportFolderLiftTonePayload read(FriendlyByteBuf buf) {
        return new ImportFolderLiftTonePayload(buf.readLong(), buf.readUtf(32), buf.readUtf(128));
    }

    private static void write(FriendlyByteBuf buf, ImportFolderLiftTonePayload payload) {
        buf.writeLong(payload.key());
        buf.writeUtf(payload.which(), 32);
        buf.writeUtf(payload.fileName(), 128);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
