package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.Tickable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 扶梯阶梯贴图专用 Tickable：包裹原版 ticker，把 "每游戏帧推进 1 帧"
 * 改为 "每游戏帧推进 speed 帧"。
 *
 * 阶梯动画跟随「玩家脚下那条扶梯」的阶梯动画速度推进：
 * - 石斧给这条扶梯设置过阶梯动画速度 -> 用它；
 * - 否则回退到这条扶梯的运行速度；
 * - 玩家不在扶梯上时回退到维度默认速度。
 * 因为阶梯贴图在整条扶梯间共享，这里解析的是当前正在观看的扶梯。
 */
public final class EscalatorStepTicker implements Tickable {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");
    private static double lastLoggedFactor = -1.0;

    private final Tickable delegate;
    private double accumulator;

    public EscalatorStepTicker(Tickable delegate) {
        this.delegate = delegate;
    }

    @Override
    public void tick() {
        double speed = resolveStepAnimationSpeed();
        if (Double.isNaN(speed) || Double.isInfinite(speed)) {
            speed = EscalatorSpeedData.DEFAULT_SPEED;
        }
        double factor = Math.max(0.0, Math.min(50.0, speed / EscalatorSpeedData.VANILLA_STEP));
        if (Math.abs(factor - lastLoggedFactor) > 0.01) {
            lastLoggedFactor = factor;
            LOGGER.info("[SmoothLift] escalator texture speed factor now: {}", factor);
        }
        accumulator += factor;
        int rounds = (int) accumulator;
        accumulator -= rounds;
        while (rounds-- > 0) {
            delegate.tick();
        }
    }

    private static double resolveStepAnimationSpeed() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return EscalatorSpeedData.VANILLA_STEP;
        }
        Level level = minecraft.level;
        if (minecraft.player == null) {
            return EscalatorSpeedManager.getGlobalStepDefault(level);
        }
        for (BlockPos pos : candidateBlocks(minecraft.player.blockPosition())) {
            if (EscalatorUtil.isEscalator(level.getBlockState(pos))) {
                return EscalatorSpeedManager.getStepAnimationSpeed(level, pos);
            }
        }
        return EscalatorSpeedManager.getGlobalStepDefault(level);
    }

    /** 在玩家脚下周边一个小范围内找扶梯，覆盖站上、站在一边或紧邻观看的情况。 */
    private static Iterable<BlockPos> candidateBlocks(BlockPos center) {
        List<BlockPos> list = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    if (dx != 0 || dz != 0 || dy != 0) {
                        list.add(center.offset(dx, dy, dz));
                    }
                }
            }
        }
        list.add(center);
        return list;
    }
}