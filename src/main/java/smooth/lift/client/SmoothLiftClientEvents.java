package smooth.lift.client;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;
import smooth.lift.SmoothLift;
import smooth.lift.network.Packets;
import smooth.lift.network.RequestSyncPacket;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Mod.EventBusSubscriber(modid = SmoothLift.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SmoothLiftClientEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 【1.7】服务端音频同步分块拼接缓冲：维度ID → (块索引 → 数据)。 */
    private static final Map<String, Map<Integer, byte[]>> PENDING_SYNC_CHUNKS = new HashMap<>();

    /** 拿着石斧右键扶梯 -> 打开速度输入界面（客户端拦截）。 */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!level.isClientSide() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!event.getEntity().getMainHandItem().is(Items.STONE_AXE)) {
            return;
        }
        if (!EscalatorUtil.isEscalator(level.getBlockState(event.getPos()))) {
            return;
        }
        Minecraft.getInstance().setScreen(new EscalatorSpeedScreen(event.getPos()));
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
    }

    /** 客户端完全进世界后主动向服务端请求速度 + 音频 + 音量数据。 */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Packets.sendToServer(new RequestSyncPacket());
    }

    /** 断开连接时清空客户端镜像与逐条渲染状态，避免残留上一个世界的数据。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        EscalatorSpeedManager.clearClientData();
        PENDING_SYNC_CHUNKS.clear();
        // 【1.7】停掉所有扶梯声音，并清掉链缓存/解码失败记录。
        EscalatorAudioPlayer.onDisconnect();
        // 【1.15】停掉无障碍提示音并清掉定位缓存。
        EscalatorChimePlayer.onDisconnect();
        EscalatorStepRenderer.onDisconnect();
    }

    /** 每客户端刻尾推进动画时钟与阶梯索引（含周期重扫），并更新扶梯声音。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        EscalatorStepRenderer.onClientTick(mc);
        // 【1.7】自定义扶梯声音播放器：每 tick 检查附近扶梯并调整音量与位置。
        EscalatorAudioPlayer.onClientTick(mc);
        // 【1.15】香港式无障碍提示音播放器：每 tick 找最近扶梯、按两端距离起停两路提示音。
        EscalatorChimePlayer.onClientTick(mc);
    }

    /** 世界渲染到 AFTER_ENTITIES 阶段时逐条绘制阶梯面。 */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        EscalatorStepRenderer.onRenderLevelStage(event);
    }

    /** 客户端区块加载 -> 扫描建索引。 */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (!(chunk.getLevel() instanceof ClientLevel level)) {
            return;
        }
        EscalatorStepIndex.onChunkLoad(level, chunk);
    }

    /** 客户端区块卸载 -> 移出索引。 */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (!(chunk.getLevel() instanceof ClientLevel level)) {
            return;
        }
        EscalatorStepIndex.onChunkUnload(level, chunk);
    }

    // ------------------------------------------------------------------
    // 【1.7】服务端音频同步：分块拼装 + 解析 + 应用
    // ------------------------------------------------------------------

    /**
     * 收到一个音频同步分块（由 {@code AudioSyncPacket} 在 PLAY_TO_CLIENT 方向转发到这里）。
     *
     * <p>全部块到齐后把负载拼回一个 byte[] 并按约定顺序解析：
     * 音频库 → 来源文件夹名单 → 扶梯-音频绑定 → 默认音频；
     * 随后应用镜像、停掉旧播放实例（下一 tick 用新数据重放）、并刷新可能开着的音乐选择界面。
     */
    public static void onAudioSyncChunk(String dimId, int totalChunks, int chunkIndex, byte[] chunk) {
        if (totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks || chunk == null) {
            return;
        }
        Map<Integer, byte[]> chunks = PENDING_SYNC_CHUNKS.computeIfAbsent(dimId, k -> new HashMap<>());
        chunks.put(chunkIndex, chunk);
        if (chunks.size() < totalChunks) {
            return;
        }
        PENDING_SYNC_CHUNKS.remove(dimId);
        int total = 0;
        for (byte[] part : chunks.values()) {
            total += part.length;
        }
        byte[] payload = new byte[total];
        int offset = 0;
        for (int i = 0; i < totalChunks; i++) {
            byte[] part = chunks.get(i);
            if (part == null) {
                return;
            }
            System.arraycopy(part, 0, payload, offset, part.length);
            offset += part.length;
        }
        final ResourceKey<Level> dimKey;
        try {
            dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
        } catch (Exception e) {
            return;
        }
        final FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        int audioCount = data.readVarInt();
        final Map<String, byte[]> audioLibrary = new HashMap<>();
        for (int i = 0; i < audioCount; i++) {
            String id = data.readUtf(128);
            byte[] bytes = data.readByteArray();
            if (bytes.length > 0 && bytes.length <= EscalatorSpeedData.MAX_AUDIO_BYTES) {
                audioLibrary.put(id, bytes);
            }
        }
        int folderCount = data.readVarInt();
        final Set<String> folderAudio = new HashSet<>();
        for (int i = 0; i < folderCount; i++) {
            folderAudio.add(data.readUtf(128));
        }
        int bindCount = data.readVarInt();
        final Map<BlockPos, String> blockAudio = new HashMap<>();
        for (int i = 0; i < bindCount; i++) {
            blockAudio.put(data.readBlockPos(), data.readUtf(128));
        }
        // 【1.11】默认扶梯音频（空串 = 没有默认音频）
        String defaultAudioRaw = data.readUtf(128);
        final String defaultAudio = defaultAudioRaw.isEmpty() ? null : defaultAudioRaw;

        Minecraft.getInstance().execute(() -> {
            EscalatorSpeedManager.applyClientAudioData(dimKey, audioLibrary, folderAudio, blockAudio,
                    defaultAudio);
            long kb = 0L;
            for (byte[] value : audioLibrary.values()) {
                kb += value.length;
            }
            // 「绑定了却没声音」时，这条日志能立刻看出客户端到底有没有拿到音频数据。
            LOGGER.info("[SmoothLift/Audio] 音频同步完成（{}）：已入库 {} 个音频（{}KB）、"
                            + "文件夹待导入 {} 个、扶梯绑定 {} 处、默认音频 {}",
                    dimKey.location(), audioLibrary.size(), kb / 1024, folderAudio.size(),
                    blockAudio.size(), defaultAudio == null ? "（无）" : defaultAudio);
            EscalatorAudioPlayer.onAudioReloaded();
            AudioSetupScreen.notifyAudioDataChanged();
        });
    }
}
