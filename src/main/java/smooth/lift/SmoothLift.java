package smooth.lift;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import smooth.lift.network.RequestSyncPayload;
import smooth.lift.network.SetSpeedPayload;
import smooth.lift.network.SyncPayload;

public class SmoothLift implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("[SmoothLift] Loaded");

        PayloadTypeRegistry.playC2S().register(SetSpeedPayload.TYPE, SetSpeedPayload.CODEC);
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
}
