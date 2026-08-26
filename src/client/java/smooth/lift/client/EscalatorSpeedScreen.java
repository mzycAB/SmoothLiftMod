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
 * 拿着石斧右键扶梯后弹出的速度输入界面。
 * 确定后把「方块坐标 + 速度(格/秒)」发给服务端，由服务端对整条扶梯链
 * 设置速度并持久化保存。
 */
public class EscalatorSpeedScreen extends Screen {
    private static final double MIN_SPEED = 0.0;

    private final BlockPos pos;
    private EditBox input;
    private Component status = Component.empty();

    public EscalatorSpeedScreen(BlockPos pos) {
        super(Component.literal("扶梯速度设置"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        input = new EditBox(this.font, this.width / 2 - 100, 75, 200, 20, Component.literal("速度"));
        input.setMaxLength(32);
        Minecraft mc = Minecraft.getInstance();
        Double current = mc.level != null ? EscalatorSpeedManager.getClientSpeed(mc.level, pos) : null;
        if (current == null) {
            current = mc.level != null ? EscalatorSpeedManager.getClientDefault(mc.level) : EscalatorSpeedData.DEFAULT_SPEED;
        }
        input.setValue(EscalatorSpeedData.format(current));
        addRenderableWidget(input);
        setInitialFocus(input);

        addRenderableWidget(Button.builder(Component.literal("确定"), button -> confirm())
                .bounds(this.width / 2 - 100, 110, 95, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
                .bounds(this.width / 2 + 5, 110, 95, 20)
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
        if (speed < MIN_SPEED || speed > EscalatorSpeedData.MAX_SPEED) {
            status = Component.literal("速度需在 " + EscalatorSpeedData.format(MIN_SPEED) + " ~ "
                    + EscalatorSpeedData.format(EscalatorSpeedData.MAX_SPEED) + " 格/秒之间");
            return;
        }

        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeDouble(speed);
        ClientPlayNetworking.send(SmoothLift.SET_SPEED_CHANNEL, buf);
        onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && input != null && input.isFocused()) {
            confirm();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 45, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.literal("速度（格/秒）"),
                this.width / 2, 62, 0xA0A0A0);
        guiGraphics.drawCenteredString(this.font,
                Component.literal("扶梯位置: " + pos.toShortString()),
                this.width / 2, 145, 0x707070);
        if (!status.getString().isEmpty()) {
            guiGraphics.drawCenteredString(this.font, status, this.width / 2, 140, 0xFF5555);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
