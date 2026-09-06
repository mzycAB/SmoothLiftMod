package smooth.lift;

import com.mojang.brigadier.arguments.FloatArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import smooth.lift.network.Packets;

@Mod(SmoothLift.MOD_ID)
public class SmoothLift {
    public static final String MOD_ID = "smooth_lift";

    public SmoothLift() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(Packets::register);
    }

    /** /futispeed：设置玩家所在维度的默认速度（未单独调速的扶梯使用）。 */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("futispeed")
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                        .executes(context -> {
                            float speed = FloatArgumentType.getFloat(context, "speed");
                            ServerLevel level = context.getSource().getLevel();
                            EscalatorSpeedManager.setDefault(level, speed);
                            EscalatorSpeedManager.syncToAll(context.getSource().getServer());
                            context.getSource().sendSuccess(
                                    () -> Component.literal("本维度扶梯默认速度已设置为 "
                                            + EscalatorSpeedData.format(speed) + " 格/秒"),
                                    false
                            );
                            return 1;
                        })
                )
        );
    }

    /** 服务端兜底：拿着石斧右键扶梯时取消原版交互（正常情况下客户端已拦截，不会发包）。 */
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (event.getEntity().getMainHandItem().is(Items.STONE_AXE)
                && EscalatorUtil.isEscalator(event.getLevel().getBlockState(event.getPos()))) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    /** 玩家进入游戏时同步全部数据（服务端侧兜底，客户端还会主动请求一次）。 */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EscalatorSpeedManager.syncToAll(player.server);
        }
    }

    /** 扶梯方块被破坏时清除对应记录。 */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!EscalatorUtil.isEscalator(event.getState())) {
            return;
        }
        EscalatorSpeedManager.removeSpeed(level, event.getPos());
        EscalatorSpeedManager.syncToAll(level.getServer());
    }
}
