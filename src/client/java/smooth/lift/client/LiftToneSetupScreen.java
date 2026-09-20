package smooth.lift.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.network.ImportFolderLiftTonePayload;
import smooth.lift.network.RequestSyncPayload;
import smooth.lift.network.SetLiftChimeVolumePayload;
import smooth.lift.network.SetLiftTonePayload;
import smooth.lift.network.SetLiftToneSwitchPayload;
import smooth.lift.network.SetLiftToneVolumePayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 【1.45】石斧右键直梯楼层轨道打开的「直梯无障碍提示音」选择界面。
 *
 * <h2>布局（【1.48】改版后固定区三行，从下往上互不重叠）</h2>
 * <pre>
 *   …列表可视区（到这里为止）        listBottom = height - 108
 *   状态文字 / 说明                   height - 96 起（render 画）
 *   音量输入框行（按钮+输入框+应用）  height - 56
 *   返回 / 刷新                        height - 30
 * </pre>
 * 只要「输入框行」与「返回/刷新」分别钉在各自的行，就不会像 1.48 初版那样重叠
 * （当时输入框在 listBottom+4 ≈ height-80，返回/刷新在 height-78，只差 2px）。
 *
 *
 * <h2>结构（【1.48】改版）</h2>
 * <ul>
 *   <li><b>主界面（{@code page == 0}）</b>：三个按钮（上楼提示音 / 下楼提示音 / 开关门提示音，
 *       点任一进入对应列表）+ 一个「共用默认音量」输入框（{@code /lifthelploud <音量>}）；</li>
 *   <li><b>单项列表（{@code page == 1/2/3}，对应 up / down / chime）</b>：该提示音的素材选择
 *       （默认素材 / 不播 / 待导入 / 已入库）+ 开关（{@code /lifthelpup|down|chime}）+ 该项的音量输入框
 *       （{@code /lifthelploud up|down|door}）。返回按钮回主界面。</li>
 * </ul>
 *
 * <h2>「这一条」怎么定位</h2>
 * 直梯没有跨重启稳定的 ID，所以右键到的那个楼层轨道方格的**竖井列 (X, Z)** 就是身份：
 * 同一条直梯的所有楼层轨道共享 X/Z、只有 Y 不同（{@link EscalatorSpeedManager#liftToneKey(int, int)}）。
 *
 * <p>音频库与扶梯共用（{@code smoothlift_audio} 同一个文件夹、同一份导入库）。
 */
public class LiftToneSetupScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    private static final int ROW_H = 22;
    private static final int LIST_TOP = 40;
    /** 列表区底部距窗口底部的预留：给「状态文字 + 音量输入框行 + 返回/刷新」三行让位。 */
    private static final int BOTTOM_RESERVE = 108;
    /** 【1.48】底部三行的固定 y（从下往上：返回/刷新 → 音量输入框行 → 状态文字）。 */
    private static final int BTN_Y = -30;      // this.height + BTN_Y = 返回/刷新这一行
    private static final int INPUT_Y = -56;    // this.height + INPUT_Y = 音量输入框这一行
    private static final int STATUS_Y = -96;   // this.height + STATUS_Y = 状态文字这一行
    private static final int BTN_W = 200;

    /** 【1.46】「开关行」的值哨兵（与真正的 audioId 区分开：它不代表任何素材）。 */
    private static final String TOGGLE_SENTINEL = "\u0000TOGGLE";

    private static final int T_HEADER = 0;
    private static final int T_NOTE = 1;
    private static final int T_PICK = 2;   // 点=设为该项音频（默认 / 不播 / 某段音频）

    private static final class Row {
        final int type;
        final String which;   // up / down / chime；标题行为 null
        final String value;   // 选中的 audioId（default / off / 文件名）；标题行为 null
        final String text;
        final boolean fromFolder; // true = 待导入（还没进音频库，点它要先导入）

        Row(int type, String which, String value, String text) {
            this(type, which, value, text, false);
        }

        Row(int type, String which, String value, String text, boolean fromFolder) {
            this.type = type;
            this.which = which;
            this.value = value;
            this.text = text;
            this.fromFolder = fromFolder;
        }
    }

    private static final String[] PAGES = {"up", "down", "chime"};

    private final BlockPos pos;
    private final long key;
    private final List<String> stored = new ArrayList<>();
    private final List<String> pending = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private int scroll;
    private int maxScroll;
    private int listBottom;
    private String statusText;

    /** 当前页：0 = 主界面（三按钮 + 共用音量输入框）；1/2/3 = up/down/chime 单项列表。 */
    private int page;

    /** 主界面的「共用默认音量」输入框。 */
    private EditBox defaultVolumeInput;
    /** 单项列表里的「这一项的音量」输入框。 */
    private EditBox toneVolumeInput;

    private static volatile LiftToneSetupScreen OPEN;

    public LiftToneSetupScreen(BlockPos pos) {
        super(Component.literal("选择直梯无障碍提示音"));
        this.pos = pos;
        this.key = EscalatorSpeedManager.liftToneKey(pos.getX(), pos.getZ());
    }

    /** 服务端同步回来时刷新列表（界面还开着的情况下）。 */
    public static void notifyToneDataChanged() {
        LiftToneSetupScreen s = OPEN;
        if (s == null) {
            return;
        }
        Minecraft.getInstance().execute(() -> {
            if (OPEN != s) {
                return;
            }
            s.statusText = null;
            s.init();
        });
    }

    @Override
    protected void init() {
        OPEN = this;
        Minecraft mc = Minecraft.getInstance();
        stored.clear();
        pending.clear();
        if (mc.level != null) {
            stored.addAll(EscalatorSpeedManager.getClientAudioLibraryKeys(mc.level));
            pending.addAll(EscalatorSpeedManager.getClientFolderAudioKeys(mc.level));
        }
        Collections.sort(stored);
        Collections.sort(pending);
        buildUi();
    }

    @Override
    public void onClose() {
        if (OPEN == this) {
            OPEN = null;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void buildUi() {
        clearWidgets();
        listBottom = Math.max(LIST_TOP + ROW_H, this.height - BOTTOM_RESERVE);
        rows.clear();

        if (page == 0) {
            buildMainPage();
        } else {
            String which = PAGES[page - 1];
            buildTonePage(which);
        }
    }

    // ------------------------------------------------------------------
    // 主界面：3 按钮 + 共用默认音量输入框
    // ------------------------------------------------------------------
    private void buildMainPage() {
        int cx = this.width / 2;
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(cx - 100, this.height + BTN_Y, 96, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> {
            ClientPlayNetworking.send(new RequestSyncPayload());
            setStatus("已请求刷新，同步回来后自动更新");
        }).bounds(cx + 4, this.height + BTN_Y, 96, 20).build());

        // 三个按钮：进各自列表
        int y = LIST_TOP + 10;
        for (int i = 0; i < PAGES.length; i++) {
            String which = PAGES[i];
            final int targetPage = i + 1;
            addRenderableWidget(Button.builder(
                            Component.literal(liftToneTitle(which) + "设置…"), button -> {
                        page = targetPage;
                        scroll = 0;
                        init();
                    })
                    .bounds(cx - BTN_W / 2, y, BTN_W, 20)
                    .build());
            y += ROW_H;
        }

        // 共用默认音量输入框（/lifthelploud <音量>，100 = 原始音量）—— 固定在底部中间行
        int inputY = this.height + INPUT_Y;
        addRenderableWidget(Button.builder(Component.literal("设置默认音量"), button -> applyDefaultVolume())
                .bounds(cx - BTN_W / 2 - 60, inputY, 60, 20)
                .build());
        defaultVolumeInput = new EditBox(this.font, cx + 10, inputY, BTN_W / 2 + 40, 20,
                Component.literal("默认音量"));
        defaultVolumeInput.setMaxLength(8);
        defaultVolumeInput.setValue(String.valueOf(EscalatorSpeedManager.getLiftHelpVolume(mcLevel())));
        addRenderableWidget(defaultVolumeInput);
        addRenderableWidget(Button.builder(Component.literal("应用"), button -> applyDefaultVolume())
                .bounds(cx - BTN_W / 2 - 60 + 70, inputY, 60, 20)
                .build());
    }

    /** 【1.48】主界面「设置默认音量」按钮。 */
    private void applyDefaultVolume() {
        Integer v = parseVolume(defaultVolumeInput.getValue());
        if (v == null) {
            setStatus("音量必须是 1~1000 的整数（100 = 原始音量）");
            return;
        }
        ClientPlayNetworking.send(new SetLiftChimeVolumePayload(v));
        setStatus("已请求把共用默认音量设为 " + v);
        // 本地镜像直接改，服务端会再同步权威值回来
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientLiftVolumeLocal(level.dimension(), v);
        }
    }

    // ------------------------------------------------------------------
    // 单项列表：素材 + 开关 + 该项音量输入框
    // ------------------------------------------------------------------
    private void buildTonePage(String which) {
        int cx = this.width / 2;
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> {
            page = 0;
            scroll = 0;
            init();
        }).bounds(cx - 100, this.height + BTN_Y, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> {
            ClientPlayNetworking.send(new RequestSyncPayload());
            setStatus("已请求刷新，同步回来后列表会自动更新");
        }).bounds(cx + 4, this.height + BTN_Y, 96, 20).build());

        rebuildToneRows(which);

        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = rowY(i);
            if (!fullyVisible(y)) {
                continue;
            }
            if (row.type == T_PICK) {
                addRenderableWidget(Button.builder(Component.literal(truncate(row.text, 26)), button -> pick(row.which, row.value, row.fromFolder))
                        .bounds(cx - BTN_W / 2, y, BTN_W, 20)
                        .build());
            }
        }

        // 这项的音量输入框（/lifthelploud up|down|door <音量>）—— 固定在底部中间行
        int inputY = this.height + INPUT_Y;
        addRenderableWidget(Button.builder(Component.literal("音量"), button -> applyToneVolume(which))
                .bounds(cx - BTN_W / 2 - 60, inputY, 60, 20)
                .build());
        toneVolumeInput = new EditBox(this.font, cx + 10, inputY, BTN_W / 2 + 40, 20,
                Component.literal("音量"));
        toneVolumeInput.setMaxLength(8);
        toneVolumeInput.setValue(String.valueOf(EscalatorSpeedManager.getLiftToneVolume(mcLevel(), which)));
        addRenderableWidget(toneVolumeInput);
        addRenderableWidget(Button.builder(Component.literal("应用"), button -> applyToneVolume(which))
                .bounds(cx - BTN_W / 2 - 60 + 70, inputY, 60, 20)
                .build());
    }

    /** 【1.48】单项列表「音量」按钮：/lifthelploud up|down|door <音量>。 */
    private void applyToneVolume(String which) {
        Integer v = parseVolume(toneVolumeInput.getValue());
        if (v == null) {
            setStatus("音量必须是 1~1000 的整数（100 = 原始音量）");
            return;
        }
        ClientPlayNetworking.send(new SetLiftToneVolumePayload(which, v));
        setStatus("已请求把「" + liftToneTitle(which) + "」音量设为 " + v);
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientLiftToneVolumeLocal(level.dimension(), which, v);
        }
    }

    private void rebuildToneRows(String which) {
        rows.clear();
        rows.add(new Row(T_HEADER, null, null, liftToneTitle(which)));
        // 【1.46】第一行 = 维度默认子开关（/lifthelpup|down|chime 的 UI 版）
        rows.add(new Row(T_PICK, which, TOGGLE_SENTINEL,
                "开关：" + (isToneEnabled(which) ? "开 ✓" : "关")));
        addPicks(which);
        rows.add(new Row(T_HEADER, null, null, "存档文件夹 smoothlift_audio 待导入（点=导入并设为" + liftToneTitle(which) + "）"));
        if (pending.isEmpty()) {
            rows.add(new Row(T_NOTE, null, null, "（暂无）"));
        } else {
            for (String id : pending) {
                rows.add(new Row(T_PICK, which, id, id, true));
            }
        }
        rows.add(new Row(T_HEADER, null, null, "已存入存档的音频（点=设为" + liftToneTitle(which) + "）"));
        if (stored.isEmpty()) {
            rows.add(new Row(T_NOTE, null, null, "（暂无）"));
        } else {
            for (String id : stored) {
                rows.add(new Row(T_PICK, which, id, id));
            }
        }
        int total = rows.size() * ROW_H;
        int visible = Math.max(ROW_H, listBottom - LIST_TOP);
        maxScroll = Math.max(0, total - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /** 一个列表的前两行：默认素材 / 不播。 */
    private void addPicks(String which) {
        boolean isDefault = isCurrently(which, EscalatorSpeedData.LIFT_TONE_DEFAULT);
        boolean isOff = isCurrently(which, EscalatorSpeedData.LIFT_TONE_OFF);
        rows.add(new Row(T_PICK, which, EscalatorSpeedData.LIFT_TONE_DEFAULT,
                "默认素材" + (isDefault ? "  ✓当前" : "")));
        rows.add(new Row(T_PICK, which, EscalatorSpeedData.LIFT_TONE_OFF,
                "不播" + (isOff ? "  ✓当前" : "")));
    }

    // ------------------------------------------------------------------
    // 公共
    // ------------------------------------------------------------------

    /** 当前竖井列、这一项实际生效的 audioId（镜像里没有 → 默认）。 */
    private String currentValue(Level level, String which) {
        EscalatorSpeedData.LiftToneAudio tone =
                level == null ? EscalatorSpeedData.LiftToneAudio.NONE
                        : EscalatorSpeedManager.getClientLiftTone(level, key);
        return switch (which) {
            case "up" -> tone.up();
            case "down" -> tone.down();
            case "chime" -> tone.chime();
            default -> EscalatorSpeedData.LIFT_TONE_DEFAULT;
        };
    }

    /** 当前这一项是不是 {code value}。 */
    private boolean isCurrently(String which, String value) {
        Minecraft mc = Minecraft.getInstance();
        return value.equals(currentValue(mc.level, which));
    }

    /** 点列表里某一行：开关行 → 切换维度默认子开关；待导入行 → 先导入再设为这一项；否则设为 {code audioId}。 */
    private void pick(String which, String audioId, boolean fromFolder) {
        if (TOGGLE_SENTINEL.equals(audioId)) {
            toggleToneEnabled(which);
            return;
        }
        if (fromFolder) {
            ClientPlayNetworking.send(new ImportFolderLiftTonePayload(key, which, audioId));
            setStatus("正在从文件夹导入并设为「" + liftToneTitle(which) + "」：" + truncate(audioId, 16));
            return;
        }
        ClientPlayNetworking.send(new SetLiftTonePayload(key, which, audioId));
        String label = audioLabel(audioId);
        setStatus("已选择「" + liftToneTitle(which) + "」：" + truncate(label, 20));
        refreshAllAfterPick(which, audioId);
    }

    /** 【1.46】当前维度这项子开关是否开着（镜像里没有 → 默认开）。 */
    private boolean isToneEnabled(String which) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null || EscalatorSpeedManager.isLiftToneEnabled(mc.level, which);
    }

    /** 【1.46】切换维度默认子开关（发到服务端，镜像等同步回来刷新）。 */
    private void toggleToneEnabled(String which) {
        boolean next = !isToneEnabled(which);
        ClientPlayNetworking.send(new SetLiftToneSwitchPayload(which, next));
        setStatus("已请求把「" + liftToneTitle(which) + "」" + (next ? "开启" : "关闭") + "（同步回来后生效）");
        Level level = mcLevel();
        if (level != null) {
            EscalatorSpeedManager.applyClientLiftToneSwitchLocal(level.dimension(), which, next);
        }
        init();
    }

    private Level mcLevel() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level;
    }

    /** 点完行后本地马上反映（服务端同步回来会再校准一次）。 */
    private void refreshAllAfterPick(String which, String audioId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        EscalatorSpeedData.LiftToneAudio cur =
                EscalatorSpeedManager.getClientLiftTone(mc.level, key);
        String up = "up".equals(which) ? audioId : cur.up();
        String down = "down".equals(which) ? audioId : cur.down();
        String chime = "chime".equals(which) ? audioId : cur.chime();
        EscalatorSpeedManager.applyClientLiftToneLocal(mc.level.dimension(), key,
                new EscalatorSpeedData.LiftToneAudio(up, down, chime));
        init();
    }

    private void setStatus(String text) {
        Minecraft.getInstance().execute(() -> {
            this.statusText = text;
        });
    }

    private int rowY(int index) {
        return LIST_TOP + index * ROW_H - scroll;
    }

    private boolean fullyVisible(int y) {
        return y >= LIST_TOP && y + 20 <= listBottom;
    }

    /** 解析输入框里的音量（1~1000 整数）；非法 → null。 */
    private Integer parseVolume(String s) {
        if (s == null) {
            return null;
        }
        try {
            int v = Integer.parseInt(s.trim());
            return EscalatorSpeedData.clampLiftHelpVolume(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (page > 0 && maxScroll > 0 && scrollY != 0.0) {
            scroll -= (int) Math.round(scrollY * ROW_H);
            scroll = Math.max(0, Math.min(scroll, maxScroll));
            buildUi();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        if (page == 0) {
            renderMainPage(guiGraphics, mouseX, mouseY, partialTick);
        } else {
            renderTonePage(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    private void renderMainPage(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int cx = this.width / 2;

        // 状态文字（最上方一行，height + STATUS_Y）
        int statusY = this.height + STATUS_Y;
        String info = statusText;
        if (info == null) {
            Minecraft mc = Minecraft.getInstance();
            int def = EscalatorSpeedManager.getLiftHelpVolume(mc.level);
            String curUp = truncate(audioLabel(currentValue(mc.level, "up")), 12);
            String curDown = truncate(audioLabel(currentValue(mc.level, "down")), 12);
            String curChime = truncate(audioLabel(currentValue(mc.level, "chime")), 12);
            info = "默认音量 " + def + " ｜ 上楼 " + curUp + " ｜ 下楼 " + curDown + " ｜ 开关门 " + curChime;
        }
        guiGraphics.drawCenteredString(this.font, Component.literal(info), cx, statusY,
                statusText == null ? 0x808080 : 0xFFFF55);
        guiGraphics.drawCenteredString(this.font,
                Component.literal("音频与扶梯共用 smoothlift_audio 文件夹 / 同一份导入库"),
                cx, statusY + 12, 0x808080);
    }

    private void renderTonePage(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        String which = PAGES[page - 1];
        guiGraphics.drawCenteredString(this.font, Component.literal(liftToneTitle(which) + "设置"),
                this.width / 2, 22, 0xFFFFFF);

        guiGraphics.enableScissor(0, LIST_TOP, this.width, listBottom);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.type != T_HEADER && row.type != T_NOTE) {
                continue;
            }
            int y = rowY(i);
            if (y + ROW_H < LIST_TOP || y > listBottom) {
                continue;
            }
            int color = row.type == T_HEADER ? 0xFFE0E0E0 : 0xFF909090;
            guiGraphics.drawCenteredString(this.font, Component.literal(row.text), this.width / 2, y + 6, color);
        }
        guiGraphics.disableScissor();

        if (maxScroll > 0) {
            int barX = this.width / 2 + BTN_W / 2 + 10;
            int trackTop = LIST_TOP;
            int trackH = Math.max(ROW_H, listBottom - LIST_TOP);
            guiGraphics.fill(barX, trackTop, barX + 4, trackTop + trackH, 0x40000000);
            int thumbH = Math.max(14, trackH * trackH / (rows.size() * ROW_H));
            int thumbY = trackTop + (trackH - thumbH) * scroll / maxScroll;
            guiGraphics.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xFFAAAAAA);
        }

        int statusY = this.height + STATUS_Y;
        String info = statusText;
        if (info == null) {
            Minecraft mc = Minecraft.getInstance();
            int vol = EscalatorSpeedManager.getLiftToneVolume(mc.level, which);
            String cur = truncate(audioLabel(currentValue(mc.level, which)), 18);
            info = liftToneTitle(which) + "　" + cur + "　音量 " + vol;
        }
        guiGraphics.drawCenteredString(this.font, Component.literal(info), this.width / 2, statusY,
                statusText == null ? 0x808080 : 0xFFFF55);
        guiGraphics.drawCenteredString(this.font,
                Component.literal("音量 = 1~1000（100 = 原始音量）；该项没单独调过则跟随共用默认"),
                this.width / 2, statusY + 12, 0x808080);
    }

    private static String liftToneTitle(String which) {
        return switch (which) {
            case "up" -> "上楼提示音";
            case "down" -> "下楼提示音";
            default -> "开关门提示音";
        };
    }

    private static String audioLabel(String id) {
        if (EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(id)) {
            return "默认素材";
        }
        if (EscalatorSpeedData.LIFT_TONE_OFF.equals(id)) {
            return "不播";
        }
        return id;
    }

    private static String truncate(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }
}