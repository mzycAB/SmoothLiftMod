package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
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
        Packets.sendToServer(new RequestSyncPacket());
    }

    /** 断开连接时清空客户端镜像与逐条渲染状态，避免残留上一个世界的数据。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        EscalatorSpeedManager.clearClientData();
        EscalatorStepRenderer.onDisconnect();
    }

    /** 每客户端刻尾推进动画时钟与阶梯索引（含周期重扫）。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        EscalatorStepRenderer.onClientTick(Minecraft.getInstance());
    }

    /** 世界渲染到 AFTER_ENTITIES 阶段时逐条绘制阶梯面。 */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        EscalatorStepRenderer.onRenderLevelStage(event);
    }

    /** 客户端区块加载 -> 扫描建索引。 */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (!(chunk.getLevel() instanceof ClientLevel level)) {
            return;
        }
        EscalatorStepIndex.onChunkLoad(level, chunk);
    }

    /** 客户端区块卸载 -> 移出索引。 */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        if (!(chunk.getLevel() instanceof ClientLevel level)) {
            return;
        }
        EscalatorStepIndex.onChunkUnload(level, chunk);
    }
}