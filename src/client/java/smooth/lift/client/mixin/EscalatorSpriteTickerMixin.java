package smooth.lift.client.mixin;

import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteTicker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import smooth.lift.client.EscalatorStepTicker;

import java.util.HashSet;
import java.util.Set;

/**
 * 拦截所有动画精灵的 createTicker()：当精灵是 MTR 扶梯阶梯的滚动贴图时，
 * 用 EscalatorStepTicker 替换默认 ticker，让贴图帧按扶梯速度同步推进。
 */
@Mixin(SpriteContents.class)
public abstract class EscalatorSpriteTickerMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    private static final Set<String> LOGGED_NAMES = new HashSet<>();

    @Inject(method = "createTicker", at = @At("RETURN"), cancellable = true)
    private void smoothlift_syncEscalatorAnimation(CallbackInfoReturnable<SpriteTicker> cir) {
        SpriteTicker original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        SpriteContents self = (SpriteContents) (Object) this;
        String name = self.name().toString();
        if (LOGGED_NAMES.add(name)) {
            LOGGER.info("[SmoothLift] createTicker called: name={}", name);
        }
        if (!name.endsWith("escalator_up") && !name.endsWith("escalator_down")
                && !name.contains("escalator_up") && !name.contains("escalator_down")) {
            return;
        }
        LOGGER.info("[SmoothLift] wrapping escalator ticker: name={}", name);
        cir.setReturnValue(new EscalatorStepTicker(original));
    }
}