package smooth.lift;

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
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import smooth.lift.network.AlignStepPayload;
import smooth.lift.network.ApplyChainPayload;
import smooth.lift.network.AudioSyncPayload;
import smooth.lift.network.BindAudioPayload;
import smooth.lift.network.DeleteAudioPayload;
import smooth.lift.network.ImportFolderAudioPayload;
import smooth.lift.network.RequestSyncPayload;
import smooth.lift.network.RestoreStepPayload;
import smooth.lift.network.SetSpeedPayload;
import smooth.lift.network.SetStepSpeedPayload;
import smooth.lift.network.SetVolumePayload;
import smooth.lift.network.SyncPayload;
import smooth.lift.network.UnbindAudioPayload;
import smooth.lift.network.UploadAudioPayload;
import smooth.lift.network.VolumeSyncPayload;

public class SmoothLift implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("[SmoothLift] Loaded");

        // 注册所有自定义网络包（1.20.5+ 起改用 CustomPacketPayload；每个包一个 Type + StreamCodec）。
        // 客户端 -> 服务端
        PayloadTypeRegistry.playC2S().register(SetSpeedPayload.TYPE, SetSpeedPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ApplyChainPayload.TYPE, ApplyChainPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetStepSpeedPayload.TYPE, SetStepSpeedPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AlignStepPayload.TYPE, AlignStepPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RestoreStepPayload.TYPE, RestoreStepPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestSyncPayload.TYPE, RequestSyncPayload.CODEC);
        // 【1.7】自定义扶梯声音
        PayloadTypeRegistry.playC2S().register(UploadAudioPayload.TYPE, UploadAudioPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(BindAudioPayload.TYPE, BindAudioPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(UnbindAudioPayload.TYPE, UnbindAudioPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(DeleteAudioPayload.TYPE, DeleteAudioPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ImportFolderAudioPayload.TYPE, ImportFolderAudioPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetVolumePayload.TYPE, SetVolumePayload.CODEC);
        // 服务端 -> 客户端
        PayloadTypeRegistry.playS2C().register(SyncPayload.TYPE, SyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AudioSyncPayload.TYPE, AudioSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(VolumeSyncPayload.TYPE, VolumeSyncPayload.CODEC);

        // /futispeed：全局扶梯运行速度（只作用于「全局扶梯」＝没被石斧改过、或改完仍与全局一致的）
        //   （无参数）   -> 显示当前扶梯速度（站在扶梯上就显示那一条，否则显示全局默认）
        //   X           -> 全局运行速度 = X
        //   X to Y      -> 只有当前全局运行速度正好是 X 时才改成 Y（没有速度为 X 的就不改）
        //   -f X        -> 强制游戏内所有扶梯运行速度 = X（不管有没有被改过）
        //   -f X to Y   -> 把所有运行速度为 X 的扶梯（含被改过的）改成 Y
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
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
        });

        // 服务端兜底：拿着石斧右键扶梯时取消原版交互（正常情况下客户端已拦截，不会发包）
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (player.getMainHandItem().is(Items.STONE_AXE)
                    && EscalatorUtil.isEscalator(world.getBlockState(hitResult.getBlockPos()))) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // 石斧设置界面退出（ESC）时发来的合并包：
        // setRun=true  -> 设运行速度，并让阶梯速度跟随运行速度；
        // setStep=true -> 只设阶梯速度，运行速度不动。
        ServerPlayNetworking.registerGlobalReceiver(ApplyChainPayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            boolean setRun = payload.setRun();
            double run = payload.run();
            boolean setStep = payload.setStep();
            double step = payload.step();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
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
                    context.player().displayClientMessage(Component.literal(text), true);
                    EscalatorSpeedManager.syncToAll(context.server());
                }
            });
        });

        // 客户端输入界面发来的设置请求
        ServerPlayNetworking.registerGlobalReceiver(SetSpeedPayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            double speed = payload.speed();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int count = EscalatorSpeedManager.setSpeed(level, pos, speed);
                if (count > 0) {
                    double applied = EscalatorSpeedManager.getSpeed(level, pos);
                    context.player().displayClientMessage(
                        Component.literal("已设置 " + count + " 个扶梯方块的速度为 " + EscalatorSpeedData.format(applied) + " 格/秒"),
                        true
                    );
                    EscalatorSpeedManager.syncToAll(context.server());
                }
            });
        });

        // 客户端输入界面发来的【阶梯动画】设置/对齐/恢复默认请求
        ServerPlayNetworking.registerGlobalReceiver(SetStepSpeedPayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            double step = payload.step();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int count = EscalatorSpeedManager.setStepSpeed(level, pos, step);
                if (count > 0) {
                    context.player().displayClientMessage(
                        Component.literal("已把这条扶梯（" + count + " 格）的阶梯动画速度设为 "
                                + EscalatorSpeedData.format(step) + " 格/秒"),
                        true
                    );
                    EscalatorSpeedManager.syncToAll(context.server());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(AlignStepPayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int count = EscalatorSpeedManager.alignStepToRunning(level, pos);
                if (count > 0) {
                    context.player().displayClientMessage(
                        Component.literal("已把这条扶梯（" + count + " 格）的阶梯动画对齐到运行速度"),
                        true
                    );
                    EscalatorSpeedManager.syncToAll(context.server());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(RestoreStepPayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int count = EscalatorSpeedManager.clearStepSpeed(level, pos);
                if (count > 0) {
                    context.player().displayClientMessage(
                        Component.literal("已清除这条扶梯（" + count + " 格）的单独阶梯动画设置，改为跟随维度默认（运行速度不变）"),
                        true
                    );
                    EscalatorSpeedManager.syncToAll(context.server());
                }
            });
        });

        // 客户端分块上传自定义音频。全部块到齐后入库并广播音频同步。
        ServerPlayNetworking.registerGlobalReceiver(UploadAudioPayload.TYPE, (payload, context) -> {
            String audioId = payload.audioId();
            int totalChunks = payload.totalChunks();
            int chunkIndex = payload.chunkIndex();
            byte[] chunk = payload.chunk();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                byte[] complete = EscalatorSpeedManager.handleAudioUploadChunk(audioId, totalChunks, chunkIndex, chunk);
                if (complete == null) {
                    return;
                }
                if (!EscalatorSpeedManager.storeAudio(level, audioId, complete)) {
                    context.player().displayClientMessage(Component.literal(
                            "音频上传失败：文件过大（最大 "
                                    + (EscalatorSpeedData.MAX_AUDIO_BYTES / 1024 / 1024)
                                    + "MB）或不是 MC 能播的 Ogg Vorbis（MP3/Opus 都不行）"), true);
                    return;
                }
                context.player().displayClientMessage(Component.literal("音频已上传并存入存档"), true);
                EscalatorSpeedManager.syncAudioToAll(context.server());
            });
        });

        // 客户端把音频绑定到扶梯
        ServerPlayNetworking.registerGlobalReceiver(BindAudioPayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            String audioId = payload.audioId();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                if (EscalatorSpeedManager.bindAudio(level, pos, audioId)) {
                    context.player().displayClientMessage(Component.literal("已为这条扶梯绑定自定义声音"), true);
                    EscalatorSpeedManager.syncAudioToAll(context.server());
                } else {
                    context.player().displayClientMessage(Component.literal("绑定失败：音频不存在"), true);
                }
            });
        });

        // 客户端解绑扶梯音频（之后静音）
        ServerPlayNetworking.registerGlobalReceiver(UnbindAudioPayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (EscalatorSpeedManager.unbindAudio(level, pos)) {
                    context.player().displayClientMessage(Component.literal("已解除这条扶梯的自定义声音（不再播放）"), true);
                    EscalatorSpeedManager.syncAudioToAll(context.server());
                }
            });
        });

        // 客户端从存档删除一段音频（同时解绑所有引用它的扶梯，并向所有玩家重发同步）
        ServerPlayNetworking.registerGlobalReceiver(DeleteAudioPayload.TYPE, (payload, context) -> {
            String audioId = payload.audioId();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (EscalatorSpeedManager.deleteAudio(level, audioId)) {
                    context.player().displayClientMessage(Component.literal("已从存档删除音频（引用它的扶梯已静音）"), true);
                    EscalatorSpeedManager.syncAudioToAll(context.server());
                }
            });
        });

        // 客户端把存档 smoothlift_audio 文件夹里的一个 OGG 导入存档并绑定到扶梯（融入存档，删原文件仍可播）
        ServerPlayNetworking.registerGlobalReceiver(ImportFolderAudioPayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            String fileName = payload.fileName();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                String problem = EscalatorSpeedManager.importAudioToStore(level, fileName);
                if (problem == null) {
                    EscalatorSpeedManager.bindAudio(level, pos, fileName);
                    context.player().displayClientMessage(Component.literal("已从文件夹导入并与这条扶梯绑定（原文件删除后仍可播放）"), true);
                    EscalatorSpeedManager.syncAudioToAll(context.server());
                } else {
                    context.player().displayClientMessage(Component.literal("导入失败：" + problem), true);
                }
            });
        });

        // 【1.9】客户端设置某条扶梯的声音音量（1~1000）
        ServerPlayNetworking.registerGlobalReceiver(SetVolumePayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            int volume = payload.volume();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int applied = EscalatorSpeedManager.setVolume(level, pos, volume);
                context.player().displayClientMessage(Component.literal("这条扶梯的音量已设为 " + applied + "%"), true);
                // 音量很小，单独发包同步即可，不必重发整个音频库
                EscalatorSpeedManager.syncVolumeToAll(context.server());
            });
        });

        // 玩家进入游戏时同步全部数据（服务端侧兜底，客户端还会主动请求一次）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            EscalatorSpeedManager.syncToAll(server);
            EscalatorSpeedManager.syncAudioToAll(server);
            EscalatorSpeedManager.syncVolumeToAll(server);
        });

        // 服务端启动时确保存档音频来源文件夹存在（没有就自动新建）
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                EscalatorSpeedManager.ensureAudioFolder(server.overworld()));

        // 客户端进世界后主动请求同步：此时双方频道均已就绪，可靠送达
        ServerPlayNetworking.registerGlobalReceiver(RequestSyncPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> {
                EscalatorSpeedManager.syncToAll(context.server());
                EscalatorSpeedManager.sendAudioSyncTo(context.player(), context.player().serverLevel());
                EscalatorSpeedManager.sendVolumeSyncTo(context.player(), context.player().serverLevel());
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
            // 速度 / 音频绑定 / 音量三类数据都要同步，否则客户端会残留旧的速度与声音
            EscalatorSpeedManager.syncToAll(world.getServer());
            EscalatorSpeedManager.syncAudioToAll(world.getServer());
            EscalatorSpeedManager.syncVolumeToAll(world.getServer());
        });
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
        int blocks = EscalatorUtil.collectChain(level, pos).size();
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
        int blocks = EscalatorUtil.collectChain(level, pos).size();
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
        int blocks = EscalatorUtil.collectChain(level, pos).size();
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
        int blocks = EscalatorUtil.collectChain(level, pos).size();
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
}
