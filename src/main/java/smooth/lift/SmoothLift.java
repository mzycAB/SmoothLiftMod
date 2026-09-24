package smooth.lift;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class SmoothLift implements ModInitializer {

    /** 客户端 -> 服务端：请求设置某个扶梯的【运行】速度。 */
    public static final ResourceLocation SET_SPEED_CHANNEL = new ResourceLocation("smoothlift", "set_speed");
    /** 客户端 -> 服务端：石斧界面按 ESC 退出时，一次性应用「扶梯速度 + 阶梯速度」的改动。 */
    public static final ResourceLocation APPLY_CHAIN_CHANNEL = new ResourceLocation("smoothlift", "apply_chain");
    /** 客户端 -> 服务端：请求设置某条扶梯的【阶梯动画】速度。 */
    public static final ResourceLocation SET_STEP_SPEED_CHANNEL = new ResourceLocation("smoothlift", "set_step_speed");
    /** 客户端 -> 服务端：请求把某条扶梯的阶梯动画对齐到它的运行速度。 */
    public static final ResourceLocation ALIGN_STEP_CHANNEL = new ResourceLocation("smoothlift", "align_step");
    /** 客户端 -> 服务端：请求把某条扶梯的阶梯动画恢复为 MTR 原版默认。 */
    public static final ResourceLocation RESTORE_STEP_CHANNEL = new ResourceLocation("smoothlift", "restore_step");
    /** 服务端 -> 客户端：同步全部速度与阶梯动画数据。 */
    public static final ResourceLocation SYNC_CHANNEL = new ResourceLocation("smoothlift", "sync");
    /** 客户端 -> 服务端：客户端进世界后主动请求同步（JOIN 时序下服务端推送不可靠）。 */
    public static final ResourceLocation REQUEST_SYNC_CHANNEL = new ResourceLocation("smoothlift", "request_sync");

    // 【1.7】自定义扶梯声音
    /** 客户端 -> 服务端：分块上传一段 OGG 音频（音频ID = 文件名，同名覆盖、可复用）。 */
    public static final ResourceLocation UPLOAD_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "upload_audio");
    /** 客户端 -> 服务端：把音频绑定到某条扶梯。 */
    public static final ResourceLocation BIND_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "bind_audio");
    /** 客户端 -> 服务端：解绑某条扶梯的音频（之后静音）。 */
    public static final ResourceLocation UNBIND_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "unbind_audio");
    /** 客户端 -> 服务端：从存档删除一段音频（同时解绑所有引用它的扶梯）。 */
    public static final ResourceLocation DELETE_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "delete_audio");
    /** 客户端 -> 服务端：把 <存档>/smoothlift_audio 里的一个 OGG 文件导入存档并绑定到扶梯。 */
    public static final ResourceLocation IMPORT_FOLDER_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "import_folder_audio");
    /** 服务端 -> 客户端：分块同步音频库与扶梯-音频绑定。 */
    public static final ResourceLocation AUDIO_SYNC_CHANNEL = new ResourceLocation("smoothlift", "audio_sync");
    /** 【1.9】客户端 -> 服务端：设置某条扶梯的声音音量（1~100）。 */
    public static final ResourceLocation SET_VOLUME_CHANNEL = new ResourceLocation("smoothlift", "set_volume");
    /** 【1.9】服务端 -> 客户端：同步「扶梯方块 → 声音音量」表（小包，不含音频字节）。 */
    public static final ResourceLocation VOLUME_SYNC_CHANNEL = new ResourceLocation("smoothlift", "volume_sync");
    /** 【1.16】客户端 -> 服务端：开关某条扶梯的无障碍提示音（石斧界面按钮）。 */
    public static final ResourceLocation SET_HELP_CHANNEL = new ResourceLocation("smoothlift", "set_help");
    /** 【1.16】服务端 -> 客户端：同步「扶梯方块 → 无障碍提示音开关」表（小包）。 */
    public static final ResourceLocation HELP_SYNC_CHANNEL = new ResourceLocation("smoothlift", "help_sync");
    /** 【1.18】客户端 -> 服务端：设置某条扶梯的无障碍提示音音量（石斧界面里的输入框）。 */
    public static final ResourceLocation SET_HELP_VOLUME_CHANNEL = new ResourceLocation("smoothlift", "set_help_volume");
    /** 【1.18】服务端 -> 客户端：同步「扶梯方块 → 无障碍提示音音量」表（小包，不含音频字节）。 */
    public static final ResourceLocation HELP_VOLUME_SYNC_CHANNEL = new ResourceLocation("smoothlift", "help_volume_sync");
    /** 【1.24】服务端 -> 客户端：同步「扶梯方块 → 运行底噪可闻范围（格）」表（小包）。 */
    public static final ResourceLocation ROUND_SYNC_CHANNEL = new ResourceLocation("smoothlift", "round_sync");
    /** 【1.24】服务端 -> 客户端：同步「扶梯方块 → 无障碍提示音可闻范围（格）」表（小包）。 */
    public static final ResourceLocation HELP_ROUND_SYNC_CHANNEL = new ResourceLocation("smoothlift", "help_round_sync");
    /** 【1.31】服务端 -> 客户端：同步无障碍提示音**速率**（入口 / 出口两套 Hz，同一只包）。 */
    public static final ResourceLocation HELP_SPEED_SYNC_CHANNEL = new ResourceLocation("smoothlift", "help_speed_sync");
    /**
     * 【1.39】客户端 -> 服务端：把一段音频设为某条扶梯的**无障碍提示音音乐**。
     *
     * <p>与 {@link #BIND_AUDIO_CHANNEL}（运行底噪）分开两只包、两套数据：
     * 用的是同一个导入文件夹和同一份音频库，但「哪段声音当提示音」是另一件事。
     */
    public static final ResourceLocation BIND_HELP_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "bind_help_audio");
    /** 【1.39】客户端 -> 服务端：清掉某条扶梯的提示音音乐单独设置（回到维度默认）。 */
    public static final ResourceLocation UNBIND_HELP_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "unbind_help_audio");
    /** 【1.39】客户端 -> 服务端：把 smoothlift_audio 文件夹里的一个 OGG 导入存档并设为提示音音乐。 */
    public static final ResourceLocation IMPORT_FOLDER_HELP_AUDIO_CHANNEL = new ResourceLocation("smoothlift", "import_folder_help_audio");
    /** 【1.39】服务端 -> 客户端：同步「默认提示音音乐 + 扶梯方块 → 提示音音乐」（小包）。 */
    public static final ResourceLocation HELP_AUDIO_SYNC_CHANNEL = new ResourceLocation("smoothlift", "help_audio_sync");
    /**
     * 【1.42】服务端 -> 客户端：同步**直梯（Lift）开关门提示音**（开关 + 倍速，按维度）。
     *
     * <p>这是「MTR 直梯关门连播 4 次 / 开门连播 2 次 liftmusic.ogg」那套设置的镜像通道，
     * 与上面所有扶梯提示音频道**都是独立的两件事**（数据、指令、播放器都不同）。
     * 包体最小：只有 {@code 维度ID + 开关 + 倍速} 三个值，没有按方块索引的表。
     */
    public static final ResourceLocation LIFT_CHIME_SYNC_CHANNEL = new ResourceLocation("smoothlift", "lift_chime_sync");

    /** 【1.45】客户端 -> 服务端：设置某条直梯的某一项提示音（up / down / chime）。 */
    public static final ResourceLocation SET_LIFT_TONE_CHANNEL = new ResourceLocation("smoothlift", "set_lift_tone");
    /** 【1.48】客户端 -> 服务端：设置**共用默认音量**（石斧 UI 主界面输入框 = /lifthelploud <音量>）。 */
    public static final ResourceLocation SET_LIFT_CHIME_VOLUME_CHANNEL = new ResourceLocation("smoothlift", "set_lift_chime_volume");
    /** 【1.48】客户端 -> 服务端：设置某一项（up/down/chime）的**单项音量**（石斧 UI 列表输入框 = /lifthelploud up|down|door）。 */
    public static final ResourceLocation SET_LIFT_TONE_VOLUME_CHANNEL = new ResourceLocation("smoothlift", "set_lift_tone_volume");
    /** 【1.46】客户端 -> 服务端：设置某类提示音（up/down/chime）的**维度默认子开关**（石斧 UI 开关）。 */
    public static final ResourceLocation SET_LIFT_TONE_SWITCH_CHANNEL = new ResourceLocation("smoothlift", "set_lift_tone_switch");
    /** 【1.45】客户端 -> 服务端：把 smoothlift_audio 里的一个 OGG 导入并存为某条直梯的某一项提示音。 */
    public static final ResourceLocation IMPORT_FOLDER_LIFT_TONE_CHANNEL = new ResourceLocation("smoothlift", "import_folder_lift_tone");
    /** 【1.45】服务端 -> 客户端：同步「竖井列 → 直梯提示音」表（小包，不含音频字节）。 */
    public static final ResourceLocation LIFT_TONE_SYNC_CHANNEL = new ResourceLocation("smoothlift", "lift_tone_sync");

    // ------------------------------------------------------------------
    // 【1.50】列车屏蔽门（PSD / APG）开关门提示音的频道
    //   与直梯那一组（LIFT_*）**一一对应**，只是「项」从 up/down/chime 变成 open/close，
    //   并且多了「每扇门单独素材」那一张表（直梯没有方块粒度）。
    // ------------------------------------------------------------------

    /** 【1.50】服务端 -> 客户端：同步屏蔽门提示音设置（总开关 + 音量 + 两项子开关 + 范围，按维度）。 */
    public static final ResourceLocation PSD_CHIME_SYNC_CHANNEL = new ResourceLocation("smoothlift", "psd_chime_sync");
    /** 【1.50】服务端 -> 客户端：同步「门锚点 → 屏蔽门提示音」表（小包，不含音频字节）。 */
    public static final ResourceLocation PSD_TONE_SYNC_CHANNEL = new ResourceLocation("smoothlift", "psd_tone_sync");
    /** 【1.50】客户端 -> 服务端：设置某一扇门的一项提示音（open / close）。 */
    public static final ResourceLocation SET_PSD_TONE_CHANNEL = new ResourceLocation("smoothlift", "set_psd_tone");
    /** 【1.50】客户端 -> 服务端：设置屏蔽门提示音的开关（which = master / open / close）。 */
    public static final ResourceLocation SET_PSD_TONE_SWITCH_CHANNEL = new ResourceLocation("smoothlift", "set_psd_tone_switch");
    /** 【1.50】客户端 -> 服务端：设置屏蔽门提示音的**共用默认音量**（石斧 UI 主界面输入框 = /pbmloud <音量>）。 */
    public static final ResourceLocation SET_PSD_CHIME_VOLUME_CHANNEL = new ResourceLocation("smoothlift", "set_psd_chime_volume");
    /** 【1.50】客户端 -> 服务端：设置某一项（open/close）的**单项音量**（石斧 UI 列表输入框 = /pbmloud open|close <音量>）。 */
    public static final ResourceLocation SET_PSD_TONE_VOLUME_CHANNEL = new ResourceLocation("smoothlift", "set_psd_tone_volume");
    /** 【1.50】客户端 -> 服务端：把 smoothlift_audio 里的一个 OGG 导入并存为某一扇门的一项提示音。 */
    public static final ResourceLocation IMPORT_FOLDER_PSD_TONE_CHANNEL = new ResourceLocation("smoothlift", "import_folder_psd_tone");
    /**
     * 【1.16】客户端 -> 服务端：设置**关门提示音的强制等待时长**
     * （石斧 UI 主界面「关门提示音强制等待时长」输入框 = {@code /pbmclosewait <秒>}）。
     */
    public static final ResourceLocation SET_PSD_CLOSE_WAIT_CHANNEL = new ResourceLocation("smoothlift", "set_psd_close_wait");
    /**
     * 【1.17】客户端 -> 服务端：设置**到站播报**
     * （石斧 UI 主界面「到站播放音频」+「等待几秒后播放」输入框 = {@code /pbmmidium <名字> <秒>}）。
     *
     * <p>buf 顺序：{@code name(utf128) → seconds(varInt)}。
     */
    public static final ResourceLocation SET_PSD_MIDIUM_CHANNEL = new ResourceLocation("smoothlift", "set_psd_midium");
    /**
     * 【1.19】客户端 -> 服务端：把 {@code smoothlift_audio} 文件夹里的一段 OGG **只导入存档音频库、
     * 不改变任何设置**（石斧 UI 到站播报页左列「点一下」= 导入）。
     *
     * <p>为什么与 {@link #SET_PSD_MIDIUM_CHANNEL} 分成两条：用户点名「导入的**不直接选用**，
     * 要在导入的音频的右侧加 2 个按钮，一个是选用，一个是删除」—— 导入与选用从此是两件事，
     * 「点一下左列」只负责把素材搬进存档。
     *
     * <p>buf 顺序：{@code name(utf128)}。
     */
    public static final ResourceLocation IMPORT_PSD_MIDIUM_AUDIO_CHANNEL =
            new ResourceLocation("smoothlift", "import_psd_midium_audio");
    /**
     * 【1.21】客户端 -> 服务端：设置**进站报站**
     * （石斧 UI 主界面「进站播放音频」+「到站前秒数」输入框
     * = {@code /pbmarrive <名字> <X>}）。
     *
     * <p>buf 顺序：{@code key(long) → name(utf128) → seconds(varInt)}，与
     * {@link #SET_PSD_MIDIUM_CHANNEL} 同形（{@code seconds} 是**负**的秒数：
     * {@code -10} = 最近一班车还剩 10 秒到站时起播）。
     */
    public static final ResourceLocation SET_PSD_ARRIVE_CHANNEL =
            new ResourceLocation("smoothlift", "set_psd_arrive");
    /**
     * 【1.22】客户端 -> 服务端：设置**到站播报自己那一项**的音量
     * （石斧 UI 主界面「到站播放音频」右边那个「音量:」输入框
     * = {@code /pbmmidiumloud <音量>}）。
     *
     * <p>buf 顺序：{@code key(long) → volume(varInt)}，与
     * {@link #SET_PSD_CHIME_VOLUME_CHANNEL} 同形。
     */
    public static final ResourceLocation SET_PSD_MIDIUM_LOUD_CHANNEL =
            new ResourceLocation("smoothlift", "set_psd_midium_loud");
    /**
     * 【1.22】客户端 -> 服务端：设置**进站报站自己那一项**的音量
     * （= {@code /pbmarriveloud <音量>}）。buf 同上。
     */
    public static final ResourceLocation SET_PSD_ARRIVE_LOUD_CHANNEL =
            new ResourceLocation("smoothlift", "set_psd_arrive_loud");

    @Override
    public void onInitialize() {
        System.out.println("[SmoothLift] Loaded");

        // 指令树全部在 registerCommands 里注册（抽出来是为了能脱离游戏环境做指令树校验）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));

        // 服务端兜底：拿着石斧右键扶梯时取消原版交互（正常情况下客户端已拦截，不会发包）
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            // 【1.30】右键 = 可能在放 / 延长扶梯。MTR 是自己在 ItemEscalator#useOnBlock 里
            // setBlockState 的，不触发原版的放置流程；这里只记下点了哪一格，
            // 下一个服务端刻（见下面的 END_SERVER_TICK）再去看实际落了哪些方块、
            // 把整条链的单独设置补齐 —— 否则「敲掉几格再放回去」之后，新放的那半段
            // 会留在全局速度上、和另一半对不上（症状：左右两半阶梯动画速度不一致）。
            if (isEscalatorItem(player.getMainHandItem())
                    || EscalatorUtil.isEscalator(world.getBlockState(hitResult.getBlockPos()))) {
                EscalatorSpeedManager.scheduleChainReconcile(world, hitResult.getBlockPos());
            }
            if (player.getMainHandItem().is(Items.STONE_AXE)
                    && EscalatorUtil.isEscalator(world.getBlockState(hitResult.getBlockPos()))) {
                return InteractionResult.FAIL;
            }
            // 【1.50】石斧右键屏蔽门 = 打开提示音设置界面（在客户端），这里同样拦掉默认交互。
            if (player.getMainHandItem().is(Items.STONE_AXE)
                    && isPsdDoor(world.getBlockState(hitResult.getBlockPos()))) {
                return InteractionResult.FAIL;
            }
            // 【1.45】石斧右键直梯楼层轨道 = 打开提示音设置界面（在客户端），这里同样拦掉默认交互。
            if (player.getMainHandItem().is(Items.STONE_AXE)
                    && isLiftTrackFloor(world.getBlockState(hitResult.getBlockPos()))) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // 【1.30】服务端刻收尾：把上一刻记下的「刚放/延长了扶梯」的位置重扫一遍。
        ServerTickEvents.END_SERVER_TICK.register(EscalatorSpeedManager::tickPendingReconcile);

        // 石斧设置界面退出（ESC）时发来的合并包：
        // setRun=true  -> 设运行速度，并让阶梯速度跟随运行速度；
        // setStep=true -> 只设阶梯速度，运行速度不动。
        ServerPlayNetworking.registerGlobalReceiver(APPLY_CHAIN_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            boolean setRun = buf.readBoolean();
            double run = buf.readDouble();
            boolean setStep = buf.readBoolean();
            double step = buf.readDouble();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int count = EscalatorSpeedManager.applyChain(level, pos, setRun, run, setStep, step);
                if (count > 0) {
                    StringBuilder message = new StringBuilder("已更新这条扶梯");
                    if (setRun) {
                        message.append("：扶梯速度 ").append(EscalatorSpeedData.format(run));
                    }
                    if (setStep) {
                        message.append(setRun ? "，" : "：")
                                .append("阶梯速度 ").append(EscalatorSpeedData.format(step));
                    }
                    final String text = message.toString();
                    player.displayClientMessage(Component.literal(text), true);
                    EscalatorSpeedManager.syncToAll(server);
                }
            });
        });

        // 客户端输入界面发来的设置请求
        ServerPlayNetworking.registerGlobalReceiver(SET_SPEED_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            double speed = buf.readDouble();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int count = EscalatorSpeedManager.setSpeed(level, pos, speed);
                if (count > 0) {
                    double applied = EscalatorSpeedManager.getSpeed(level, pos);
                    player.displayClientMessage(
                        Component.literal("已设置 " + count + " 个扶梯方块的速度为 " + EscalatorSpeedData.format(applied) + " 格/秒"),
                        true
                    );
                    EscalatorSpeedManager.syncToAll(server);
                }
            });
        });

        // 客户端输入界面发来的【阶梯动画】设置/对齐/恢复默认请求
        ServerPlayNetworking.registerGlobalReceiver(SET_STEP_SPEED_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            double step = buf.readDouble();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int count = EscalatorSpeedManager.setStepSpeed(level, pos, step);
                if (count > 0) {
                    player.displayClientMessage(
                        Component.literal("已把这条扶梯的阶梯动画速度设为 "
                                + EscalatorSpeedData.format(step) + " 格/秒"),
                        true
                    );
                    EscalatorSpeedManager.syncToAll(server);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(ALIGN_STEP_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int count = EscalatorSpeedManager.alignStepToRunning(level, pos);
                if (count > 0) {
                    player.displayClientMessage(
                        Component.literal("已把这条扶梯的阶梯动画对齐到运行速度"),
                        true
                    );
                    EscalatorSpeedManager.syncToAll(server);
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(RESTORE_STEP_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int count = EscalatorSpeedManager.clearStepSpeed(level, pos);
                if (count > 0) {
                    player.displayClientMessage(
                        Component.literal("已清除这条扶梯的单独阶梯动画设置，改为跟随维度默认"),
                        true
                    );
                    EscalatorSpeedManager.syncToAll(server);
                }
            });
        });

        // 客户端分块上传自定义音频。全部块到齐后入库并广播音频同步。
        ServerPlayNetworking.registerGlobalReceiver(UPLOAD_AUDIO_CHANNEL, (server, player, handler, buf, responseSender) -> {
            String audioId = buf.readUtf(128);
            int totalChunks = buf.readVarInt();
            int chunkIndex = buf.readVarInt();
            byte[] chunk = buf.readByteArray();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                byte[] complete = EscalatorSpeedManager.handleAudioUploadChunk(audioId, totalChunks, chunkIndex, chunk);
                if (complete == null) {
                    return;
                }
                if (!EscalatorSpeedManager.storeAudio(level, audioId, complete)) {
                    player.displayClientMessage(Component.literal(
                            "音频上传失败：文件过大或不是 MC 能播的 Ogg Vorbis"), true);
                    return;
                }
                player.displayClientMessage(Component.literal("音频已上传并存入存档"), true);
                EscalatorSpeedManager.syncAudioToAll(server);
            });
        });

        // 客户端把音频绑定到扶梯
        ServerPlayNetworking.registerGlobalReceiver(BIND_AUDIO_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String audioId = buf.readUtf(128);
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                if (EscalatorSpeedManager.bindAudio(level, pos, audioId)) {
                    player.displayClientMessage(Component.literal("已为这条扶梯绑定自定义声音"), true);
                    EscalatorSpeedManager.syncAudioToAll(server);
                } else {
                    player.displayClientMessage(Component.literal("绑定失败：音频不存在"), true);
                }
            });
        });

        // 客户端解绑扶梯音频（之后静音）
        ServerPlayNetworking.registerGlobalReceiver(UNBIND_AUDIO_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (EscalatorSpeedManager.unbindAudio(level, pos)) {
                    player.displayClientMessage(Component.literal("已解除这条扶梯的自定义声音"), true);
                    EscalatorSpeedManager.syncAudioToAll(server);
                }
            });
        });

        // 客户端从存档删除一段音频（同时解绑所有引用它的扶梯，并向所有玩家重发同步）
        ServerPlayNetworking.registerGlobalReceiver(DELETE_AUDIO_CHANNEL, (server, player, handler, buf, responseSender) -> {
            String audioId = buf.readUtf(128);
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (EscalatorSpeedManager.deleteAudio(level, audioId)) {
                    player.displayClientMessage(Component.literal("已从存档删除音频"), true);
                    EscalatorSpeedManager.syncAudioToAll(server);
                    // 【1.39】提示音那边也可能引用过这一段（共用同一个库），单独设置也要一起刷新
                    EscalatorSpeedManager.syncHelpAudioToAll(server);
                    // 【1.17】「到站播报」也可能正指着这一段 —— 不加这一步会留下一个指向空文件的引用
                    if (EscalatorSpeedManager.clearPsdMidiumIfRemoved(server, audioId) > 0) {
                        player.displayClientMessage(Component.literal(
                                "到站播报原样用的是这段音频，已一并改成「不播」"), true);
                    }
                    // 【1.21】「进站报站」同理：不一起清就会留下一个指向空文件的引用
                    if (EscalatorSpeedManager.clearPsdArriveIfRemoved(server, audioId) > 0) {
                        player.displayClientMessage(Component.literal(
                                "进站报站原样用的是这段音频，已一并改成「不播」"), true);
                    }
                    EscalatorSpeedManager.syncPsdChimeToAll(server);
                }
            });
        });

        // 客户端把存档 smoothlift_audio 文件夹里的一个 OGG 导入存档并绑定到扶梯（融入存档，删原文件仍可播）
        ServerPlayNetworking.registerGlobalReceiver(IMPORT_FOLDER_AUDIO_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String fileName = buf.readUtf(128);
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                String problem = EscalatorSpeedManager.importAudioToStore(level, fileName);
                if (problem == null) {
                    EscalatorSpeedManager.bindAudio(level, pos, fileName);
                    player.displayClientMessage(Component.literal("已从文件夹导入并与这条扶梯绑定"), true);
                    EscalatorSpeedManager.syncAudioToAll(server);
                } else {
                    player.displayClientMessage(Component.literal("导入失败：" + problem), true);
                }
            });
        });

        // 【1.41】客户端把一段音频设为这条扶梯**某一头**的无障碍提示音音乐
        //（与运行底噪的 BIND_AUDIO 完全对称，但数据独立；共用同一个音频库）
        //   buf 顺序：pos, audioId, in（in = true → 进入扶梯 / 上客端那一头）
        ServerPlayNetworking.registerGlobalReceiver(BIND_HELP_AUDIO_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String audioId = buf.readUtf(128);
            boolean in = buf.readBoolean();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                if (EscalatorSpeedManager.bindHelpAudio(level, pos, audioId, in)) {
                    player.displayClientMessage(Component.literal(audioId.equals(EscalatorSpeedData.HELP_AUDIO_OFF)
                            ? "这条扶梯" + helpEndLabel(in) + "的无障碍提示音已设为「不播」"
                            : "已把这段声音设为这条扶梯" + helpEndLabel(in) + "的无障碍提示音"), true);
                    EscalatorSpeedManager.syncHelpAudioToAll(server);
                } else {
                    player.displayClientMessage(Component.literal("设置失败：音频不存在"), true);
                }
            });
        });

        // 【1.41】客户端清掉这条扶梯**某一头**的提示音音乐单独设置（回到维度默认）
        //   buf 顺序：pos, in
        ServerPlayNetworking.registerGlobalReceiver(UNBIND_HELP_AUDIO_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            boolean in = buf.readBoolean();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (EscalatorSpeedManager.unbindHelpAudio(level, pos, in)) {
                    player.displayClientMessage(Component.literal(
                            "这条扶梯" + helpEndLabel(in) + "的无障碍提示音已改回跟随默认"), true);
                    EscalatorSpeedManager.syncHelpAudioToAll(server);
                }
            });
        });

        // 【1.41】客户端把存档 smoothlift_audio 文件夹里的一个 OGG 导入存档并设为提示音音乐
        //（与运行底噪共用同一个文件夹与同一个库：导入一次，两边都能选）
        //   buf 顺序：pos, fileName, in
        ServerPlayNetworking.registerGlobalReceiver(IMPORT_FOLDER_HELP_AUDIO_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            String fileName = buf.readUtf(128);
            boolean in = buf.readBoolean();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                String problem = EscalatorSpeedManager.importAudioToStore(level, fileName);
                if (problem == null) {
                    EscalatorSpeedManager.bindHelpAudio(level, pos, fileName, in);
                    player.displayClientMessage(Component.literal(
                            "已从文件夹导入并设为这条扶梯" + helpEndLabel(in) + "的无障碍提示音"), true);
                    EscalatorSpeedManager.syncAudioToAll(server);
                    EscalatorSpeedManager.syncHelpAudioToAll(server);
                } else {
                    player.displayClientMessage(Component.literal("导入失败：" + problem), true);
                }
            });
        });

        // 【1.9】客户端设置某条扶梯的声音音量（1~100）
        ServerPlayNetworking.registerGlobalReceiver(SET_VOLUME_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            int volume = buf.readVarInt();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int applied = EscalatorSpeedManager.setVolume(level, pos, volume);
                player.displayClientMessage(Component.literal("这条扶梯的音量已设为 " + applied + "%"), true);
                // 音量很小，单独发包同步即可，不必重发整个音频库
                EscalatorSpeedManager.syncVolumeToAll(server);
            });
        });

        // 【1.16】客户端石斧界面里的「无障碍提示音」开关
        ServerPlayNetworking.registerGlobalReceiver(SET_HELP_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            boolean enabled = buf.readBoolean();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                EscalatorSpeedManager.setHelp(level, pos, enabled);
                boolean applied = EscalatorSpeedManager.isHelpEnabled(level, pos);
                player.displayClientMessage(Component.literal(
                        "这条扶梯的无障碍提示音已" + (applied ? "开启" : "关闭")), true);
                // 开关极小，单独发包同步即可，不必重发整个音频库
                EscalatorSpeedManager.syncHelpToAll(server);
            });
        });

        // 【1.18】客户端石斧界面里的「提示音音量」输入框
        ServerPlayNetworking.registerGlobalReceiver(SET_HELP_VOLUME_CHANNEL, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            int volume = buf.readVarInt();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int applied = EscalatorSpeedManager.setHelpVolume(level, pos, volume);
                player.displayClientMessage(Component.literal(
                        "这条扶梯的无障碍提示音音量已设为 " + applied + "%"), true);
                // 音量很小，单独发包同步即可，不必重发整个音频库
                EscalatorSpeedManager.syncHelpVolumeToAll(server);
            });
        });

        // 【1.45】客户端把某条直梯的某一项提示音设为「音频库里的某段 / 默认素材 / 不播」。
        //   直梯没有稳定 ID，所以客户端在右键楼层轨道时已经把「竖井列 key」算好发过来。
        //   buf 顺序：key(long), which(utf: up|down|chime), audioId(utf)
        ServerPlayNetworking.registerGlobalReceiver(SET_LIFT_TONE_CHANNEL, (server, player, handler, buf, responseSender) -> {
            long key = buf.readLong();
            String which = buf.readUtf(32);
            String audioId = buf.readUtf(128);
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (EscalatorSpeedManager.setServerLiftTone(level, key, which, audioId)) {
                    player.displayClientMessage(Component.literal(
                            "已把这条直梯的" + liftToneLabel(which, audioId) + "设为"
                                    + (EscalatorSpeedData.LIFT_TONE_OFF.equals(audioId) ? "「不播」"
                                    : EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(audioId) ? "「默认素材」"
                                    : "「" + truncateForMsg(audioId, 20) + "」")), true);
                    EscalatorSpeedManager.syncLiftToneToAll(server);
                } else {
                    player.displayClientMessage(Component.literal("设置失败：音频不存在"), true);
                }
            });
        });

        // 【1.46】石斧 UI 里的「三类提示音开关」：改的是**维度默认子开关**（总开关 /lifthelp 之外
        //   各自还能再关一层）。buf 顺序：which(utf), enabled(boolean)。
        ServerPlayNetworking.registerGlobalReceiver(SET_LIFT_TONE_SWITCH_CHANNEL, (server, player, handler, buf, responseSender) -> {
            String which = buf.readUtf(32);
            boolean enabled = buf.readBoolean();
            server.execute(() -> {
                if (!"up".equals(which) && !"down".equals(which) && !"chime".equals(which)) {
                    return;
                }
                ServerLevel level = player.serverLevel();
                EscalatorSpeedManager.setDefaultLiftToneEnabled(level, which, enabled);
                player.displayClientMessage(Component.literal(
                        "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "已"
                                + (enabled ? "开启" : "关闭")), true);
                EscalatorSpeedManager.syncLiftChimeToAll(server);
            });
        });

        // 【1.48】石斧 UI 主界面「设置默认音量」：= /lifthelploud <音量>（共用默认，三项跟随）。
        //   buf 顺序：volume(VarInt)
        ServerPlayNetworking.registerGlobalReceiver(SET_LIFT_CHIME_VOLUME_CHANNEL, (server, player, handler, buf, responseSender) -> {
            int volume = buf.readVarInt();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                EscalatorSpeedManager.setDefaultLiftHelpVolume(level, volume);
                int applied = EscalatorSpeedManager.getLiftHelpVolume(level);
                player.displayClientMessage(Component.literal(
                        "本维度直梯提示音默认音量已设为 " + applied), true);
                EscalatorSpeedManager.syncLiftChimeToAll(server);
            });
        });

        // 【1.48】石斧 UI 单项列表「音量」：= /lifthelploud up|down|door <音量>（该项自己的音量）。
        //   buf 顺序：which(utf), volume(VarInt)
        ServerPlayNetworking.registerGlobalReceiver(SET_LIFT_TONE_VOLUME_CHANNEL, (server, player, handler, buf, responseSender) -> {
            String which = buf.readUtf(32);
            int volume = buf.readVarInt();
            server.execute(() -> {
                if (!"up".equals(which) && !"down".equals(which) && !"chime".equals(which)) {
                    return;
                }
                ServerLevel level = player.serverLevel();
                EscalatorSpeedManager.setDefaultLiftToneVolume(level, which, volume);
                int applied = EscalatorSpeedManager.getLiftToneVolume(level, which);
                player.displayClientMessage(Component.literal(
                        "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量已设为 "
                                + applied), true);
                EscalatorSpeedManager.syncLiftChimeToAll(server);
            });
        });

        // 【1.45】客户端把 smoothlift_audio 文件夹里的一个 OGG 导入存档并设为某条直梯的某一项提示音。
        //   buf 顺序：key(long), which(utf), fileName(utf)
        ServerPlayNetworking.registerGlobalReceiver(IMPORT_FOLDER_LIFT_TONE_CHANNEL, (server, player, handler, buf, responseSender) -> {
            long key = buf.readLong();
            String which = buf.readUtf(32);
            String fileName = buf.readUtf(128);
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                String problem = EscalatorSpeedManager.importAudioToStore(level, fileName);
                if (problem == null) {
                    if (EscalatorSpeedManager.setServerLiftTone(level, key, which, fileName)) {
                        player.displayClientMessage(Component.literal(
                                "已从文件夹导入并设为这条直梯的" + liftToneLabel(which, fileName)), true);
                        EscalatorSpeedManager.syncAudioToAll(server);
                        EscalatorSpeedManager.syncLiftToneToAll(server);
                    } else {
                        player.displayClientMessage(Component.literal("设置失败：导入成功但绑定失败"), true);
                        EscalatorSpeedManager.syncAudioToAll(server);
                    }
                } else {
                    player.displayClientMessage(Component.literal("导入失败：" + problem), true);
                }
            });
        });

        // 【1.50】客户端把**某一扇屏蔽门**的某一项提示音设为「音频库里的某段 / 默认素材 / 不播」。
        //   门身份 = 客户端算好的「门锚点」（左右/上下半格都折到同一格，见 PsdDoorTracker.anchorOf）。
        //   buf 顺序：key(long), which(utf: open|close), audioId(utf)
        ServerPlayNetworking.registerGlobalReceiver(SET_PSD_TONE_CHANNEL, (server, player, handler, buf, responseSender) -> {
            long key = buf.readLong();
            String which = buf.readUtf(32);
            String audioId = buf.readUtf(128);
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if (EscalatorSpeedManager.setServerPsdTone(level, key, which, audioId)) {
                    player.displayClientMessage(Component.literal(
                            "已把这一扇屏蔽门的" + EscalatorSpeedData.psdToneLabel(which) + "设为"
                                    + (EscalatorSpeedData.PSD_TONE_OFF.equals(audioId) ? "「不播」"
                                    : psdToneAudioLabel(audioId))), true);
                    EscalatorSpeedManager.syncPsdToneToAll(server);
                } else {
                    player.displayClientMessage(Component.literal(
                            "设置失败：音频不存在"), true);
                }
            });
        });

        // 【1.50】石斧 UI 的屏蔽门开关：which = master / open / close（总开关 / 两项子开关）。
        //   buf 顺序（★【1.20】前面多了 key）：key(long) → which(utf) → enabled(boolean)
        //   ★ key = 右键那一扇门的**锚点**（PsdDoorTracker.anchorOf）。带上它之后这条改动只落到
        //   **这一扇门**上（写 psdToneAudio 那条记录的覆盖项），不再写本维度默认
        //   —— 用户点名「石斧 UI 改的（要）全部都是玩家右键的连在一起的屏蔽门，
        //     而不是修改全部屏蔽门，只有指令才是修改全部」。
        ServerPlayNetworking.registerGlobalReceiver(SET_PSD_TONE_SWITCH_CHANNEL, (server, player, handler, buf, responseSender) -> {
            long key = buf.readLong();
            String which = buf.readUtf(32);
            boolean enabled = buf.readBoolean();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                if ("master".equals(which)) {
                    EscalatorSpeedManager.setDoorPsdHelp(level, key, enabled);
                    player.displayClientMessage(Component.literal(
                            "这一扇屏蔽门的开关门提示音已" + (enabled ? "开启" : "关闭")), true);
                } else if ("open".equals(which) || "close".equals(which)) {
                    EscalatorSpeedManager.setDoorPsdToneEnabled(level, key, which, enabled);
                    player.displayClientMessage(Component.literal(
                            "这一扇屏蔽门的「" + EscalatorSpeedData.psdToneLabel(which) + "」已"
                                    + (enabled ? "开启" : "关闭")), true);
                } else {
                    return;
                }
                EscalatorSpeedManager.syncPsdToneToAll(server);
            });
        });

        // 【1.50】石斧 UI 主界面「默认音量」。
        //   buf 顺序（★【1.20】前面多了 key）：key(long) → volume(VarInt)
        ServerPlayNetworking.registerGlobalReceiver(SET_PSD_CHIME_VOLUME_CHANNEL, (server, player, handler, buf, responseSender) -> {
            long key = buf.readLong();
            int volume = buf.readVarInt();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                EscalatorSpeedManager.setDoorPsdHelpVolume(level, key, volume);
                int applied = EscalatorSpeedManager.getDoorPsdHelpVolume(level, key);
                player.displayClientMessage(Component.literal(
                        "这一扇屏蔽门的音量已设为 " + applied), true);
                EscalatorSpeedManager.syncPsdToneToAll(server);
            });
        });

        // 【1.22】石斧 UI 主界面「到站播放音频」右边的「音量:」输入框。
        //   buf 顺序：key(long) → volume(VarInt)
        ServerPlayNetworking.registerGlobalReceiver(SET_PSD_MIDIUM_LOUD_CHANNEL, (server, player, handler, buf, responseSender) -> {
            long key = buf.readLong();
            int volume = buf.readVarInt();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                EscalatorSpeedManager.setDoorPsdMidiumVolume(level, key, volume);
                int applied = EscalatorSpeedManager.getDoorPsdMidiumVolume(level, key);
                player.displayClientMessage(Component.literal(
                        "这一串屏蔽门的到站播报音量已设为 " + applied), true);
                EscalatorSpeedManager.syncPsdToneToAll(server);
            });
        });

        // 【1.22】石斧 UI 主界面「进站播放音频」右边的「音量:」输入框。
        //   buf 顺序：key(long) → volume(VarInt)
        ServerPlayNetworking.registerGlobalReceiver(SET_PSD_ARRIVE_LOUD_CHANNEL, (server, player, handler, buf, responseSender) -> {
            long key = buf.readLong();
            int volume = buf.readVarInt();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                EscalatorSpeedManager.setDoorPsdArriveVolume(level, key, volume);
                int applied = EscalatorSpeedManager.getDoorPsdArriveVolume(level, key);
                player.displayClientMessage(Component.literal(
                        "这一串屏蔽门的进站报站音量已设为 " + applied), true);
                EscalatorSpeedManager.syncPsdToneToAll(server);
            });
        });

        // 【1.16】石斧 UI 主界面「关门提示音强制等待时长」。
        //   buf 顺序（★【1.20】前面多了 key）：key(long) → seconds(VarInt)
        ServerPlayNetworking.registerGlobalReceiver(SET_PSD_CLOSE_WAIT_CHANNEL, (server, player, handler, buf, responseSender) -> {
            long key = buf.readLong();
            int seconds = buf.readVarInt();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                EscalatorSpeedManager.setDoorPsdCloseWaitSeconds(level, key, seconds);
                int applied = EscalatorSpeedManager.getDoorPsdCloseWaitSeconds(level, key);
                player.displayClientMessage(Component.literal(
                        "这一扇屏蔽门的关门提示音强制等待时长已设为 " + applied + " 秒"), true);
                EscalatorSpeedManager.syncPsdToneToAll(server);
            });
        });

        // 【1.17】石斧 UI 主界面「到站播放音频 / 等待几秒后播放」。
        //   buf 顺序（★【1.20】前面多了 key）：key(long) → name(utf128) → seconds(varInt)
        ServerPlayNetworking.registerGlobalReceiver(SET_PSD_MIDIUM_CHANNEL, (server, player, handler, buf, responseSender) -> {
            long key = buf.readLong();
            String name = buf.readUtf(128);
            int seconds = buf.readVarInt();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                // ★【1.19】导入前先记下音频库大小：resolvePsdMidiumName 会在库里没有这个名字时
                //   **顺手导一次**。导入只发生在服务端，而客户端那张「已导入」列表来自
                //   音频库同步包（AUDIO_SYNC_CHANNEL）—— 不补发这一包，刚导入的曲子会一直挂在
                //   左列（表现为「点了没反应，重启游戏之后才跑到右边」）。
                int libBefore = EscalatorSpeedManager.getServerData(level).audioLibrary.size();
                String resolved = EscalatorSpeedManager.resolvePsdMidiumName(level, name);
                if (resolved == null) {
                    player.displayClientMessage(Component.literal(
                            "到站播报设置失败：找不到名为「" + name + "」的音频"), true);
                    return;
                }
                EscalatorSpeedManager.setDoorPsdMidium(level, key, resolved, seconds);
                int applied = EscalatorSpeedManager.getDoorPsdMidiumWaitSeconds(level, key);
                player.displayClientMessage(Component.literal(
                        "这一扇屏蔽门的到站播报已设为 "
                                + (EscalatorSpeedData.isPsdMidiumOff(resolved) ? "不播" : "「" + resolved + "」")
                                + "、等待 " + applied + " 秒"), true);
                // ★【1.20】按门设置走的是**按门那张表**（PSD_TONE_SYNC_CHANNEL），
                //   不是维度设置那个小包 —— 改成 syncPsdChimeToAll 的话客户端镜像不会更新。
                EscalatorSpeedManager.syncPsdToneToAll(server);
                if (EscalatorSpeedManager.getServerData(level).audioLibrary.size() != libBefore) {
                    EscalatorSpeedManager.sendAudioSyncTo(player, level);
                }
            });
        });

        // 【1.21】石斧 UI 主界面「进站播放音频 / 到站前秒数」。
        //   形状与 SET_PSD_MIDIUM_CHANNEL 逐字对称，差别只有「秒数允许为负」。
        ServerPlayNetworking.registerGlobalReceiver(SET_PSD_ARRIVE_CHANNEL, (server, player, handler, buf, responseSender) -> {
            long key = buf.readLong();
            String name = buf.readUtf(128);
            int seconds = buf.readVarInt();
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                // 与到站播报同一条教训：resolvePsdArriveName 可能**顺手导入**一次，
                // 而客户端的「已导入」列表来自音频库同步包 —— 不补发这一包，
                // 刚导入的曲子会一直挂在左列（表现为「点了没反应」）。
                int libBefore = EscalatorSpeedManager.getServerData(level).audioLibrary.size();
                String resolved = EscalatorSpeedManager.resolvePsdArriveName(level, name);
                if (resolved == null) {
                    player.displayClientMessage(Component.literal(
                            "进站报站设置失败：找不到名为「" + name + "」的音频"), true);
                    return;
                }
                EscalatorSpeedManager.setDoorPsdArrive(level, key, resolved, seconds);
                int applied = EscalatorSpeedManager.getDoorPsdArriveSeconds(level, key);
                player.displayClientMessage(Component.literal(
                        "这一串屏蔽门的进站报站已设为 "
                                + (EscalatorSpeedData.isPsdArriveOff(resolved) ? "不播" : "「" + resolved + "」")
                                + "、到站前 " + (-applied) + " 秒"), true);
                EscalatorSpeedManager.syncPsdToneToAll(server);
                if (EscalatorSpeedManager.getServerData(level).audioLibrary.size() != libBefore) {
                    EscalatorSpeedManager.sendAudioSyncTo(player, level);
                }
            });
        });

        // 【1.19】客户端把 smoothlift_audio 文件夹里的一段 OGG **只导入存档音频库**（不改设置）。
        //   buf 顺序：name(utf128)
        ServerPlayNetworking.registerGlobalReceiver(IMPORT_PSD_MIDIUM_AUDIO_CHANNEL,
                (server, player, handler, buf, responseSender) -> {
            String name = buf.readUtf(128);
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                String problem = EscalatorSpeedManager.importAudioToStore(level, name);
                if (problem == null) {
                    player.displayClientMessage(Component.literal(
                            "已导入存档音频库：「" + name + "」"), true);
                    // ★ 必须补发音频库同步包：客户端右列（已导入）就是从它来的，
                    //   否则导入完左列不会空、右列不会出现这一条。
                    EscalatorSpeedManager.sendAudioSyncTo(player, level);
                } else {
                    player.displayClientMessage(Component.literal("导入失败：" + problem), true);
                }
            });
        });

        // 【1.50】石斧 UI 单项列表「音量」：这一项自己的音量（-1 = 跟随共用）。
        //   buf 顺序（★【1.20】前面多了 key）：key(long) → which(utf) → volume(VarInt)
        ServerPlayNetworking.registerGlobalReceiver(SET_PSD_TONE_VOLUME_CHANNEL, (server, player, handler, buf, responseSender) -> {
            long key = buf.readLong();
            String which = buf.readUtf(32);
            int volume = buf.readVarInt();
            server.execute(() -> {
                if (!"open".equals(which) && !"close".equals(which)) {
                    return;
                }
                ServerLevel level = player.serverLevel();
                EscalatorSpeedManager.setDoorPsdToneVolume(level, key, which, volume);
                int applied = EscalatorSpeedManager.getDoorPsdToneVolume(level, key, which);
                player.displayClientMessage(Component.literal(
                        "这一扇屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」音量已设为 "
                                + applied), true);
                EscalatorSpeedManager.syncPsdToneToAll(server);
            });
        });

        // 【1.50】客户端把 smoothlift_audio 文件夹里的一个 OGG 导入存档并设为某一扇门的一项提示音。
        //   buf 顺序：key(long), which(utf), fileName(utf)
        ServerPlayNetworking.registerGlobalReceiver(IMPORT_FOLDER_PSD_TONE_CHANNEL, (server, player, handler, buf, responseSender) -> {
            long key = buf.readLong();
            String which = buf.readUtf(32);
            String fileName = buf.readUtf(128);
            server.execute(() -> {
                ServerLevel level = player.serverLevel();
                String problem = EscalatorSpeedManager.importAudioToStore(level, fileName);
                if (problem == null) {
                    if (EscalatorSpeedManager.setServerPsdTone(level, key, which, fileName)) {
                        player.displayClientMessage(Component.literal(
                                "已从文件夹导入并设为这一扇屏蔽门的"
                                        + EscalatorSpeedData.psdToneLabel(which)), true);
                        EscalatorSpeedManager.syncAudioToAll(server);
                        EscalatorSpeedManager.syncPsdToneToAll(server);
                    } else {
                        player.displayClientMessage(Component.literal("设置失败：导入成功但绑定失败"), true);
                        EscalatorSpeedManager.syncAudioToAll(server);
                    }
                } else {
                    player.displayClientMessage(Component.literal("导入失败：" + problem), true);
                }
            });
        });

        // 玩家进入游戏时同步全部数据（服务端侧兜底，客户端还会主动请求一次）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            EscalatorSpeedManager.syncToAll(server);
            EscalatorSpeedManager.syncAudioToAll(server);
            EscalatorSpeedManager.syncVolumeToAll(server);
            EscalatorSpeedManager.syncHelpToAll(server);
            EscalatorSpeedManager.syncHelpVolumeToAll(server);
            EscalatorSpeedManager.syncRoundToAll(server);
            EscalatorSpeedManager.syncHelpRoundToAll(server);
            EscalatorSpeedManager.syncHelpSpeedToAll(server);
            EscalatorSpeedManager.syncHelpAudioToAll(server);
            // 【1.42】直梯开关门提示音（开关 + 倍速）
            EscalatorSpeedManager.syncLiftChimeToAll(server);
            // 【1.45】直梯楼层轨道提示音（石斧右键设置的三列表）
            EscalatorSpeedManager.syncLiftToneToAll(server);
            // 【1.50】屏蔽门开关门提示音（开关 + 每扇门单独素材）
            EscalatorSpeedManager.syncPsdChimeToAll(server);
            EscalatorSpeedManager.syncPsdToneToAll(server);
        });

        // 服务端启动时确保存档音频来源文件夹存在（没有就自动新建）
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                EscalatorSpeedManager.ensureAudioFolder(server.overworld()));

        // 客户端进世界后主动请求同步：此时双方频道均已就绪，可靠送达
        ServerPlayNetworking.registerGlobalReceiver(REQUEST_SYNC_CHANNEL, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                EscalatorSpeedManager.syncToAll(server);
                EscalatorSpeedManager.sendAudioSyncTo(player, player.serverLevel());
                EscalatorSpeedManager.sendVolumeSyncTo(player, player.serverLevel());
                EscalatorSpeedManager.sendHelpSyncTo(player, player.serverLevel());
                EscalatorSpeedManager.sendHelpVolumeSyncTo(player, player.serverLevel());
                EscalatorSpeedManager.sendRoundSyncTo(player, player.serverLevel());
                EscalatorSpeedManager.sendHelpRoundSyncTo(player, player.serverLevel());
                EscalatorSpeedManager.sendHelpSpeedSyncTo(player, player.serverLevel());
                EscalatorSpeedManager.sendHelpAudioSyncTo(player, player.serverLevel());
                // 【1.42】直梯提示音要**每个维度都发一遍**（设置是按维度存的），
                // 不像上面那些只需要当前维度。
                for (ServerLevel level : server.getAllLevels()) {
                    EscalatorSpeedManager.sendLiftChimeSyncTo(player, level);
                }
                // 【1.45】直梯楼层轨道提示音同理会**每个维度都发一遍**。
                for (ServerLevel level : server.getAllLevels()) {
                    EscalatorSpeedManager.sendLiftToneSyncTo(player, level);
                }
                // 【1.50】屏蔽门提示音（设置 + 每扇门单独素材）同样**每个维度都发一遍**。
                for (ServerLevel level : server.getAllLevels()) {
                    EscalatorSpeedManager.sendPsdChimeSyncTo(player, level);
                    EscalatorSpeedManager.sendPsdToneSyncTo(player, level);
                }
            });
        });

        // 扶梯方块被破坏时清除对应记录
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide() || !(world instanceof ServerLevel level)) {
                return;
            }
            if (!EscalatorUtil.isEscalator(state)) {
                return;
            }
            // 有记录被清除才广播，避免拆没有配置过的扶梯也重发全量同步包
            if (!EscalatorSpeedManager.removeSpeed(level, pos)) {
                return;
            }
            // 速度 / 音频绑定 / 底噪音量 / 提示音开关 / 提示音音量 / 两个可闻范围 / 提示音速率
            // 八类数据都要同步，否则客户端会残留旧的速度与声音
            EscalatorSpeedManager.syncToAll(world.getServer());
            EscalatorSpeedManager.syncAudioToAll(world.getServer());
            EscalatorSpeedManager.syncVolumeToAll(world.getServer());
            EscalatorSpeedManager.syncHelpToAll(world.getServer());
            EscalatorSpeedManager.syncHelpVolumeToAll(world.getServer());
            EscalatorSpeedManager.syncRoundToAll(world.getServer());
            EscalatorSpeedManager.syncHelpRoundToAll(world.getServer());
            EscalatorSpeedManager.syncHelpSpeedToAll(world.getServer());
        });
    }

    /** 手里拿的是不是 MTR 的扶梯物品（按类名判断，避免编译期依赖 MTR）。 */
    private static boolean isEscalatorItem(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && stack.getItem().getClass().getName().toLowerCase(Locale.ROOT).contains("escalator");
    }

    // ------------------------------------------------------------------
    // /futispeed
    // ------------------------------------------------------------------

    /**
     * /futispeed（不带参数）—— 显示当前扶梯速度。
     *
     * <p>能定位到「玩家当前所在的扶梯」（脚下/身上 → 准星 → 附近最近）就显示那一条的速度，
     * 并标出它是单独设置还是跟随全局；否则显示全局默认值。
     */
    private static int futiShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        double global = EscalatorSpeedManager.getGlobalRunSpeed(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "当前扶梯速度：" + EscalatorSpeedData.format(global) + " 格/秒"), false);
            return 1;
        }
        double speed = EscalatorSpeedManager.getSpeed(level, pos);
        boolean individual = EscalatorSpeedManager.getIndividualRunSpeed(level, pos) != null;
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯速度：" + EscalatorSpeedData.format(speed) + " 格/秒；全局默认 " + EscalatorSpeedData.format(global) + " 格/秒"), false);
        return 1;
    }

    /** 注册 `-f` 分支：`-f X` 与 `-f X to Y`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                        .executes(SmoothLift::futiForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                                        .executes(SmoothLift::futiForceFromTo))));
    }

    /** /futispeed X —— 只改全局扶梯的运行速度（阶梯速度一起跟随）。 */
    private static int futiGlobal(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setGlobalRunSpeed(level, speed);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("全局扶梯速度改为" + EscalatorSpeedData.format(speed)),
                false);
        return 1;
    }

    /** /futispeed X to Y —— 只有当前全局运行速度正好是 X 时才改成 Y。 */
    private static int futiFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        double current = EscalatorSpeedManager.getGlobalRunSpeed(level);
        if (!EscalatorSpeedManager.same(current, from)) {
            source.sendSuccess(
                    () -> Component.literal("全局扶梯速度没有" + EscalatorSpeedData.format(from)
                            + "，未做修改"),
                    false);
            return 0;
        }
        EscalatorSpeedManager.setGlobalRunSpeed(level, to);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("全局扶梯速度从" + EscalatorSpeedData.format(from)
                        + "改为" + EscalatorSpeedData.format(to)),
                false);
        return 1;
    }

    /** /futispeed -f X —— 强制游戏内所有扶梯运行速度 = X。 */
    private static int futiForceAll(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.forceGlobalRunSpeed(level, speed);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("所有扶梯速度改为" + EscalatorSpeedData.format(speed)),
                false);
        return 1;
    }

    /** /futispeed -f X to Y —— 把所有运行速度为 X 的扶梯改成 Y。 */
    private static int futiForceFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean globalMatched = EscalatorSpeedManager.same(EscalatorSpeedManager.getGlobalRunSpeed(level), from);
        int changed = EscalatorSpeedManager.forceRunFromTo(level, from, to);
        EscalatorSpeedManager.syncToAll(source.getServer());
        if (!globalMatched && changed == 0) {
            source.sendSuccess(
                    () -> Component.literal("没有速度为" + EscalatorSpeedData.format(from) + "的扶梯，未做修改"),
                    false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal("所有扶梯速度从" + EscalatorSpeedData.format(from)
                        + "改为" + EscalatorSpeedData.format(to)),
                false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /jietispeed （只动阶梯速度，绝不动运行速度）
    // ------------------------------------------------------------------

    /**
     * /jietispeed（不带参数）—— 显示当前阶梯速度。
     * 与 {@link #futiShow} 同一套「当前扶梯」定位规则。
     */
    private static int jietiShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean enabled = EscalatorSpeedManager.isStepEnabled(level);
        double global = EscalatorSpeedManager.getGlobalStepSpeed(level);
        String globalNote = enabled
                ? "全局阶梯速度 " + EscalatorSpeedData.format(global) + " 格/秒"
                : "全局阶梯速度未单独设置，跟随扶梯速度";
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal("当前阶梯速度：" + globalNote), false);
            return 1;
        }
        double step = EscalatorSpeedManager.getAnimationSpeed(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前阶梯速度：" + EscalatorSpeedData.format(step) + " 格/秒；"
                        + globalNote), false);
        return 1;
    }

    /** 注册 `-f` 分支：`-f X` 与 `-f X to Y`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> jietiForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                        .executes(SmoothLift::jietiForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                                        .executes(SmoothLift::jietiForceFromTo))));
    }

    /** /jietispeed X —— 只改全局扶梯的阶梯速度。 */
    private static int jietiGlobal(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setGlobalStepSpeed(level, speed);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("全局扶梯阶梯速度改为" + EscalatorSpeedData.format(speed)),
                false);
        return 1;
    }

    /** /jietispeed X to Y —— 只有当前全局阶梯速度正好是 X 时才改成 Y。 */
    private static int jietiFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        double current = EscalatorSpeedManager.getGlobalStepSpeed(level);
        if (!EscalatorSpeedManager.same(current, from)) {
            source.sendSuccess(
                    () -> Component.literal("全局扶梯阶梯速度没有" + EscalatorSpeedData.format(from)
                            + "，未做修改"),
                    false);
            return 0;
        }
        EscalatorSpeedManager.setGlobalStepSpeed(level, to);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("全局扶梯阶梯速度从" + EscalatorSpeedData.format(from)
                        + "改为" + EscalatorSpeedData.format(to)),
                false);
        return 1;
    }

    /** /jietispeed -f X —— 强制所有扶梯阶梯速度 = X。 */
    private static int jietiForceAll(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.forceGlobalStepSpeed(level, speed);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("所有扶梯阶梯速度改为" + EscalatorSpeedData.format(speed)),
                false);
        return 1;
    }

    /** /jietispeed -f X to Y —— 把所有阶梯速度为 X 的扶梯改成 Y。 */
    private static int jietiForceFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean globalMatched = EscalatorSpeedManager.isStepEnabled(level)
                && EscalatorSpeedManager.same(EscalatorSpeedManager.getStepValue(level), from);
        int changed = EscalatorSpeedManager.forceStepFromTo(level, from, to);
        EscalatorSpeedManager.syncToAll(source.getServer());
        if (!globalMatched && changed == 0) {
            source.sendSuccess(
                    () -> Component.literal("没有阶梯速度为" + EscalatorSpeedData.format(from) + "的扶梯，未做修改"),
                    false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal("所有扶梯阶梯速度从" + EscalatorSpeedData.format(from)
                        + "改为" + EscalatorSpeedData.format(to)),
                false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /futimusic （扶梯音频，数据模型与 /futispeed 完全对称）
    // ------------------------------------------------------------------

    /** 注册 `-f` 分支：`-f <名字>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiMusicForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(SmoothLift::futiMusicForceSet)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .executes(SmoothLift::futiMusicForceFromTo))));
    }

    /** 音频 ID 在指令反馈里的显示名：内置音频显示中文名 + ID，玩家上传的直接显示文件名。 */
    private static String audioLabel(String audioId) {
        if (audioId == null) {
            return "无";
        }
        String name = EscalatorSpeedManager.displayName(audioId);
        return name.equals(audioId) ? audioId : name;
    }

    /** /futimusic（不带参数）—— 显示当前扶梯播放的音频名。 */
    private static int futiMusicShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String defaultAudio = EscalatorSpeedManager.getDefaultAudio(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认扶梯音频：" + audioLabel(defaultAudio)), false);
            return 1;
        }
        String id = EscalatorSpeedManager.effectiveAudioId(level, pos);
        boolean individual = EscalatorSpeedManager.hasIndividualAudio(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        final boolean own = individual;
        if (id == null) {
            source.sendSuccess(() -> Component.literal(
                    "当前扶梯没有音频：没有单独绑定，也没有设置默认音频"), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "当前扶梯音频：" + audioLabel(id)), false);
        }
        return 1;
    }

    /** /futimusic &lt;名字&gt; —— 设置**默认**扶梯音频（已单独绑定音频的扶梯不变）。 */
    private static int futiMusicSet(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveAudioName(level, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal(arg.error()));
            return 0;
        }
        EscalatorSpeedManager.setDefaultAudio(level, arg.id());
        EscalatorSpeedManager.syncAudioToAll(source.getServer());
        if (arg.off()) {
            source.sendSuccess(() -> Component.literal(
                    "已清除默认扶梯音频：没有单独绑定音频的扶梯将静音"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                "默认扶梯音频已设为 " + audioLabel(arg.id())
                        + "；没有单独绑定音频的扶梯都会播放它"), false);
        return 1;
    }

    /** /futimusic &lt;X&gt; to &lt;Y&gt; —— 默认音频正好是 X 时才改成 Y（单独绑定的不动）。 */
    private static int futiMusicFromTo(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveAudioName(level, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal(from.error()));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveAudioName(level, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal(to.error()));
            return 0;
        }
        String current = EscalatorSpeedManager.getDefaultAudio(level);
        if (!java.util.Objects.equals(current, from.id())) {
            source.sendSuccess(() -> Component.literal(
                    "默认扶梯音频不是 " + audioLabel(from.id()) + "，未做修改；已单独绑定音频的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultAudio(level, from.id(), to.id());
        EscalatorSpeedManager.syncAudioToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "默认扶梯音频从 " + audioLabel(from.id()) + " 改为 " + audioLabel(to.id())), false);
        return 1;
    }

    /** /futimusic -f &lt;名字&gt; —— 强制游戏内**所有**扶梯都用这个音频。 */
    private static int futiMusicForceSet(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveAudioName(level, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal(arg.error()));
            return 0;
        }
        int cleared = EscalatorSpeedManager.forceDefaultAudio(level, arg.id());
        EscalatorSpeedManager.syncAudioToAll(source.getServer());
        final int clearedCount = cleared;
        if (arg.off()) {
            source.sendSuccess(() -> Component.literal(
                    "已强制所有扶梯静音"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯播放 " + audioLabel(arg.id())), false);
        return 1;
    }

    /** /futimusic -f &lt;X&gt; to &lt;Y&gt; —— 把所有音频为 X 的扶梯（含单独绑定的）改成 Y。 */
    private static int futiMusicForceFromTo(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveAudioName(level, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal(from.error()));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveAudioName(level, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal(to.error()));
            return 0;
        }
        int changed = EscalatorSpeedManager.forceReplaceAudioFromTo(level, from.id(), to.id());
        EscalatorSpeedManager.syncAudioToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有音频为 " + audioLabel(from.id()) + " 的扶梯，未做修改"), false);
            return 0;
        }
        final int changedCount = changed;
        source.sendSuccess(() -> Component.literal(
                "已把所有音频为 " + audioLabel(from.id()) + " 的扶梯换成 "
                        + audioLabel(to.id())), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /futihelpmusic （无障碍提示音「音乐」，数据模型与 /futimusic 完全对称）
    //
    // 与 /futimusic 的唯一语义差别：
    //   `default` = **模组原来的提示音**（五档「咔啪」素材 + /futihelpspeed 速率），
    //   而不是内置运行底噪；另外多一个 `off` = 这一头不播提示音。
    // 导入文件夹与音频库**与运行底噪共用**：导入一次，两边都能选。
    //
    // ★【1.41】有 `in`（进入扶梯 / 上客端）与 `out`（离开扶梯 / 落客端）两个子命令，
    //   各是一套**互不影响**的数据（形状与 /futihelpspeed 的 in|out 完全一致）：
    //     /futihelpmusic                       -> 显示这条扶梯两头当前的提示音
    //     /futihelpmusic in|out <名字>         -> 默认提示音 = 名字（已单独设置的扶梯不变）
    //     /futihelpmusic in|out <X> to <Y>     -> 默认提示音正好是 X 时才改成 Y
    //     /futihelpmusic -f in|out <名字>      -> 强制所有扶梯这一头都用它（清掉这一头的单独设置）
    //     /futihelpmusic -f in|out <X> to <Y>  -> 把这一头提示音为 X 的扶梯（含单独设置的）改成 Y
    //   ★ 所以「进站播一段、出站播另一端」不用改素材：给两头各设一段即可；
    //     连 `off` 都细到了单头 —— 可以只让某一头不响、另一头照常响。
    //   ★ 别把 in/out 挪到 -f 之外：与 /futihelpspeed 一样，in/out 在「带 -f」和「不带 -f」
    //     两层下各有一个，位置对齐才好记。
    // ------------------------------------------------------------------

    /** 注册 `-f` 分支：`-f in|out <名字>` 与 `-f in|out <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpMusicForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("in")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> futiHelpMusicForceSet(context, true))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .executes(context -> futiHelpMusicForceFromTo(context, true))))))
                .then(Commands.literal("out")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> futiHelpMusicForceSet(context, false))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .executes(context -> futiHelpMusicForceFromTo(context, false))))));
    }

    /** 提示音音乐 ID 在指令反馈里的显示名。 */
    private static String helpAudioLabel(String audioId) {
        if (audioId == null) {
            return "无";
        }
        if (EscalatorSpeedData.HELP_AUDIO_DEFAULT.equals(audioId)) {
            return "模组原来的提示音";
        }
        if (EscalatorSpeedData.HELP_AUDIO_OFF.equals(audioId)) {
            return "不播提示音";
        }
        return audioLabel(audioId);
    }

    /** /futihelpmusic（不带参数）—— 显示这条扶梯**两头**当前用的无障碍提示音。 */
    private static int futiHelpMusicShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String defaultIn = EscalatorSpeedManager.getDefaultHelpAudio(level, true);
        String defaultOut = EscalatorSpeedManager.getDefaultHelpAudio(level, false);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认无障碍提示音：进入扶梯 " + helpAudioLabel(defaultIn)
                            + "、离开扶梯 " + helpAudioLabel(defaultOut)), false);
            return 1;
        }
        String inId = EscalatorSpeedManager.effectiveHelpAudioId(level, pos, true);
        String outId = EscalatorSpeedManager.effectiveHelpAudioId(level, pos, false);
        boolean ownIn = EscalatorSpeedManager.hasIndividualHelpAudio(level, pos, true);
        boolean ownOut = EscalatorSpeedManager.hasIndividualHelpAudio(level, pos, false);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯的无障碍提示音：进入扶梯 " + helpAudioLabel(inId)
                        + "、离开扶梯 " + helpAudioLabel(outId)), false);
        return 1;
    }

    /** /futihelpmusic in|out &lt;名字&gt; —— 设置这一头的**默认**提示音（已单独设置的扶梯不变）。 */
    private static int futiHelpMusicSet(CommandContext<CommandSourceStack> context, boolean in) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveHelpAudioName(level, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal(arg.error()));
            return 0;
        }
        EscalatorSpeedManager.setDefaultHelpAudio(level, arg.id(), in);
        EscalatorSpeedManager.syncHelpAudioToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "默认" + helpEndLabel(in) + "的无障碍提示音已设为 " + helpAudioLabel(arg.id())
                        + "；没有单独设置过的扶梯都会用它"), false);
        return 1;
    }

    /** /futihelpmusic in|out &lt;X&gt; to &lt;Y&gt; —— 这一头默认提示音正好是 X 时才改成 Y（单独设置的按兵不动）。 */
    private static int futiHelpMusicFromTo(CommandContext<CommandSourceStack> context, boolean in) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveHelpAudioName(level, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal(from.error()));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveHelpAudioName(level, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal(to.error()));
            return 0;
        }
        String current = EscalatorSpeedManager.getDefaultHelpAudio(level, in);
        if (!java.util.Objects.equals(current, from.id())) {
            source.sendSuccess(() -> Component.literal(
                    "默认" + helpEndLabel(in) + "的无障碍提示音不是 " + helpAudioLabel(from.id())
                            + "，未做修改；已单独设置的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultHelpAudio(level, from.id(), to.id(), in);
        EscalatorSpeedManager.syncHelpAudioToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "默认" + helpEndLabel(in) + "的无障碍提示音从 " + helpAudioLabel(from.id()) + " 改为 "
                        + helpAudioLabel(to.id())), false);
        return 1;
    }

    /** /futihelpmusic -f in|out &lt;名字&gt; —— 强制游戏内**所有**扶梯这一头的提示音都用这一段。 */
    private static int futiHelpMusicForceSet(CommandContext<CommandSourceStack> context, boolean in) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveHelpAudioName(level, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal(arg.error()));
            return 0;
        }
        int cleared = EscalatorSpeedManager.forceDefaultHelpAudio(level, arg.id(), in);
        EscalatorSpeedManager.syncHelpAudioToAll(source.getServer());
        final int clearedCount = cleared;
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯" + helpEndLabel(in) + "的无障碍提示音为 " + helpAudioLabel(arg.id())), false);
        return 1;
    }

    /** /futihelpmusic -f in|out &lt;X&gt; to &lt;Y&gt; —— 把这一头提示音为 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpMusicForceFromTo(CommandContext<CommandSourceStack> context, boolean in) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveHelpAudioName(level, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal(from.error()));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveHelpAudioName(level, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal(to.error()));
            return 0;
        }
        int changed = EscalatorSpeedManager.forceReplaceHelpAudioFromTo(level, from.id(), to.id(), in);
        EscalatorSpeedManager.syncHelpAudioToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有" + helpEndLabel(in) + "提示音为 " + helpAudioLabel(from.id()) + " 的扶梯，未做修改"), false);
            return 0;
        }
        final int changedCount = changed;
        source.sendSuccess(() -> Component.literal(
                "已把所有" + helpEndLabel(in) + "提示音为 " + helpAudioLabel(from.id()) + " 的扶梯换成 "
                        + helpAudioLabel(to.id())), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /futiloud （扶梯音量，数据模型与 /futispeed、/futimusic 完全对称）
    // ------------------------------------------------------------------

    /** /futiloud 的音量参数：1~1000（100 = 原始音量，1000 = 10× 放大）。 */
    private static IntegerArgumentType volumeArg() {
        return IntegerArgumentType.integer(EscalatorSpeedData.AUDIO_VOLUME_MIN,
                EscalatorSpeedData.AUDIO_VOLUME_MAX);
    }

    /** 注册 `-f` 分支：`-f <音量>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiLoudForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(SmoothLift::futiLoudForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(SmoothLift::futiLoudForceFromTo))));
    }

    /** /futiloud（不带参数）—— 显示当前扶梯音量。 */
    private static int futiLoudShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int global = EscalatorSpeedManager.getDefaultVolume(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认扶梯音量：" + global), false);
            return 1;
        }
        int volume = EscalatorSpeedManager.getVolume(level, pos);
        boolean individual = EscalatorSpeedManager.hasIndividualVolume(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯音量：" + volume + "；默认音量 " + global), false);
        return 1;
    }

    /** /futiloud &lt;音量&gt; —— 设置**默认**扶梯音量（单独设置过音量的扶梯不变）。 */
    private static int futiLoudGlobal(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultVolume(level, volume);
        EscalatorSpeedManager.syncVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultVolume(level);
        source.sendSuccess(() -> Component.literal(
                "默认扶梯音量已设为 " + applied + "；"
                        + "没有单独设置过音量的扶梯都会用它"), false);
        return 1;
    }

    /** /futiloud &lt;X&gt; to &lt;Y&gt; —— 默认音量正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiLoudFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getDefaultVolume(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "默认扶梯音量不是 " + from + "，未做修改；"
                            + "单独设置过音量的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultVolume(level, from, to);
        EscalatorSpeedManager.syncVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultVolume(level);
        source.sendSuccess(() -> Component.literal(
                "默认扶梯音量从 " + from + " 改为 " + applied), false);
        return 1;
    }

    /** /futiloud -f &lt;音量&gt; —— 强制游戏内**所有**扶梯都用这个音量（清掉单独设置）。 */
    private static int futiLoudForceAll(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = EscalatorSpeedManager.forceDefaultVolume(level, volume);
        EscalatorSpeedManager.syncVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultVolume(level);
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯音量 = " + applied), false);
        return 1;
    }

    /** /futiloud -f &lt;X&gt; to &lt;Y&gt; —— 把所有音量正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiLoudForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceVolumeFromTo(level, from, to);
        EscalatorSpeedManager.syncVolumeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有音量正好是 " + from + " 的扶梯，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有音量正好是 " + from + " 的扶梯改成 " + to), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /futihelp （无障碍提示音开关，数据模型与 /futispeed、/futiloud 完全对称）
    // ------------------------------------------------------------------

    /** 开关在指令反馈里的显示名。 */
    private static String helpLabel(boolean enabled) {
        return enabled ? "开" : "关";
    }

    /**
     * 注册 `/futihelp` 的 `-f` 分支：`-f on|off` 与 `-f &lt;X&gt; to &lt;Y&gt;`。
     *
     * <p>开关只有两个取值，所以直接用 {@code on}/{@code off} 两个字面量而不是自定义参数类型 ——
     * 这样 Tab 补全能补出全部合法输入，也让「X to Y」只能写出 {@code on to off} / {@code off to on}
     * 两种有意义的形式（与 /futiloud 的 {@code X to Y} 完全对称）。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("on")
                        .executes(context -> futiHelpForceAll(context, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> futiHelpForceFromTo(context, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> futiHelpForceAll(context, false))
                        .then(Commands.literal("to")
                                .then(Commands.literal("on")
                                        .executes(context -> futiHelpForceFromTo(context, false, true)))));
    }

    /** /futihelp（不带参数）—— 显示当前扶梯的无障碍提示音开关（站在扶梯上显示那一条，否则显示默认）。 */
    private static int futiHelpShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean global = EscalatorSpeedManager.getDefaultHelp(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认无障碍提示音：" + helpLabel(global)), false);
            return 1;
        }
        boolean enabled = EscalatorSpeedManager.isHelpEnabled(level, pos);
        boolean own = EscalatorSpeedManager.hasOwnHelp(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯无障碍提示音：" + helpLabel(enabled) + "；默认开关 " + helpLabel(global)), false);
        return 1;
    }

    /** /futihelp &lt;on|off&gt; —— 设置**默认**开关（已单独设置过的扶梯不变）。 */
    private static int futiHelpGlobal(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultHelp(level, enabled);
        EscalatorSpeedManager.syncHelpToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "默认无障碍提示音已设为 " + helpLabel(enabled)
                        + "；没有单独设置过的扶梯都会用它"), false);
        return 1;
    }

    /** /futihelp &lt;X&gt; to &lt;Y&gt; —— 默认开关正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiHelpFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean current = EscalatorSpeedManager.getDefaultHelp(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "默认无障碍提示音不是 " + helpLabel(from) + "，未做修改；单独设置过的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultHelp(level, from, to);
        EscalatorSpeedManager.syncHelpToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "默认无障碍提示音从 " + helpLabel(from) + " 改为 " + helpLabel(to)), false);
        return 1;
    }

    /** /futihelp -f &lt;on|off&gt; —— 强制游戏内**所有**扶梯 = 该开关（清掉单独设置）。 */
    private static int futiHelpForceAll(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = EscalatorSpeedManager.forceDefaultHelp(level, enabled);
        EscalatorSpeedManager.syncHelpToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯的无障碍提示音 = " + helpLabel(enabled)), false);
        return 1;
    }

    /** /futihelp -f &lt;X&gt; to &lt;Y&gt; —— 把所有开关正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpForceFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceHelpFromTo(level, from, to);
        EscalatorSpeedManager.syncHelpToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有无障碍提示音为 " + helpLabel(from) + " 的扶梯，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有无障碍提示音为 " + helpLabel(from) + " 的扶梯改成 "
                        + helpLabel(to)), false);
        return 1;
    }


    // ------------------------------------------------------------------
    // /futihelploud （无障碍**提示音**音量，数据模型与 /futiloud、/futihelp 完全对称）
    //
    //   注意与 /futiloud 的区别：/futiloud 管的是「扶梯运行底噪」的音量（整条扶梯、射程 16 格）；
    //   /futihelploud 管的是「无障碍提示音」的音量（装在端头单块方块上、射程 4 格）。
    //   两者是**两套独立数据**，改一个不影响另一个。
    // ------------------------------------------------------------------

    /** 注册 `/futihelploud` 的 `-f` 分支：`-f <音量>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpLoudForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(SmoothLift::futiHelpLoudForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(SmoothLift::futiHelpLoudForceFromTo))));
    }

    /** /futihelploud（不带参数）—— 显示当前扶梯的无障碍提示音音量。 */
    private static int futiHelpLoudShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int global = EscalatorSpeedManager.getDefaultHelpVolume(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认无障碍提示音音量：" + global), false);
            return 1;
        }
        int volume = EscalatorSpeedManager.getHelpVolume(level, pos);
        boolean own = EscalatorSpeedManager.hasOwnHelpVolume(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯无障碍提示音音量：" + volume + "；默认音量 " + global), false);
        return 1;
    }

    /** /futihelploud &lt;音量&gt; —— 设置**默认**提示音音量（单独设置过的扶梯不变）。 */
    private static int futiHelpLoudGlobal(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultHelpVolume(level, volume);
        EscalatorSpeedManager.syncHelpVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpVolume(level);
        source.sendSuccess(() -> Component.literal(
                "默认无障碍提示音音量已设为 " + applied + "；"
                        + "没有单独设置过音量的扶梯都会用它"), false);
        return 1;
    }

    /** /futihelploud &lt;X&gt; to &lt;Y&gt; —— 默认音量正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiHelpLoudFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getDefaultHelpVolume(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "默认无障碍提示音音量不是 " + from + "，未做修改；"
                            + "单独设置过音量的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultHelpVolume(level, from, to);
        EscalatorSpeedManager.syncHelpVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpVolume(level);
        source.sendSuccess(() -> Component.literal(
                "默认无障碍提示音音量从 " + from + " 改为 " + applied), false);
        return 1;
    }

    /** /futihelploud -f &lt;音量&gt; —— 强制游戏内**所有**扶梯提示音都用这个音量（清掉单独设置）。 */
    private static int futiHelpLoudForceAll(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = EscalatorSpeedManager.forceDefaultHelpVolume(level, volume);
        EscalatorSpeedManager.syncHelpVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpVolume(level);
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯的无障碍提示音音量 = " + applied), false);
        return 1;
    }

    /** /futihelploud -f &lt;X&gt; to &lt;Y&gt; —— 把所有音量正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpLoudForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceHelpVolumeFromTo(level, from, to);
        EscalatorSpeedManager.syncHelpVolumeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有无障碍提示音音量正好是 " + from + " 的扶梯，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有无障碍提示音音量正好是 " + from + " 的扶梯改成 " + to), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.24】/futiround —— 扶梯运行底噪的淡入淡出范围（默认 16 格）
    //
    //   与 /futiloud（音量）是**两件事**：音量决定「多响」，范围决定「多远还听得见」。
    //   仅指令可改，**没有石斧界面控件**。
    // ------------------------------------------------------------------

    /** /futiround 的范围参数：1~128 格。 */
    private static IntegerArgumentType roundArg() {
        return IntegerArgumentType.integer(EscalatorSpeedData.ROUND_MIN, EscalatorSpeedData.ROUND_MAX);
    }

    /**
     * 【1.16】{@code /pbmclosewait} 的秒数参数：0~60 秒。
     *
     * <p>★ 参数类型这里**再夹一次**（数据层 {@code clampPsdCloseWaitSeconds} 还会夹一次）：
     * 指令层夹是为了让 Brigadier 直接在 Tab 补全/报错上就挡住越界值，
     * 数据层夹是为了挡住「存档被外部改坏」与「UI 输入框绕过参数类型」两条路。
     * 两处都留不是冗余 —— {@code _tools/check-close-wait.py} 把两处都钉住了。
     */
    private static IntegerArgumentType closeWaitArg() {
        return IntegerArgumentType.integer(
                EscalatorSpeedData.PSD_CLOSE_WAIT_MIN, EscalatorSpeedData.PSD_CLOSE_WAIT_MAX);
    }

    /**
     * 【1.17】{@code /pbmmidium} 的等待秒数参数：**0 ~ 正无穷**（{@code [0, +∞)}）。
     *
     * <p>★ 与 {@link #closeWaitArg()} 的差别就是**没有上界** —— 用户点名
     * 「pbmmidium 指令和 ui 允许输入 0 到正无穷的数字」。
     * {@code IntegerArgumentType.integer(0)} 的默认上界就是 {@code Integer.MAX_VALUE}
     * （≈ 68 年），已经是「正无穷」在 int 里的全部空间。
     *
     * <p>下界 0 仍然要显式写：不写就是 {@code Integer.MIN_VALUE}，负值会被接住，
     * 而「等 -3 秒」在语义上没有意义（会让计划 tick 落到过去）。
     */
    private static IntegerArgumentType midiumWaitArg() {
        return IntegerArgumentType.integer(EscalatorSpeedData.PSD_MIDIUM_WAIT_MIN);
    }

    /** 【1.17】{@code /pbmmidium} 的素材名参数（带音频库补全，含 {@code off}）。 */
    private static StringArgumentType midiumNameArg() {
        return StringArgumentType.string();
    }

    /**
     * 【1.21】{@code /pbmarrive} 的秒数参数：{@code (-∞, 0]}。
     *
     * <p>★ 与 {@link #midiumWaitArg()}（{@code [0, +∞)}）**正好相反**：那一个只给下界，
     * 这一个只给上界 —— 用户点名「输入框范围：(-无穷,0]」，例子 {@code X=-10} =
     * 「最近一班车还剩 10 秒到站」时起播进站提示音。
     * {@code IntegerArgumentType.integer(Integer.MIN_VALUE, 0)} 就是「负无穷到 0」。
     */
    private static IntegerArgumentType arriveArg() {
        return IntegerArgumentType.integer(Integer.MIN_VALUE, EscalatorSpeedData.PSD_ARRIVE_SECONDS_MAX);
    }

    /** 【1.21】{@code /pbmarrive} 的素材名参数（带音频库补全，含 {@code off}）。 */
    private static StringArgumentType arriveNameArg() {
        return StringArgumentType.string();
    }

    /** 注册 `/futiround` 的 `-f` 分支：`-f <范围>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiRoundForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("round", roundArg())
                        .executes(SmoothLift::futiRoundForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", roundArg())
                                        .executes(SmoothLift::futiRoundForceFromTo))));
    }

    /** /futiround（不带参数）—— 显示当前扶梯运行音效的淡入淡出范围。 */
    private static int futiRoundShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int global = EscalatorSpeedManager.getDefaultRound(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认扶梯音效淡入淡出范围：" + global + " 格"), false);
            return 1;
        }
        int round = EscalatorSpeedManager.getRound(level, pos);
        boolean own = EscalatorSpeedManager.hasOwnRound(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯音效淡入淡出范围：" + round + " 格；默认范围 " + global + " 格"), false);
        return 1;
    }

    /** /futiround &lt;范围&gt; —— 设置**默认**范围（单独设置过的扶梯不变）。 */
    private static int futiRoundGlobal(CommandContext<CommandSourceStack> context) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultRound(level, round);
        EscalatorSpeedManager.syncRoundToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultRound(level);
        source.sendSuccess(() -> Component.literal(
                "默认扶梯音效淡入淡出范围已设为 " + applied + " 格；"
                        + "没有单独设置过范围的扶梯都会用它"), false);
        return 1;
    }

    /** /futiround &lt;X&gt; to &lt;Y&gt; —— 默认范围正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiRoundFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getDefaultRound(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "默认扶梯音效淡入淡出范围不是 " + from + " 格，未做修改；"
                            + "单独设置过范围的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultRound(level, from, to);
        EscalatorSpeedManager.syncRoundToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultRound(level);
        source.sendSuccess(() -> Component.literal(
                "默认扶梯音效淡入淡出范围从 " + from + " 格改为 " + applied + " 格"), false);
        return 1;
    }

    /** /futiround -f &lt;范围&gt; —— 强制游戏内**所有**扶梯音效都用这个范围（清掉单独设置）。 */
    private static int futiRoundForceAll(CommandContext<CommandSourceStack> context) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = EscalatorSpeedManager.forceDefaultRound(level, round);
        EscalatorSpeedManager.syncRoundToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultRound(level);
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯音效的淡入淡出范围 = " + applied + " 格"), false);
        return 1;
    }

    /** /futiround -f &lt;X&gt; to &lt;Y&gt; —— 把所有范围正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiRoundForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceRoundFromTo(level, from, to);
        EscalatorSpeedManager.syncRoundToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有扶梯音效淡入淡出范围正好是 " + from + " 格的扶梯，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有扶梯音效淡入淡出范围正好是 " + from + " 格的扶梯改成 " + to + " 格"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.24】/futihelpround —— 无障碍提示音的淡入淡出范围（默认 4 格）
    //
    //   ★ 与 /futiround 是**两件事**：本指令管的是端头**单块**那块提示音（默认 4 格），
    //     /futiround 管的是整条扶梯一起响的运行底噪（默认 16 格）。数据与指令互不影响。
    // ------------------------------------------------------------------

    /** 注册 `/futihelpround` 的 `-f` 分支：`-f <范围>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpRoundForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("round", roundArg())
                        .executes(SmoothLift::futiHelpRoundForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", roundArg())
                                        .executes(SmoothLift::futiHelpRoundForceFromTo))));
    }

    /** /futihelpround（不带参数）—— 显示当前扶梯无障碍提示音的淡入淡出范围。 */
    private static int futiHelpRoundShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int global = EscalatorSpeedManager.getDefaultHelpRound(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认无障碍提示音淡入淡出范围：" + global + " 格"), false);
            return 1;
        }
        int round = EscalatorSpeedManager.getHelpRound(level, pos);
        boolean own = EscalatorSpeedManager.hasOwnHelpRound(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前无障碍提示音淡入淡出范围：" + round + " 格；默认范围 " + global + " 格"), false);
        return 1;
    }

    /** /futihelpround &lt;范围&gt; —— 设置**默认**范围（单独设置过的扶梯不变）。 */
    private static int futiHelpRoundGlobal(CommandContext<CommandSourceStack> context) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultHelpRound(level, round);
        EscalatorSpeedManager.syncHelpRoundToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpRound(level);
        source.sendSuccess(() -> Component.literal(
                "默认无障碍提示音淡入淡出范围已设为 " + applied + " 格；"
                        + "没有单独设置过范围的扶梯都会用它"), false);
        return 1;
    }

    /** /futihelpround &lt;X&gt; to &lt;Y&gt; —— 默认范围正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiHelpRoundFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getDefaultHelpRound(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "默认无障碍提示音淡入淡出范围不是 " + from + " 格，未做修改；"
                            + "单独设置过范围的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultHelpRound(level, from, to);
        EscalatorSpeedManager.syncHelpRoundToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpRound(level);
        source.sendSuccess(() -> Component.literal(
                "默认无障碍提示音淡入淡出范围从 " + from + " 格改为 " + applied + " 格"), false);
        return 1;
    }

    /** /futihelpround -f &lt;范围&gt; —— 强制游戏内**所有**提示音都用这个范围（清掉单独设置）。 */
    private static int futiHelpRoundForceAll(CommandContext<CommandSourceStack> context) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = EscalatorSpeedManager.forceDefaultHelpRound(level, round);
        EscalatorSpeedManager.syncHelpRoundToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpRound(level);
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯无障碍提示音的淡入淡出范围 = " + applied + " 格"), false);
        return 1;
    }

    /** /futihelpround -f &lt;X&gt; to &lt;Y&gt; —— 把所有范围正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpRoundForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceHelpRoundFromTo(level, from, to);
        EscalatorSpeedManager.syncHelpRoundToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有无障碍提示音淡入淡出范围正好是 " + from + " 格的扶梯，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有无障碍提示音淡入淡出范围正好是 " + from + " 格的扶梯改成 " + to + " 格"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.31】/futihelpspeed —— 无障碍提示音的**速率**（每秒响几次，单位 Hz）
    //
    //   与上面三个提示音指令凑成完整一套、互不影响：
    //     /futihelp      开关    -> 响不响
    //     /futihelploud  音量    -> 多响
    //     /futihelpround 范围    -> 多远还听得见
    //     本指令         速率    -> 响得多快
    //   ★ 与 /futiround（整条扶梯的运行底噪）是两件事：本指令只管端头**单块**那路提示音。
    //   ★ 有 `in`（进入扶梯 / 上客端）与 `out`（离开扶梯 / 落客端）两个子命令，各是一套数据。
    //   ★ 速率靠「换素材 + 调 pitch」实现（原版把 pitch 夹在 [0.5,2.0]），
    //     所以区间是 1~100 Hz（【1.34】上限由 20 提到 100），见 EscalatorSpeedData#HELP_SPEED_MAX。
    // ------------------------------------------------------------------

    /** /futihelpspeed 的速率参数：{@link EscalatorSpeedData#HELP_SPEED_MIN}~{@link EscalatorSpeedData#HELP_SPEED_MAX} Hz（每秒响几次）。 */
    private static IntegerArgumentType helpSpeedArg() {
        return IntegerArgumentType.integer(EscalatorSpeedData.HELP_SPEED_MIN, EscalatorSpeedData.HELP_SPEED_MAX);
    }

    /** 指令文案里用的端头名（/futihelpspeed 与 /futihelpmusic 共用）。 */
    private static String helpEndLabel(boolean in) {
        return in ? "进入扶梯" : "离开扶梯";
    }

    /**
     * 【1.45】判定：方块注册名以 {@code lift_track_floor} 开头（MTR3 / MTR4 的「楼层轨道」
     * 注册名都是这个前缀，竖轨 {@code lift_track_vertical_*} 不会误命中）。
     * 服务端和客户端共用这一个判据（石斧右键时两侧都要拦默认交互）。
     */
    public static boolean isLiftTrackFloor(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.startsWith("lift_track_floor");
    }

    /**
     * 【1.50】判定「屏蔽门（可开合的那一格）」—— 按注册名判，不依赖 MTR 编译期。
     *
     * <p>MTR 的屏蔽门方块注册名（MTR3 3.2.2 与 MTR4 4.0.5 的 blockstates 都是这一组）：
     * {@code psd_door} / {@code psd_door_2}（站台幕门，两代样式）、{@code apg_door}
     * （半高安全门 = Automatic Platform Gate，和 PSD 共用同一套方块实体与门值逻辑）。
     *
     * <p><b>刻意不收玻璃与顶板</b>（{@code psd_glass*} / {@code psd_top} / {@code apg_glass*}）：
     * 那些方块的 {@code side}/{@code half} 与门**不是同一对**，拿它们算出来的「门锚点」
     * 会落到另一格上 ⇒ 玩家右键玻璃配好、门开的时候却查不到这份设置（症状是「配了不响」）。
     * 宁可「右键玻璃没反应」，也不要「看起来配上了但不生效」。
     */
    public static boolean isPsdDoor(BlockState state) {
        if (state == null) {
            return false;
        }
        String path = registryPathOf(state);
        return path.startsWith("psd_door") || path.startsWith("apg_door");
    }

    /** 方块注册名（{@code psd_door_2} 之类）；取不到返回空串。 */
    public static String registryPathOf(BlockState state) {
        if (state == null) {
            return "";
        }
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    /**
     * 【1.45】直梯提示音三项的中文名（石斧界面 / 指令反馈共用同一套词）。
     * {@code which} 不是 up/down/chime → 返回「提示音」兜底。
     */
    private static String liftToneLabel(String which, String audioId) {
        return switch (which) {
            case "up" -> "上楼提示音";
            case "down" -> "下楼提示音";
            case "chime" -> "开关门提示音";
            default -> "提示音";
        };
    }

    /** 【1.45】反馈文案里的名字截断（界面 / 指令都用 20 字符上限），过长直接截。 */
    private static String truncateForMsg(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }

    /** 注册 `/futihelpspeed` 的 `-f` 分支：`-f in|out <Hz>` 与 `-f in|out <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpSpeedForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("in")
                        .then(Commands.argument("hz", helpSpeedArg())
                                .executes(context -> futiHelpSpeedForceAll(context, true))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", helpSpeedArg())
                                                .executes(context -> futiHelpSpeedForceFromTo(context, true))))))
                .then(Commands.literal("out")
                        .then(Commands.argument("hz", helpSpeedArg())
                                .executes(context -> futiHelpSpeedForceAll(context, false))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", helpSpeedArg())
                                                .executes(context -> futiHelpSpeedForceFromTo(context, false))))));
    }

    /** /futihelpspeed（不带参数）—— 显示当前扶梯两头的无障碍提示音速率。 */
    private static int futiHelpSpeedShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int globalIn = EscalatorSpeedManager.getDefaultHelpSpeedIn(level);
        int globalOut = EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认无障碍提示音速率：进入扶梯 " + globalIn + " 次/秒、离开扶梯 "
                            + globalOut + " 次/秒"), false);
            return 1;
        }
        int in = EscalatorSpeedManager.getHelpSpeedIn(level, pos);
        int out = EscalatorSpeedManager.getHelpSpeedOut(level, pos);
        boolean ownIn = EscalatorSpeedManager.hasOwnHelpSpeedIn(level, pos);
        boolean ownOut = EscalatorSpeedManager.hasOwnHelpSpeedOut(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前无障碍提示音速率：进入扶梯 " + in + " 次/秒、离开扶梯 " + out + " 次/秒；默认 进入 " + globalIn + "、离开 "
                        + globalOut + " 次/秒"), false);
        return 1;
    }

    /** /futihelpspeed in|out &lt;Hz&gt; —— 设置**默认**速率（单独设置过的扶梯不变）。 */
    private static int futiHelpSpeedGlobal(CommandContext<CommandSourceStack> context, boolean in) {
        int hz = IntegerArgumentType.getInteger(context, "hz");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (in) {
            EscalatorSpeedManager.setDefaultHelpSpeedIn(level, hz);
        } else {
            EscalatorSpeedManager.setDefaultHelpSpeedOut(level, hz);
        }
        EscalatorSpeedManager.syncHelpSpeedToAll(source.getServer());
        int applied = in ? EscalatorSpeedManager.getDefaultHelpSpeedIn(level)
                : EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        source.sendSuccess(() -> Component.literal(
                "默认" + helpEndLabel(in) + "无障碍提示音速率已设为 " + applied + " 次/秒；"
                        + "没有单独设置过速率的扶梯都会用它"), false);
        return 1;
    }

    /** /futihelpspeed in|out &lt;X&gt; to &lt;Y&gt; —— 默认速率正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiHelpSpeedFromTo(CommandContext<CommandSourceStack> context, boolean in) {
        int from = IntegerArgumentType.getInteger(context, "hz");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = in ? EscalatorSpeedManager.getDefaultHelpSpeedIn(level)
                : EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "默认" + helpEndLabel(in) + "无障碍提示音速率不是 " + from + " 次/秒，未做修改；单独设置过速率的扶梯不受本指令影响"), false);
            return 0;
        }
        if (in) {
            EscalatorSpeedManager.replaceDefaultHelpSpeedIn(level, from, to);
        } else {
            EscalatorSpeedManager.replaceDefaultHelpSpeedOut(level, from, to);
        }
        EscalatorSpeedManager.syncHelpSpeedToAll(source.getServer());
        int applied = in ? EscalatorSpeedManager.getDefaultHelpSpeedIn(level)
                : EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        source.sendSuccess(() -> Component.literal(
                "默认" + helpEndLabel(in) + "无障碍提示音速率从 " + from + " 次/秒改为 " + applied + " 次/秒"), false);
        return 1;
    }

    /** /futihelpspeed -f in|out &lt;Hz&gt; —— 强制**所有**扶梯这一头都用该速率（清掉单独设置）。 */
    private static int futiHelpSpeedForceAll(CommandContext<CommandSourceStack> context, boolean in) {
        int hz = IntegerArgumentType.getInteger(context, "hz");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = in ? EscalatorSpeedManager.forceDefaultHelpSpeedIn(level, hz)
                : EscalatorSpeedManager.forceDefaultHelpSpeedOut(level, hz);
        EscalatorSpeedManager.syncHelpSpeedToAll(source.getServer());
        int applied = in ? EscalatorSpeedManager.getDefaultHelpSpeedIn(level)
                : EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯" + helpEndLabel(in) + "的无障碍提示音速率 = " + applied + " 次/秒"), false);
        return 1;
    }

    /** /futihelpspeed -f in|out &lt;X&gt; to &lt;Y&gt; —— 把速率正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpSpeedForceFromTo(CommandContext<CommandSourceStack> context, boolean in) {
        int from = IntegerArgumentType.getInteger(context, "hz");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = in ? EscalatorSpeedManager.forceReplaceHelpSpeedInFromTo(level, from, to)
                : EscalatorSpeedManager.forceReplaceHelpSpeedOutFromTo(level, from, to);
        EscalatorSpeedManager.syncHelpSpeedToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有" + helpEndLabel(in) + "无障碍提示音速率正好是 " + from + " 次/秒的扶梯，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有" + helpEndLabel(in) + "无障碍提示音速率正好是 " + from + " 次/秒的扶梯改成 " + to
                        + " 次/秒"), false);
        return 1;
    }

    // ==================================================================
    // 【1.42】直梯（Lift）开关门提示音 liftmusic.ogg
    //
    //   /lifthelp                     显示 / on|off / on to off / -f on|off / -f on to off
    //   /lifthelp up|down|door        三提示音子命令（子开关 + 默认素材），详见下面那一段
    //   /lifthelploud                 音量（共用默认 + up|down|door 单项）
    //   /lifthelpround                淡入淡出范围（三项共用）
    //
    //   ★ 为什么是 lifthelp 而不是需求原文里的 futihelp：
    //     那条指令**早就存在**、管的是**扶梯**的无障碍提示音（进/出口「咔啪」声），
    //     直接复用会把扶梯那套设置顶掉。直梯提示音是完全另一件事，所以另开一条指令，
    //     扶梯的 futihelp / futihelpspeed 一个字节都不动。
    //
    //   ★ 直梯提示音的「开关 / 音量 / 范围」只有「维度默认」一层数据（没有单条直梯的
    //     单独设置），所以那几条的 `-f` 取「**对所有维度**强制」的含义 ——
    //     见 EscalatorSpeedManager 里那一段的说明。
    //
    //   ★【1.15】`/lifthelpspeed` 已按用户要求**删除**：倍速数据（defaultLiftHelpSpeed）
    //     与播放端的 pitch 逻辑都留着（旧存档里可能存着非 1.0 的值），只是不再提供改它的
    //     入口（石斧界面本来也没有）。显示里仍会报出当前倍速，那是**实际生效的值**。
    // ==================================================================

    /** 倍速在指令反馈里的显示（两位小数就够，1.0 显示成 1）。 */
    private static String liftSpeedLabel(float speed) {
        if (speed == Math.round(speed)) {
            return String.valueOf((int) Math.round(speed));
        }
        return String.format(Locale.ROOT, "%.2f", speed);
    }

    /** /lifthelp（不带参数）—— 显示当前维度生效的直梯提示音开关。 */
    private static int liftHelpShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean enabled = EscalatorSpeedManager.isLiftHelpEnabled(level);
        float speed = EscalatorSpeedManager.getLiftHelpSpeed(level);
        source.sendSuccess(() -> Component.literal(
                "当前维度直梯开关门提示音：" + helpLabel(enabled) + "，倍速 " + liftSpeedLabel(speed)), false);
        return 1;
    }

    /** /lifthelp &lt;on|off&gt; —— 设置**本维度**的开关。 */
    private static int liftHelpGlobal(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftHelp(level, enabled);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度直梯开关门提示音已设为 " + helpLabel(enabled)
                        + "；其它维度不变"), false);
        return 1;
    }

    /** /lifthelp &lt;X&gt; to &lt;Y&gt; —— 本维度开关正好是 X 时才改成 Y。 */
    private static int liftHelpFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultLiftHelp(level, from, to)) {
            boolean current = EscalatorSpeedManager.isLiftHelpEnabled(level);
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯开关门提示音不是 " + helpLabel(from) + "，未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度直梯开关门提示音从 " + helpLabel(from) + " 改为 " + helpLabel(to)), false);
        return 1;
    }

    /** /lifthelp -f &lt;on|off&gt; —— **所有维度**都设成该开关。 */
    private static int liftHelpForceAll(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftHelpAll(source.getServer(), enabled);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯开关门提示音 = " + helpLabel(enabled)), false);
        return 1;
    }

    /** /lifthelp -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里开关正好是 X 的那些改成 Y。 */
    private static int liftHelpForceFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftHelpAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的直梯开关门提示音是 " + helpLabel(from) + "，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有直梯开关门提示音为 " + helpLabel(from) + " 的维度改成 " + helpLabel(to)), false);
        return 1;
    }

    /** /lifthelploud（不带参数）—— 显示当前维度生效的直梯提示音音量。 */
    private static int liftHelpLoudShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int volume = EscalatorSpeedManager.getLiftHelpVolume(level);
        boolean enabled = EscalatorSpeedManager.isLiftHelpEnabled(level);
        source.sendSuccess(() -> Component.literal(
                "当前维度直梯提示音音量：" + volume + "，提示音开关："
                        + helpLabel(enabled) + "，范围 "
                        + EscalatorSpeedData.HELP_VOLUME_MIN + "~"
                        + EscalatorSpeedData.HELP_VOLUME_MAX), false);
        return 1;
    }

    /** /lifthelploud &lt;音量&gt; —— 设置**本维度**的音量。 */
    private static int liftHelpLoudGlobal(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftHelpVolume(level, volume);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftHelpVolume(level);
        source.sendSuccess(() -> Component.literal(
                "本维度直梯提示音音量已设为 " + applied
                        + "；其它维度不变"), false);
        return 1;
    }

    /** /lifthelploud &lt;X&gt; to &lt;Y&gt; —— 本维度音量正好是 X 时才改成 Y。 */
    private static int liftHelpLoudFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultLiftHelpVolume(level, from, to)) {
            int current = EscalatorSpeedManager.getLiftHelpVolume(level);
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯提示音音量不是 " + from + "，未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftHelpVolume(level);
        source.sendSuccess(() -> Component.literal(
                "本维度直梯提示音音量从 " + from + " 改为 " + applied), false);
        return 1;
    }

    /** /lifthelploud -f &lt;音量&gt; —— **所有维度**都设成该音量。 */
    private static int liftHelpLoudForceAll(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftHelpVolumeAll(source.getServer(), volume);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedData.clampLiftHelpVolume(volume);
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯提示音音量 = " + applied), false);
        return 1;
    }

    /** /lifthelploud -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里音量正好是 X 的那些改成 Y。 */
    private static int liftHelpLoudForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftHelpVolumeAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的直梯提示音音量是 " + from + "，未做修改"), false);
            return 0;
        }
        int applied = EscalatorSpeedData.clampLiftHelpVolume(to);
        source.sendSuccess(() -> Component.literal(
                "已把所有直梯提示音音量为 " + from + " 的维度改成 " + applied), false);
        return 1;
    }

    /** 注册 `/lifthelploud` 的 `-f` 分支：`-f <音量>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftHelpLoudForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(SmoothLift::liftHelpLoudForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(SmoothLift::liftHelpLoudForceFromTo))));
    }

    // ------------------------------------------------------------------
    // 【1.48】/lifthelploud up|down|door：三项提示音**各自的**音量
    //   up = 上楼 / down = 下楼 / door = 开关门（chime 的别名）。
    //   形状与「共用默认音量」一致（<音量> / <X> to <Y> / -f <音量> / -f <X> to <Y>），
    //   只是改的是对应那一项；没单独调过的项跟随共用默认。
    // ------------------------------------------------------------------

    /** 注册某一项（up/down/chime）的音量子命令树。{@code literal} = 界面用名，{@code which} = 数据用名。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftToneLoudCommand(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> liftToneLoudGlobal(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> liftToneLoudFromTo(context, which)))));
    }

    /** 【1.48】`-f` 节点下的单项分支：{@code up|down|door <音量>} 与 {@code up|down|door <X> to <Y>}。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftToneLoudForceBranch(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> liftToneLoudForceAll(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> liftToneLoudForceFromTo(context, which)))));
    }

    /** /lifthelploud up|down|door &lt;音量&gt; —— 设置**本维度**这一项的音量。 */
    private static int liftToneLoudGlobal(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftToneVolume(level, which, volume);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftToneVolume(level, which);
        source.sendSuccess(() -> Component.literal(
                "本维度直梯"
                        + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量已设为 " + applied), false);
        return 1;
    }

    /** /lifthelploud up|down|door &lt;X&gt; to &lt;Y&gt; —— 本维度这一项音量正好是 X 时才改成 Y。 */
    private static int liftToneLoudFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getLiftToneVolume(level, which);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量不是 " + from
                            + "，未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultLiftToneVolume(level, which, from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftToneVolume(level, which);
        source.sendSuccess(() -> Component.literal(
                "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量从 " + from
                        + " 改为 " + applied), false);
        return 1;
    }

    /** /lifthelploud -f up|down|door &lt;音量&gt; —— **所有维度**这一项都设成该音量。 */
    private static int liftToneLoudForceAll(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftToneVolumeAll(source.getServer(), which, volume);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedData.clampLiftToneVolume(volume);
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量 = "
                        + applied), false);
        return 1;
    }

    /** /lifthelploud -f up|down|door &lt;X&gt; to &lt;Y&gt; —— 所有维度里这一项音量正好是 X 的改成 Y。 */
    private static int liftToneLoudForceFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftToneVolumeAll(source.getServer(), which, from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量是 "
                            + from + "，未做修改"), false);
            return 0;
        }
        int applied = EscalatorSpeedData.clampLiftToneVolume(to);
        source.sendSuccess(() -> Component.literal(
                "已把所有直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量为 " + from
                        + " 的维度改成 " + applied), false);
        return 1;
    }

    /** 【1.48】数据用名（up/down/chime）→ 指令里的字面名（door 是 chime 的别名）。 */
    private static String literalName(String which) {
        return switch (which) {
            case "chime" -> "door";
            default -> which;
        };
    }

    // ------------------------------------------------------------------
    // 【1.47】/lifthelpround：直梯提示音（三项共用）淡入淡出范围
    // ------------------------------------------------------------------

    /** 注册 `/lifthelpround` 的 `-f` 分支：`-f <范围>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftHelpRoundForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("round", roundArg())
                        .executes(SmoothLift::liftHelpRoundForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", roundArg())
                                        .executes(SmoothLift::liftHelpRoundForceFromTo))));
    }

    /** /lifthelpround（不带参数）—— 显示当前维度生效的淡入淡出范围。 */
    private static int liftHelpRoundShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int round = EscalatorSpeedManager.getLiftHelpRound(level);
        source.sendSuccess(() -> Component.literal(
                "当前维度直梯提示音淡入淡出范围：" + round + " 格"), false);
        return 1;
    }

    /** /lifthelpround &lt;范围&gt; —— 设置**本维度**的范围。 */
    private static int liftHelpRoundGlobal(CommandContext<CommandSourceStack> context) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftHelpRound(level, round);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftHelpRound(level);
        source.sendSuccess(() -> Component.literal(
                "本维度直梯提示音淡入淡出范围已设为 " + applied + " 格；"
                        + "其它维度不变"), false);
        return 1;
    }

    /** /lifthelpround &lt;X&gt; to &lt;Y&gt; —— 本维度范围正好是 X 时才改成 Y。 */
    private static int liftHelpRoundFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getLiftHelpRound(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯提示音淡入淡出范围不是 " + from + " 格，未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultLiftHelpRound(level, from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftHelpRound(level);
        source.sendSuccess(() -> Component.literal(
                "本维度直梯提示音淡入淡出范围从 " + from + " 格改为 " + applied + " 格"), false);
        return 1;
    }

    /** /lifthelpround -f &lt;范围&gt; —— **所有维度**都设成该范围。 */
    private static int liftHelpRoundForceAll(CommandContext<CommandSourceStack> context) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftHelpRoundAll(source.getServer(), round);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftHelpRound(source.getLevel());
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯提示音淡入淡出范围 = " + applied + " 格"), false);
        return 1;
    }

    /** /lifthelpround -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里范围正好是 X 的那些改成 Y。 */
    private static int liftHelpRoundForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftHelpRoundAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的直梯提示音淡入淡出范围是 " + from + " 格，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有直梯提示音淡入淡出范围为 " + from + " 格的维度改成 " + to + " 格"), false);
        return 1;
    }

    /** 注册 `/lifthelp` 的 `-f` 分支：`-f on|off` 与 `-f on to off` / `-f off to on`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftHelpForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("on")
                        .executes(context -> liftHelpForceAll(context, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> liftHelpForceFromTo(context, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> liftHelpForceAll(context, false))
                        .then(Commands.literal("to")
                                .then(Commands.literal("on")
                                        .executes(context -> liftHelpForceFromTo(context, false, true)))));
    }

    // ------------------------------------------------------------------
    // 【1.46】三提示音的子命令：/lifthelp up / /lifthelp down / /lifthelp door
    //
    //   【1.15】结构改动（用户点名）：原来是三条**顶级**指令
    //   /lifthelpup、/lifthelpdown、/lifthelpchime；现在收进 /lifthelp 下面变成子命令，
    //   并且把 chime 的**对外名字**改成 door（= 开关门）——
    //   数据层（EscalatorSpeedData / Manager / 石斧 UI）继续叫 chime，一个字节都没动，
    //   只有指令这一层做别名映射（literal "door" → which "chime"）。
    //
    //   每一条子命令的形状（与 /lifthelp 本身一致）：
    //     /lifthelp up                      显示：子开关 + 本维度默认素材
    //     /lifthelp up on|off               本维度子开关
    //     /lifthelp up on to off            本维度子开关正好是 on 才改成 off（off to on 同理）
    //     /lifthelp up <名字>               本维度**默认素材**（config：default / none / 导入的 .ogg）
    //     /lifthelp up <名字> to <另一个>   本维度默认素材正好是它才改掉
    //     /lifthelp up -f on|off            强制**所有维度**子开关（含 on to off / off to on）
    //     /lifthelp up -f <名字>            强制**所有维度**默认素材，并清掉按竖井列的单独设置
    //     /lifthelp up -f <名字> to <另一个> 所有维度里默认素材正好是它的改成另一个（单独设置的一起改）
    //
    //   ★ 同一层上「字面量 on/off/-f」与「音频名字参数」会**同时**匹配（StringArgumentType
    //     把 on 也读成一个字符串），Brigadier 取的是**先注册**的那个子节点。
    //     所以下面一律**先 literal 后 argument**，顺序不能调 —— 由
    //     _tools/CmdTreeCheck.java 的 expectNode 断言钉住（`lifthelp up on` 必须落在字面量上）。
    //   ★ 也正因为 on/off 被字面量占了，「这一项不播」在指令里写成 `none`。
    // ------------------------------------------------------------------

    /** 直梯提示音素材 id 在指令反馈里的显示名。 */
    private static String liftToneAudioLabel(String audioId) {
        if (audioId == null) {
            return "无";
        }
        if (EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(audioId)) {
            return "default";
        }
        if (EscalatorSpeedData.LIFT_TONE_OFF.equals(audioId)) {
            return "none";
        }
        return "「" + audioId + "」";
    }

    /** 【1.15】屏蔽门提示音素材 id 在指令反馈里的显示名。 */
    private static String psdToneAudioLabel(String audioId) {
        if (audioId == null) {
            return "无";
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_OPEN.equals(audioId)) {
            return "default";
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE.equals(audioId)) {
            return "default-c";
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M.equals(audioId)) {
            return "default-m";
        }
        if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S.equals(audioId)) {
            return "default-s：同素材但不播语音播报段，只播嘀嘀";
        }
        if (EscalatorSpeedData.PSD_TONE_OFF.equals(audioId)) {
            return "none";
        }
        return "「" + audioId + "」";
    }

    /**
     * 音频名字参数的 Tab 补全：**default / none + 玩家导入的每一个 .ogg 文件名**（字典序）。
     * 服务端算好发给客户端（指令树与补全都在服务端算）。
     *
     * <p>source 为 null 时（{@code _tools/CmdTreeCheck} 故意用 null source 脱离游戏环境解析
     * 真·指令树 —— 见 {@code registerCommands} 的说明）直接给空补全项，
     * 不要在这里抛 NPE：补全异常不会被 Brigadier 的 {@code CommandSyntaxException} 兜住。
     */
    private static CompletableFuture<Suggestions> liftToneNameSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        CommandSourceStack source = context.getSource();
        if (source == null) {
            return builder.buildFuture();
        }
        String typed = builder.getRemainingLowerCase();
        for (String candidate : EscalatorSpeedManager.liftToneNameCandidates(source.getLevel())) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }

    /** /lifthelp up|down|door（不带参数）—— 显示当前维度这项的子开关与默认素材。 */
    private static int liftToneShow(CommandContext<CommandSourceStack> context, String literal, String which) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean enabled = EscalatorSpeedManager.isLiftToneEnabled(level, which);
        String audio = EscalatorSpeedManager.getLiftToneAudio(level, which);
        source.sendSuccess(() -> Component.literal(
                "当前维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "：开关 " + helpLabel(enabled)
                        + "，默认素材 " + liftToneAudioLabel(audio)), false);
        return 1;
    }

    /** /lifthelp up|down|door &lt;on|off&gt; —— 设置**本维度**这项子开关。 */
    private static int liftToneSwitchGlobal(CommandContext<CommandSourceStack> context, String literal,
                                            String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftToneEnabled(level, which, enabled);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which)
                        + "已设为 " + helpLabel(enabled)
                        + "；其它维度不变"), false);
        return 1;
    }

    /** /lifthelp up|down|door &lt;X&gt; to &lt;Y&gt; —— 本维度这项子开关正好是 X 时才改成 Y。 */
    private static int liftToneSwitchFromTo(CommandContext<CommandSourceStack> context, String which,
                                            boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultLiftToneEnabled(level, which, from, to)) {
            boolean current = EscalatorSpeedManager.isLiftToneEnabled(level, which);
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "不是 " + helpLabel(from)
                            + "，未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "从 " + helpLabel(from)
                        + " 改为 " + helpLabel(to)), false);
        return 1;
    }

    /** /lifthelp up|down|door -f &lt;on|off&gt; —— **所有维度**这项子开关都设成该值。 */
    private static int liftToneSwitchForceAll(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftToneEnabledAll(source.getServer(), which, enabled);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + " = " + helpLabel(enabled)), false);
        return 1;
    }

    /** /lifthelp up|down|door -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里这项子开关正好是 X 的那些改成 Y。 */
    private static int liftToneSwitchForceFromTo(CommandContext<CommandSourceStack> context, String which,
                                                 boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftToneEnabledAll(source.getServer(), which, from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "是 " + helpLabel(from)
                            + "，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "为 " + helpLabel(from)
                        + " 的维度改成 " + helpLabel(to)), false);
        return 1;
    }

    /**
     * /lifthelp up|down|door &lt;名字&gt; —— 设置**本维度**这项的默认素材。
     * 名字：{@code default}（模组内置）/ {@code none}（这一项不播）/ 玩家导入的 .ogg 文件名。
     */
    private static int liftToneAudioSet(CommandContext<CommandSourceStack> context, String literal, String which) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveLiftToneName(level, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal(arg.error()));
            return 0;
        }
        EscalatorSpeedManager.setDefaultLiftToneAudio(level, which, arg.id());
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        String label = EscalatorSpeedManager.liftToneEnabledLabel(which);
        source.sendSuccess(() -> Component.literal(
                "本维度直梯" + label + "的默认素材已设为 "
                        + liftToneAudioLabel(arg.id())
                        + "；没有单独设置过这项的直梯都会用它"), false);
        return 1;
    }

    /** /lifthelp up|down|door &lt;X&gt; to &lt;Y&gt; —— 本维度默认素材正好是 X 时才改成 Y。 */
    private static int liftToneAudioFromTo(CommandContext<CommandSourceStack> context, String literal, String which) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveLiftToneName(level, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal(from.error()));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveLiftToneName(level, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal(to.error()));
            return 0;
        }
        if (!EscalatorSpeedManager.replaceDefaultLiftToneAudio(level, which, from.id(), to.id())) {
            String current = EscalatorSpeedManager.getLiftToneAudio(level, which);
            // 「不是 X 就没改」按惯例用 sendSuccess（不是错误，只是没命中），与 /futimusic X to Y 一致
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "的默认素材不是 "
                            + liftToneAudioLabel(from.id()) + "，未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "的默认素材从 "
                        + liftToneAudioLabel(from.id()) + " 改为 " + liftToneAudioLabel(to.id())), false);
        return 1;
    }

    /**
     * /lifthelp up|down|door -f &lt;名字&gt; —— **所有维度**这项的默认素材都设成它，
     * 并清掉「按竖井列单独设置」里的这一项（那些直梯从此跟维度默认）。
     */
    private static int liftToneAudioForceSet(CommandContext<CommandSourceStack> context, String literal, String which) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveLiftToneName(level, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal(arg.error()));
            return 0;
        }
        int changed = EscalatorSpeedManager.setDefaultLiftToneAudioAll(source.getServer(), which, arg.id());
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        EscalatorSpeedManager.syncLiftToneToAll(source.getServer());
        String label = EscalatorSpeedManager.liftToneEnabledLabel(which);
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯" + label + "默认素材 = " + liftToneAudioLabel(arg.id())
                        + "，并清掉按直梯的单独设置"), false);
        return 1;
    }

    /** /lifthelp up|down|door -f &lt;X&gt; to &lt;Y&gt; —— 所有维度（含单独设置）里这项是 X 的改成 Y。 */
    private static int liftToneAudioForceFromTo(CommandContext<CommandSourceStack> context, String which) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveLiftToneName(level, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal(from.error()));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveLiftToneName(level, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal(to.error()));
            return 0;
        }
        int changed = EscalatorSpeedManager.replaceDefaultLiftToneAudioAll(
                source.getServer(), which, from.id(), to.id());
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        EscalatorSpeedManager.syncLiftToneToAll(source.getServer());
        String label = EscalatorSpeedManager.liftToneEnabledLabel(which);
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的直梯" + label + "默认素材是 " + liftToneAudioLabel(from.id())
                            + "，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有直梯" + label + "默认素材为 " + liftToneAudioLabel(from.id()) + " 的维度改成 "
                        + liftToneAudioLabel(to.id())), false);
        return 1;
    }

    /**
     * 注册 `/lifthelp` 下面的一条子命令树：{@code up} / {@code down} / {@code door}。
     *
     * @param literal 指令里写的名字（{@code up} / {@code down} / {@code door}）
     * @param which   数据层用的项目名（{@code up} / {@code down} / {@code chime}）
     *                —— 只有 door 需要映射成 chime，其余两个同名。
     */
    private static LiteralArgumentBuilder<CommandSourceStack> liftToneBranch(String literal, String which) {
        return Commands.literal(literal)
                .executes(context -> liftToneShow(context, literal, which))
                // ★ 顺序即优先级：字面量必须排在字符串参数前面（详见本节开头那段说明）。
                .then(Commands.literal("on")
                        .executes(context -> liftToneSwitchGlobal(context, literal, which, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> liftToneSwitchFromTo(context, which, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> liftToneSwitchGlobal(context, literal, which, false))
                        .then(Commands.literal("to")
                                .then(Commands.literal("on")
                                        .executes(context -> liftToneSwitchFromTo(context, which, false, true)))))
                .then(Commands.literal("-f")
                        .then(Commands.literal("on")
                                .executes(context -> liftToneSwitchForceAll(context, which, true))
                                .then(Commands.literal("to")
                                        .then(Commands.literal("off")
                                                .executes(context -> liftToneSwitchForceFromTo(context, which, true, false)))))
                        .then(Commands.literal("off")
                                .executes(context -> liftToneSwitchForceAll(context, which, false))
                                .then(Commands.literal("to")
                                        .then(Commands.literal("on")
                                                .executes(context -> liftToneSwitchForceFromTo(context, which, false, true)))))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(SmoothLift::liftToneNameSuggestions)
                                .executes(context -> liftToneAudioForceSet(context, literal, which))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .suggests(SmoothLift::liftToneNameSuggestions)
                                                .executes(context -> liftToneAudioForceFromTo(context, which))))))
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests(SmoothLift::liftToneNameSuggestions)
                        .executes(context -> liftToneAudioSet(context, literal, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests(SmoothLift::liftToneNameSuggestions)
                                        .executes(context -> liftToneAudioFromTo(context, literal, which)))));
    }

    // ==================================================================
    // 【1.50】屏蔽门（MTR 平台幕门 / 半高安全门）开关门提示音指令：
    //   /pbmmusic  —— 总开关 + open/close 两项子开关
    //   /pbmloud   —— 共用默认音量 + open/close 两项各自的音量
    //   /pbmround  —— 淡入淡出范围（两项共用一份）
    // 【1.23】另加三条「按条音频各存一份」的范围指令（形状与 /pbmround 同构）：
    //   /pbmmusicround   —— 同上（点名用法；与 /pbmround 读写同一份数据）
    //   /pbmmidiumround  —— 到站播报那条音频的范围
    //   /pbmarriveround  —— 进站报站那条音频的范围
    // 结构与直梯那几套（/lifthelp[up|down|chime] + /lifthelploud + /lifthelpround）**完全对称**，
    // 只是把「up / down / chime 三项」换成「open / close 两项」。
    // ★ 数据是**每个维度一份**（与直梯同），所以：不带 -f 只改当前维度；带 -f 改所有维度。
    // ★ 与 /futihelp（扶梯无障碍提示音）、/lifthelp（直梯）互不影响，各存各的。
    // ==================================================================

    /** 注册 `/pbmmusic` 的 `-f` 分支：`-f on|off` 与 `-f on to off` / `-f off to on`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmMusicForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("on")
                        .executes(context -> pbmMusicForceAll(context, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> pbmMusicForceFromTo(context, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> pbmMusicForceAll(context, false))
                        .then(Commands.literal("to")
                                .then(Commands.literal("on")
                                        .executes(context -> pbmMusicForceFromTo(context, false, true)))));
    }

    /**
     * `/pbmmusic open|close` 子树的完整形状：显示 / on|off / X to Y / -f on|off / -f X to Y，
     * 以及【1.15】改素材的 &lt;名字&gt; / &lt;X&gt; to &lt;Y&gt; / -f &lt;名字&gt; / -f &lt;X&gt; to &lt;Y&gt;。
     *
     * <p>★ 字面量（on / off / -f）必须排在字符串参数 {@code name} 前面 —— Brigadier 里
     * 字面量优先于参数匹配，顺序即优先级（与 {@link #liftToneBranch} 完全同一套理由）。
     *
     * @param literal 指令里出现的字面名（open / close）
     * @param which   数据用名（open / close，与 {@code EscalatorSpeedManager} 一致）
     */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmMusicItemCommand(String literal, String which) {
        return Commands.literal(literal)
                .executes(context -> pbmMusicItemShow(context, which))
                .then(Commands.literal("on")
                        .executes(context -> pbmMusicItemGlobal(context, which, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> pbmMusicItemFromTo(context, which, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> pbmMusicItemGlobal(context, which, false))
                        .then(Commands.literal("to")
                                .then(Commands.literal("on")
                                        .executes(context -> pbmMusicItemFromTo(context, which, false, true)))))
                .then(Commands.literal("-f")
                        .then(Commands.literal("on")
                                .executes(context -> pbmMusicItemForceAll(context, which, true))
                                .then(Commands.literal("to")
                                        .then(Commands.literal("off")
                                                .executes(context -> pbmMusicItemForceFromTo(context, which, true, false)))))
                        .then(Commands.literal("off")
                                .executes(context -> pbmMusicItemForceAll(context, which, false))
                                .then(Commands.literal("to")
                                        .then(Commands.literal("on")
                                                .executes(context -> pbmMusicItemForceFromTo(context, which, false, true)))))
                        // 【1.15】-f <名字> / -f <X> to <Y>
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests(SmoothLift::psdToneNameSuggestions)
                                .executes(context -> pbmMusicItemAudioForceSet(context, literal, which))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .suggests(SmoothLift::psdToneNameSuggestions)
                                                .executes(context -> pbmMusicItemAudioForceFromTo(context, which))))))
                // 【1.15】<名字> / <X> to <Y>（不带 -f = 只改本维度）
                .then(Commands.argument("name", StringArgumentType.string())
                        .suggests(SmoothLift::psdToneNameSuggestions)
                        .executes(context -> pbmMusicItemAudioSet(context, literal, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .suggests(SmoothLift::psdToneNameSuggestions)
                                        .executes(context -> pbmMusicItemAudioFromTo(context, literal, which)))));
    }

    /**
     * 【1.15】屏蔽门素材名参数的 Tab 补全：**四段内置名排最前 + none + 玩家导入的每个 .ogg**。
     * 与 {@link #liftToneNameSuggestions} 同一套「source 为 null 时给空补全」的保护。
     */
    private static CompletableFuture<Suggestions> psdToneNameSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        CommandSourceStack source = context.getSource();
        if (source == null) {
            return builder.buildFuture();
        }
        String typed = builder.getRemainingLowerCase();
        for (String candidate : EscalatorSpeedManager.psdNameCandidates(source.getLevel())) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }

    /** `/pbmmusic`（不带参数）—— 显示当前维度的总开关与两项子开关。 */
    private static int pbmMusicShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        source.sendSuccess(() -> Component.literal(
                "当前维度屏蔽门开关门提示音：" + helpLabel(EscalatorSpeedManager.isPsdHelpEnabled(level))
                        + "；音量 " + EscalatorSpeedManager.getPsdHelpVolume(level)
                        + "、范围 " + EscalatorSpeedManager.getPsdHelpRound(level) + " 格"), false);
        return 1;
    }

    /** `/pbmmusic <on|off>` —— 设置**本维度**的总开关。 */
    private static int pbmMusicGlobal(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultPsdHelp(level, enabled);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度屏蔽门开关门提示音已设为 " + helpLabel(enabled)
                        + "；其它维度不变"), false);
        return 1;
    }

    /** `/pbmmusic <X> to <Y>` —— 本维度总开关正好是 X 时才改成 Y。 */
    private static int pbmMusicFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultPsdHelp(level, from, to)) {
            boolean current = EscalatorSpeedManager.isPsdHelpEnabled(level);
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门开关门提示音不是 " + helpLabel(from) + "，未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度屏蔽门开关门提示音从 " + helpLabel(from) + " 改为 " + helpLabel(to)), false);
        return 1;
    }

    /** `/pbmmusic -f <on|off>` —— **所有维度**的总开关都设成该值。 */
    private static int pbmMusicForceAll(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultPsdHelpAll(source.getServer(), enabled);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的屏蔽门开关门提示音 = " + helpLabel(enabled)), false);
        return 1;
    }

    /** `/pbmmusic -f <X> to <Y>` —— 所有维度里总开关正好是 X 的那些改成 Y。 */
    private static int pbmMusicForceFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultPsdHelpAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的屏蔽门开关门提示音是 " + helpLabel(from) + "，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有屏蔽门开关门提示音为 " + helpLabel(from) + " 的维度改成 " + helpLabel(to)), false);
        return 1;
    }

    /** `/pbmmusic open|close`（不带参数）—— 显示当前维度这一项子开关与默认素材。 */
    private static int pbmMusicItemShow(CommandContext<CommandSourceStack> context, String which) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean enabled = EscalatorSpeedManager.isPsdToneEnabled(level, which);
        String audio = EscalatorSpeedManager.getPsdToneAudio(level, which);
        source.sendSuccess(() -> Component.literal(
                "当前维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音：开关 " + helpLabel(enabled)
                        + "，默认素材 " + psdToneAudioLabel(audio)), false);
        return 1;
    }

    /** `/pbmmusic open|close <on|off>` —— 设置**本维度**这一项子开关。 */
    private static int pbmMusicItemGlobal(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultPsdToneEnabled(level, which, enabled);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度屏蔽门「"
                        + EscalatorSpeedData.psdToneLabel(which) + "」提示音已设为 " + helpLabel(enabled)
                        + "；其它维度不变"), false);
        return 1;
    }

    /** `/pbmmusic open|close <X> to <Y>` —— 本维度这一项子开关正好是 X 时才改成 Y。 */
    private static int pbmMusicItemFromTo(CommandContext<CommandSourceStack> context, String which,
                                          boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultPsdToneEnabled(level, which, from, to)) {
            boolean current = EscalatorSpeedManager.isPsdToneEnabled(level, which);
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音不是 " + helpLabel(from)
                            + "，未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音从 " + helpLabel(from)
                        + " 改为 " + helpLabel(to)), false);
        return 1;
    }

    /** `/pbmmusic -f open|close <on|off>` —— **所有维度**这一项子开关都设成该值。 */
    private static int pbmMusicItemForceAll(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultPsdToneEnabledAll(source.getServer(), which, enabled);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音 = "
                        + helpLabel(enabled)), false);
        return 1;
    }

    /** `/pbmmusic -f open|close <X> to <Y>` —— 所有维度里这一项子开关正好是 X 的改成 Y。 */
    private static int pbmMusicItemForceFromTo(CommandContext<CommandSourceStack> context, String which,
                                               boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultPsdToneEnabledAll(source.getServer(), which, from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音是 "
                            + helpLabel(from) + "，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音为 " + helpLabel(from)
                        + " 的维度改成 " + helpLabel(to)), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.15】/pbmmusic open|close <名字>：屏蔽门提示音的**默认素材**（与 /lifthelp up|down|door 对称）。
    //   数据层在维度默认那一份里（open → defaultPsdToneAudioOpen、close → defaultPsdToneAudioClose），
    //   走 chime 同步包下发；-f 还会清/改「按扇门单独设置」那一张表，所以额外再走一次 tone 同步包。
    // ------------------------------------------------------------------

    /**
     * {@code /pbmmusic open|close <名字>} —— 设置**本维度**这一项的默认素材。
     * 名字：{@code default}（跟内置，按端别落 dooropen/mdoorclose）/ {@code default-c}（doorclose.ogg）
     * / {@code default-m}（mdoorclose.ogg）/ {@code none}（这一项不播）/ 玩家导入的 .ogg 文件名。
     */
    private static int pbmMusicItemAudioSet(CommandContext<CommandSourceStack> context, String literal, String which) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolvePsdToneName(level, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal(arg.error()));
            return 0;
        }
        EscalatorSpeedManager.setDefaultPsdToneAudio(level, which, arg.id());
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度屏蔽门「"
                        + EscalatorSpeedData.psdToneLabel(which) + "」的默认素材已设为 "
                        + psdToneAudioLabel(arg.id())
                        + "；没有单独设置过这一项的门都会用它"), false);
        return 1;
    }

    /** {@code /pbmmusic open|close <X> to <Y>} —— 本维度默认素材正好是 X 时才改成 Y。 */
    private static int pbmMusicItemAudioFromTo(CommandContext<CommandSourceStack> context, String literal, String which) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolvePsdToneName(level, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal(from.error()));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolvePsdToneName(level, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal(to.error()));
            return 0;
        }
        if (!EscalatorSpeedManager.replaceDefaultPsdToneAudio(level, which, from.id(), to.id())) {
            String current = EscalatorSpeedManager.getPsdToneAudio(level, which);
            // 「不是 X 就没改」按惯例用 sendSuccess（不是错误，只是没命中），与直梯那套一致
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」的默认素材不是 "
                            + psdToneAudioLabel(from.id()) + "，未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」的默认素材从 "
                        + psdToneAudioLabel(from.id()) + " 改为 " + psdToneAudioLabel(to.id())), false);
        return 1;
    }

    /**
     * {@code /pbmmusic open|close -f <名字>} —— **所有维度**这一项的默认素材都设成它，
     * 并清掉「按扇门单独设置」里的这一项（那些门从此跟维度默认）。
     */
    private static int pbmMusicItemAudioForceSet(CommandContext<CommandSourceStack> context, String literal, String which) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolvePsdToneName(level, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal(arg.error()));
            return 0;
        }
        int changed = EscalatorSpeedManager.setDefaultPsdToneAudioAll(source.getServer(), which, arg.id());
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」默认素材 = "
                        + psdToneAudioLabel(arg.id())
                        + "，并清掉按扇门的单独设置"), false);
        return 1;
    }

    /** {@code /pbmmusic open|close -f <X> to <Y>} —— 所有维度（含单独设置）里这项是 X 的改成 Y。 */
    private static int pbmMusicItemAudioForceFromTo(CommandContext<CommandSourceStack> context, String which) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolvePsdToneName(level, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal(from.error()));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolvePsdToneName(level, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal(to.error()));
            return 0;
        }
        int changed = EscalatorSpeedManager.replaceDefaultPsdToneAudioAll(
                source.getServer(), which, from.id(), to.id());
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」默认素材是 "
                            + psdToneAudioLabel(from.id()) + "，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」默认素材为 "
                        + psdToneAudioLabel(from.id()) + " 的维度改成 " + psdToneAudioLabel(to.id())), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.50】/pbmloud：屏蔽门提示音音量（1~1000，100 = 原始音量，1000 = 10×）
    //   形状与 /lifthelploud 完全一致，只是「三项」变「两项」。
    // ------------------------------------------------------------------

    /** 注册 `/pbmloud` 的 `-f` 分支：`-f <音量>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmLoudForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(SmoothLift::pbmLoudForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(SmoothLift::pbmLoudForceFromTo))));
    }

    /** `/pbmloud open|close` 的音量子树（不带 -f）：`<音量>` 与 `<X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmLoudItemCommand(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> pbmLoudItemGlobal(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> pbmLoudItemFromTo(context, which)))));
    }

    /** `-f` 节点下的单项分支：`open|close <音量>` 与 `open|close <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmLoudItemForceBranch(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> pbmLoudItemForceAll(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> pbmLoudItemForceFromTo(context, which)))));
    }

    /** `/pbmloud`（不带参数）—— 显示当前维度生效的共用默认音量。 */
    private static int pbmLoudShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int volume = EscalatorSpeedManager.getPsdHelpVolume(level);
        source.sendSuccess(() -> Component.literal(
                "当前维度屏蔽门提示音共用音量：" + volume
                        + "，100 = 原始音量；开=" + pbmVolumeText(level, "open")
                        + "、关=" + pbmVolumeText(level, "close")
                        + "，范围 " + EscalatorSpeedData.AUDIO_VOLUME_MIN + "~"
                        + EscalatorSpeedData.AUDIO_VOLUME_MAX), false);
        return 1;
    }

    /** 单项音量的显示文本：没单独调过就标「跟随共用」。 */
    private static String pbmVolumeText(ServerLevel level, String which) {
        int v = EscalatorSpeedManager.getPsdToneVolume(level, which);
        return EscalatorSpeedManager.hasOwnPsdToneVolume(level, which) ? String.valueOf(v) : v + "跟随共用";
    }

    /** `/pbmloud <音量>` —— 设置**本维度**的共用默认音量。 */
    private static int pbmLoudGlobal(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultPsdHelpVolume(level, volume);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdHelpVolume(level);
        source.sendSuccess(() -> Component.literal(
                "本维度屏蔽门提示音共用音量已设为 " + applied
                        + "；其它维度不变"),
                false);
        return 1;
    }

    /** `/pbmloud <X> to <Y>` —— 本维度共用音量正好是 X 时才改成 Y。 */
    private static int pbmLoudFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getPsdHelpVolume(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门提示音共用音量不是 " + from + "，未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultPsdHelpVolume(level, from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdHelpVolume(level);
        source.sendSuccess(() -> Component.literal(
                "本维度屏蔽门提示音共用音量从 " + from + " 改为 " + applied), false);
        return 1;
    }

    /** `/pbmloud -f <音量>` —— **所有维度**的共用音量都设成该值。 */
    private static int pbmLoudForceAll(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultPsdHelpVolumeAll(source.getServer(), volume);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedData.clampLiftHelpVolume(volume);
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的屏蔽门提示音共用音量 = " + applied), false);
        return 1;
    }

    /** `/pbmloud -f <X> to <Y>` —— 所有维度里共用音量正好是 X 的那些改成 Y。 */
    private static int pbmLoudForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultPsdHelpVolumeAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的屏蔽门提示音共用音量是 " + from + "，未做修改"), false);
            return 0;
        }
        int applied = EscalatorSpeedData.clampLiftHelpVolume(to);
        source.sendSuccess(() -> Component.literal(
                "已把所有屏蔽门提示音共用音量为 " + from + " 的维度改成 " + applied), false);
        return 1;
    }

    /** `/pbmloud open|close <音量>` —— 设置**本维度**这一项的音量。 */
    private static int pbmLoudItemGlobal(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultPsdToneVolume(level, which, volume);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdToneVolume(level, which);
        source.sendSuccess(() -> Component.literal(
                "本维度屏蔽门「"
                        + EscalatorSpeedData.psdToneLabel(which) + "」提示音音量已设为 " + applied), false);
        return 1;
    }

    /** `/pbmloud open|close <X> to <Y>` —— 本维度这一项音量正好是 X 时才改成 Y。 */
    private static int pbmLoudItemFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getPsdToneVolume(level, which);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音音量不是 " + from
                            + "，未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultPsdToneVolume(level, which, from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdToneVolume(level, which);
        source.sendSuccess(() -> Component.literal(
                "本维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音音量从 " + from
                        + " 改为 " + applied), false);
        return 1;
    }

    /** `/pbmloud -f open|close <音量>` —— **所有维度**这一项都设成该音量。 */
    private static int pbmLoudItemForceAll(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultPsdToneVolumeAll(source.getServer(), which, volume);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedData.clampPsdToneVolume(volume);
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音音量 = "
                        + applied), false);
        return 1;
    }

    /** `/pbmloud -f open|close <X> to <Y>` —— 所有维度里这一项音量正好是 X 的改成 Y。 */
    private static int pbmLoudItemForceFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultPsdToneVolumeAll(source.getServer(), which, from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音音量是 "
                            + from + "，未做修改"), false);
            return 0;
        }
        int applied = EscalatorSpeedData.clampPsdToneVolume(to);
        source.sendSuccess(() -> Component.literal(
                "已把所有屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音音量为 " + from
                        + " 的维度改成 " + applied), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.22】/pbmmidiumloud / /pbmarriveloud：到站播报 / 进站报站
    //   **各自那一项**的音量。形状与 /pbmloud 完全一致。
    // ------------------------------------------------------------------

    /** 这一项的中文名（反馈文案用）。 */
    private static String pbmItemLabel(String which) {
        return "midium".equals(which) ? "到站播报" : "进站报站";
    }

    /** 这一项音量指令的名字（反馈里提示 -f 用哪个指令）。 */
    private static String pbmItemCommand(String which) {
        return "midium".equals(which) ? "/pbmmidiumloud" : "/pbmarriveloud";
    }

    /** 注册 `-f` 分支：`-f <音量>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmItemLoudForce(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> pbmItemLoudForceAll(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> pbmItemLoudForceFromTo(context, which)))));
    }

    /** 无参数——显示当前维度这一项生效的音量。 */
    private static int pbmItemLoudShow(CommandContext<CommandSourceStack> context, String which) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int v = EscalatorSpeedManager.getPsdItemVolume(level, which);
        boolean own = EscalatorSpeedManager.hasOwnPsdItemVolume(level, which);
        source.sendSuccess(() -> Component.literal(
                "当前维度屏蔽门" + pbmItemLabel(which) + "音量：" + v
                        + (own ? "" : " 跟随共用")
                        + "；范围 " + EscalatorSpeedData.AUDIO_VOLUME_MIN + "~"
                        + EscalatorSpeedData.AUDIO_VOLUME_MAX), false);
        return 1;
    }

    /** `<音量>` —— 设置**本维度**这一项的音量。 */
    private static int pbmItemLoudGlobal(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if ("midium".equals(which)) {
            EscalatorSpeedManager.setDefaultPsdMidiumVolume(level, volume);
        } else {
            EscalatorSpeedManager.setDefaultPsdArriveVolume(level, volume);
        }
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdItemVolume(level, which);
        source.sendSuccess(() -> Component.literal(
                "本维度 " + level.dimension().location() + " 屏蔽门" + pbmItemLabel(which)
                        + "音量已设为 " + applied
                        + "；其它维度不变，要对所有维度生效用 "
                        + pbmItemCommand(which) + " -f " + applied), false);
        return 1;
    }

    /** `<X> to <Y>` —— 本维度这一项音量正好是 X 时才改成 Y。 */
    private static int pbmItemLoudFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getPsdItemVolume(level, which);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门" + pbmItemLabel(which) + "音量不是 " + from
                            + "，当前为 " + current + "，未做修改"), false);
            return 0;
        }
        if ("midium".equals(which)) {
            EscalatorSpeedManager.replaceDefaultPsdMidiumVolume(level, from, to);
        } else {
            EscalatorSpeedManager.replaceDefaultPsdArriveVolume(level, from, to);
        }
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdItemVolume(level, which);
        source.sendSuccess(() -> Component.literal(
                "本维度屏蔽门" + pbmItemLabel(which) + "音量从 " + from
                        + " 改为 " + applied), false);
        return 1;
    }

    /** `-f <音量>` —— **所有维度**这一项都设成该音量。 */
    private static int pbmItemLoudForceAll(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        int changed = "midium".equals(which)
                ? EscalatorSpeedManager.setDefaultPsdMidiumVolumeAll(source.getServer(), volume)
                : EscalatorSpeedManager.setDefaultPsdArriveVolumeAll(source.getServer(), volume);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedData.clampPsdToneVolume(volume);
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的屏蔽门" + pbmItemLabel(which)
                        + "音量 = " + applied + "，改动 " + changed + " 个维度"), false);
        return 1;
    }

    /** `-f <X> to <Y>` —— 所有维度里这一项音量正好是 X 的改成 Y。 */
    private static int pbmItemLoudForceFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = "midium".equals(which)
                ? EscalatorSpeedManager.replaceDefaultPsdMidiumVolumeAll(source.getServer(), from, to)
                : EscalatorSpeedManager.replaceDefaultPsdArriveVolumeAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的屏蔽门" + pbmItemLabel(which)
                            + "音量是 " + from + "，未做修改"), false);
            return 0;
        }
        int applied = EscalatorSpeedData.clampPsdToneVolume(to);
        source.sendSuccess(() -> Component.literal(
                "已把所有屏蔽门" + pbmItemLabel(which) + "音量为 " + from
                        + " 的维度改成 " + applied + "，共 " + changed + " 个维度"),
                false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.50 / 1.23】三类「淡入淡出范围」（格）—— 四条指令共用同一套形状
    //
    //   /pbmround        = 屏蔽门开关门提示音（open / close 两项共用一份）
    //   /pbmmusicround   = 同上（用户点名的名字；与 /pbmround 读写**同一份数据**）
    //   /pbmmidiumround  = 到站播报（/pbmmidium 那条音频）
    //   /pbmarriveround  = 进站报站（/pbmarrive 那条音频）
    //
    //   形状完全同构（都是「一个整数 + to + -f」）：
    //     （无参数）      -> 显示当前维度生效的范围
    //     <范围>          -> 本维度范围 = 范围（1~128）
    //     <X> to <Y>      -> 本维度范围正好是 X 时才改成 Y
    //     -f <范围>       -> 强制**所有维度** = 范围
    //     -f <X> to <Y>   -> 所有维度里范围正好是 X 的改成 Y
    //
    //   ★ 为什么三类要各存一份而不是继续共用提示音那一个值：开关门提示音是**机械事件**
    //     （站在门口听最合理，默认 16 格），站台广播 / 进站报站是**说给整个站台听的**，
    //     用户希望各自能调 —— 与 1.22 把三类**音量**拆开是同一个理由。
    //   ★ 三者默认都是 16 格 ⇒ 老存档、以及没敲过这几条指令时，行为与 1.22 及以前**逐位相同**。
    //   ★ 四条指令的树由 {@link #roundCommand} 一处产出 ⇒ 形状想不一致都难。
    // ------------------------------------------------------------------

    /** 这几条范围指令读写的是**哪一组**字段。 */
    private enum RoundKind {
        /** 屏蔽门开关门提示音（{@code /pbmround}、{@code /pbmmusicround}）。 */
        TONE("屏蔽门提示音"),
        /** 到站播报（{@code /pbmmidiumround}）。 */
        MIDIUM("到站播报"),
        /** 进站报站（{@code /pbmarriveround}）。 */
        ARRIVE("进站报站");

        /** 反馈文案里的那一项名字（与 /pbmloud 那一套用词一致）。 */
        private final String label;

        RoundKind(String label) {
            this.label = label;
        }
    }

    /** 读「本维度」的范围。 */
    private static int roundOf(CommandSourceStack source, RoundKind kind) {
        return switch (kind) {
            case MIDIUM -> EscalatorSpeedManager.getPsdMidiumRound(source.getLevel());
            case ARRIVE -> EscalatorSpeedManager.getPsdArriveRound(source.getLevel());
            default -> EscalatorSpeedManager.getPsdHelpRound(source.getLevel());
        };
    }

    /** 写「本维度」的范围。 */
    private static void setRound(ServerLevel level, RoundKind kind, int round) {
        switch (kind) {
            case MIDIUM -> EscalatorSpeedManager.setDefaultPsdMidiumRound(level, round);
            case ARRIVE -> EscalatorSpeedManager.setDefaultPsdArriveRound(level, round);
            default -> EscalatorSpeedManager.setDefaultPsdHelpRound(level, round);
        }
    }

    /** 「本维度范围正好是 X 才改成 Y」；返回成没成。 */
    private static boolean replaceRound(ServerLevel level, RoundKind kind, int from, int to) {
        return switch (kind) {
            case MIDIUM -> EscalatorSpeedManager.replaceDefaultPsdMidiumRound(level, from, to);
            case ARRIVE -> EscalatorSpeedManager.replaceDefaultPsdArriveRound(level, from, to);
            default -> EscalatorSpeedManager.replaceDefaultPsdHelpRound(level, from, to);
        };
    }

    /** 「所有维度 = 范围」；返回改动个数。 */
    private static int setRoundAll(MinecraftServer server, RoundKind kind, int round) {
        return switch (kind) {
            case MIDIUM -> EscalatorSpeedManager.setDefaultPsdMidiumRoundAll(server, round);
            case ARRIVE -> EscalatorSpeedManager.setDefaultPsdArriveRoundAll(server, round);
            default -> EscalatorSpeedManager.setDefaultPsdHelpRoundAll(server, round);
        };
    }

    /** 「所有维度里范围正好是 X 的改成 Y」；返回改动个数。 */
    private static int replaceRoundAll(MinecraftServer server, RoundKind kind, int from, int to) {
        return switch (kind) {
            case MIDIUM -> EscalatorSpeedManager.replaceDefaultPsdMidiumRoundAll(server, from, to);
            case ARRIVE -> EscalatorSpeedManager.replaceDefaultPsdArriveRoundAll(server, from, to);
            default -> EscalatorSpeedManager.replaceDefaultPsdHelpRoundAll(server, from, to);
        };
    }

    /** 造一条范围指令（含 `-f` 分支）；四条指令都由它产出 ⇒ 形状必然一致。 */
    private static LiteralArgumentBuilder<CommandSourceStack> roundCommand(String literal, RoundKind kind) {
        return Commands.literal(literal)
                .executes(context -> roundShow(context, kind))
                .then(Commands.argument("round", roundArg())
                        .executes(context -> roundGlobal(context, kind))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", roundArg())
                                        .executes(context -> roundFromTo(context, kind)))))
                .then(roundForce(kind));
    }

    /** 范围指令的 `-f` 分支：`-f <范围>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> roundForce(RoundKind kind) {
        return Commands.literal("-f")
                .then(Commands.argument("round", roundArg())
                        .executes(context -> roundForceAll(context, kind))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", roundArg())
                                        .executes(context -> roundForceFromTo(context, kind)))));
    }

    /** （不带参数）—— 显示当前维度生效的淡入淡出范围。 */
    private static int roundShow(CommandContext<CommandSourceStack> context, RoundKind kind) {
        CommandSourceStack source = context.getSource();
        int round = roundOf(source, kind);
        source.sendSuccess(() -> Component.literal(
                "当前维度" + kind.label + "淡入淡出范围：" + round + " 格"), false);
        return 1;
    }

    /** `<范围>` —— 设置**本维度**的范围。 */
    private static int roundGlobal(CommandContext<CommandSourceStack> context, RoundKind kind) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        setRound(level, kind, round);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = roundOf(source, kind);
        source.sendSuccess(() -> Component.literal(
                "本维度" + kind.label + "淡入淡出范围已设为 " + applied
                        + " 格；其它维度不变"), false);
        return 1;
    }

    /** `<X> to <Y>` —— 本维度范围正好是 X 时才改成 Y。 */
    private static int roundFromTo(CommandContext<CommandSourceStack> context, RoundKind kind) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!replaceRound(level, kind, from, to)) {
            source.sendSuccess(() -> Component.literal(
                    "本维度" + kind.label + "淡入淡出范围不是 " + from + " 格，未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = roundOf(source, kind);
        source.sendSuccess(() -> Component.literal(
                "本维度" + kind.label + "淡入淡出范围从 " + from + " 格改为 " + applied + " 格"), false);
        return 1;
    }

    /** `-f <范围>` —— **所有维度**都设成该范围。 */
    private static int roundForceAll(CommandContext<CommandSourceStack> context, RoundKind kind) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        setRoundAll(source.getServer(), kind, round);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = roundOf(source, kind);
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的" + kind.label + "淡入淡出范围 = " + applied
                        + " 格"), false);
        return 1;
    }

    /** `-f <X> to <Y>` —— 所有维度里范围正好是 X 的那些改成 Y。 */
    private static int roundForceFromTo(CommandContext<CommandSourceStack> context, RoundKind kind) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = replaceRoundAll(source.getServer(), kind, from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的" + kind.label + "淡入淡出范围是 " + from + " 格，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有" + kind.label + "淡入淡出范围为 " + from + " 格的维度改成 " + to
                        + " 格"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.16】/pbmclosewait：关门提示音的「强制等待时长」（秒）
    //
    //   语义（只在「停站时长不够放完整条关门素材」时生效）：
    //     开门音效播完 → 等 N 秒 → 播语音播报 → 门一动（嘀嘀开始）就立刻掐断这段人声。
    //   停站够长时这个值被**完全忽略**（走「整段提前播、结尾落在门上」那套）。
    //   默认 5 秒（第一次加入模组时就是这个值）。
    //
    //   形状与 /pbmround 完全同构：显示 / <秒> / <X> to <Y> / -f <秒> / -f <X> to <Y>。
    // ------------------------------------------------------------------

    /** 注册 `/pbmclosewait` 的 `-f` 分支：`-f <秒>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmCloseWaitForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("seconds", closeWaitArg())
                        .executes(SmoothLift::pbmCloseWaitForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", closeWaitArg())
                                        .executes(SmoothLift::pbmCloseWaitForceFromTo))));
    }

    /** `/pbmclosewait`（不带参数）—— 显示当前维度生效的强制等待时长。 */
    private static int pbmCloseWaitShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int seconds = EscalatorSpeedManager.getPsdCloseWaitSeconds(level);
        source.sendSuccess(() -> Component.literal(
                "当前维度关门提示音强制等待时长：" + seconds + " 秒"
                        + "。只在「停站时长不够放完整条关门素材」时生效："
                        + "开门音播完后等这么多秒再放人声，门一动就把人声掐断；"
                        + "停站够长时这个值被完全忽略"), false);
        return 1;
    }

    /** `/pbmclosewait <秒>` —— 设置**本维度**的强制等待时长。 */
    private static int pbmCloseWaitGlobal(CommandContext<CommandSourceStack> context) {
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultPsdCloseWaitSeconds(level, seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdCloseWaitSeconds(level);
        source.sendSuccess(() -> Component.literal(
                "本维度关门提示音强制等待时长已设为 " + applied
                        + " 秒；其它维度不变"), false);
        return 1;
    }

    /** `/pbmclosewait <X> to <Y>` —— 本维度正好是 X 时才改成 Y。 */
    private static int pbmCloseWaitFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "seconds");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getPsdCloseWaitSeconds(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "本维度关门提示音强制等待时长不是 " + from + " 秒，未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultPsdCloseWaitSeconds(level, from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdCloseWaitSeconds(level);
        source.sendSuccess(() -> Component.literal(
                "本维度关门提示音强制等待时长从 " + from + " 秒改为 " + applied + " 秒"), false);
        return 1;
    }

    /** `/pbmclosewait -f <秒>` —— **所有维度**都设成该值。 */
    private static int pbmCloseWaitForceAll(CommandContext<CommandSourceStack> context) {
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultPsdCloseWaitSecondsAll(source.getServer(), seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdCloseWaitSeconds(source.getLevel());
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的关门提示音强制等待时长 = " + applied
                        + " 秒"), false);
        return 1;
    }

    /** `/pbmclosewait -f <X> to <Y>` —— 所有维度里正好是 X 的那些改成 Y。 */
    private static int pbmCloseWaitForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "seconds");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultPsdCloseWaitSecondsAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的关门提示音强制等待时长是 " + from + " 秒，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有关门提示音强制等待时长为 " + from + " 秒的维度改成 " + to
                        + " 秒"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.17】/pbmmidium：到站播报（素材名 + 等待秒数）
    //
    //   语义（与关门提示音那一套**互不相干**，两段声音各自独立）：
    //     列车到站 → 屏蔽门**开门音（嘀嘀嘀）播完** → 等 Y 秒 → 播这一段语音播报。
    //   ★ 它**永远不会被掐断**（用户点名「即使列车出站也要继续播放，直到播完」）——
    //     门关不关、车走不走都不影响它。
    //
    //   形状：显示 / <名字> / <名字> <秒> / -f <名字> <秒>。
    // ------------------------------------------------------------------

    /** 注册 `/pbmmidium` 的 `-f` 分支：`-f <名字> <秒>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmMidiumForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("name", midiumNameArg())
                        .suggests(SmoothLift::pbmMidiumNameSuggestions)
                        .then(Commands.argument("seconds", midiumWaitArg())
                                .executes(SmoothLift::pbmMidiumForceAll)));
    }

    /** `/pbmmidium`（不带参数）—— 显示当前维度生效的到站播报。 */
    private static int pbmMidiumShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String id = EscalatorSpeedManager.getPsdMidiumAudio(level);
        int seconds = EscalatorSpeedManager.getPsdMidiumWaitSeconds(level);
        boolean off = EscalatorSpeedData.isPsdMidiumOff(id);
        source.sendSuccess(() -> Component.literal(
                "当前维度到站播报：" + (off ? "不播" : "「" + id + "」")
                        + "，等待 " + seconds + " 秒"
                        + "。含义：列车到站、开门音播完后再等这么多秒开始播报；"
                        + "这段播报**不会被掐断**。"
                        + "等待秒数允许 0 ~ 正无穷"), false);
        return 1;
    }

    /** `/pbmmidium <名字>` —— 只改素材，保留当前等待秒数。 */
    private static int pbmMidiumSetNameOnly(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int keep = EscalatorSpeedManager.getPsdMidiumWaitSeconds(level);
        return pbmMidiumApply(source, level, name, keep, null);
    }

    /** `/pbmmidium <名字> <秒>` —— 设置**本维度**。 */
    private static int pbmMidiumGlobal(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        return pbmMidiumApply(source, level, name, seconds, "；其它维度不变");
    }

    /** `/pbmmidium -f <名字> <秒>` —— **所有维度**。 */
    private static int pbmMidiumForceAll(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        String resolved = EscalatorSpeedManager.resolvePsdMidiumName(source.getLevel(), name);
        if (resolved == null) {
            sendUnknownMidiumName(source, name);
            return 0;
        }
        int changed = EscalatorSpeedManager.setDefaultPsdMidiumAll(source.getServer(), resolved, seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdMidiumWaitSeconds(source.getLevel());
        boolean off = EscalatorSpeedData.isPsdMidiumOff(resolved);
        source.sendSuccess(() -> Component.literal(
                "已把所有维度的到站播报设为 " + (off ? "不播" : "「" + resolved + "」")
                        + "、等待 " + applied + " 秒"), false);
        return 1;
    }

    /**
     * `本维度` 那条路共用的落地：解析名字 → 落库 → 同步 → 反馈。
     *
     * @param suffix 反馈尾注（`null` = 不带尾注）
     */
    private static int pbmMidiumApply(CommandSourceStack source, ServerLevel level,
                                      String name, int seconds, String suffix) {
        String resolved = EscalatorSpeedManager.resolvePsdMidiumName(level, name);
        if (resolved == null) {
            sendUnknownMidiumName(source, name);
            return 0;
        }
        EscalatorSpeedManager.setDefaultPsdMidium(level, resolved, seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdMidiumWaitSeconds(level);
        boolean off = EscalatorSpeedData.isPsdMidiumOff(resolved);
        String tail = suffix == null ? "" : suffix;
        source.sendSuccess(() -> Component.literal(
                "本维度到站播报已设为 "
                        + (off ? "不播" : "「" + resolved + "」")
                        + "、等待 " + applied + " 秒" + tail), false);
        return 1;
    }

    /** 「这个名字找不到」的统一提示（顺便列出库里已有的名字）。 */
    private static void sendUnknownMidiumName(CommandSourceStack source, String name) {
        java.util.List<String> have = EscalatorSpeedManager.psdMidiumSuggestions(source.getLevel());
        String list = String.join("、", have);
        source.sendFailure(Component.literal(
                "找不到名为「" + name + "」的音频。"
                        + "已有的：" + (list.isEmpty() ? "" : list)));
    }

    /** `/pbmmidium` 素材名参数的 Tab 补全。 */
    private static CompletableFuture<Suggestions> pbmMidiumNameSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        CommandSourceStack source = context.getSource();
        if (source == null) {
            return builder.buildFuture();
        }
        String typed = builder.getRemainingLowerCase();
        for (String candidate : EscalatorSpeedManager.psdMidiumSuggestions(source.getLevel())) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }

    // ------------------------------------------------------------------
    // 【1.21】/pbmarrive：**进站报站**（素材名 + 秒数：-X = 最近一班车还剩 X 秒到站时播）
    //
    //   语义（与到站播报**互相独立**，两段声音各自播各自的）：
    //     时刻表里下一班车还有 |X| 秒到站 → 播这一段语音，一直播到完。
    //   ★ 与 /pbmmidium 的**唯一本质差别**是触发时刻：到站播报在「开门音之后 + Y 秒」，
    //     进站报站在「开门音**之前** |X| 秒」。相同的是「永远不会被掐断」。
    //   ★ 触发靠的是**时刻表**（MTR 自己的到达缓存，见 MtrDwellAccess#nextArrivalRemainingMs），
    //     不是靠猜列车位置/速度 —— 用户点名「看时刻表啊，不要猜」。
    //
    //   形状：显示 / <名字> / <名字> <X> / -f <名字> <X>。
    // ------------------------------------------------------------------

    /** 注册 `/pbmarrive` 的 `-f` 分支：`-f <名字> <X>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> pbmArriveForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("name", arriveNameArg())
                        .suggests(SmoothLift::pbmArriveNameSuggestions)
                        .then(Commands.argument("seconds", arriveArg())
                                .executes(SmoothLift::pbmArriveForceAll)));
    }

    /** `/pbmarrive`（不带参数）—— 显示当前维度生效的进站报站。 */
    private static int pbmArriveShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String id = EscalatorSpeedManager.getPsdArriveAudio(level);
        int seconds = EscalatorSpeedManager.getPsdArriveSeconds(level);
        boolean off = EscalatorSpeedData.isPsdArriveOff(id);
        source.sendSuccess(() -> Component.literal(
                "当前维度进站报站：" + (off ? "不播" : "「" + id + "」")
                        + "，到站前 " + (-seconds) + " 秒"
                        + "。含义：**读 MTR 时刻表**，这个站台「最近的一班列车还剩这么多秒到站」时开始播报"
                        + "；"
                        + "这段播报**不会被掐断**。"
                        + "秒数允许 (-∞, 0]"), false);
        return 1;
    }

    /** `/pbmarrive <名字>` —— 只改素材，保留当前秒数。 */
    private static int pbmArriveSetNameOnly(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int keep = EscalatorSpeedManager.getPsdArriveSeconds(level);
        return pbmArriveApply(source, level, name, keep, null);
    }

    /** `/pbmarrive <名字> <X>` —— 设置**本维度**。 */
    private static int pbmArriveGlobal(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        return pbmArriveApply(source, level, name, seconds, "；其它维度不变");
    }

    /** `/pbmarrive -f <名字> <X>` —— **所有维度**。 */
    private static int pbmArriveForceAll(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        int seconds = IntegerArgumentType.getInteger(context, "seconds");
        CommandSourceStack source = context.getSource();
        String resolved = EscalatorSpeedManager.resolvePsdArriveName(source.getLevel(), name);
        if (resolved == null) {
            sendUnknownArriveName(source, name);
            return 0;
        }
        int changed = EscalatorSpeedManager.setDefaultPsdArriveAll(source.getServer(), resolved, seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdArriveSeconds(source.getLevel());
        boolean off = EscalatorSpeedData.isPsdArriveOff(resolved);
        source.sendSuccess(() -> Component.literal(
                "已把所有维度的进站报站设为 " + (off ? "不播" : "「" + resolved + "」")
                        + "、到站前 " + (-applied) + " 秒"), false);
        return 1;
    }

    /** `本维度` 那条路共用的落地：解析名字 → 落库 → 同步 → 反馈。 */
    private static int pbmArriveApply(CommandSourceStack source, ServerLevel level,
                                      String name, int seconds, String suffix) {
        String resolved = EscalatorSpeedManager.resolvePsdArriveName(level, name);
        if (resolved == null) {
            sendUnknownArriveName(source, name);
            return 0;
        }
        EscalatorSpeedManager.setDefaultPsdArrive(level, resolved, seconds);
        EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getPsdArriveSeconds(level);
        boolean off = EscalatorSpeedData.isPsdArriveOff(resolved);
        String tail = suffix == null ? "" : suffix;
        source.sendSuccess(() -> Component.literal(
                "本维度进站报站已设为 "
                        + (off ? "不播" : "「" + resolved + "」")
                        + "、到站前 " + (-applied) + " 秒" + tail), false);
        return 1;
    }

    /** 「这个名字找不到」的统一提示（顺便列出库里已有的名字）。 */
    private static void sendUnknownArriveName(CommandSourceStack source, String name) {
        java.util.List<String> have = EscalatorSpeedManager.psdArriveSuggestions(source.getLevel());
        String list = String.join("、", have);
        source.sendFailure(Component.literal(
                "找不到名为「" + name + "」的音频。"
                        + "已有的：" + (list.isEmpty() ? "" : list)));
    }

    /** `/pbmarrive` 素材名参数的 Tab 补全。 */
    private static CompletableFuture<Suggestions> pbmArriveNameSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        CommandSourceStack source = context.getSource();
        if (source == null) {
            return builder.buildFuture();
        }
        String typed = builder.getRemainingLowerCase();
        for (String candidate : EscalatorSpeedManager.psdArriveSuggestions(source.getLevel())) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                builder.suggest(candidate);
            }
        }
        return builder.buildFuture();
    }

    /**
     * 注册全部指令：`/futispeed`、`/jietispeed`、`/futimusic`、`/futiloud`、`/futihelp`、
     * `/futihelploud`、`/futiround`、`/futihelpround`、`/futihelpspeed`、`/futihelpmusic`，
     * 以及【1.42】直梯提示音的 `/lifthelp`、`/lifthelpspeed`、【1.43】`/lifthelploud`，
     * 以及【1.50】屏蔽门提示音的 `/pbmmusic`、`/pbmloud`、`/pbmround`、
     * 【1.16】`/pbmclosewait`。
     *
     * <p>单独抽成一个方法是为了能**脱离游戏环境**直接建一棵 Brigadier 指令树来校验：
     * {@code _tools/CmdTreeCheck.java} 会把整棵树的补全项 dump 出来，确认
     * {@code /futihelp on to off}、{@code /futihelploud -f 200 to 300} 这类分支真的可达、
     * Tab 补全能补得出来。
     */
    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /futispeed：全局扶梯运行速度（只作用于「全局扶梯」＝没被石斧改过、或改完仍与全局一致的）
        //   （无参数）   -> 显示当前扶梯速度（站在扶梯上就显示那一条，否则显示全局默认）
        //   X           -> 全局运行速度 = X
        //   X to Y      -> 只有当前全局运行速度正好是 X 时才改成 Y（没有速度为 X 的就不改）
        //   -f X        -> 强制游戏内所有扶梯运行速度 = X（不管有没有被改过）
        //   -f X to Y   -> 把所有运行速度为 X 的扶梯（含被改过的）改成 Y
        dispatcher.register(Commands.literal("futispeed")
            .executes(SmoothLift::futiShow)
            .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                .executes(SmoothLift::futiGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                        .executes(SmoothLift::futiFromTo))))
            .then(futiForce("-f"))
        );

        // /jietispeed：全局扶梯阶梯速度（**永远不动运行速度**）
        //   （无参数）   -> 显示当前阶梯速度
        //   X           -> 全局阶梯速度 = X
        //   X to Y      -> 只有当前全局阶梯速度正好是 X 时才改成 Y
        //   -f X        -> 强制所有扶梯阶梯速度 = X
        //   -f X to Y   -> 把所有阶梯速度为 X 的扶梯改成 Y
        dispatcher.register(Commands.literal("jietispeed")
            .executes(SmoothLift::jietiShow)
            .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                .executes(SmoothLift::jietiGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                        .executes(SmoothLift::jietiFromTo))))
            .then(jietiForce("-f"))
        );

        // /futimusic：扶梯音频（数据模型与 futispeed 完全对称）
        //   （无参数）     -> 显示当前扶梯播放的音频名
        //   <名字>         -> 默认音频 = 名字（已单独绑过音频的扶梯不变）
        //   <X> to <Y>     -> 默认音频正好是 X 时才改成 Y（单独绑定的一律不动）
        //   -f <名字>      -> 强制游戏内所有扶梯都用这个名字的音频
        //   -f <X> to <Y>  -> 把所有音频为 X 的扶梯（含单独绑定的）改成 Y
        // 名字可以是 `default`（模组内置音频）、`off`（清除默认音频），
        // 或玩家上传的音频文件名（要带后缀，如 example.ogg）。
        dispatcher.register(Commands.literal("futimusic")
            .executes(SmoothLift::futiMusicShow)
            .then(Commands.argument("name", StringArgumentType.string())
                .executes(SmoothLift::futiMusicSet)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", StringArgumentType.string())
                        .executes(SmoothLift::futiMusicFromTo))))
            .then(futiMusicForce("-f"))
        );

        // /futiloud：扶梯音量（数据模型与 /futispeed、/futimusic 完全对称）
        //   （无参数）     -> 显示当前扶梯音量
        //   <音量>         -> 默认音量 = 音量（单独设置过音量的扶梯不变）
        //   <X> to <Y>     -> 默认音量正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f <音量>      -> 强制游戏内所有扶梯都用这个音量（清掉单独设置）
        //   -f <X> to <Y>  -> 把所有音量正好是 X 的扶梯（含单独设置的）改成 Y
        // 音量范围 1~1000：100 = 原始音量，1000 = 10× 放大（>100 才谈得上"放大"）。
        dispatcher.register(Commands.literal("futiloud")
            .executes(SmoothLift::futiLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::futiLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::futiLoudFromTo))))
            .then(futiLoudForce("-f"))
        );

        // /futihelp：无障碍提示音（香港式「视障人士提升音」）开关，数据模型与 /futispeed 完全对称。
        //   （无参数）    -> 显示当前扶梯的提示音开关
        //   on | off      -> 默认开关 = on/off（单独设置过的扶梯不变）
        //   on to off     -> 默认开关正好是 on 时才改成 off（单独设置的一律不动）
        //   -f on | off   -> 强制游戏内所有扶梯 = on/off（清掉单独设置）
        //   -f on to off  -> 把所有生效开关正好是 on 的扶梯（含单独设置的）改成 off
        // 打开/关闭的是「进扶梯一端急促咔咔、出扶梯一端缓慢咔咔」这路提示音；
        // 扶梯被停掉（status=false）时本来就静音，与本开关无关。
        dispatcher.register(Commands.literal("futihelp")
            .executes(SmoothLift::futiHelpShow)
            .then(Commands.literal("on")
                .executes(context -> futiHelpGlobal(context, true))
                .then(Commands.literal("to")
                    .then(Commands.literal("off")
                        .executes(context -> futiHelpFromTo(context, true, false)))))
            .then(Commands.literal("off")
                .executes(context -> futiHelpGlobal(context, false))
                .then(Commands.literal("to")
                    .then(Commands.literal("on")
                        .executes(context -> futiHelpFromTo(context, false, true)))))
            .then(futiHelpForce("-f"))
        );


        // /futihelploud：无障碍**提示音**音量（数据模型与 /futiloud、/futihelp 完全对称）。
        //   （无参数）     -> 显示当前扶梯的提示音音量
        //   <音量>         -> 默认音量 = 音量（单独设置过音量的扶梯不变）
        //   <X> to <Y>     -> 默认音量正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f <音量>      -> 强制游戏内所有扶梯提示音都用这个音量（清掉单独设置）
        //   -f <X> to <Y>  -> 把所有音量正好是 X 的扶梯（含单独设置的）改成 Y
        // 音量范围 1~1000：100 = 原始音量，1000 = 10× 放大。
        // ★ 这与 /futiloud 是两件事：/futiloud = 扶梯**运行底噪**（整条扶梯、射程 16 格）的音量，
        //   本指令 = 无障碍**提示音**（端头单块、射程 4 格）的音量。数据与指令互不影响。
        dispatcher.register(Commands.literal("futihelploud")
            .executes(SmoothLift::futiHelpLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::futiHelpLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::futiHelpLoudFromTo))))
            .then(futiHelpLoudForce("-f"))
        );

        // /futiround：扶梯**运行底噪**（整条扶梯一起响）的淡入淡出范围（单位格，默认 16）。
        //   （无参数）     -> 显示当前扶梯的底噪范围
        //   <范围>         -> 默认范围 = 范围（单独设置过的扶梯不变）
        //   <X> to <Y>     -> 默认范围正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f <范围>      -> 强制游戏内所有扶梯都用这个范围（清掉单独设置）
        //   -f <X> to <Y>  -> 把所有范围正好是 X 的扶梯（含单独设置的）改成 Y
        // 范围 1~128 格。★ 这与 /futihelpround 是两件事：本指令管**整条扶梯**的运行底噪（默认 16 格），
        //   /futihelpround 管端头**单块**的无障碍提示音（默认 4 格）。数据与指令互不影响。
        dispatcher.register(Commands.literal("futiround")
            .executes(SmoothLift::futiRoundShow)
            .then(Commands.argument("round", roundArg())
                .executes(SmoothLift::futiRoundGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", roundArg())
                        .executes(SmoothLift::futiRoundFromTo))))
            .then(futiRoundForce("-f"))
        );

        // /futihelpround：无障碍**提示音**（端头单块）的淡入淡出范围（单位格，默认 4）。
        //   （无参数）     -> 显示当前扶梯的提示音范围
        //   <范围>         -> 默认范围 = 范围（单独设置过的扶梯不变）
        //   <X> to <Y>     -> 默认范围正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f <范围>      -> 强制游戏内所有扶梯提示音都用这个范围（清掉单独设置）
        //   -f <X> to <Y>  -> 把所有范围正好是 X 的扶梯（含单独设置的）改成 Y
        // 范围 1~128 格。★ 与 /futiround 是两件事（见上）。
        dispatcher.register(Commands.literal("futihelpround")
            .executes(SmoothLift::futiHelpRoundShow)
            .then(Commands.argument("round", roundArg())
                .executes(SmoothLift::futiHelpRoundGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", roundArg())
                        .executes(SmoothLift::futiHelpRoundFromTo))))
            .then(futiHelpRoundForce("-f"))
        );

        // /futihelpspeed：无障碍**提示音**（端头单块）的**速率**（单位 Hz，每秒响几次，1~20）。
        //   （无参数）            -> 显示这条扶梯两头当前的速率
        //   in|out <Hz>          -> 默认速率 = Hz（单独设置过的扶梯不变）
        //   in|out <X> to <Y>    -> 默认速率正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f in|out <Hz>       -> 强制游戏内所有扶梯这一头都用该速率（清掉单独设置）
        //   -f in|out <X> to <Y> -> 把这一头速率正好是 X 的扶梯（含单独设置的）改成 Y
        // `in` = 进入扶梯（上客端，默认 10 次/秒）、`out` = 离开扶梯（落客端，默认 1 次/秒）。
        // ★ 与 /futihelp（开关）、/futihelploud（音量）、/futihelpround（范围）是四件独立的事；
        //   与 /futiround（整条扶梯的运行底噪）也是两件事。
        // 速率上限 100 是因为靠「5 个素材 × pitch」拼速率（原版把 pitch 夹在 [0.5,2.0]）。
        // ★ 命令结构：`in`/`out` 分别在「带 -f」和「不带 -f」两层下面，即
        //   /futihelpspeed in 5          （默认速率）
        //   /futihelpspeed -f in 5       （强制所有扶梯，清掉单独设置）
        //   各都带 `<X> to <Y>`。别把 in/out 和 -f 摆平级之外的地方。
        dispatcher.register(Commands.literal("futihelpspeed")
            .executes(SmoothLift::futiHelpSpeedShow)
            .then(Commands.literal("in")
                .then(Commands.argument("hz", helpSpeedArg())
                    .executes(context -> futiHelpSpeedGlobal(context, true))
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", helpSpeedArg())
                            .executes(context -> futiHelpSpeedFromTo(context, true))))))
            .then(Commands.literal("out")
                .then(Commands.argument("hz", helpSpeedArg())
                    .executes(context -> futiHelpSpeedGlobal(context, false))
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", helpSpeedArg())
                            .executes(context -> futiHelpSpeedFromTo(context, false))))))
            .then(futiHelpSpeedForce("-f"))
        );

        // /futihelpmusic：无障碍**提示音**播放哪一段声音（数据模型与 /futimusic 完全对称）。
        //   （无参数）            -> 显示这条扶梯两头当前用的提示音
        //   in|out <名字>         -> 默认提示音 = 名字（已单独设置过的扶梯不变）
        //   in|out <X> to <Y>     -> 默认提示音正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f in|out <名字>      -> 强制游戏内所有扶梯这一头都用这段提示音（清掉这一头的单独设置）
        //   -f in|out <X> to <Y>  -> 把这一头提示音为 X 的扶梯（含单独设置的）改成 Y
        // 名字可以是 `default`（模组原来的提示音）、`off`（这一头不播提示音），
        // 或导入过的音频文件名（可省略 .ogg 后缀；与运行底噪**共用同一个导入文件夹**）。
        // `in` = 进入扶梯（上客端）、`out` = 离开扶梯（落客端），两头各有一套数据。
        // ★ 与 /futihelp（开关）、/futihelploud（音量）、/futihelpround（范围）、
        //   /futihelpspeed（速率）是五件独立的事：本指令只管「用哪段声音」。
        // ★ 速率只对 `default` 生效：自定义音频按原速循环播（素材是玩家自己的，没法按 Hz 分档），
        //   音量与范围对所有选择都生效。
        // ★ 命令结构：`in`/`out` 分别在「带 -f」和「不带 -f」两层下面（与 /futihelpspeed 完全一致），即
        //   /futihelpmusic in x.ogg        （默认提示音，进扶梯那头）
        //   /futihelpmusic -f out x.ogg    （强制所有扶梯的落客端）
        //   各都带 `<X> to <Y>`。别把 in/out 摆到别的地方去。
        dispatcher.register(Commands.literal("futihelpmusic")
            .executes(SmoothLift::futiHelpMusicShow)
            .then(Commands.literal("in")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(context -> futiHelpMusicSet(context, true))
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", StringArgumentType.string())
                            .executes(context -> futiHelpMusicFromTo(context, true))))))
            .then(Commands.literal("out")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(context -> futiHelpMusicSet(context, false))
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", StringArgumentType.string())
                            .executes(context -> futiHelpMusicFromTo(context, false))))))
            .then(futiHelpMusicForce("-f"))
        );

        // 【1.42】/lifthelp：**直梯（Lift）**开关门提示音（liftmusic.ogg）开关。
        //   （无参数）       -> 显示当前维度的开关
        //   on | off         -> 本维度开关 = on/off
        //   on to off        -> 本维度开关正好是 on 时才改成 off
        //   -f on | off      -> 强制**所有维度** = on/off
        //   -f on to off     -> 所有维度里开关正好是 on 的改成 off
        //   up | down | door -> 【1.15】三提示音子命令（子开关 + 默认素材），见下面那一节
        // ★ 与 /futihelp 是两件事：那条管的是**扶梯**的无障碍提示音（进/出口「咔啪」声），
        //   本指令管的是**直梯**关门/开门时连播 liftmusic.ogg（关门 4 次、开门 2 次）。
        // ★ 直梯提示音只有「维度默认」一层数据，所以 -f 的含义是「对所有维度」而不是
        //   「对所有直梯」—— 见 EscalatorSpeedManager 里 1.42 那一段。
        // ★ 命令结构：`on`/`off` 在「不带 -f」和「带 -f」两层下面各有一套（与 /futihelp 完全一致）。
        //   `up`/`down`/`door` 三个子节点也是「不带 -f」和「带 -f」各一套，且都带 <名字> 音频分支。
        dispatcher.register(Commands.literal("lifthelp")
            .executes(SmoothLift::liftHelpShow)
            .then(Commands.literal("on")
                .executes(context -> liftHelpGlobal(context, true))
                .then(Commands.literal("to")
                    .then(Commands.literal("off")
                        .executes(context -> liftHelpFromTo(context, true, false)))))
            .then(Commands.literal("off")
                .executes(context -> liftHelpGlobal(context, false))
                .then(Commands.literal("to")
                    .then(Commands.literal("on")
                        .executes(context -> liftHelpFromTo(context, false, true)))))
            .then(liftHelpForce("-f"))
            // 【1.15】三提示音子命令：up / down / door（door = 开关门，数据层仍叫 chime）。
            //   literal 是玩家写的词，which 是数据层的项目名，只有 door → chime 需要映射。
            .then(liftToneBranch("up", "up"))
            .then(liftToneBranch("down", "down"))
            .then(liftToneBranch("door", "chime"))
        );

        // 【1.43】/lifthelploud：**直梯**开关门提示音的**音量**（1~1000，100 = 原始音量，
        //   1000 = 10× 放大，与扶梯那两套音量的区间完全一致）。
        //   （无参数）       -> 显示当前维度的音量
        //   <音量>           -> 本维度音量 = 音量
        //   <X> to <Y>       -> 本维度音量正好是 X 时才改成 Y
        //   -f <音量>        -> 强制**所有维度** = 音量
        //   -f <X> to <Y>    -> 所有维度里音量正好是 X 的改成 Y
        // ★ 这是**第三套**互不影响的音量：/futiloud = 扶梯运行底噪（整条、射程 16 格）、
        //   /futihelploud = 扶梯无障碍提示音（端头单块、射程 4 格）、本指令 = 直梯开关门提示音。
        //   三者各存各的，改一个不影响另外两个。
        dispatcher.register(Commands.literal("lifthelploud")
            .executes(SmoothLift::liftHelpLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::liftHelpLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::liftHelpLoudFromTo))))
            // 【1.48】三项提示音各自的音量（不带 -f 的主分支）：
            //   /lifthelploud up 200           本维度上楼提示音音量 = 200
            //   /lifthelploud up 200 to 300    本维度上楼提示音音量正好是 200 时才改成 300
            //   （down = 下楼、door = 开关门即 chime 的别名；没单独调过的项跟随共用默认）
            .then(liftToneLoudCommand("up", "up"))
            .then(liftToneLoudCommand("down", "down"))
            .then(liftToneLoudCommand("door", "chime"))
            // 【1.48】-f 合并成**一个**节点：下面既有共用音量（<音量>），也有三项分支（up|down|door）。
            //   /lifthelploud -f 200        所有维度共用默认音量 = 200
            //   /lifthelploud -f up 200     所有维度上楼提示音音量 = 200
            //   /lifthelploud -f up 200 to 300
            //   （door = chime 的别名，开关门提示音）
            .then(Commands.literal("-f")
                .then(Commands.argument("volume", volumeArg())
                    .executes(SmoothLift::liftHelpLoudForceAll)
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", volumeArg())
                            .executes(SmoothLift::liftHelpLoudForceFromTo))))
                .then(liftToneLoudForceBranch("up", "up"))
                .then(liftToneLoudForceBranch("down", "down"))
                .then(liftToneLoudForceBranch("door", "chime")))
        );

        // 【1.47】/lifthelpround：直梯提示音（上楼 / 下楼 / 开关门，**三项共用一份**）的
        //   淡入淡出范围（格）。首次载入模组默认 4 格。
        //   （无参数）      -> 显示当前维度的范围
        //   <范围>          -> 本维度范围 = 范围（1~128，复用扶梯那组范围常量）
        //   <X> to <Y>      -> 本维度范围正好是 X 时才改成 Y
        //   -f <范围>       -> 强制**所有维度** = 范围
        //   -f <X> to <Y>   -> 所有维度里范围正好是 X 的改成 Y
        // 与 /futiround / /futihelpround（扶梯）是**互不影响**的两件事：这里是直梯那一路。
        dispatcher.register(Commands.literal("lifthelpround")
            .executes(SmoothLift::liftHelpRoundShow)
            .then(Commands.argument("round", roundArg())
                .executes(SmoothLift::liftHelpRoundGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", roundArg())
                        .executes(SmoothLift::liftHelpRoundFromTo))))
            .then(liftHelpRoundForce("-f"))
        );

        // 【1.50】/pbmmusic：**屏蔽门（MTR 平台幕门 / 半高安全门）**开关门提示音。
        //   （无参数）            -> 显示当前维度的总开关 + open/close 两项子开关
        //   on | off              -> 本维度总开关 = on/off
        //   on to off             -> 本维度总开关正好是 on 时才改成 off（off to on 同理）
        //   -f on | off           -> 强制**所有维度** = on/off
        //   -f on to off          -> 所有维度里总开关正好是 on 的改成 off
        //   open  | close         -> 显示该项子开关 + 默认素材
        //   open on | close off   -> 本维度该项子开关
        //   open on to off        -> 本维度该项子开关正好是 on 时才改成 off
        //   open -f on | off      -> **所有维度**该项子开关
        //   open -f on to off     -> 所有维度里该项子开关正好是 on 的改成 off
        //   【1.15】改素材（与 /lifthelp up|down|door 同一套形状，只是「三项」变「两项」）：
        //   open  <名字>          -> 本维度该项**默认素材**
        //   open  <X> to <Y>      -> 本维度该项默认素材正好是 X 时才改成 Y
        //   open  -f <名字>       -> 强制**所有维度**该项默认素材（并清掉按扇门的单独设置）
        //   open  -f <X> to <Y>   -> 所有维度（含单独设置）里该项是 X 的改成 Y
        //   名字：default（跟内置）/ default-c（doorclose.ogg）/ default-m（mdoorclose.ogg）
        //         / none（这一项不播）/ 导入过的 .ogg 文件名。default 落到维度默认那一层时
        //         按**端别**取内置：开门 dooropen.ogg、关门 mdoorclose.ogg。
        // ★ 与 /lifthelp（直梯 liftmusic）是两件事：本指令管的是**屏蔽门**开关门时那一下。
        //   两者各存各的，改一个不影响另一个。
        // ★ 开关只有两个取值，所以用 on/off 两个字面量而不是自定义参数类型；素材名是开的字符串参数，
        //   与 /lifthelp 一样把字面量（on/off/-f）**排在字符串参数前面**（Brigadier 字面量优先，
        //   顺序即优先级，详见 liftToneBranch 上方那段说明）。这也是为什么「不播」推荐写 none 而不是 off。
        dispatcher.register(Commands.literal("pbmmusic")
            .executes(SmoothLift::pbmMusicShow)
            .then(Commands.literal("on")
                .executes(context -> pbmMusicGlobal(context, true))
                .then(Commands.literal("to")
                    .then(Commands.literal("off")
                        .executes(context -> pbmMusicFromTo(context, true, false)))))
            .then(Commands.literal("off")
                .executes(context -> pbmMusicGlobal(context, false))
                .then(Commands.literal("to")
                    .then(Commands.literal("on")
                        .executes(context -> pbmMusicFromTo(context, false, true)))))
            .then(pbmMusicForce("-f"))
            .then(pbmMusicItemCommand("open", "open"))
            .then(pbmMusicItemCommand("close", "close"))
        );

        // 【1.50】/pbmloud：**屏蔽门**开关门提示音的音量（1~1000，100 = 原始音量，1000 = 10×）。
        //   （无参数）              -> 显示当前维度生效的共用音量（并标出两项是否跟随共用）
        //   <音量>                  -> 本维度共用音量 = 音量
        //   <X> to <Y>              -> 本维度共用音量正好是 X 时才改成 Y
        //   -f <音量>               -> 强制**所有维度** = 音量
        //   -f <X> to <Y>           -> 所有维度里共用音量正好是 X 的改成 Y
        //   open|close <音量>       -> 本维度该项自己的音量（没单独调过的项跟随共用默认）
        //   open|close <X> to <Y>   -> 本维度该项音量正好是 X 时才改成 Y
        //   -f open|close <音量>    -> 所有维度该项音量
        //   -f open|close <X> to <Y>
        // ★ 这是与 /futiloud（扶梯底噪）、/futihelploud（扶梯无障碍提示音）、/lifthelploud（直梯）
        //   **互不影响**的第 4 套音量，各存各的。
        dispatcher.register(Commands.literal("pbmloud")
            .executes(SmoothLift::pbmLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::pbmLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::pbmLoudFromTo))))
            .then(pbmLoudItemCommand("open", "open"))
            .then(pbmLoudItemCommand("close", "close"))
            .then(Commands.literal("-f")
                .then(Commands.argument("volume", volumeArg())
                    .executes(SmoothLift::pbmLoudForceAll)
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", volumeArg())
                            .executes(SmoothLift::pbmLoudForceFromTo))))
                .then(pbmLoudItemForceBranch("open", "open"))
                .then(pbmLoudItemForceBranch("close", "close")))
        );

        // 【1.22】/pbmmusicloud：屏蔽门**开关门提示音素材**那一套音量的别名。
        //   与 /pbmloud **完全同构、同一份数据**（新增它只是给"音量"这件事一个与素材指令
        //   /pbmmusic 对称的名字）；/pbmloud 原样保留，行为一个字没改。
        dispatcher.register(Commands.literal("pbmmusicloud")
            .executes(SmoothLift::pbmLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::pbmLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::pbmLoudFromTo))))
            .then(pbmLoudItemCommand("open", "open"))
            .then(pbmLoudItemCommand("close", "close"))
            .then(Commands.literal("-f")
                .then(Commands.argument("volume", volumeArg())
                    .executes(SmoothLift::pbmLoudForceAll)
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", volumeArg())
                            .executes(SmoothLift::pbmLoudForceFromTo))))
                .then(pbmLoudItemForceBranch("open", "open"))
                .then(pbmLoudItemForceBranch("close", "close")))
        );

        // 【1.22】/pbmmidiumloud：**到站播报**素材自己的音量（1~1000）。
        //   形状与 /pbmloud 同构，只是"共用 + 两项"变成"这一项"。
        //     （无参数）      -> 显示当前维度生效的到站播报音量
        //     <音量>          -> 本维度 = 音量
        //     <X> to <Y>      -> 本维度正好是 X 时才改成 Y
        //     -f <音量>       -> 强制**所有维度** = 音量
        //     -f <X> to <Y>   -> 所有维度里正好是 X 的改成 Y
        dispatcher.register(Commands.literal("pbmmidiumloud")
            .executes(context -> pbmItemLoudShow(context, "midium"))
            .then(Commands.argument("volume", volumeArg())
                .executes(context -> pbmItemLoudGlobal(context, "midium"))
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(context -> pbmItemLoudFromTo(context, "midium")))))
            .then(pbmItemLoudForce("-f", "midium"))
        );

        // 【1.22】/pbmarriveloud：**进站报站**素材自己的音量（1~1000）。形状同上。
        dispatcher.register(Commands.literal("pbmarriveloud")
            .executes(context -> pbmItemLoudShow(context, "arrive"))
            .then(Commands.argument("volume", volumeArg())
                .executes(context -> pbmItemLoudGlobal(context, "arrive"))
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(context -> pbmItemLoudFromTo(context, "arrive")))))
            .then(pbmItemLoudForce("-f", "arrive"))
        );

        // 【1.50 / 1.23】四条「淡入淡出范围」（格）指令 —— 形状、上下限（1~128）、反馈句式完全一致。
        //   默认都是 16 格（车站尺度，比直梯的 4 格大得多：一列车到站时整排门都要能听见）。
        //   /pbmround        = 屏蔽门开关门提示音（open / close 两项共用一份）
        //   /pbmmusicround   = 同上（用户点名的名字；与 /pbmround 读写同一份数据）
        //   /pbmmidiumround  = 到站播报
        //   /pbmarriveround  = 进站报站
        //   树由 roundCommand 一处产出 —— 想不一致都难。
        dispatcher.register(roundCommand("pbmround", RoundKind.TONE));
        dispatcher.register(roundCommand("pbmmusicround", RoundKind.TONE));
        dispatcher.register(roundCommand("pbmmidiumround", RoundKind.MIDIUM));
        dispatcher.register(roundCommand("pbmarriveround", RoundKind.ARRIVE));

        // 【1.16】/pbmclosewait：**屏蔽门关门提示音**的「强制等待时长」（秒，0~60，默认 5）。
        //
        //   它是一套**兜底**，只在「这一轮的停站时长不够放完整条关门素材」时才生效：
        //     开门音效播完 → 等 N 秒 → 播语音播报 → 门一动（嘀嘀开始）立刻掐断这段人声。
        //   停站**够长**时它被完全忽略（那条路是「整段提前播、结尾落在门上」）。
        //
        //   （无参数）      -> 显示当前维度生效的秒数 + 这条语义的一句话说明
        //   <秒>            -> 本维度 = 秒
        //   <X> to <Y>      -> 本维度正好是 X 时才改成 Y
        //   -f <秒>         -> 强制**所有维度** = 秒
        //   -f <X> to <Y>   -> 所有维度里正好是 X 的改成 Y
        // ★ 与 /pbmround 形状同构（都是「一个整数 + to + -f」），改动时两处对着看。
        dispatcher.register(Commands.literal("pbmclosewait")
            .executes(SmoothLift::pbmCloseWaitShow)
            .then(Commands.argument("seconds", closeWaitArg())
                .executes(SmoothLift::pbmCloseWaitGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", closeWaitArg())
                        .executes(SmoothLift::pbmCloseWaitFromTo))))
            .then(pbmCloseWaitForce("-f"))
        );

        // 【1.17】/pbmmidium：**到站播报**（列车到站、开门音播完后再等 Y 秒播这一段语音）。
        //   与关门提示音那套互不相干；★ 这段声音**永远不会被掐断**（车出站也照播到完）。
        //   `/pbmmidium`                 -> 显示
        //   `/pbmmidium <名字>`          -> 只改素材（保留当前秒数）
        //   `/pbmmidium <名字> <秒>`     -> 本维度（秒数 0 ~ 正无穷）
        //   `/pbmmidium -f <名字> <秒>`  -> 所有维度
        dispatcher.register(Commands.literal("pbmmidium")
            .executes(SmoothLift::pbmMidiumShow)
            .then(Commands.argument("name", midiumNameArg())
                .suggests(SmoothLift::pbmMidiumNameSuggestions)
                .executes(SmoothLift::pbmMidiumSetNameOnly)
                .then(Commands.argument("seconds", midiumWaitArg())
                    .executes(SmoothLift::pbmMidiumGlobal)))
            .then(pbmMidiumForce("-f"))
        );

        // 【1.21】/pbmarrive：**进站报站**（时刻表里最近的一班车还剩 |X| 秒到站时播这一段语音）。
        //   与到站播报互不相干；★ 这段声音同样**永远不会被掐断**（车进站后也照播到完）。
        //   `/pbmarrive`                 -> 显示
        //   `/pbmarrive <名字>`          -> 只改素材（保留当前秒数）
        //   `/pbmarrive <名字> <X>`      -> 本维度（X ∈ (-∞, 0]）
        //   `/pbmarrive -f <名字> <X>`   -> 所有维度
        dispatcher.register(Commands.literal("pbmarrive")
            .executes(SmoothLift::pbmArriveShow)
            .then(Commands.argument("name", arriveNameArg())
                .suggests(SmoothLift::pbmArriveNameSuggestions)
                .executes(SmoothLift::pbmArriveSetNameOnly)
                .then(Commands.argument("seconds", arriveArg())
                    .executes(SmoothLift::pbmArriveGlobal)))
            .then(pbmArriveForce("-f"))
        );
    }

}
