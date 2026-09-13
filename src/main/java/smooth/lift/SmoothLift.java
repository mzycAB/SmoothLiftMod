package smooth.lift;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

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

    @Override
    public void onInitialize() {
        System.out.println("[SmoothLift] Loaded");

        // /futispeed：全局扶梯运行速度（只作用于「全局扶梯」＝没被石斧改过、或改完仍与全局一致的）
        //   X           -> 全局运行速度 = X
        //   X to Y      -> 只有当前全局运行速度正好是 X 时才改成 Y（没有速度为 X 的就不改）
        //   -f X        -> 强制游戏内所有扶梯运行速度 = X（不管有没有被改过）
        //   -f X to Y   -> 把所有运行速度为 X 的扶梯（含被改过的）改成 Y
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("futispeed")
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                    .executes(SmoothLift::futiGlobal)
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                            .executes(SmoothLift::futiFromTo))))
                .then(futiForce("-f"))
            );

            // /jietispeed：全局扶梯阶梯速度（**永远不动运行速度**）
            //   X           -> 全局阶梯速度 = X
            //   X to Y      -> 只有当前全局阶梯速度正好是 X 时才改成 Y
            //   -f X        -> 强制所有扶梯阶梯速度 = X
            //   -f X to Y   -> 把所有阶梯速度为 X 的扶梯改成 Y
            dispatcher.register(Commands.literal("jietispeed")
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                    .executes(SmoothLift::jietiGlobal)
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                            .executes(SmoothLift::jietiFromTo))))
                .then(jietiForce("-f"))
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

        // 玩家进入游戏时同步全部数据（服务端侧兜底，客户端还会主动请求一次）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            EscalatorSpeedManager.syncToAll(server);
        });

        // 客户端进世界后主动请求同步：此时双方频道均已就绪，可靠送达
        ServerPlayNetworking.registerGlobalReceiver(REQUEST_SYNC_CHANNEL, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> EscalatorSpeedManager.syncToAll(server));
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

    // ------------------------------------------------------------------
    // /futispeed
    // ------------------------------------------------------------------

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
}
