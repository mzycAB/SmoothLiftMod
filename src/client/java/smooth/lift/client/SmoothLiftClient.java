package smooth.lift.client;

import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
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
import smooth.lift.SmoothLift;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SmoothLiftClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 【1.7】服务端音频同步分块拼接缓冲：维度ID → (块索引 → 数据)。 */
    private static final Map<String, Map<Integer, byte[]>> PENDING_SYNC_CHUNKS = new HashMap<>();

    /**
     * 【1.45】判定「直梯楼层轨道」（按注册名前缀，两端共用主类的判据，避免各写一份走样）。
     * 见 {@link smooth.lift.SmoothLift#isLiftTrackFloor}。
     */
    private static boolean isLiftTrackFloor(net.minecraft.world.level.block.state.BlockState state) {
        return smooth.lift.SmoothLift.isLiftTrackFloor(state);
    }

    @Override
    public void onInitializeClient() {
        // 逐条扶梯独立的阶梯动画：注册区块索引 + 世界渲染回调
        EscalatorStepRenderer.register();
        ClientTickEvents.END_CLIENT_TICK.register(EscalatorStepRenderer::onClientTick);

        // 【1.7】自定义扶梯声音播放器：每 tick 检查附近扶梯并调整音量
        ClientTickEvents.END_CLIENT_TICK.register(EscalatorAudioPlayer::onClientTick);

        // 【1.15】香港式扶梯视障人士提示音：进扶梯一端急促咔咔、出扶梯一端缓慢咔咔（【1.23】改为敲击声）
        ClientTickEvents.END_CLIENT_TICK.register(EscalatorChimePlayer::onClientTick);

        // 【1.42】直梯（MTR Lift）开关门提示音：关门连播 4 次 liftmusic.ogg、开门连播 2 次。
        // 与上面两个播放器互不影响：那两个只认「扶梯阶梯方块」，本播放器只认 MTR 的直梯对象。
        ClientTickEvents.END_CLIENT_TICK.register(LiftChimePlayer::onClientTick);

        // 【1.50】MTR 屏蔽门（平台幕门）开关门提示音：默认开门 → dooropen.ogg、关门 → mdoorclose.ogg
        // （【1.15】起三段内置音频，指令名分别是 default / default-c / default-m，
        //  另有 default-s =「默认（短）」（同一段素材但不播语音播报段）—— 见 PsdChimePlayer）。
        // 门值从哪里来：见 PsdDoorTracker（挂在两个 MTR 版本的屏蔽门渲染读口上，每帧每扇门回报一次）。
        // 只认「屏蔽门方块」的注册名，所以与上面三个播放器互不影响。
        ClientTickEvents.END_CLIENT_TICK.register(PsdChimePlayer::onClientTick);

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

        // 【1.45】拿着石斧右键**直梯楼层轨道** -> 打开直梯提示音三列表界面。
        //   「是楼层轨道」按注册名判（lift_track_floor_*），不依赖 MTR 编译期。
        //   直梯没有稳定 ID，所以把右键到的这格的「竖井列 key (X, Z)」传进界面，
        //   同一条直梯的所有楼层轨道共享同一个 key。
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!player.getMainHandItem().is(Items.STONE_AXE)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            if (!isLiftTrackFloor(world.getBlockState(pos))) {
                return InteractionResult.PASS;
            }
            Minecraft.getInstance().setScreen(new LiftToneSetupScreen(pos));
            return InteractionResult.FAIL;
        });

        // 【1.50】拿着石斧右键**屏蔽门** -> 打开开关门提示音界面（与直梯那份布局一致：开关 + 两个列表 + 音量）。
        //   「是屏蔽门」按注册名判（psd_door_* / apg_door_*），不依赖 MTR 编译期；
        //   玻璃 / 上半格被排除，保证「右键哪一格都是同一扇门」（key 与播放端同一个 anchorOf）。
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!player.getMainHandItem().is(Items.STONE_AXE)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            if (!SmoothLift.isPsdDoor(world.getBlockState(pos))) {
                return InteractionResult.PASS;
            }
            Minecraft.getInstance().setScreen(new PsdToneSetupScreen(pos));
            return InteractionResult.FAIL;
        });

        // 【1.57】拿着石斧右键**侧线铁轨的轨道节点**（`mtr:rail`）-> 打开「列车音效」界面。
        //   用户原话：「石斧右键侧线铁路轨道连接处（就是黄色的那个）打开 ui 功能，
        //   如果连接处同时连接两段轨道，就打开玩家面向的那个轨道的 ui」。
        //
        //   ★ 两层判据，都要过：
        //     ① 方块是 `mtr:rail`（{@link SmoothLift#isMtrRail}）—— 那里**同时看命名空间**，
        //        只比路径 "rail" 会把原版的 minecraft:rail 一起命中；
        //     ② 面向的那条轨道 `Rail.isSiding()` 为真、且能认到它属于哪条侧线
        //        （{@link MtrSidingAccess#facingSidingKey()}，「黄色 = isSiding」「面向的那段」
        //        「一条侧线 = 一段轨道」三件事都在那里的注释里反汇编核过）。
        //
        //   ★ 认不到侧线就**不开界面**（传 PASS，让原版行为照常）。退化成「按轨道自己的 hash 认」
        //     会让同一条侧线有两个身份、静默分桶（【1.28】踩过），宁可不打开。
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!player.getMainHandItem().is(Items.STONE_AXE)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            if (!SmoothLift.isMtrRail(world.getBlockState(pos))) {
                return InteractionResult.PASS;
            }
            long sidingKey = MtrSidingAccess.facingSidingKey();
            if (sidingKey == MtrSidingAccess.NO_SIDING) {
                return InteractionResult.PASS;
            }
            Minecraft.getInstance().setScreen(new TrainSoundScreen(sidingKey));
            return InteractionResult.FAIL;
        });

        // 客户端完全进世界后主动向服务端请求速度数据。
        // 服务端侧的 ServerPlayConnectionEvents.JOIN 推送发生在玩家连接建立过程中
        // （早于频道握手完成），此时发的包可能被客户端丢弃，导致进游戏后速度显示为默认。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientPlayNetworking.send(SmoothLift.REQUEST_SYNC_CHANNEL, PacketByteBufs.empty());
        });

        // 断开连接时清空客户端镜像，避免残留上一个世界的速度数据
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            EscalatorSpeedManager.clearClientData();
            PENDING_SYNC_CHUNKS.clear();
            EscalatorAudioPlayer.onDisconnect();
            EscalatorChimePlayer.onDisconnect();
            LiftChimePlayer.onDisconnect();
            PsdChimePlayer.onDisconnect();
            EscalatorAnimationDriver.clear();
            EscalatorStepRenderer.onDisconnect();
        });

        // 接收服务端同步的全部速度+阶梯动画数据
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            int dimCount = buf.readVarInt();
            final Map<ResourceKey<Level>, SyncEntry> parsed = new HashMap<>();
            for (int i = 0; i < dimCount; i++) {
                String dimId = buf.readUtf(256);
                double defaultSpeed = buf.readDouble();
                boolean stepEnabled = buf.readBoolean();
                double stepValue = buf.readDouble();
                int speedCount = buf.readVarInt();
                Map<BlockPos, Double> speeds = new HashMap<>();
                for (int j = 0; j < speedCount; j++) {
                    speeds.put(buf.readBlockPos(), buf.readDouble());
                }
                int stepCount = buf.readVarInt();
                Map<BlockPos, Double> stepSpeeds = new HashMap<>();
                for (int j = 0; j < stepCount; j++) {
                    stepSpeeds.put(buf.readBlockPos(), buf.readDouble());
                }
                try {
                    parsed.put(EscalatorSpeedManager.parseDimensionKey(dimId),
                            new SyncEntry(defaultSpeed, speeds, stepSpeeds, stepEnabled, stepValue));
                } catch (Exception ignored) {
                }
            }
            client.execute(() -> {
                for (Map.Entry<ResourceKey<Level>, SyncEntry> entry : parsed.entrySet()) {
                    EscalatorSpeedManager.applyClientData(entry.getKey(),
                            entry.getValue().defaultSpeed, entry.getValue().speeds, entry.getValue().stepSpeeds,
                            entry.getValue().stepEnabled, entry.getValue().stepValue);
                }
            });
        });

        // 【1.7】接收服务端分块同步的音频库与扶梯-音频绑定，全部块到齐后应用
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.AUDIO_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int totalChunks = buf.readVarInt();
            int chunkIndex = buf.readVarInt();
            byte[] chunk = buf.readByteArray();
            if (totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks) {
                return;
            }
            Map<Integer, byte[]> chunks = PENDING_SYNC_CHUNKS.computeIfAbsent(dimId, k -> new HashMap<>());
            // ★【1.19】一次重发 = 从 index 0 重新开始 ⇒ 见到 0 就把上一次的残留丢掉。
            //   为什么现在才需要：改版后**导入音频会立刻触发一次整库重发**（见
            //   SET_PSD_MIDIUM_CHANNEL / IMPORT_PSD_MIDIUM_AUDIO_CHANNEL 的 receiver），
            //   而包长是「几 MB 的字节 + 256 个/块」。上一次还没传完就再点一次导入，
            //   两批分块会在同一个 key 下拼起来 ⇒ 拼出的 payload 是垃圾（读序错位、格式崩），
            //   表现是「列表变成一堆乱码名字」或整表清空。TCP 保序，所以 0 号块先到，
            //   在 0 号块上清空即可把每一批重发都变回独立的一批。
            if (chunkIndex == 0) {
                chunks.clear();
            }
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
            client.execute(() -> {
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
                        blockAudio.size(), defaultAudio == null ? "" : defaultAudio);
                EscalatorAudioPlayer.onAudioReloaded();
                AudioSetupScreen.notifyAudioDataChanged();
                // 【1.17】屏蔽门界面的「到站播放音频」也列出这两张表（未导入 / 已导入），
                //   库里增删之后它得跟着刷新，否则刚导入的那条会一直挂在左边。
                PsdToneSetupScreen.notifyToneDataChanged();
                // 【1.57】列车音效的二级页列的也是这两张表（左列未导入 / 右列已导入）——
                //   同一个理由，库里增删之后它也得跟着刷新。
                TrainSoundScreen.notifyToneDataChanged();
            });
        });

        // 【1.9】接收服务端同步的「扶梯方块 → 声音音量」表（小包，不含音频字节）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.VOLUME_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int defaultVolume = buf.readVarInt();
            int count = buf.readVarInt();
            final Map<BlockPos, Integer> volumes = new HashMap<>();
            for (int i = 0; i < count; i++) {
                volumes.put(buf.readBlockPos(), buf.readVarInt());
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            final int defVol = defaultVolume;
            client.execute(() -> EscalatorSpeedManager.applyClientVolumes(dimKey, volumes, defVol));
        });

        // 【1.16】接收服务端同步的「扶梯方块 → 无障碍提示音开关」表（小包）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.HELP_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            boolean defaultHelp = buf.readBoolean();
            int count = buf.readVarInt();
            final Map<BlockPos, Boolean> help = new HashMap<>();
            for (int i = 0; i < count; i++) {
                help.put(buf.readBlockPos(), buf.readBoolean());
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientHelp(dimKey, defaultHelp, help);
                // 「开关关不掉」时先看这条日志：没打出来 = 同步包根本没到（装错 jar / 频道没注册）；
                // 打出来了但还响 = 去看 [SmoothLift/Chime] 那两条「已静音 / 恢复播放」。
                LOGGER.info("[SmoothLift/Help] 无障碍提示音开关已同步（{}）：默认 {}、单独设置 {} 处",
                        dimKey.location(), defaultHelp ? "开" : "关", help.size());
            });
        });

        // 【1.18】接收服务端同步的「扶梯方块 → 无障碍提示音音量」表（小包）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.HELP_VOLUME_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int defaultHelpVolume = buf.readVarInt();
            int count = buf.readVarInt();
            final Map<BlockPos, Integer> helpVolume = new HashMap<>();
            for (int i = 0; i < count; i++) {
                helpVolume.put(buf.readBlockPos(), buf.readVarInt());
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            final int defVolume = defaultHelpVolume;
            client.execute(() -> {
                EscalatorSpeedManager.applyClientHelpVolume(dimKey, defVolume, helpVolume);
                LOGGER.info("[SmoothLift/HelpVolume] 无障碍提示音音量已同步（{}）：默认 {}、单独设置 {} 处",
                        dimKey.location(), defVolume, helpVolume.size());
            });
        });

        // 【1.24】接收服务端同步的「扶梯方块 → 运行底噪可闻范围（格）」表（小包）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.ROUND_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int defaultRound = buf.readVarInt();
            int count = buf.readVarInt();
            final Map<BlockPos, Integer> round = new HashMap<>();
            for (int i = 0; i < count; i++) {
                round.put(buf.readBlockPos(), buf.readVarInt());
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientRounds(dimKey, defaultRound, round);
                LOGGER.info("[SmoothLift/Round] 扶梯音效淡入淡出范围已同步（{}）：默认 {} 格、单独设置 {} 处",
                        dimKey.location(), defaultRound, round.size());
            });
        });

        // 【1.24】接收服务端同步的「扶梯方块 → 无障碍提示音可闻范围（格）」表（小包）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.HELP_ROUND_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int defaultHelpRound = buf.readVarInt();
            int count = buf.readVarInt();
            final Map<BlockPos, Integer> helpRound = new HashMap<>();
            for (int i = 0; i < count; i++) {
                helpRound.put(buf.readBlockPos(), buf.readVarInt());
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientHelpRounds(dimKey, defaultHelpRound, helpRound);
                LOGGER.info("[SmoothLift/HelpRound] 无障碍提示音淡入淡出范围已同步（{}）：默认 {} 格、单独设置 {} 处",
                        dimKey.location(), defaultHelpRound, helpRound.size());
            });
        });

        // 【1.31】接收服务端同步的无障碍提示音**速率**（入口 / 出口两套 Hz，同一只包）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.HELP_SPEED_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int defaultIn = buf.readVarInt();
            int countIn = buf.readVarInt();
            final Map<BlockPos, Integer> blockIn = new HashMap<>();
            for (int i = 0; i < countIn; i++) {
                blockIn.put(buf.readBlockPos(), buf.readVarInt());
            }
            int defaultOut = buf.readVarInt();
            int countOut = buf.readVarInt();
            final Map<BlockPos, Integer> blockOut = new HashMap<>();
            for (int i = 0; i < countOut; i++) {
                blockOut.put(buf.readBlockPos(), buf.readVarInt());
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientHelpSpeeds(dimKey, defaultIn, blockIn, defaultOut, blockOut);
                LOGGER.info("[SmoothLift/HelpSpeed] 无障碍提示音速率已同步（{}）：默认 进入 {} 次/秒、离开 {} 次/秒，"
                                + "单独设置 {} / {} 处",
                        dimKey.location(), defaultIn, defaultOut, blockIn.size(), blockOut.size());
            });
        });

        // 【1.41】接收服务端同步的「默认提示音音乐 + 扶梯方块 → 提示音音乐」表
        //   （小包，进 / 出两套 —— 顺序同 buildHelpAudioPacket：默认in, 表in, 默认out, 表out）
        //   音频字节仍然来自 AUDIO_SYNC 那一份（同一个库），这里只发「选了哪一个」。
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.HELP_AUDIO_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            String defaultIn = buf.readUtf(128);
            int countIn = buf.readVarInt();
            final Map<BlockPos, String> blockIn = new HashMap<>();
            for (int i = 0; i < countIn; i++) {
                blockIn.put(buf.readBlockPos(), buf.readUtf(128));
            }
            String defaultOut = buf.readUtf(128);
            int countOut = buf.readVarInt();
            final Map<BlockPos, String> blockOut = new HashMap<>();
            for (int i = 0; i < countOut; i++) {
                blockOut.put(buf.readBlockPos(), buf.readUtf(128));
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientHelpAudio(dimKey, defaultIn, blockIn, defaultOut, blockOut);
                LOGGER.info("[SmoothLift/HelpAudio] 无障碍提示音已同步（{}）：默认 进入 {}、离开 {}，单独设置 {} / {} 处",
                        dimKey.location(), defaultIn, defaultOut, blockIn.size(), blockOut.size());
                HelpAudioSetupScreen.notifyDataChanged();
            });
        });

        // 【1.42】接收服务端同步的**直梯开关门提示音**设置（开关 + 倍速 + 【1.43】音量 + 【1.46】三子开关 + 【1.47】范围，按维度；最小的包）
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.LIFT_CHIME_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            boolean enabled = buf.readBoolean();
            float speed = buf.readFloat();
            // 【1.43】音量 —— 读的顺序必须与 EscalatorSpeedManager.buildLiftChimePacket 的写序一致
            int volume = buf.readVarInt();
            // 【1.46】三提示音独立子开关（追加在音量后面，顺序与写侧一致：up → down → chime）
            boolean upEnabled = buf.readBoolean();
            boolean downEnabled = buf.readBoolean();
            boolean chimeEnabled = buf.readBoolean();
            // 【1.47】淡入淡出范围（包尾追加）
            int round = buf.readVarInt();
            // 【1.48】三项各自音量（-1 = 跟随共用默认）
            int toneVolumeUp = buf.readVarInt();
            int toneVolumeDown = buf.readVarInt();
            int toneVolumeChime = buf.readVarInt();
            // 【1.15】三项的维度默认素材（default / off / 音频库文件名）—— 读序同 buildLiftChimePacket
            String toneAudioUp = buf.readUtf(128);
            String toneAudioDown = buf.readUtf(128);
            String toneAudioChime = buf.readUtf(128);
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientLiftChime(dimKey, enabled, speed, volume,
                        upEnabled, downEnabled, chimeEnabled, round,
                        toneVolumeUp, toneVolumeDown, toneVolumeChime,
                        toneAudioUp, toneAudioDown, toneAudioChime);
                LOGGER.info("[SmoothLift/LiftChime] 直梯提示音设置已同步（{}）：{}、倍速 {}、音量 {}、"
                                + "子开关 up={} down={} chime={}、范围 {} 格、单项音量 up={} down={} chime={}、"
                                + "默认素材 up={} down={} chime={}",
                        dimKey.location(), enabled ? "开" : "关", speed, volume,
                        upEnabled, downEnabled, chimeEnabled, round,
                        toneVolumeUp, toneVolumeDown, toneVolumeChime,
                        toneAudioUp, toneAudioDown, toneAudioChime);
            });
        });

        // 【1.45】接收服务端同步的**直梯楼层轨道提示音**（竖井列 → up/down/chime 三音频 id）。
        //   播放端（LiftChimePlayer）按「最近直梯的竖井列」查这份镜像；打开石斧界面时也要读它。
        //   ★ 顺序同 buildLiftTonePacket：dimId → 条数 → (key, up, down, chime) × N。
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.LIFT_TONE_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int n = buf.readVarInt();
            final Map<Long, EscalatorSpeedData.LiftToneAudio> tones = new HashMap<>();
            for (int i = 0; i < n; i++) {
                long key = buf.readLong();
                String up = buf.readUtf(128);
                String down = buf.readUtf(128);
                String chime = buf.readUtf(128);
                tones.put(key, new EscalatorSpeedData.LiftToneAudio(up, down, chime));
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientLiftTone(dimKey, tones);
                int count = tones.size();
                LOGGER.info("[SmoothLift/LiftChime] 直梯楼层轨道提示音已同步（{}）：{} 条",
                        dimKey.location(), count);
                LiftToneSetupScreen.notifyToneDataChanged();
            });
        });

        // 【1.50】接收服务端同步的**屏蔽门开关门提示音**设置（按维度；最小的包）。
        //   ★ 读序必须与 EscalatorSpeedManager.buildPsdChimePacket 的写序严格一致：
        //     dimId → 总开关 → 共用默认音量 → open子开关 → close子开关 → 范围
        //     → open单独音量 → close单独音量 → 【1.15】open默认素材 → close默认素材
        //     → 【1.16】关门提示音强制等待时长（秒）
        //     → 【1.17】到站播报素材 id → 到站播报等待秒数
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.PSD_CHIME_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            boolean enabled = buf.readBoolean();
            int volume = buf.readVarInt();
            boolean openEnabled = buf.readBoolean();
            boolean closeEnabled = buf.readBoolean();
            int round = buf.readVarInt();
            int toneVolumeOpen = buf.readVarInt();
            int toneVolumeClose = buf.readVarInt();
            // 【1.15】两项的维度默认素材（末尾追加，写侧同序）
            String toneAudioOpen = buf.readUtf(128);
            String toneAudioClose = buf.readUtf(128);
            // 【1.16】关门提示音的强制等待时长（秒，末尾再追加一格，写侧同序）
            int closeWaitSeconds = buf.readVarInt();
            // 【1.17】到站播报：素材 id + 等待秒数（末尾再追加两格，写侧同序）
            String midiumAudio = buf.readUtf(128);
            int midiumWaitSeconds = buf.readVarInt();
            // 【1.21】进站报站：素材 id + 秒数（末尾再追加两格，写侧同序）
            String arriveAudio = buf.readUtf(128);
            int arriveSeconds = buf.readVarInt();
            // 【1.22】到站 / 进站播报各自那一项的音量（末尾再追加两格，写侧同序）
            int midiumVolume = buf.readVarInt();
            int arriveVolume = buf.readVarInt();
            // 【1.23】到站 / 进站播报各自的**可闻范围**（末尾再追加两格，写侧同序）
            int midiumRound = buf.readVarInt();
            int arriveRound = buf.readVarInt();
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientPsdChime(dimKey, enabled, volume,
                        openEnabled, closeEnabled, round, toneVolumeOpen, toneVolumeClose,
                        toneAudioOpen, toneAudioClose, closeWaitSeconds,
                        midiumAudio, midiumWaitSeconds, arriveAudio, arriveSeconds,
                        midiumVolume, arriveVolume, midiumRound, arriveRound);
                LOGGER.info("[SmoothLift/PsdChime] 屏蔽门提示音设置已同步（{}）：{}、音量 {}、"
                                + "子开关 open={} close={}、范围 提示音 {} 格 / 到站 {} 格 / 进站 {} 格、"
                                + "单项音量 open={} close={} 到站={} 进站={}、"
                                + "默认素材 open={} close={}、关门强制等待 {} 秒、到站播报 {}（等待 {} 秒）、"
                                + "进站报站 {}（提前 {} 秒）",
                        dimKey.location(), enabled ? "开" : "关", volume,
                        openEnabled, closeEnabled, round, midiumRound, arriveRound,
                        toneVolumeOpen, toneVolumeClose, midiumVolume, arriveVolume,
                        toneAudioOpen, toneAudioClose, closeWaitSeconds,
                        midiumAudio, midiumWaitSeconds, arriveAudio, arriveSeconds);
            });
        });

        // 【1.50】接收服务端同步的**每扇屏蔽门单独素材**（门锚点 → open/close 两音频 id）。
        //   播放端（PsdChimePlayer）按「最近那扇门的锚点」查这份镜像；打开石斧界面时也读它。
        //   ★ 顺序同 buildPsdTonePacket：dimId → 条数 → (key, open, close) × N。
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.PSD_TONE_SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            String dimId = buf.readUtf(256);
            int n = buf.readVarInt();
            final Map<Long, EscalatorSpeedData.PsdToneAudio> tones = new HashMap<>();
            for (int i = 0; i < n; i++) {
                long key = buf.readLong();
                String open = buf.readUtf(128);
                String close = buf.readUtf(128);
                // 【1.20】这一扇门的其余覆盖项 —— ★ 读序必须与 buildPsdTonePacket 的写序一致。
                //   先读进局部变量再构造：参数求值顺序虽然也保证是从左到右，但读写成对这种东西
                //   写成一串嵌套调用以后没人看得出来哪一行对哪一行。
                Boolean help = EscalatorSpeedManager.readDoorOptBool(buf);
                Boolean openEnabled = EscalatorSpeedManager.readDoorOptBool(buf);
                Boolean closeEnabled = EscalatorSpeedManager.readDoorOptBool(buf);
                Integer volume = EscalatorSpeedManager.readDoorOptInt(buf);
                Integer openVolume = EscalatorSpeedManager.readDoorOptInt(buf);
                Integer closeVolume = EscalatorSpeedManager.readDoorOptInt(buf);
                Integer openWaitSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
                Integer closeWaitSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
                String midium = EscalatorSpeedManager.readDoorOptString(buf);
                Integer midiumWaitSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
                // 【1.21】进站报站（读序同 buildPsdTonePacket 写序）
                String arrive = EscalatorSpeedManager.readDoorOptString(buf);
                Integer arriveSeconds = EscalatorSpeedManager.readDoorOptInt(buf);
                // 【1.22】到站 / 进站各自那一项的音量（读序同 buildPsdTonePacket 写序）
                Integer midiumVolume = EscalatorSpeedManager.readDoorOptInt(buf);
                Integer arriveVolume = EscalatorSpeedManager.readDoorOptInt(buf);
                tones.put(key, new EscalatorSpeedData.PsdToneAudio(open, close,
                        help, openEnabled, closeEnabled,
                        volume, openVolume, closeVolume, openWaitSeconds, closeWaitSeconds,
                        midium, midiumWaitSeconds, midiumVolume,
                        arrive, arriveSeconds, arriveVolume));
            }
            final ResourceKey<Level> dimKey;
            try {
                dimKey = EscalatorSpeedManager.parseDimensionKey(dimId);
            } catch (Exception e) {
                return;
            }
            client.execute(() -> {
                EscalatorSpeedManager.applyClientPsdTone(dimKey, tones);
                LOGGER.info("[SmoothLift/PsdChime] 屏蔽门单独素材已同步（{}）：{} 条",
                        dimKey.location(), tones.size());
                PsdToneSetupScreen.notifyToneDataChanged();
            });
        });

        // 【1.53】服务端让我们打开「预设选择」界面（`/MBM help` / `/MBM` 的执行端在服务端，
        //   界面在客户端，所以要走这一只空包）。
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.MBM_HELP_OPEN_CHANNEL,
                (client, handler, buf, responseSender) -> client.execute(
                        () -> Minecraft.getInstance().setScreen(new MbmHelpScreen())));

    }

    private record SyncEntry(double defaultSpeed, Map<BlockPos, Double> speeds, Map<BlockPos, Double> stepSpeeds,
                             boolean stepEnabled, double stepValue) {
    }
}
