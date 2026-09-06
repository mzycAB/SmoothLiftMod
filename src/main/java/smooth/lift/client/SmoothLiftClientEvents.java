package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;
import smooth.lift.SmoothLift;
import smooth.lift.network.Packets;
import smooth.lift.network.RequestSyncPacket;

@Mod.EventBusSubscriber(modid = SmoothLift.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SmoothLiftClientEvents {

    /** 拿着石斧右键扶梯 -> 打开速度输入界面（客户端拦截）。 */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!level.isClientSide() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        if (!event.getEntity().getMainHandItem().is(Items.STONE_AXE)) {
            return;
        }
        if (!EscalatorUtil.isEscalator(level.getBlockState(event.getPos()))) {
            return;
        }
        Minecraft.getInstance().setScreen(new EscalatorSpeedScreen(event.getPos()));
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
    }

    /** 客户端完全进世界后主动向服务端请求速度数据。 */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Packets.CHANNEL.send(new RequestSyncPacket(), PacketDistributor.SERVER.noArg());
    }

    /** 断开连接时清空客户端镜像，避免残留上一个世界的速度数据。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        EscalatorSpeedManager.clearClientData();
    }
}
