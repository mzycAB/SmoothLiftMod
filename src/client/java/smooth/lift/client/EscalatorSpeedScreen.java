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
 * 上半部分：运行速度；下半部分：阶梯动画速度 + 对齐 + 恢复默认。
 * 所有改动只作用于右键的这条扶梯，发送给服务端持久化保存。
 */
public class EscalatorSpeedScreen extends Screen {
    private static final double MIN_SPEED = 0.0;

    private final BlockPos pos;
    private EditBox input;
    private EditBox stepInput;
    private Component status = Component.empty();

    public EscalatorSpeedScreen(BlockPos pos) {
        super(Component.literal("扶梯设置"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();

        input = new EditBox(this.font, this.width / 2 - 100, 62, 200, 20, Component.literal("运行速度"));
        input.setMaxLength(32);
        Double current = mc.level != null ? EscalatorSpeedManager.getClientSpeed(mc.level, pos) : null;
        if (current == null) {
            current = mc.level != null ? EscalatorSpeedManager.getClientDefault(mc.level) : EscalatorSpeedData.DEFAULT_SPEED;
        }
        input.setValue(EscalatorSpeedData.format(current));
        addRenderableWidget(input);

        stepInput = new EditBox(this.font, this.width / 2 - 100, 142, 200, 20, Component.literal("阶梯动画速度"));
        stepInput.setMaxLength(32);
        double step = mc.level != null ? EscalatorSpeedManager.getStepAnimationSpeed(mc.level, pos) : EscalatorSpeedData.DEFAULT_SPEED;
        stepInput.setValue(EscalatorSpeedData.format(step));
        addRenderableWidget(stepInput);
        setInitialFocus(input);

        addRenderableWidget(Button.builder(Component.literal("确定"), button -> confirm())
                .bounds(this.width / 2 - 100, 92, 95, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
                .bounds(this.width / 2 + 5, 92, 95, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("应用"), button -> applyStep())
                .bounds(this.width / 2 - 100, 172, 62, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("对齐"), button -> alignStep())
                .bounds(this.width / 2 - 33, 172, 62, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("恢复默认"), button -> restoreStep())
                .bounds(this.width / 2 + 34, 172, 66, 20)
                .build());
    }

    private void confirm() {
        double speed;
        try {
            speed = Double.parseDouble(input.getValue().trim());
        } catch (NumberFormatException e) {
            status = Component.literal("请输入有效的数字");
            return;
        }
        if (!validate(speed)) {
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeDouble(speed);
        ClientPlayNetworking.send(SmoothLift.SET_SPEED_CHANNEL, buf);
        status = Component.literal("已发送：运行速度 " + EscalatorSpeedData.format(speed) + " 格/秒");
    }

    private void applyStep() {
        double step;
        try {
            step = Double.parseDouble(stepInput.getValue().trim());
        } catch (NumberFormatException e) {
            status = Component.literal("请输入有效的阶梯动画速度");
            return;
        }
        if (!validate(step)) {
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeDouble(step);
        ClientPlayNetworking.send(SmoothLift.SET_STEP_SPEED_CHANNEL, buf);
        status = Component.literal("已发送：阶梯动画速度 " + EscalatorSpeedData.format(step) + " 格/秒");
    }

    private void alignStep() {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        ClientPlayNetworking.send(SmoothLift.ALIGN_STEP_CHANNEL, buf);
        status = Component.literal("已发送：阶梯动画对齐到运行速度");
    }

    private void restoreStep() {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        ClientPlayNetworking.send(SmoothLift.RESTORE_STEP_CHANNEL, buf);
        status = Component.literal("已发送：阶梯动画恢复为 MTR 原版默认");
    }

    private boolean validate(double speed) {
        if (speed < MIN_SPEED || speed > EscalatorSpeedData.MAX_SPEED) {
            status = Component.literal("速度需在 " + EscalatorSpeedData.format(MIN_SPEED) + " ~ "
                    + EscalatorSpeedData.format(EscalatorSpeedData.MAX_SPEED) + " 格/秒之间");
            return false;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean enter = keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
        if (enter && input != null && input.isFocused()) {
            confirm();
            return true;
        }
        if (enter && stepInput != null && stepInput.isFocused()) {
            applyStep();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 32, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.literal("运行速度（格/秒）"),
                this.width / 2, 50, 0xA0A0A0);
        guiGraphics.drawCenteredString(this.font, Component.literal("阶梯动画速度（格/秒）"),
                this.width / 2, 130, 0xA0A0A0);
        guiGraphics.drawCenteredString(this.font,
                Component.literal("扶梯位置: " + pos.toShortString()),
                this.width / 2, 205, 0x707070);
        if (!status.getString().isEmpty()) {
            guiGraphics.drawCenteredString(this.font, status, this.width / 2, this.height - 20, 0xFF5555);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}