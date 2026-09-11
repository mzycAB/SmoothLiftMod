package smooth.lift;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import smooth.lift.network.AlignStepPayload;
import smooth.lift.network.RequestSyncPayload;
import smooth.lift.network.RestoreStepPayload;
import smooth.lift.network.SetSpeedPayload;
import smooth.lift.network.SetStepSpeedPayload;
import smooth.lift.network.SyncPayload;

public class SmoothLift implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("[SmoothLift] Loaded");

        PayloadTypeRegistry.playC2S().register(SetSpeedPayload.TYPE, SetSpeedPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SetStepSpeedPayload.TYPE, SetStepSpeedPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AlignStepPayload.TYPE, AlignStepPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RestoreStepPayload.TYPE, RestoreStepPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestSyncPayload.TYPE, RequestSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncPayload.TYPE, SyncPayload.CODEC);

        // /futispeed：设置玩家所在维度的默认速度（未单独调速的扶梯使用）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("futispeed")
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                    .executes(context -> {
                        float speed = FloatArgumentType.getFloat(context, "speed");
                        ServerLevel level = context.getSource().getLevel();
                        EscalatorSpeedManager.setDefault(level, speed);
                        EscalatorSpeedManager.syncToAll(context.getSource().getServer());
                        context.getSource().sendSuccess(
                            () -> Component.literal("本维度扶梯默认速度已设置为 " + EscalatorSpeedData.format(speed) + " 格/秒"),
                            false
                        );
                        return 1;
                    })
                )
                // /futispeed f X：强制所有扶梯运行速度 = X（包括石斧自定义过的）
                .then(Commands.literal("f")
                    .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                        .executes(context -> {
                            float speed = FloatArgumentType.getFloat(context, "speed");
                            ServerLevel level = context.getSource().getLevel();
                            int count = EscalatorSpeedManager.forceAllRunningSpeed(level, speed);
                            EscalatorSpeedManager.syncToAll(context.getSource().getServer());
                            context.getSource().sendSuccess(
                                () -> Component.literal("已强制把所有 " + count + " 个扶梯方块的运行速度设为 "
                                        + EscalatorSpeedData.format(speed) + " 格/秒"),
                                false
                            );
                            return 1;
                        })
                    )
                )
            );

            // /jietispeed：阶梯动画速度全局调节
            //   X        -> 开启调节，阶梯动画速度 = X
            //   on       -> 开启调节，恢复上次 /jietispeed X 的值
            //   off      -> 关闭调节，阶梯动画恢复 MTR 原版
            // 三种都默认忽略石斧自定义过的扶梯；带 f（f X / f on / f off）则强制包含
            dispatcher.register(Commands.literal("jietispeed")
                .then(Commands.literal("on").executes(context -> jietiOnOff(context, true, false)))
                .then(Commands.literal("off").executes(context -> jietiOnOff(context, false, false)))
                .then(Commands.literal("f")
                    .then(Commands.literal("on").executes(context -> jietiOnOff(context, true, true)))
                    .then(Commands.literal("off").executes(context -> jietiOnOff(context, false, true)))
                    .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                        .executes(context -> jietiValue(context, true)))
                )
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                    .executes(context -> jietiValue(context, false)))
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

        // 客户端输入界面发来的【阶梯动画】设置请求
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

        // 客户端输入界面：对齐阶梯动画到运行速度
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

        // 客户端输入界面：恢复阶梯动画为 MTR 原版默认
        ServerPlayNetworking.registerGlobalReceiver(RestoreStepPayload.TYPE, (payload, context) -> {
            BlockPos pos = payload.pos();
            context.server().execute(() -> {
                ServerLevel level = context.player().serverLevel();
                if (!EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                    return;
                }
                int count = EscalatorSpeedManager.restoreStepDefault(level, pos);
                if (count > 0) {
                    context.player().displayClientMessage(
                        Component.literal("已把这条扶梯（" + count + " 格）的阶梯动画恢复为 MTR 原版默认（运行速度不变）"),
                        true
                    );
                    EscalatorSpeedManager.syncToAll(context.server());
                }
            });
        });

        // 玩家进入游戏时同步全部数据（服务端侧兜底，客户端还会主动请求一次）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            EscalatorSpeedManager.syncToAll(server);
        });

        // 客户端进世界后主动请求同步：此时双方频道均已就绪，可靠送达
        ServerPlayNetworking.registerGlobalReceiver(RequestSyncPayload.TYPE, (payload, context) -> {
            context.server().execute(() -> EscalatorSpeedManager.syncToAll(context.server()));
        });

        // 扶梯方块被破坏时清除对应记录
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide() || !(world instanceof ServerLevel level)) {
                return;
            }
            if (!EscalatorUtil.isEscalator(state)) {
                return;
            }
            EscalatorSpeedManager.removeSpeed(level, pos);
            EscalatorSpeedManager.syncToAll(world.getServer());
        });
    }

    private static int jietiValue(CommandContext<CommandSourceStack> context, boolean includeAdjusted) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setJietiValue(level, speed);
        int forced = includeAdjusted ? EscalatorSpeedManager.jietiCommand(level, true, true) : 0;
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
            () -> Component.literal("已开启阶梯动画调节，速度 = " + EscalatorSpeedData.format(speed)
                    + " 格/秒" + (includeAdjusted ? "（含石斧自定义，强制覆盖 " + forced + " 格）" : "（石斧自定义的不动）")),
            false
        );
        return 1;
    }

    private static int jietiOnOff(CommandContext<CommandSourceStack> context, boolean enabled, boolean includeAdjusted) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int forced = EscalatorSpeedManager.jietiCommand(level, enabled, includeAdjusted);
        EscalatorSpeedManager.syncToAll(source.getServer());
        String state = enabled
                ? "已开启阶梯动画调节" + (includeAdjusted ? "（含石斧自定义）" : "（石斧自定义的不动）")
                : "已关闭阶梯动画调节，阶梯动画恢复 MTR 原版" + (includeAdjusted ? "（含石斧自定义）" : "（石斧自定义的不动）");
        String extra = forced > 0 ? "，强制覆盖 " + forced + " 格石斧自定义扶梯" : "";
        source.sendSuccess(() -> Component.literal(state + extra), false);
        return 1;
    }
}