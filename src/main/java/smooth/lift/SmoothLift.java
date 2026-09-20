package smooth.lift;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

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
                    StringBuilder message = new StringBuilder("已更新这条扶梯（" + count + " 格）");
                    if (setRun) {
                        message.append("：扶梯速度 ").append(EscalatorSpeedData.format(run));
                        if (!setStep) {
                            message.append("（阶梯速度跟随）");
                        }
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
                        Component.literal("已把这条扶梯（" + count + " 格）的阶梯动画速度设为 "
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
                        Component.literal("已把这条扶梯（" + count + " 格）的阶梯动画对齐到运行速度"),
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
                        Component.literal("已清除这条扶梯（" + count + " 格）的单独阶梯动画设置，改为跟随维度默认（运行速度不变）"),
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
                            "音频上传失败：文件过大（最大 "
                                    + (EscalatorSpeedData.MAX_AUDIO_BYTES / 1024 / 1024)
                                    + "MB）或不是 MC 能播的 Ogg Vorbis（MP3/Opus 都不行）"), true);
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
                    player.displayClientMessage(Component.literal("已解除这条扶梯的自定义声音（不再播放）"), true);
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
                    player.displayClientMessage(Component.literal("已从存档删除音频（引用它的扶梯已静音）"), true);
                    EscalatorSpeedManager.syncAudioToAll(server);
                    // 【1.39】提示音那边也可能引用过这一段（共用同一个库），单独设置也要一起刷新
                    EscalatorSpeedManager.syncHelpAudioToAll(server);
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
                    player.displayClientMessage(Component.literal("已从文件夹导入并与这条扶梯绑定（原文件删除后仍可播放）"), true);
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
                    player.displayClientMessage(Component.literal("设置失败：音频不存在（先在提示音选择界面里导入 .ogg）"), true);
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
                            "已从文件夹导入并设为这条扶梯" + helpEndLabel(in) + "的无障碍提示音（原文件删除后仍可播放）"), true);
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
                    player.displayClientMessage(Component.literal("设置失败：音频不存在（先在提示音选择界面里导入 .ogg）"), true);
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
                        "本维度直梯提示音默认音量已设为 " + applied + "（100 = 原始音量）"), true);
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
                                + applied + "（100 = 原始音量）"), true);
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
                                "已从文件夹导入并设为这条直梯的" + liftToneLabel(which, fileName)
                                        + "（原文件删除后仍可播放）"), true);
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
                    "当前扶梯速度：" + EscalatorSpeedData.format(global) + " 格/秒（全局默认）"), false);
            return 1;
        }
        double speed = EscalatorSpeedManager.getSpeed(level, pos);
        boolean individual = EscalatorSpeedManager.getIndividualRunSpeed(level, pos) != null;
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯速度：" + EscalatorSpeedData.format(speed) + " 格/秒（这条扶梯，共 " + blocks + " 格，"
                        + (individual ? "单独设置" : "跟随全局")
                        + "）；全局默认 " + EscalatorSpeedData.format(global) + " 格/秒"), false);
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
                            + "，未做修改（当前为 " + EscalatorSpeedData.format(current) + "）"),
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
                : "全局阶梯速度未单独设置，跟随扶梯速度（" + EscalatorSpeedData.format(global) + " 格/秒）";
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal("当前阶梯速度：" + globalNote), false);
            return 1;
        }
        double step = EscalatorSpeedManager.getAnimationSpeed(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前阶梯速度：" + EscalatorSpeedData.format(step) + " 格/秒（这条扶梯，共 " + blocks + " 格）；"
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
                            + "，未做修改（当前为 " + EscalatorSpeedData.format(current) + "）"),
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
            return "无（静音）";
        }
        String name = EscalatorSpeedManager.displayName(audioId);
        return name.equals(audioId) ? audioId : name + "（" + audioId + "）";
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
                    "当前扶梯（共 " + blocks + " 格）没有音频：没有单独绑定，也没有设置默认音频"), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "当前扶梯音频：" + audioLabel(id) + "（这条扶梯，共 " + blocks + " 格，"
                            + (own ? "单独绑定" : "使用默认音频") + "）"), false);
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
                    "已清除默认扶梯音频：没有单独绑定音频的扶梯将静音（已单独绑定的不受影响）"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                "默认扶梯音频已设为 " + audioLabel(arg.id())
                        + "；没有单独绑定音频的扶梯都会播放它（已单独绑定的不受影响）"), false);
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
                    "默认扶梯音频不是 " + audioLabel(from.id()) + "，未做修改（当前为 "
                            + audioLabel(current) + "）；已单独绑定音频的扶梯不受本指令影响"), false);
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
                    "已强制所有扶梯静音（清掉 " + clearedCount + " 处单独绑定）"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯播放 " + audioLabel(arg.id()) + "（清掉 " + clearedCount + " 处单独绑定）"), false);
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
                        + audioLabel(to.id()) + "（共 " + changedCount + " 条）"), false);
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
            return "无（静音）";
        }
        if (EscalatorSpeedData.HELP_AUDIO_DEFAULT.equals(audioId)) {
            return "模组原来的提示音（default，速率见 /futihelpspeed）";
        }
        if (EscalatorSpeedData.HELP_AUDIO_OFF.equals(audioId)) {
            return "不播提示音（off）";
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
                        + "（" + (ownIn ? "单独设置" : "使用默认") + "）、离开扶梯 " + helpAudioLabel(outId)
                        + "（" + (ownOut ? "单独设置" : "使用默认") + "）（这条扶梯，共 " + blocks + " 格；开关 "
                        + (EscalatorSpeedManager.isHelpEnabled(level, pos) ? "开" : "关") + "）"), false);
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
                        + "；没有单独设置过的扶梯都会用它（已单独设置的不受影响）"), false);
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
                            + "，未做修改（当前为 " + helpAudioLabel(current)
                            + "）；已单独设置的扶梯不受本指令影响"), false);
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
                "已强制所有扶梯" + helpEndLabel(in) + "的无障碍提示音为 " + helpAudioLabel(arg.id())
                        + "（清掉 " + clearedCount + " 处这一头的单独设置）"), false);
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
                        + helpAudioLabel(to.id()) + "（共 " + changedCount + " 条）"), false);
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
                    "没有站在扶梯上。默认扶梯音量：" + global + "（100 = 原始音量）"), false);
            return 1;
        }
        int volume = EscalatorSpeedManager.getVolume(level, pos);
        boolean individual = EscalatorSpeedManager.hasIndividualVolume(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯音量：" + volume + "（这条扶梯，共 " + blocks + " 格，"
                        + (individual ? "单独设置" : "使用默认音量")
                        + "）；默认音量 " + global), false);
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
                "默认扶梯音量已设为 " + applied + "（100 = 原始音量）；"
                        + "没有单独设置过音量的扶梯都会用它（单独设置过的不受影响）"), false);
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
                    "默认扶梯音量不是 " + from + "（当前为 " + current + "），未做修改；"
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
                "已强制所有扶梯音量 = " + applied + "（清掉 " + cleared + " 处单独设置）"), false);
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
                "已把所有音量正好是 " + from + " 的扶梯改成 " + to + "（共 " + changed + " 处）"), false);
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
                    "没有站在扶梯上。默认无障碍提示音：" + helpLabel(global)
                            + "（开 = 进扶梯一端急促咔咔、出扶梯一端缓慢咔咔）"), false);
            return 1;
        }
        boolean enabled = EscalatorSpeedManager.isHelpEnabled(level, pos);
        boolean own = EscalatorSpeedManager.hasOwnHelp(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯无障碍提示音：" + helpLabel(enabled) + "（这条扶梯，共 " + blocks + " 格，"
                        + (own ? "单独设置" : "使用默认开关")
                        + "）；默认开关 " + helpLabel(global)), false);
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
                        + "；没有单独设置过的扶梯都会用它（单独设置过的不受影响）"), false);
        return 1;
    }

    /** /futihelp &lt;X&gt; to &lt;Y&gt; —— 默认开关正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiHelpFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean current = EscalatorSpeedManager.getDefaultHelp(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "默认无障碍提示音不是 " + helpLabel(from) + "（当前为 " + helpLabel(current)
                            + "），未做修改；单独设置过的扶梯不受本指令影响"), false);
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
                "已强制所有扶梯的无障碍提示音 = " + helpLabel(enabled)
                        + "（清掉 " + cleared + " 处单独设置）"), false);
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
                        + helpLabel(to) + "（共 " + changed + " 处）"), false);
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
                    "没有站在扶梯上。默认无障碍提示音音量：" + global + "（100 = 原始音量）"), false);
            return 1;
        }
        int volume = EscalatorSpeedManager.getHelpVolume(level, pos);
        boolean own = EscalatorSpeedManager.hasOwnHelpVolume(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯无障碍提示音音量：" + volume + "（这条扶梯，共 " + blocks + " 格，"
                        + (own ? "单独设置" : "使用默认音量")
                        + "）；默认音量 " + global), false);
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
                "默认无障碍提示音音量已设为 " + applied + "（100 = 原始音量）；"
                        + "没有单独设置过音量的扶梯都会用它（单独设置过的不受影响）"), false);
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
                    "默认无障碍提示音音量不是 " + from + "（当前为 " + current + "），未做修改；"
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
                "已强制所有扶梯的无障碍提示音音量 = " + applied + "（清掉 " + cleared + " 处单独设置）"), false);
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
                "已把所有无障碍提示音音量正好是 " + from + " 的扶梯改成 " + to + "（共 " + changed + " 处）"), false);
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
                "当前扶梯音效淡入淡出范围：" + round + " 格（这条扶梯，共 " + blocks + " 格，"
                        + (own ? "单独设置" : "使用默认范围")
                        + "）；默认范围 " + global + " 格"), false);
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
                        + "没有单独设置过范围的扶梯都会用它（单独设置过的不受影响）"), false);
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
                    "默认扶梯音效淡入淡出范围不是 " + from + " 格（当前为 " + current + " 格），未做修改；"
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
                "已强制所有扶梯音效的淡入淡出范围 = " + applied + " 格（清掉 " + cleared + " 处单独设置）"), false);
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
                "已把所有扶梯音效淡入淡出范围正好是 " + from + " 格的扶梯改成 " + to + " 格（共 " + changed + " 处）"), false);
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
                "当前无障碍提示音淡入淡出范围：" + round + " 格（这条扶梯，共 " + blocks + " 格，"
                        + (own ? "单独设置" : "使用默认范围")
                        + "）；默认范围 " + global + " 格"), false);
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
                        + "没有单独设置过范围的扶梯都会用它（单独设置过的不受影响）"), false);
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
                    "默认无障碍提示音淡入淡出范围不是 " + from + " 格（当前为 " + current + " 格），未做修改；"
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
                "已强制所有扶梯无障碍提示音的淡入淡出范围 = " + applied + " 格（清掉 " + cleared + " 处单独设置）"), false);
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
                "已把所有无障碍提示音淡入淡出范围正好是 " + from + " 格的扶梯改成 " + to + " 格（共 " + changed + " 处）"), false);
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
        return in ? "进入扶梯（上客端）" : "离开扶梯（落客端）";
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
                "当前无障碍提示音速率：进入扶梯 " + in + " 次/秒（" + (ownIn ? "单独设置" : "使用默认速率")
                        + "）、离开扶梯 " + out + " 次/秒（" + (ownOut ? "单独设置" : "使用默认速率")
                        + "）（这条扶梯，共 " + blocks + " 格）；默认 进入 " + globalIn + "、离开 "
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
                        + "没有单独设置过速率的扶梯都会用它（单独设置过的不受影响）"), false);
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
                    "默认" + helpEndLabel(in) + "无障碍提示音速率不是 " + from + " 次/秒（当前为 " + current
                            + " 次/秒），未做修改；单独设置过速率的扶梯不受本指令影响"), false);
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
                "已强制所有扶梯" + helpEndLabel(in) + "的无障碍提示音速率 = " + applied + " 次/秒（清掉 "
                        + cleared + " 处单独设置）"), false);
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
                        + " 次/秒（共 " + changed + " 处）"), false);
        return 1;
    }

    // ==================================================================
    // 【1.42】直梯（Lift）开关门提示音 liftmusic.ogg
    //
    //   /lifthelp        显示 / on|off / on to off / -f on|off / -f on to off
    //   /lifthelpspeed   显示 / <倍速> / <X> to <Y> / -f <倍速> / -f <X> to <Y>
    //
    //   ★ 为什么是 lifthelp / lifthelpspeed 而不是需求原文里的 futihelp / futihelpspeed：
    //     那两条指令**早就存在**、管的是**扶梯**的无障碍提示音（进/出口「咔啪」声），
    //     直接复用会把扶梯那套设置顶掉。直梯提示音是完全另一件事，所以另开两条指令，
    //     扶梯的 futihelp / futihelpspeed 一个字节都不动。
    //
    //   ★ 直梯提示音只有「维度默认」一层数据（没有单条直梯的单独设置），所以 `-f`
    //     取「**对所有维度**强制」的含义 —— 见 EscalatorSpeedManager 里那一段的说明。
    // ==================================================================

    /** 直梯提示音倍速的取值参数（0.5~2.0，允许小数；上限来自原版 pitch 夹取，见 EscalatorSpeedData）。 */
    private static FloatArgumentType liftHelpSpeedArg() {
        return FloatArgumentType.floatArg(
                EscalatorSpeedData.LIFT_HELP_SPEED_MIN, EscalatorSpeedData.LIFT_HELP_SPEED_MAX);
    }

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
                "当前维度直梯开关门提示音：" + helpLabel(enabled) + "，倍速 " + liftSpeedLabel(speed)
                        + "（关门连播 " + EscalatorSpeedData.LIFT_HELP_CLOSE_REPEATS + " 次、开门连播 "
                        + EscalatorSpeedData.LIFT_HELP_OPEN_REPEATS + " 次；倍速范围 "
                        + liftSpeedLabel(EscalatorSpeedData.LIFT_HELP_SPEED_MIN) + "~"
                        + liftSpeedLabel(EscalatorSpeedData.LIFT_HELP_SPEED_MAX) + "）"), false);
        return 1;
    }

    /** /lifthelp &lt;on|off&gt; —— 设置**本维度**的开关。 */
    private static int liftHelpGlobal(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftHelp(level, enabled);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度（" + level.dimension().location() + "）直梯开关门提示音已设为 " + helpLabel(enabled)
                        + "；其它维度不变（要对所有维度生效用 /lifthelp -f " + (enabled ? "on" : "off") + "）"), false);
        return 1;
    }

    /** /lifthelp &lt;X&gt; to &lt;Y&gt; —— 本维度开关正好是 X 时才改成 Y。 */
    private static int liftHelpFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultLiftHelp(level, from, to)) {
            boolean current = EscalatorSpeedManager.isLiftHelpEnabled(level);
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯开关门提示音不是 " + helpLabel(from) + "（当前为 " + helpLabel(current)
                            + "），未做修改"), false);
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
                "已强制**所有维度**的直梯开关门提示音 = " + helpLabel(enabled) + "（改动 " + changed + " 个维度）"), false);
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
                "已把所有直梯开关门提示音为 " + helpLabel(from) + " 的维度改成 " + helpLabel(to)
                        + "（共 " + changed + " 个维度）"), false);
        return 1;
    }

    /** /lifthelpspeed（不带参数）—— 显示当前维度生效的倍速。 */
    private static int liftHelpSpeedShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        float speed = EscalatorSpeedManager.getLiftHelpSpeed(level);
        boolean enabled = EscalatorSpeedManager.isLiftHelpEnabled(level);
        source.sendSuccess(() -> Component.literal(
                "当前维度直梯提示音倍速：" + liftSpeedLabel(speed) + "（提示音：" + helpLabel(enabled)
                        + "；范围 " + liftSpeedLabel(EscalatorSpeedData.LIFT_HELP_SPEED_MIN) + "~"
                        + liftSpeedLabel(EscalatorSpeedData.LIFT_HELP_SPEED_MAX)
                        + "，1 = 原速。上限 2 是原版对播放速率的硬夹取）"), false);
        return 1;
    }

    /** /lifthelpspeed &lt;倍速&gt; —— 设置**本维度**的倍速。 */
    private static int liftHelpSpeedGlobal(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftHelpSpeed(level, speed);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        float applied = EscalatorSpeedManager.getLiftHelpSpeed(level);
        source.sendSuccess(() -> Component.literal(
                "本维度（" + level.dimension().location() + "）直梯提示音倍速已设为 "
                        + liftSpeedLabel(applied) + "；其它维度不变"), false);
        return 1;
    }

    /** /lifthelpspeed &lt;X&gt; to &lt;Y&gt; —— 本维度倍速正好是 X 时才改成 Y。 */
    private static int liftHelpSpeedFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultLiftHelpSpeed(level, from, to)) {
            float current = EscalatorSpeedManager.getLiftHelpSpeed(level);
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯提示音倍速不是 " + liftSpeedLabel(from) + "（当前为 "
                            + liftSpeedLabel(current) + "），未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        float applied = EscalatorSpeedManager.getLiftHelpSpeed(level);
        source.sendSuccess(() -> Component.literal(
                "本维度直梯提示音倍速从 " + liftSpeedLabel(from) + " 改为 "
                        + liftSpeedLabel(applied)), false);
        return 1;
    }

    /** /lifthelpspeed -f &lt;倍速&gt; —— **所有维度**都设成该倍速。 */
    private static int liftHelpSpeedForceAll(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftHelpSpeedAll(source.getServer(), speed);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        float applied = EscalatorSpeedData.clampLiftHelpSpeed(speed);
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯提示音倍速 = " + liftSpeedLabel(applied)
                        + "（改动 " + changed + " 个维度）"), false);
        return 1;
    }

    /** /lifthelpspeed -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里倍速正好是 X 的那些改成 Y。 */
    private static int liftHelpSpeedForceFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftHelpSpeedAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的直梯提示音倍速是 " + liftSpeedLabel(from) + "，未做修改"), false);
            return 0;
        }
        float applied = EscalatorSpeedData.clampLiftHelpSpeed(to);
        source.sendSuccess(() -> Component.literal(
                "已把所有直梯提示音倍速为 " + liftSpeedLabel(from) + " 的维度改成 " + liftSpeedLabel(applied)
                        + "（共 " + changed + " 个维度）"), false);
        return 1;
    }

    /** /lifthelploud（不带参数）—— 显示当前维度生效的直梯提示音音量。 */
    private static int liftHelpLoudShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int volume = EscalatorSpeedManager.getLiftHelpVolume(level);
        boolean enabled = EscalatorSpeedManager.isLiftHelpEnabled(level);
        source.sendSuccess(() -> Component.literal(
                "当前维度直梯提示音音量：" + volume + "（提示音：" + helpLabel(enabled)
                        + "；范围 " + EscalatorSpeedData.HELP_VOLUME_MIN + "~"
                        + EscalatorSpeedData.HELP_VOLUME_MAX
                        + "，100 = 原始音量、1000 = 10× 放大）"), false);
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
                "本维度（" + level.dimension().location() + "）直梯提示音音量已设为 " + applied
                        + "（100 = 原始音量）；其它维度不变（要对所有维度生效用 /lifthelploud -f "
                        + applied + "）"), false);
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
                    "本维度直梯提示音音量不是 " + from + "（当前为 " + current + "），未做修改"), false);
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
                "已强制**所有维度**的直梯提示音音量 = " + applied + "（改动 " + changed + " 个维度）"), false);
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
                "已把所有直梯提示音音量为 " + from + " 的维度改成 " + applied
                        + "（共 " + changed + " 个维度）"), false);
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
                "本维度（" + level.dimension().location() + "）直梯"
                        + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量已设为 " + applied
                        + "（100 = 原始音量；其它维度不变，要对所有维度生效用 /lifthelploud "
                        + literalName(which) + " -f " + applied + "）"), false);
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
                            + "（当前为 " + current + "），未做修改"), false);
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
                        + applied + "（改动 " + changed + " 个维度）"), false);
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
                        + " 的维度改成 " + applied + "（共 " + changed + " 个维度）"), false);
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
                "当前维度直梯提示音（上楼 / 下楼 / 开关门共用）淡入淡出范围：" + round + " 格"
                        + "（范围 "
                        + EscalatorSpeedData.LIFT_HELP_ROUND_MIN + "~"
                        + EscalatorSpeedData.LIFT_HELP_ROUND_MAX + "）"), false);
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
                "本维度（" + level.dimension().location() + "）直梯提示音淡入淡出范围已设为 " + applied + " 格；"
                        + "其它维度不变（要对所有维度生效用 /lifthelpround -f " + applied + "）"), false);
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
                    "本维度直梯提示音淡入淡出范围不是 " + from + " 格（当前为 " + current + " 格），未做修改"), false);
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
                "已强制**所有维度**的直梯提示音淡入淡出范围 = " + applied + " 格（改动 " + changed + " 个维度）"), false);
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
                "已把所有直梯提示音淡入淡出范围为 " + from + " 格的维度改成 " + to + " 格（共 " + changed + " 个维度）"), false);
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
    // 【1.46】三提示音独立子开关：/lifthelpup / /lifthelpdown / /lifthelpchime
    //   形状与 /lifthelp 完全一致（无参显示 / on|off / X to Y / -f 全维度），
    //   只是把「总开关」换成「up / down / chime 各自的子开关」。总开关与子开关是「与」的关系。
    // ------------------------------------------------------------------

    /** /lifthelpup|down|chime（不带参数）—— 显示当前维度这项子开关。 */
    private static int liftToneSwitchShow(CommandContext<CommandSourceStack> context, String which) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean enabled = EscalatorSpeedManager.isLiftToneEnabled(level, which);
        source.sendSuccess(() -> Component.literal(
                "当前维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "：" + helpLabel(enabled)),
                false);
        return 1;
    }

    /** /lifthelpup|down|chime &lt;on|off&gt; —— 设置**本维度**这项子开关。 */
    private static int liftToneSwitchGlobal(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftToneEnabled(level, which, enabled);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度（" + level.dimension().location() + "）直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which)
                        + "已设为 " + helpLabel(enabled)
                        + "；其它维度不变（要对所有维度生效用 /lifthelp" + which + " -f " + (enabled ? "on" : "off") + "）"), false);
        return 1;
    }

    /** /lifthelpup|down|chime &lt;X&gt; to &lt;Y&gt; —— 本维度这项子开关正好是 X 时才改成 Y。 */
    private static int liftToneSwitchFromTo(CommandContext<CommandSourceStack> context, String which, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultLiftToneEnabled(level, which, from, to)) {
            boolean current = EscalatorSpeedManager.isLiftToneEnabled(level, which);
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "不是 " + helpLabel(from)
                            + "（当前为 " + helpLabel(current) + "），未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "从 " + helpLabel(from)
                        + " 改为 " + helpLabel(to)), false);
        return 1;
    }

    /** /lifthelpup|down|chime -f &lt;on|off&gt; —— **所有维度**这项子开关都设成该值。 */
    private static int liftToneSwitchForceAll(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftToneEnabledAll(source.getServer(), which, enabled);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + " = " + helpLabel(enabled)
                        + "（改动 " + changed + " 个维度）"), false);
        return 1;
    }

    /** /lifthelpup|down|chime -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里这项子开关正好是 X 的那些改成 Y。 */
    private static int liftToneSwitchForceFromTo(CommandContext<CommandSourceStack> context, String which, boolean from, boolean to) {
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
                        + " 的维度改成 " + helpLabel(to) + "（共 " + changed + " 个维度）"), false);
        return 1;
    }

    /** 注册某条子开关指令的完整树（含 `-f` 分支）。{@code which} ∈ {"up","down","chime"}。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftToneSwitchCommand(String which) {
        return Commands.literal("lifthelp" + which)
                .executes(context -> liftToneSwitchShow(context, which))
                .then(Commands.literal("on")
                        .executes(context -> liftToneSwitchGlobal(context, which, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> liftToneSwitchFromTo(context, which, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> liftToneSwitchGlobal(context, which, false))
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
                                                .executes(context -> liftToneSwitchForceFromTo(context, which, false, true))))));
    }

    /** 注册 `/lifthelpspeed` 的 `-f` 分支：`-f <倍速>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftHelpSpeedForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("speed", liftHelpSpeedArg())
                        .executes(SmoothLift::liftHelpSpeedForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", liftHelpSpeedArg())
                                        .executes(SmoothLift::liftHelpSpeedForceFromTo))));
    }

    /**
     * 注册全部指令：`/futispeed`、`/jietispeed`、`/futimusic`、`/futiloud`、`/futihelp`、
     * `/futihelploud`、`/futiround`、`/futihelpround`、`/futihelpspeed`、`/futihelpmusic`，
     * 以及【1.42】直梯提示音的 `/lifthelp`、`/lifthelpspeed`、【1.43】`/lifthelploud`。
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
        // ★ 与 /futihelp 是两件事：那条管的是**扶梯**的无障碍提示音（进/出口「咔啪」声），
        //   本指令管的是**直梯**关门/开门时连播 liftmusic.ogg（关门 4 次、开门 2 次）。
        // ★ 直梯提示音只有「维度默认」一层数据，所以 -f 的含义是「对所有维度」而不是
        //   「对所有直梯」—— 见 EscalatorSpeedManager 里 1.42 那一段。
        // ★ 命令结构：`on`/`off` 在「不带 -f」和「带 -f」两层下面各有一套（与 /futihelp 完全一致）。
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
        );

        // 【1.46】三提示音独立子开关：形状与 /lifthelp 一致，但分别管 up / down / chime。
        //   /lifthelpup 上楼提示音 / /lifthelpdown 下楼提示音 / /lifthelpchime 开关门提示音
        dispatcher.register(liftToneSwitchCommand("up"));
        dispatcher.register(liftToneSwitchCommand("down"));
        dispatcher.register(liftToneSwitchCommand("chime"));

        // 【1.42】/lifthelpspeed：**直梯**开关门提示音的**倍速**（允许小数，0.5~2.0）。
        //   （无参数）       -> 显示当前维度的倍速
        //   <倍速>           -> 本维度倍速 = 倍速
        //   <X> to <Y>       -> 本维度倍速正好是 X 时才改成 Y
        //   -f <倍速>        -> 强制**所有维度** = 倍速
        //   -f <X> to <Y>    -> 所有维度里倍速正好是 X 的改成 Y
        // 1 = 原速。上限 2 / 下限 0.5 是**原版 SoundEngine.calculatePitch 对 pitch 的硬夹取**
        // （Mth.clamp(pitch, 0.5F, 2.0F)），超出这一段的取值会被悄悄压回去，
        // 所以参数类型直接夹住，玩家填 3 会当场看到「必须在 0.5 和 2.0 之间」而不是「填了没用」。
        // ★ 与 /futihelpspeed（扶梯提示音速率，单位 Hz）是两件完全不同的事。
        dispatcher.register(Commands.literal("lifthelpspeed")
            .executes(SmoothLift::liftHelpSpeedShow)
            .then(Commands.argument("speed", liftHelpSpeedArg())
                .executes(SmoothLift::liftHelpSpeedGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", liftHelpSpeedArg())
                        .executes(SmoothLift::liftHelpSpeedFromTo))))
            .then(liftHelpSpeedForce("-f"))
        );

        // 【1.43】/lifthelploud：**直梯**开关门提示音的**音量**（1~1000，100 = 原始音量，
        //   1000 = 10× 放大，与扶梯那两套音量的区间完全一致）。
        //   （无参数）       -> 显示当前维度的音量
        //   <音量>           -> 本维度音量 = 音量
        //   <X> to <Y>       -> 本维度音量正好是 X 时才改成 Y
        //   -f <音量>        -> 强制**所有维度** = 音量
        //   -f <X> to <Y>    -> 所有维度里音量正好是 X 的改成 Y
        // 命令结构与 /lifthelpspeed 完全一致（只有参数类型不同：整数 vs 小数）。
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
    }

}
