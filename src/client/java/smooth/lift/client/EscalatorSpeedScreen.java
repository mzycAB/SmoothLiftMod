package smooth.lift.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

/**
 * 拿着石斧右键扶梯后弹出的设置界面。
 *
 * <p>只有两个输入框和两个按钮：
 * <ul>
 *   <li>「扶梯速度」——这条扶梯的运行速度；</li>
 *   <li>「阶梯速度」——这条扶梯的阶梯动画速度；</li>
 *   <li>「阶梯速度对齐扶梯速度」——把阶梯速度框填成扶梯速度框的值。</li>
 * </ul>
 *
 * <p>没有「确定」按钮：**按 ESC 退出界面时统一应用**（若两项都没改动则什么都不发）。
 *
 * <p>联动规则：
 * <ul>
 *   <li>改「扶梯速度」时，「阶梯速度」框会自动跟着一起变（除非玩家自己动手改过阶梯速度框）
 *       —— 应用后阶梯速度就跟随了新的扶梯速度；</li>
 *   <li>改「阶梯速度」不会反过来影响「扶梯速度」。</li>
 * </ul>
 */
public class EscalatorSpeedScreen extends Screen {
    private final BlockPos pos;

    private EditBox runInput;
    private EditBox stepInput;

    /** 打开界面时两个框里显示的基准值，用来判断玩家到底改了哪一项。 */
    private double openRun;
    private double openStep;

    /** 玩家是否手动改过阶梯速度框；没改过时，改扶梯速度会把阶梯速度一起带着变。 */
    private boolean stepEdited;
    /** 程序内部回填阶梯速度框时置位，避免被误判成「玩家手动修改」。 */
    private boolean suppressStepResponder;

    public EscalatorSpeedScreen(BlockPos pos) {
        super(Component.literal("扶梯设置"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        openRun = currentRunningSpeed(mc);
        openStep = currentStepSpeed(mc);

        runInput = new EditBox(this.font, this.width / 2 - 100, 62, 200, 20, Component.literal("扶梯速度"));
        runInput.setMaxLength(32);
        runInput.setValue(EscalatorSpeedData.format(openRun));
        runInput.setResponder(this::onRunEdited);
        addRenderableWidget(runInput);

        stepInput = new EditBox(this.font, this.width / 2 - 100, 118, 200, 20, Component.literal("阶梯速度"));
        stepInput.setMaxLength(32);
        stepInput.setValue(EscalatorSpeedData.format(openStep));
        stepInput.setResponder(this::onStepEdited);
        addRenderableWidget(stepInput);

        addRenderableWidget(Button.builder(Component.literal("阶梯速度对齐扶梯速度"), button -> alignStepToRun())
                .bounds(this.width / 2 - 100, 144, 200, 20)
                .build());

        setInitialFocus(runInput);
    }

    /** 这条扶梯当前的运行速度（未单独设置就是维度默认）。 */
    private double currentRunningSpeed(Minecraft mc) {
        if (mc.level == null) {
            return EscalatorSpeedData.DEFAULT_SPEED;
        }
        return EscalatorSpeedManager.getSpeed(mc.level, pos);
    }

    /** 这条扶梯当前的阶梯动画速度（单独设置 > /jietispeed 维度值 > 跟随运行速度）。 */
    private double currentStepSpeed(Minecraft mc) {
        if (mc.level == null) {
            return EscalatorSpeedData.DEFAULT_SPEED;
        }
        return EscalatorSpeedManager.getAnimationSpeed(mc.level, pos);
    }

    /** 改扶梯速度：只要玩家没自己动过阶梯速度框，就把阶梯速度框同步成一样的值。 */
    private void onRunEdited(String value) {
        if (suppressStepResponder || stepEdited || stepInput == null) {
            return;
        }
        suppressStepResponder = true;
        try {
            stepInput.setValue(value);
        } finally {
            suppressStepResponder = false;
        }
    }

    /** 改阶梯速度：标记玩家动过它，之后改扶梯速度就不再自动覆盖阶梯速度框。 */
    private void onStepEdited(String value) {
        if (suppressStepResponder) {
            return;
        }
        stepEdited = true;
    }

    /** 「阶梯速度对齐扶梯速度」：把阶梯速度框填成扶梯速度框当前的值。 */
    private void alignStepToRun() {
        if (runInput == null || stepInput == null) {
            return;
        }
        Double typed = parse(runInput.getValue());
        double value = typed != null ? typed : currentRunningSpeed(Minecraft.getInstance());
        suppressStepResponder = true;
        try {
            stepInput.setValue(EscalatorSpeedData.format(EscalatorSpeedData.clamp(value)));
        } finally {
            suppressStepResponder = false;
        }
        // 对齐之后重新回到「跟着扶梯速度变」的状态。
        stepEdited = false;
    }

    /** 按 ESC（或回车）退出时统一应用改动。 */
    @Override
    public void onClose() {
        applyAndClose();
    }

    private void applyAndClose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && runInput != null && stepInput != null) {
            Double run = parse(runInput.getValue());
            Double step = parse(stepInput.getValue());

            boolean runChanged = run != null && !nearly(run, openRun);
            boolean stepChanged = step != null && !nearly(step, openStep);
            // 阶梯速度正好等于（新的）扶梯速度时不必单独发送：设置扶梯速度会清掉单独设置，
            // 阶梯速度自然就跟随扶梯速度了，数据也更干净。
            boolean stepIsJustRun = runChanged && step != null && run != null && nearly(step, run);

            if (runChanged) {
                sendApply(true, EscalatorSpeedData.clamp(run),
                        stepChanged && !stepIsJustRun,
                        step == null ? 0.0 : EscalatorSpeedData.clamp(step));
            } else if (stepChanged) {
                sendApply(false, 0.0, true, EscalatorSpeedData.clamp(step));
            }
        }
        // Screen.onClose() 内部就是 minecraft.setScreen(null)。
        super.onClose();
    }

    private void sendApply(boolean setRun, double run, boolean setStep, double step) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeBoolean(setRun);
        buf.writeDouble(run);
        buf.writeBoolean(setStep);
        buf.writeDouble(step);
        ClientPlayNetworking.send(SmoothLift.APPLY_CHAIN_CHANNEL, buf);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            applyAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 32, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.literal("扶梯速度（格/秒）"),
                this.width / 2, 50, 0xA0A0A0);
        guiGraphics.drawCenteredString(this.font, Component.literal("阶梯速度（格/秒）"),
                this.width / 2, 106, 0xA0A0A0);

        Minecraft mc = Minecraft.getInstance();
        guiGraphics.drawCenteredString(this.font,
                Component.literal("当前：扶梯 " + EscalatorSpeedData.format(currentRunningSpeed(mc))
                        + "，阶梯 " + EscalatorSpeedData.format(currentStepSpeed(mc)) + " 格/秒"),
                this.width / 2, 178, 0x808080);
        guiGraphics.drawCenteredString(this.font,
                Component.literal("改扶梯速度会同步阶梯速度；改阶梯速度不影响扶梯速度"),
                this.width / 2, 193, 0x808080);

        guiGraphics.drawCenteredString(this.font,
                Component.literal("按 ESC 保存并退出　·　扶梯位置 " + pos.toShortString()),
                this.width / 2, this.height - 24, 0x707070);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 解析输入框内容；不是合法数字返回 null（视为未改动）。 */
    private static Double parse(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean nearly(double a, double b) {
        return Math.abs(a - b) < 1.0E-6;
    }
}
