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
import smooth.lift.network.BindHelpAudioPayload;
import smooth.lift.network.HelpAudioSyncPayload;
import smooth.lift.network.HelpRoundSyncPayload;
import smooth.lift.network.HelpSpeedSyncPayload;
import smooth.lift.network.HelpSyncPayload;
import smooth.lift.network.HelpVolumeSyncPayload;
import smooth.lift.network.ImportFolderHelpAudioPayload;
import smooth.lift.network.RoundSyncPayload;
import smooth.lift.network.SetHelpPayload;
import smooth.lift.network.SetHelpVolumePayload;
import smooth.lift.network.UnbindHelpAudioPayload;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.world.item.ItemStack;
import java.util.Locale;

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
        // 【1.16】无障碍提示音开关 / 【1.18】提示音音量
        PayloadTypeRegistry.playC2S().register(SetHelpPayload.TYPE, SetHelpPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HelpSyncPayload.TYPE, HelpSyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetHelpVolumePayload.TYPE, SetHelpVolumePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HelpVolumeSyncPayload.TYPE, HelpVolumeSyncPayload.CODEC);
        // 【1.24】两个淡入淡出范围（底噪 / 提示音）：只有 S2C
        PayloadTypeRegistry.playS2C().register(RoundSyncPayload.TYPE, RoundSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HelpRoundSyncPayload.TYPE, HelpRoundSyncPayload.CODEC);
        // 【1.31】提示音速率（进/出两套）：只有 S2C
        PayloadTypeRegistry.playS2C().register(HelpSpeedSyncPayload.TYPE, HelpSpeedSyncPayload.CODEC);
        // 【1.41】提示音音乐（进/出两套）：S2C + C2S
        PayloadTypeRegistry.playS2C().register(HelpAudioSyncPayload.TYPE, HelpAudioSyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(BindHelpAudioPayload.TYPE, BindHelpAudioPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(UnbindHelpAudioPayload.TYPE, UnbindHelpAudioPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ImportFolderHelpAudioPayload.TYPE, ImportFolderHelpAudioPayload.CODEC);

        // /futispeed：全局扶梯运行速度（只作用于「全局扶梯」＝没被石斧改过、或改完仍与全局一致的）
        //   （无参数）   -> 显示当前扶梯速度（站在扶梯上就显示那一条，否则显示全局默认）
        //   X           -> 全局运行速度 = X
        //   X to Y      -> 只有当前全局运行速度正好是 X 时才改成 Y（没有速度为 X 的就不改）
        //   -f X        -> 强制游戏内所有扶梯运行速度 = X（不管有没有被改过）
        //   -f X to Y   -> 把所有运行速度为 X 的扶梯（含被改过的）改成 Y
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
            return InteractionResult.PASS;
        });

        // 【1.30】服务端刻收尾：把上一刻记下的「刚放/延长了扶梯」的位置重扫一遍。
        ServerTickEvents.END_SERVER_TICK.register(EscalatorSpeedManager::tickPendingReconcile);

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

        // 【1.16】客户端石斧界面里的「无障碍提示音」开关
        ServerPlayNetworking.registerGlobalReceiver(SetHelpPayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            boolean enabled = payload.helpEnabled();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                EscalatorSpeedManager.setHelp(level, pos, enabled);
                boolean applied = EscalatorSpeedManager.isHelpEnabled(level, pos);
                context.player().displayClientMessage(Component.literal(
                        "这条扶梯的无障碍提示音已" + (applied ? "开启" : "关闭")), true);
                // 开关极小，单独发包同步即可，不必重发整个音频库
                EscalatorSpeedManager.syncHelpToAll(context.server());
            });
        });

        // 【1.18】客户端石斧界面里的「提示音音量」输入框
        ServerPlayNetworking.registerGlobalReceiver(SetHelpVolumePayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            int volume = payload.volume();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int applied = EscalatorSpeedManager.setHelpVolume(level, pos, volume);
                context.player().displayClientMessage(Component.literal(
                        "这条扶梯的无障碍提示音音量已设为 " + applied + "%"), true);
                // 音量很小，单独发包同步即可，不必重发整个音频库
                EscalatorSpeedManager.syncHelpVolumeToAll(context.server());
            });
        });

        // 【1.41】客户端把一段音频设为这条扶梯**某一头**的无障碍提示音音乐
        //（与运行底噪的 BIND_AUDIO 完全对称，但数据独立；共用同一个音频库）
        ServerPlayNetworking.registerGlobalReceiver(BindHelpAudioPayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            String audioId = payload.audioId();
            boolean in = payload.in();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                if (EscalatorSpeedManager.bindHelpAudio(level, pos, audioId, in)) {
                    context.player().displayClientMessage(Component.literal(audioId.equals(EscalatorSpeedData.HELP_AUDIO_OFF)
                            ? "这条扶梯" + helpEndLabel(in) + "的无障碍提示音已设为「不播」"
                            : "已把这段声音设为这条扶梯" + helpEndLabel(in) + "的无障碍提示音"), true);
                    EscalatorSpeedManager.syncHelpAudioToAll(context.server());
                } else {
                    context.player().displayClientMessage(Component.literal("设置失败：音频不存在（先在提示音选择界面里导入 .ogg）"), true);
                }
            });
        });

        // 【1.41】客户端清掉这条扶梯**某一头**的提示音音乐单独设置（回到维度默认）
        ServerPlayNetworking.registerGlobalReceiver(UnbindHelpAudioPayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            boolean in = payload.in();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (EscalatorSpeedManager.unbindHelpAudio(level, pos, in)) {
                    context.player().displayClientMessage(Component.literal(
                            "这条扶梯" + helpEndLabel(in) + "的无障碍提示音已改回跟随默认"), true);
                    EscalatorSpeedManager.syncHelpAudioToAll(context.server());
                }
            });
        });

        // 【1.41】客户端把存档 smoothlift_audio 文件夹里的一个 OGG 导入存档并设为提示音音乐
        //（与运行底噪共用同一个文件夹与同一个库：导入一次，两边都能选）
        ServerPlayNetworking.registerGlobalReceiver(ImportFolderHelpAudioPayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            String fileName = payload.fileName();
            boolean in = payload.in();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                String problem = EscalatorSpeedManager.importAudioToStore(level, fileName);
                if (problem == null) {
                    EscalatorSpeedManager.bindHelpAudio(level, pos, fileName, in);
                    context.player().displayClientMessage(Component.literal(
                            "已从文件夹导入并设为这条扶梯" + helpEndLabel(in) + "的无障碍提示音（原文件删除后仍可播放）"), true);
                    EscalatorSpeedManager.syncAudioToAll(context.server());
                    EscalatorSpeedManager.syncHelpAudioToAll(context.server());
                } else {
                    context.player().displayClientMessage(Component.literal("导入失败：" + problem), true);
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
                EscalatorSpeedManager.sendHelpSyncTo(context.player(), context.player().serverLevel());
                EscalatorSpeedManager.sendHelpVolumeSyncTo(context.player(), context.player().serverLevel());
                EscalatorSpeedManager.sendRoundSyncTo(context.player(), context.player().serverLevel());
                EscalatorSpeedManager.sendHelpRoundSyncTo(context.player(), context.player().serverLevel());
                EscalatorSpeedManager.sendHelpSpeedSyncTo(context.player(), context.player().serverLevel());
                EscalatorSpeedManager.sendHelpAudioSyncTo(context.player(), context.player().serverLevel());
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

    /**
     * 注册全部指令：`/futispeed`、`/jietispeed`、`/futimusic`、`/futiloud`、`/futihelp`、
     * `/futihelploud`、`/futiround`、`/futihelpround`、`/futihelpspeed`、`/futihelpmusic`。
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
    }

}
