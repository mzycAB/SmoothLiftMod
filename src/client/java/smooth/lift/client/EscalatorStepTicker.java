package smooth.lift.client;

import net.minecraft.client.renderer.texture.Tickable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;

/**
 * 扶梯阶梯贴图专用 Tickable（1.19.2 的动画精灵接口）：包裹原版 ticker，把
 * 「每游戏帧推进 1 帧」改为「每游戏帧推进 speed 帧」。
 *
 * <p>速度由 {@link EscalatorAnimationDriver} 解析 —— 阶梯贴图在地图上共享，
 * 同一时刻只能由一条扶梯驱动动画，具体选择规则见那个类。
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
        double speed = EscalatorAnimationDriver.resolveStepAnimationSpeed();
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
}
