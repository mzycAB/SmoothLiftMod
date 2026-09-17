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
 * <p>速度部分：
 * <ul>
 *   <li>「扶梯速度」——这条扶梯的运行速度；</li>
 *   <li>「阶梯速度」——这条扶梯的阶梯动画速度；</li>
 *   <li>「阶梯速度对齐扶梯速度」——把阶梯速度框填成扶梯速度框的值；</li>
 *   <li>「声音设置…」——进入自定义声音子界面（给这条扶梯的运行底噪绑定/解绑 OGG 音频）；</li>
 *   <li>【1.39】「提示音设置…」——进入无障碍提示音子界面（选这条扶梯两端放什么提示音：
 *       模组原声 / 不播 / 导入的 OGG，与底噪**共用同一个导入文件夹**）；</li>
 *   <li>「无障碍：开/关」——这条扶梯的无障碍提示音总开关。</li>
 * </ul>
 *
 * <p>【1.9】声音部分：
 * <ul>
 *   <li>「声音音量」——这条扶梯**运行底噪**的音量，输入 <b>1~1000</b>（100 = 原始音量，1000 = 10× 放大），
 *       实际音量 = 距离衰减 × 这个百分比；</li>
 *   <li>界面里会提示「离开**整条扶梯** 16 格才静音」——底噪的距离是按整条扶梯算的，不是按某个方块算。</li>
 * </ul>
 *
 * <p>【1.16】无障碍部分：
 * <ul>
 *   <li>「无障碍：开/关」——这条扶梯是否播放香港式「视障人士提升音」（进扶梯一端急促咔咔、
 *       出扶梯一端缓慢咔咔）。点一下切换，**按 ESC 时**才发出去；</li>
 *   <li>【1.18】「提示音音量」——上面那路提示音的音量（1~1000，100 = 原始音量），
 *       与「声音音量」（底噪）是**两个独立的输入框**、两套独立数据；
 *       提示音的射程只有 **4 格**（装在一端那一块扶梯方块上），所以是「进出口处才听得到」；</li>
 *   <li>开关与音量的根都是维度默认值（{@code /futihelp on|off}、{@code /futihelploud <音量>}），
 *       这里改的是**这一条扶梯**的单独设置。</li>
 * </ul>
 *
 * <p>没有「确定」按钮：**按 ESC 退出界面时统一应用**（若都没改动则什么都不发）。
 * 点「声音设置…」进入子界面**之前**也会先把改动发出去，避免「刚填好音量就点了音乐选择」导致丢失。
 */
public class EscalatorSpeedScreen extends Screen {
    private final BlockPos pos;

    private EditBox runInput;
    private EditBox stepInput;
    private EditBox volumeInput;
    /** 【1.18】无障碍**提示音**音量输入框（与 {@link #volumeInput}（底噪音量）并排，互不影响）。 */
    private EditBox helpVolumeInput;
    /** 【1.16】无障碍提示音开关按钮（标签随开关状态变化）。 */
    private Button helpButton;

    /** 打开界面时四个框/开关里显示的基准值，用来判断玩家到底改了哪一项。 */
    private double openRun;
    private double openStep;
    private int openVolume;
    /** 【1.18】打开界面时提示音音量框里的基准值，用来判断玩家有没有改。 */
    private int openHelpVolume;
    private boolean openHelp;

    /** 【1.16】无障碍提示音的当前（待应用）值；按 ESC 时与 {@link #openHelp} 不同才发包。 */
    private boolean helpEnabled;

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
        openHelpVolume = currentHelpVolume(mc);
        openHelp = currentHelp(mc);
        helpEnabled = openHelp;

        runInput = new EditBox(this.font, this.width / 2 - 100, 36, 200, 20, Component.literal("扶梯速度"));
        runInput.setMaxLength(32);
        runInput.setValue(EscalatorSpeedData.format(openRun));
        runInput.setResponder(this::onRunEdited);
        addRenderableWidget(runInput);

        stepInput = new EditBox(this.font, this.width / 2 - 100, 70, 200, 20, Component.literal("阶梯速度"));
        stepInput.setMaxLength(32);
        stepInput.setValue(EscalatorSpeedData.format(openStep));
        stepInput.setResponder(this::onStepEdited);
        addRenderableWidget(stepInput);

        addRenderableWidget(Button.builder(Component.literal("阶梯速度对齐扶梯速度"), button -> alignStepToRun())
                .bounds(this.width / 2 - 100, 92, 200, 20)
                .build());

        // 【1.9】声音音量：1~1000（【1.12】100 = 原始音量，可放大到 1000），只允许输入数字。
        // 【1.18】右侧再并排放一个「提示音音量」（无障碍提示音，独立数据），
        //         两个框共用一行、各自 96 宽，省下的纵向空间给底部三行提示。
        volumeInput = new EditBox(this.font, this.width / 2 - 100, 126, 96, 20, Component.literal("声音音量"));
        volumeInput.setMaxLength(4);
        volumeInput.setValue(String.valueOf(openVolume));
        volumeInput.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        addRenderableWidget(volumeInput);

        helpVolumeInput = new EditBox(this.font, this.width / 2 + 4, 126, 96, 20, Component.literal("提示音音量"));
        helpVolumeInput.setMaxLength(4);
        helpVolumeInput.setValue(String.valueOf(openHelpVolume));
        helpVolumeInput.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        addRenderableWidget(helpVolumeInput);

        // 【1.7】自定义声音：运行底噪的音乐选择子界面入口
        // 【1.39】同一行再并排一个「提示音设置…」—— 无障碍提示音现在也能导入自定义 OGG。
        //   两者共用同一个导入文件夹与同一份音频库（导入一次两边都能选），只是
        //   「哪段声音用在哪儿」是两套独立数据。三个按钮各 64 宽（共 200，与上面的输入框同宽）、间距 4。
        addRenderableWidget(Button.builder(Component.literal("声音设置…"), button -> openAudioSetup())
                .bounds(this.width / 2 - 100, 150, 64, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("提示音设置…"), button -> openHelpAudioSetup())
                .bounds(this.width / 2 - 32, 150, 64, 20)
                .build());

        // 【1.16】无障碍提示音开关：点一下切换，按 ESC 退出时与其他改动一起发出去
        helpButton = Button.builder(helpLabel(), button -> toggleHelp())
                .bounds(this.width / 2 + 36, 150, 64, 20)
                .build();
        addRenderableWidget(helpButton);

        setInitialFocus(runInput);
    }

    /** 【1.16】开关按钮的标签：直接显示当前状态，点一下就切到另一边。 */
    private Component helpLabel() {
        return Component.literal("无障碍：" + (helpEnabled ? "开" : "关"));
    }

    /** 【1.16】切换无障碍提示音开关（只改本地待应用值，按 ESC 时统一发包）。 */
    private void toggleHelp() {
        helpEnabled = !helpEnabled;
        if (helpButton != null) {
            helpButton.setMessage(helpLabel());
        }
    }

    /**
     * 打开自定义声音子界面（本界面被替换掉，返回时由声音界面重建）。
     * 先把改动发出去：否则「填好音量 → 点声音设置 → 返回」会看到音量被重置回旧值。
     */
    private void openAudioSetup() {
        applyChanges();
        Minecraft.getInstance().setScreen(new AudioSetupScreen(pos));
    }

    /**
     * 【1.39】打开「选择无障碍提示音」子界面（本界面被替换掉，返回时由提示音界面重建）。
     * 同样先把改动发出去，理由与 {@link #openAudioSetup()} 一样。
     */
    private void openHelpAudioSetup() {
        applyChanges();
        Minecraft.getInstance().setScreen(new HelpAudioSetupScreen(pos));
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

    /**
     * 【1.16】这条扶梯当前的无障碍提示音开关。
     * 用 {@code isHelpEnabled}：会顺着扶梯链找同一条扶梯上已设过的开关，
     * 所以在这条扶梯的任意一个方块上打开界面，看到的都是同一个状态。
     */
    private boolean currentHelp(Minecraft mc) {
        if (mc.level == null) {
            return true;
        }
        return EscalatorSpeedManager.isHelpEnabled(mc.level, pos);
    }

    /**
     * 【1.18】这条扶梯当前的**提示音**音量（1~1000，100 = 原始音量）。
     * 用 {@code getHelpVolume}：会顺着扶梯链找同一条扶梯上已设过的音量，
     * 所以在这条扶梯的任意一个方块上打开界面，看到的都是同一个值。
     * （注意与 {@link #currentVolume}（底噪音量）是两套独立数据。）
     */
    private int currentHelpVolume(Minecraft mc) {
        if (mc.level == null) {
            return EscalatorSpeedData.DEFAULT_HELP_VOLUME;
        }
        return EscalatorSpeedManager.getHelpVolume(mc.level, pos);
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

    /** 把改动过的值发出去（速度 / 音量 / 无障碍开关各自独立，没改的不发）。 */
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
                FriendlyByteBuf buf = PacketByteBufs.create();
                buf.writeBlockPos(pos);
                buf.writeVarInt(volume);
                ClientPlayNetworking.send(SmoothLift.SET_VOLUME_CHANNEL, buf);
                openVolume = volume;
            }
        }
        // 【1.16】无障碍提示音开关：和打开界面时的状态不同才发
        if (helpEnabled != openHelp) {
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeBlockPos(pos);
            buf.writeBoolean(helpEnabled);
            ClientPlayNetworking.send(SmoothLift.SET_HELP_CHANNEL, buf);
            openHelp = helpEnabled;
        }
        // 【1.18】无障碍提示音音量（独立于上面的底噪音量）
        if (mc.level != null && helpVolumeInput != null) {
            Integer volume = parseVolume(helpVolumeInput.getValue());
            if (volume != null && volume != openHelpVolume) {
                FriendlyByteBuf buf = PacketByteBufs.create();
                buf.writeBlockPos(pos);
                buf.writeVarInt(volume);
                ClientPlayNetworking.send(SmoothLift.SET_HELP_VOLUME_CHANNEL, buf);
                openHelpVolume = volume;
            }
        }
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
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.literal("扶梯速度（格/秒）"),
                this.width / 2, 24, 0xA0A0A0);
        guiGraphics.drawCenteredString(this.font, Component.literal("阶梯速度（格/秒）"),
                this.width / 2, 58, 0xA0A0A0);
        // 【1.18】两个音量框并排：左边底噪音量、右边提示音音量（各 96 宽，标签各自居中在半边）
        guiGraphics.drawCenteredString(this.font, Component.literal("声音音量（100=原始）"),
                this.width / 2 - 52, 114, 0xA0A0A0);
        guiGraphics.drawCenteredString(this.font, Component.literal("提示音音量（100=原始）"),
                this.width / 2 + 52, 114, 0xA0A0A0);

        guiGraphics.drawCenteredString(this.font,
                Component.literal("改扶梯速度会同步阶梯速度；改阶梯速度不影响扶梯速度"),
                this.width / 2, 176, 0x808080);

        guiGraphics.drawCenteredString(this.font,
                Component.literal("声音=整条扶梯底噪，16 格内听得见；提示音=只在首尾两块，4 格内才听得见"),
                this.width / 2, 188, 0x808080);

        guiGraphics.drawCenteredString(this.font,
                Component.literal("无障碍提示音：进扶梯一端急促咔咔，出扶梯一端缓慢咔咔（视障人士用）"),
                this.width / 2, 200, 0x808080);

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
