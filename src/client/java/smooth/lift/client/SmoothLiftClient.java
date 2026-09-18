package smooth.lift.client;

import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;
import smooth.lift.network.AudioSyncPayload;
import smooth.lift.network.HelpAudioSyncPayload;
import smooth.lift.network.HelpRoundSyncPayload;
import smooth.lift.network.HelpSpeedSyncPayload;
import smooth.lift.network.HelpSyncPayload;
import smooth.lift.network.HelpVolumeSyncPayload;
import smooth.lift.network.RequestSyncPayload;
import smooth.lift.network.RoundSyncPayload;
import smooth.lift.network.SyncPayload;
import smooth.lift.network.VolumeSyncPayload;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SmoothLiftClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 【1.7】服务端音频同步分块拼接缓冲：维度ID → (块索引 → 数据)。 */
    private static final Map<String, Map<Integer, byte[]>> PENDING_SYNC_CHUNKS = new HashMap<>();

    @Override
    public void onInitializeClient() {
        // 逐条扶梯独立的阶梯动画：注册区块索引 + 世界渲染回调
        EscalatorStepRenderer.register();
        ClientTickEvents.END_CLIENT_TICK.register(EscalatorStepRenderer::onClientTick);

        // 【1.7】自定义扶梯声音播放器：每 tick 检查附近扶梯并调整音量
        ClientTickEvents.END_CLIENT_TICK.register(EscalatorAudioPlayer::onClientTick);

        // 【1.15】香港式扶梯视障人士提示音：进扶梯一端急促咔咔、出扶梯一端缓慢咔咔（【1.23】改为敲击声）
        ClientTickEvents.END_CLIENT_TICK.register(EscalatorChimePlayer::onClientTick);

        // 拿着石斧右键扶梯 -> 打开速度输入界面
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!player.getMainHandItem().is(Items.STONE_AXE)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            if (!EscalatorUtil.isEscalator(world.getBlockState(pos))) {
                return InteractionResult.PASS;
            }
            Minecraft.getInstance().setScreen(new EscalatorSpeedScreen(pos));
            return InteractionResult.FAIL;
        });

        // 客户端完全进世界后主动向服务端请求速度数据。
        // 服务端侧的 ServerPlayConnectionEvents.JOIN 推送发生在玩家连接建立过程中
        // （早于频道握手完成），此时发的包可能被客户端丢弃，导致进游戏后速度显示为默认。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientPlayNetworking.send(new RequestSyncPayload());
        });

        // 断开连接时清空客户端镜像，避免残留上一个世界的速度数据
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            EscalatorSpeedManager.clearClientData();
            PENDING_SYNC_CHUNKS.clear();
            EscalatorAudioPlayer.onDisconnect();
            EscalatorChimePlayer.onDisconnect();
            EscalatorAnimationDriver.clear();
            EscalatorStepRenderer.onDisconnect();
        });

        // 接收服务端同步的全部速度+阶梯动画数据
        ClientPlayNetworking.registerGlobalReceiver(SyncPayload.TYPE, (payload, context) -> {
            final Map<ResourceKey<Level>, SyncEntry> parsed = new HashMap<>();
            for (SyncPayload.DimensionEntry entry : payload.dimensions()) {
                try {
                    parsed.put(EscalatorSpeedManager.parseDimensionKey(entry.dimensionId()),
                            new SyncEntry(entry.defaultSpeed(), entry.speeds(), entry.stepSpeeds(),
                                    entry.stepEnabled(), entry.stepValue()));
                } catch (Exception ignored) {
                }
            }
            context.client().execute(() -> {
                for (Map.Entry<ResourceKey<Level>, SyncEntry> entry : parsed.entrySet()) {
                    EscalatorSpeedManager.applyClientData(entry.getKey(),
                            entry.getValue().defaultSpeed, entry.getValue().speeds, entry.getValue().stepSpeeds,
                            entry.getValue().stepEnabled, entry.getValue().stepValue);
                }
            });
        });

        // 【1.7】接收服务端分块同步的音频库与扶梯-音频绑定，全部块到齐后应用
        ClientPlayNetworking.registerGlobalReceiver(AudioSyncPayload.TYPE, (payload, context) -> {
            String dimId = payload.dimension();
            int totalChunks = payload.totalChunks();
            int chunkIndex = payload.chunkIndex();
            byte[] chunk = payload.chunk();
            if (totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks) {
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
            byte[] audioBytes = new byte[total];
            int offset = 0;
            for (int i = 0; i < totalChunks; i++) {
                byte[] part = chunks.get(i);
                if (part == null) {
                    return;
                }
                System.arraycopy(part, 0, audioBytes, offset, part.length);
                offset += part.length;
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            final FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(audioBytes));
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
            context.client().execute(() -> {
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
        });

        // 【1.9】接收服务端同步的「扶梯方块 → 声音音量」表（小包，不含音频字节）
        ClientPlayNetworking.registerGlobalReceiver(VolumeSyncPayload.TYPE, (payload, context) -> {
            final Map<BlockPos, Integer> volumes = new HashMap<>(payload.volumes());
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(payload.dimension());
            } catch (Exception e) {
                return;
            }
            final int defVol = payload.defaultVolume();
            context.client().execute(() -> EscalatorSpeedManager.applyClientVolumes(dimKey, volumes, defVol));
        });

        // 【1.16】接收服务端同步的「扶梯方块 → 无障碍提示音开关」表（小包）
        ClientPlayNetworking.registerGlobalReceiver(HelpSyncPayload.TYPE, (payload, context) -> {
            final Map<BlockPos, Boolean> help = new HashMap<>(payload.help());
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(payload.dimension());
            } catch (Exception e) {
                return;
            }
            final boolean defaultHelp = payload.defaultHelp();
            context.client().execute(() -> {
                EscalatorSpeedManager.applyClientHelp(dimKey, defaultHelp, help);
                // 「开关关不掉」时先看这条日志：没打出来 = 同步包根本没到（装错 jar / 频道没注册）；
                // 打出来了但还响 = 去看 [SmoothLift/Chime] 那两条「已静音 / 恢复播放」。
                LOGGER.info("[SmoothLift/Help] 无障碍提示音开关已同步（{}）：默认 {}、单独设置 {} 处",
                        dimKey.location(), defaultHelp ? "开" : "关", help.size());
            });
        });

        // 【1.18】接收服务端同步的「扶梯方块 → 无障碍提示音音量」表（小包）
        ClientPlayNetworking.registerGlobalReceiver(HelpVolumeSyncPayload.TYPE, (payload, context) -> {
            final Map<BlockPos, Integer> helpVolume = new HashMap<>(payload.helpVolumes());
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(payload.dimension());
            } catch (Exception e) {
                return;
            }
            final int defVolume = payload.defaultHelpVolume();
            context.client().execute(() -> {
                EscalatorSpeedManager.applyClientHelpVolume(dimKey, defVolume, helpVolume);
                LOGGER.info("[SmoothLift/HelpVolume] 无障碍提示音音量已同步（{}）：默认 {}、单独设置 {} 处",
                        dimKey.location(), defVolume, helpVolume.size());
            });
        });

        // 【1.24】接收服务端同步的「扶梯方块 → 运行底噪可闻范围（格）」表（小包）
        ClientPlayNetworking.registerGlobalReceiver(RoundSyncPayload.TYPE, (payload, context) -> {
            final Map<BlockPos, Integer> round = new HashMap<>(payload.rounds());
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(payload.dimension());
            } catch (Exception e) {
                return;
            }
            final int defaultRound = payload.defaultRound();
            context.client().execute(() -> {
                EscalatorSpeedManager.applyClientRounds(dimKey, defaultRound, round);
                LOGGER.info("[SmoothLift/Round] 扶梯音效淡入淡出范围已同步（{}）：默认 {} 格、单独设置 {} 处",
                        dimKey.location(), defaultRound, round.size());
            });
        });

        // 【1.24】接收服务端同步的「扶梯方块 → 无障碍提示音可闻范围（格）」表（小包）
        ClientPlayNetworking.registerGlobalReceiver(HelpRoundSyncPayload.TYPE, (payload, context) -> {
            final Map<BlockPos, Integer> helpRound = new HashMap<>(payload.helpRounds());
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(payload.dimension());
            } catch (Exception e) {
                return;
            }
            final int defaultHelpRound = payload.defaultHelpRound();
            context.client().execute(() -> {
                EscalatorSpeedManager.applyClientHelpRounds(dimKey, defaultHelpRound, helpRound);
                LOGGER.info("[SmoothLift/HelpRound] 无障碍提示音淡入淡出范围已同步（{}）：默认 {} 格、单独设置 {} 处",
                        dimKey.location(), defaultHelpRound, helpRound.size());
            });
        });

        // 【1.31】接收服务端同步的无障碍提示音**速率**（入口 / 出口两套 Hz，同一只包）
        ClientPlayNetworking.registerGlobalReceiver(HelpSpeedSyncPayload.TYPE, (payload, context) -> {
            final Map<BlockPos, Integer> blockIn = new HashMap<>(payload.helpSpeedIn());
            final Map<BlockPos, Integer> blockOut = new HashMap<>(payload.helpSpeedOut());
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(payload.dimension());
            } catch (Exception e) {
                return;
            }
            final int defaultIn = payload.defaultHelpSpeedIn();
            final int defaultOut = payload.defaultHelpSpeedOut();
            context.client().execute(() -> {
                EscalatorSpeedManager.applyClientHelpSpeeds(dimKey, defaultIn, blockIn, defaultOut, blockOut);
                LOGGER.info("[SmoothLift/HelpSpeed] 无障碍提示音速率已同步（{}）：默认 进入 {} 次/秒、离开 {} 次/秒，"
                                + "单独设置 {} / {} 处",
                        dimKey.location(), defaultIn, defaultOut, blockIn.size(), blockOut.size());
            });
        });

        // 【1.41】接收服务端同步的「默认提示音音乐 + 扶梯方块 → 提示音音乐」表
        //   （小包，进 / 出两套 —— 顺序同 buildHelpAudioPacket：默认in, 表in, 默认out, 表out）
        //   音频字节仍然来自 AUDIO_SYNC 那一份（同一个库），这里只发「选了哪一个」。
        ClientPlayNetworking.registerGlobalReceiver(HelpAudioSyncPayload.TYPE, (payload, context) -> {
            final Map<BlockPos, String> blockIn = new HashMap<>(payload.helpAudioIn());
            final Map<BlockPos, String> blockOut = new HashMap<>(payload.helpAudioOut());
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(payload.dimension());
            } catch (Exception e) {
                return;
            }
            final String defaultIn = payload.defaultHelpAudioIn();
            final String defaultOut = payload.defaultHelpAudioOut();
            context.client().execute(() -> {
                EscalatorSpeedManager.applyClientHelpAudio(dimKey, defaultIn, blockIn, defaultOut, blockOut);
                LOGGER.info("[SmoothLift/HelpAudio] 无障碍提示音已同步（{}）：默认 进入 {}、离开 {}，单独设置 {} / {} 处",
                        dimKey.location(), defaultIn, defaultOut, blockIn.size(), blockOut.size());
                HelpAudioSetupScreen.notifyDataChanged();
            });
        });

    }

    private record SyncEntry(double defaultSpeed, Map<BlockPos, Double> speeds, Map<BlockPos, Double> stepSpeeds,
                             boolean stepEnabled, double stepValue) {
    }
}
