package smooth.lift.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;
import smooth.lift.SmoothLift;

import java.util.HashMap;
import java.util.Map;

public class SmoothLiftClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 拿着石斧右键扶梯 -> 打开速度输入界面
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!player.getMainHandItem().is(Items.STONE_AXE)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            if (!EscalatorUtil.isEscalator(world.getBlockState(pos))) {
                return InteractionResult.PASS;
            }
            Minecraft.getInstance().setScreen(new EscalatorSpeedScreen(pos));
            return InteractionResult.FAIL;
        });

        // 接收服务端同步的全部速度数据
        ClientPlayNetworking.registerGlobalReceiver(SmoothLift.SYNC_CHANNEL, (client, handler, buf, responseSender) -> {
            int dimCount = buf.readVarInt();
            final Map<ResourceKey<Level>, Map.Entry<Double, Map<BlockPos, Double>>> parsed = new HashMap<>();
            for (int i = 0; i < dimCount; i++) {
                String dimId = buf.readUtf(256);
                double defaultSpeed = buf.readDouble();
                int entryCount = buf.readVarInt();
                Map<BlockPos, Double> speeds = new HashMap<>();
                for (int j = 0; j < entryCount; j++) {
                    speeds.put(buf.readBlockPos(), buf.readDouble());
                }
                try {
                    parsed.put(EscalatorSpeedManager.parseDimensionKey(dimId), Map.entry(defaultSpeed, speeds));
                } catch (Exception ignored) {
                }
            }
            client.execute(() -> {
                for (Map.Entry<ResourceKey<Level>, Map.Entry<Double, Map<BlockPos, Double>>> entry : parsed.entrySet()) {
                    EscalatorSpeedManager.applyClientData(entry.getKey(),
                            entry.getValue().getKey(), entry.getValue().getValue());
                }
            });
        });
    }
}
