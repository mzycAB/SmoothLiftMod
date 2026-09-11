package smooth.lift.mixin;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.Tickable;
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
 * 拦截所有动画精灵的 getAnimationTicker()：当精灵是 MTR 扶梯阶梯的滚动贴图时，
 * 用 EscalatorStepTicker 替换默认 ticker，让贴图帧按扶梯速度同步推进。
 */
@Mixin(TextureAtlasSprite.class)
public abstract class EscalatorSpriteTickerMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    private static final Set<String> LOGGED_NAMES = new HashSet<>();

    @Inject(method = "getAnimationTicker", at = @At("RETURN"), cancellable = true)
    private void smoothlift_syncEscalatorAnimation(CallbackInfoReturnable<Tickable> cir) {
        Tickable original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        TextureAtlasSprite self = (TextureAtlasSprite) (Object) this;
        String name = self.getName().toString();
        if (LOGGED_NAMES.add(name)) {
            LOGGER.info("[SmoothLift] getAnimationTicker called: name={}", name);
        }
        if (!name.endsWith("escalator_up") && !name.endsWith("escalator_down")
                && !name.contains("escalator_up") && !name.contains("escalator_down")) {
            return;
        }
        LOGGER.info("[SmoothLift] wrapping escalator ticker: name={}", name);
        cir.setReturnValue(new EscalatorStepTicker(original));
    }
}