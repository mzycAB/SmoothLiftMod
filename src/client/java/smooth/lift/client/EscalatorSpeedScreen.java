package smooth.lift.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.network.ApplyChainPayload;
import smooth.lift.network.SetVolumePayload;

/**
 * 拿着石斧右键扶梯后弹出的设置界面。
 *
 * <p>速度部分：
 * <ul>
 *   <li>「扶梯速度」——这条扶梯的运行速度；</li>
 *   <li>「阶梯速度」——这条扶梯的阶梯动画速度；</li>
 *   <li>「阶梯速度对齐扶梯速度」——把阶梯速度框填成扶梯速度框的值；</li>
 *   <li>「声音设置…」——进入自定义声音子界面（绑定/解绑 OGG 音频）。</li>
 * </ul>
 *
 * <p>【1.9】声音部分：
 * <ul>
 *   <li>「声音音量」——这条扶梯的声音音量，输入 <b>1~1000</b>（100 = 原始音量，1000 = 10× 放大），
 *       实际音量 = 距离衰减 × 这个百分比；</li>
 *   <li>界面里会提示「离开**整条扶梯** 16 格才静音」——距离是按整条扶梯算的，不是按某个方块算。</li>
 * </ul>
 *
 * <p>没有「确定」按钮：**按 ESC 退出界面时统一应用**（若三项都没改动则什么都不发）。
 * 点「声音设置…」进入子界面**之前**也会先把改动发出去，避免「刚填好音量就点了音乐选择」导致丢失。
 */
public class EscalatorSpeedScreen extends Screen {
    private final BlockPos pos;

    private EditBox runInput;
    private EditBox stepInput;
    private EditBox volumeInput;

    /** 打开界面时三个框里显示的基准值，用来判断玩家到底改了哪一项。 */
    private double openRun;
    private double openStep;
    private int openVolume;

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
        openVolume = currentVolume(mc);

        runInput = new EditBox(this.font, this.width / 2 - 100, 44, 200, 20, Component.literal("扶梯速度"));
        runInput.setMaxLength(32);
        runInput.setValue(EscalatorSpeedData.format(openRun));
        runInput.setResponder(this::onRunEdited);
        addRenderableWidget(runInput);

        stepInput = new EditBox(this.font, this.width / 2 - 100, 80, 200, 20, Component.literal("阶梯速度"));
        stepInput.setMaxLength(32);
        stepInput.setValue(EscalatorSpeedData.format(openStep));
        stepInput.setResponder(this::onStepEdited);
        addRenderableWidget(stepInput);

        addRenderableWidget(Button.builder(Component.literal("阶梯速度对齐扶梯速度"), button -> alignStepToRun())
                .bounds(this.width / 2 - 100, 102, 200, 20)
                .build());

        // 【1.9】声音音量：1~1000（【1.12】100 = 原始音量，可放大到 1000），只允许输入数字
        volumeInput = new EditBox(this.font, this.width / 2 - 100, 138, 200, 20, Component.literal("声音音量"));
        volumeInput.setMaxLength(4);
        volumeInput.setValue(String.valueOf(openVolume));
        volumeInput.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        addRenderableWidget(volumeInput);

        // 【1.7】自定义声音：音乐选择子界面入口
        addRenderableWidget(Button.builder(Component.literal("声音设置…"), button -> openAudioSetup())
                .bounds(this.width / 2 - 100, 162, 200, 20)
                .build());

        setInitialFocus(runInput);
    }

    /**
     * 打开自定义声音子界面（本界面被替换掉，返回时由声音界面重建）。
     * 先把改动发出去：否则「填好音量 → 点声音设置 → 返回」会看到音量被重置回旧值。
     */
    private void openAudioSetup() {
        applyChanges();
        Minecraft.getInstance().setScreen(new AudioSetupScreen(pos));
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

    /**
     * 【1.9】这条扶梯当前的声音音量。
     * 用 {@code getVolumeForScreen}：会顺着扶梯链找同一条扶梯上已设过的音量，
     * 所以在这条扶梯的任意一个方块上打开界面，看到的都是同一个值。
     */
    private int currentVolume(Minecraft mc) {
        if (mc.level == null) {
            return EscalatorSpeedData.DEFAULT_AUDIO_VOLUME;
        }
        return EscalatorSpeedManager.getVolumeForScreen(mc.level, pos);
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
        applyChanges();
        // Screen.onClose() 内部就是 minecraft.setScreen(null)。
        super.onClose();
    }

    /** 把三个框里改过的值发出去（速度与音量各自独立，没改的不发）。 */
    private void applyChanges() {
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
                openRun = run;
                openStep = step != null ? step : openStep;
            } else if (stepChanged) {
                sendApply(false, 0.0, true, EscalatorSpeedData.clamp(step));
                openStep = step;
            }
        }
        // 【1.9】声音音量：1~1000，越界自动夹取
        if (mc.level != null && volumeInput != null) {
            Integer volume = parseVolume(volumeInput.getValue());
            if (volume != null && volume != openVolume) {
                ClientPlayNetworking.send(new SetVolumePayload(pos, volume));
                openVolume = volume;
            }
        }
    }

    private void sendApply(boolean setRun, double run, boolean setStep, double step) {
        ClientPlayNetworking.send(new ApplyChainPayload(pos, setRun, run, setStep, step));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.literal("扶梯速度（格/秒）"),
                this.width / 2, 32, 0xA0A0A0);
        guiGraphics.drawCenteredString(this.font, Component.literal("阶梯速度（格/秒）"),
                this.width / 2, 68, 0xA0A0A0);
        guiGraphics.drawCenteredString(this.font, Component.literal("声音音量（1-1000，100 = 原始音量，可放大）"),
                this.width / 2, 126, 0xA0A0A0);

        guiGraphics.drawCenteredString(this.font,
                Component.literal("改扶梯速度会同步阶梯速度；改阶梯速度不影响扶梯速度"),
                this.width / 2, 186, 0x808080);

        guiGraphics.drawCenteredString(this.font,
                Component.literal("音量随距离衰减：离开整条扶梯 16 格内才听得见"),
                this.width / 2, 198, 0x808080);

        guiGraphics.drawCenteredString(this.font,
                Component.literal("按 ESC 保存并退出　·　扶梯位置 " + pos.toShortString()),
                this.width / 2, this.height - 22, 0x707070);
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

    /** 解析音量输入框（1~1000，越界夹取）；空/非法返回 null（视为未改动）。 */
    private static Integer parseVolume(String text) {
        try {
            return EscalatorSpeedData.clampVolume(Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean nearly(double a, double b) {
        return Math.abs(a - b) < 1.0E-6;
    }
}
