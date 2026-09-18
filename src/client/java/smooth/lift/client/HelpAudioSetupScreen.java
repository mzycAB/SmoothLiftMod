package smooth.lift.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.SmoothLift;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 【1.39】石斧界面里的「选择无障碍提示音」子界面 —— 与 {@link AudioSetupScreen}（选运行底噪）
 * **同一套排版与交互**，只有语义不同：这里选的是**扶梯两端那路无障碍提示音**放什么声音。
 *
 * <p>列表从上到下四段：
 * <ol>
 *   <li><b>默认提示音（模组原声）</b>：= 模组原来的「咔啪」提示音（进扶梯端 10 次/秒、出扶梯端 1 次/秒，
 *       速率可用 {@code /futihelpspeed} 改）。<b>永远排在最顶端</b>（与运行底噪界面把「默认音乐」放最顶一致）。</li>
 *   <li><b>不播提示音</b>：这条扶梯**当前这一头**单独哑掉（比 {@code /futihelp off} 更细），其它不受影响。</li>
 *   <li>存档文件夹 {@code smoothlift_audio} 里的 OGG（**与运行底噪共用同一个文件夹**）：
 *       点 = 导入存档并设为这条扶梯的提示音（之后删原文件仍可播）。</li>
 *   <li>已存入存档的音频：点名字 = 设为提示音；删除 = 从存档移除。</li>
 * </ol>
 *
 * <p>★★【1.41】界面里的每一次「选择 / 清除」都只作用在**当前正在设置的那一头**上
 * （底部按钮切换「进入扶梯（上客端）」⇄「离开扶梯（落客端）」），与指令
 * {@code /futihelpmusic in|out} 是同一套数据 —— 于是「进站一段、出站另一段」在界面上也能配。
 * 进 / 出两头的设置**互不影响**：切换端头只是换一个视图，不会动另一头的数据。
 * <p>★ 想「改回跟随默认」直接点列表顶端的「默认提示音」行即可（那一行就是默认层）。
 *
 * <p>★ 与运行底噪界面共享同一份音频库，所以「删除」会**同时**影响底噪那边的绑定
 * （底部有一行提示写明了这一点）。
 *
 * <p>★ 速率（{@code /futihelpspeed}）只对「默认提示音」生效：自定义音频按原速循环播
 * （素材是玩家自己的，没法按 Hz 分档）；音量与范围对所有选择都生效。
 *
 * <p>行数可能超过一屏，支持鼠标滚轮滚动；列表右侧有滚动条。
 * 界面在按钮点击后保持打开，只在按 ESC 或「返回」时回到设置界面。
 */
public class HelpAudioSetupScreen extends Screen {

    private static final int ROW_H = 22;          // 每行固定高度（含行间距）
    private static final int LIST_TOP = 50;       // 列表可视区顶部
    private static final int BOTTOM_RESERVE = 96;  // 底部固定区（端头切换 + 状态 + 提示）占用的高度
    private static final int BTN_W = 200;         // 单列按钮宽度

    // 行类型
    private static final int T_HEADER = 0;   // 分段标题（不可点）
    private static final int T_NOTE = 1;     // 灰色说明（不可点）
    private static final int T_DEFAULT = 2;  // 默认提示音（模组原声）：点=设为默认
    private static final int T_OFF = 3;      // 不播提示音：点=设为不播
    private static final int T_STORED = 4;   // 存档音频：点=绑定 / 删除
    private static final int T_PENDING = 5;  // 待导入：点=导入并绑定

    /** 列表里的一行。 */
    private static final class Row {
        final int type;
        final String id;    // 可点行的绑定 ID；不可点行为 null
        final String text;  // 显示文字

        Row(int type, String id, String text) {
            this.type = type;
            this.id = id;
            this.text = text;
        }
    }

    private final BlockPos pos;

    /** 已存入存档的音频 ID（文件名）——与运行底噪共用同一个库。 */
    private final List<String> stored = new ArrayList<>();
    /** 存档文件夹里尚未入库、待导入的 OGG 文件名。 */
    private final List<String> pending = new ArrayList<>();
    /** 四段拼成的扁平行列表（每次刷新重建）。 */
    private final List<Row> rows = new ArrayList<>();

    /**
     * 【1.41】当前**正在设置哪一头**：true = 进入扶梯（上客端），false = 离开扶梯（落客端）。
     * 列表里点的每一次选择 / 清除都只作用在这一头上；右下角按钮切换它。
     */
    private boolean editIn = true;

    /** 这条扶梯**当前这一头实际生效**的提示音 ID（单独设置 &gt; 维度默认；永远不会是 null）。 */
    private String effectiveAudioId = EscalatorSpeedData.HELP_AUDIO_DEFAULT;
    /** 上面那个 ID 是不是「本扶梯这一头单独设置」的（否则来自默认层）。只影响文案。 */
    private boolean individualAudio;
    /** 这条扶梯的提示音音量（1~1000；100 = 原始音量），仅用于回显。 */
    private int boundVolume = EscalatorSpeedData.DEFAULT_HELP_VOLUME;
    /** 最近一次操作的反馈文字；非空时在底部用黄色显示。 */
    private String statusText;

    /** 列表滚动像素偏移 / 最大可滚动量 / 列表可视区底部。 */
    private int scroll;
    private int maxScroll;
    private int listBottom;

    /** 当前打开的实例；服务端数据同步回来时由 {@link #notifyDataChanged()} 回调刷新。 */
    private static volatile HelpAudioSetupScreen OPEN;

    public HelpAudioSetupScreen(BlockPos pos) {
        super(Component.literal("选择无障碍提示音"));
        this.pos = pos;
    }

    /**
     * 服务端提示音数据同步完成（设置/导入/删除/刷新应答）后调用：刷新列表，
     * 并清除「正在导入…」这类占位反馈，让界面回退显示真实的设置状态。
     */
    public static void notifyDataChanged() {
        HelpAudioSetupScreen s = OPEN;
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
        // 【1.41】回显「**当前这一头**实际会播什么」，而不是「本方块自己设了什么」——
        // 单独设置优先，其次默认层。
        refreshEndState();

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

    /**
     * 【1.41】重新回显「**当前正在设置的那一头**实际会播什么」：
     * 单独设置优先，其次维度默认层。切换端头 / 收到同步 / 点完按钮后都会走这里。
     */
    private void refreshEndState() {
        Minecraft mc = Minecraft.getInstance();
        effectiveAudioId = mc.level == null
                ? EscalatorSpeedData.HELP_AUDIO_DEFAULT
                : EscalatorSpeedManager.effectiveHelpAudioId(mc.level, pos, editIn);
        individualAudio = mc.level != null
                && EscalatorSpeedManager.hasIndividualHelpAudio(mc.level, pos, editIn);
        boundVolume = mc.level == null
                ? EscalatorSpeedData.DEFAULT_HELP_VOLUME
                : EscalatorSpeedManager.getHelpVolume(mc.level, pos);
    }

    /** 【1.41】当前端头在界面上的短名（「进入扶梯」/「离开扶梯」）。 */
    private String endLabel() {
        return editIn ? "进入扶梯" : "离开扶梯";
    }

    /** 清空并重建控件（滚动、删除、同步回调后都会走到这里）。 */
    private void buildUi() {
        clearWidgets();
        listBottom = Math.max(LIST_TOP + ROW_H, this.height - BOTTOM_RESERVE);
        rebuildRows();

        // 顶部：返回 / 刷新
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose())
                .bounds(this.width / 2 - 100, 24, 96, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("刷新"), button -> {
            ClientPlayNetworking.send(SmoothLift.REQUEST_SYNC_CHANNEL, PacketByteBufs.empty());
            setStatus("已请求刷新，同步回来后列表会自动更新");
        }).bounds(this.width / 2 + 4, 24, 96, 20).build());

        // 列表：只为「完整可见 + 可点击」的行创建按钮。
        // （滚出可视区的行不建控件，避免按钮溢出到标题/底部文字上。）
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = rowY(i);
            if (!fullyVisible(y)) {
                continue;
            }
            if (row.type == T_DEFAULT || row.type == T_OFF) {
                addRenderableWidget(Button.builder(Component.literal(truncate(row.text, 28)), button -> setHelpAudio(row.id))
                        .bounds(this.width / 2 - BTN_W / 2, y, BTN_W, 20)
                        .build());
            } else if (row.type == T_STORED) {
                addRenderableWidget(Button.builder(Component.literal(truncate(row.text, 22)), button -> setHelpAudio(row.id))
                        .bounds(this.width / 2 - BTN_W / 2, y, BTN_W - 50, 20)
                        .build());
                addRenderableWidget(Button.builder(Component.literal("删除"), button -> deleteAudio(row.id))
                        .bounds(this.width / 2 - BTN_W / 2 + BTN_W - 46, y, 46, 20)
                        .build());
            } else if (row.type == T_PENDING) {
                addRenderableWidget(Button.builder(Component.literal(truncate(row.text, 28)), button -> importFolderAudio(row.id))
                        .bounds(this.width / 2 - BTN_W / 2, y, BTN_W, 20)
                        .build());
            }
        }

        // 【1.41】底部：切换「正在设置的那一头」（只换视图，不动任何一头的数据）。
        // 原来是「跟随默认（清这一头）」+「设置端头」两只 130 宽的并排按钮；
        // 「跟随默认」那只已按需求从界面上撤掉（回默认改用列表顶端的「默认提示音」行），
        // 于是这里把「设置端头」恢复成与列表同一套的常规宽度（BTN_W）并居中，
        // 正好占满原先两只按钮让出来的位置。
        addRenderableWidget(Button.builder(
                Component.literal("设置端头：" + endLabel()), button -> {
            editIn = !editIn;
            init();
        }).bounds(this.width / 2 - BTN_W / 2, this.height - BOTTOM_RESERVE + 6, BTN_W, 20).build());
    }

    /**
     * 把四段列表拼成扁平行列表，并重算滚动范围。
     *
     * <p>顺序（与运行底噪界面同一套约定）：**「默认提示音」永远排在最顶端**，
     * 紧跟一行「不播提示音」，然后才是「待导入」与「已存入」。
     */
    private void rebuildRows() {
        rows.clear();

        // 第一段（最上端、位置固定）：模组原来的提示音 —— 永远可用，不依赖存档 / 文件夹 / 同步。
        // ★ 按用户要求，这一行只留「默认提示音」五个字（不带任何括号说明）；
        //   「它是什么、速率怎么调」放到界面底部那两行提示里讲。
        rows.add(new Row(T_DEFAULT, EscalatorSpeedData.HELP_AUDIO_DEFAULT, "默认提示音"));
        // 第二段：这条扶梯单独不播提示音（等价于 /futihelpmusic off）
        rows.add(new Row(T_OFF, EscalatorSpeedData.HELP_AUDIO_OFF, "不播提示音（仅这条扶梯）"));

        // 第三段：存档文件夹里的 OGG（与运行底噪共用同一个文件夹），点=导入并设为提示音。
        rows.add(new Row(T_HEADER, null, "存档文件夹 smoothlift_audio 待导入（点=导入并设为提示音）"));
        if (pending.isEmpty()) {
            rows.add(new Row(T_NOTE, null, "（暂无）"));
        } else {
            for (String id : pending) {
                rows.add(new Row(T_PENDING, id, id));
            }
        }

        // 第四段：已存入存档的音频（与运行底噪共用同一个库；点名字=设为提示音，右边「删除」=从存档移除）。
        for (String id : stored) {
            rows.add(new Row(T_STORED, id, id));
        }

        int total = rows.size() * ROW_H;
        int visible = Math.max(ROW_H, listBottom - LIST_TOP);
        maxScroll = Math.max(0, total - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
    }

    /** 第 index 行的屏幕 y（含滚动偏移）。 */
    private int rowY(int index) {
        return LIST_TOP + index * ROW_H - scroll;
    }

    /** 该行是否完整落在列表可视区内。 */
    private boolean fullyVisible(int y) {
        return y >= LIST_TOP && y + 20 <= listBottom;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0 && scrollY != 0.0) {
            scroll -= (int) Math.round(scrollY * ROW_H);
            scroll = Math.max(0, Math.min(scroll, maxScroll));
            buildUi();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** 【1.41】把一个音频（默认提示音 / 不播 / 已存档的某段）设为这条扶梯**当前这一头**的提示音。 */
    private void setHelpAudio(String id) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeUtf(id, 128);
        buf.writeBoolean(editIn);
        ClientPlayNetworking.send(SmoothLift.BIND_HELP_AUDIO_CHANNEL, buf);
        setStatus("已选择「" + endLabel() + "」：" + truncate(helpAudioLabel(id), 18));
    }

    /** 从存档删除一段音频（服务端会同时解绑引用它的扶梯 —— 底噪与提示音两边都解）。 */
    private void deleteAudio(String name) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(name, 128);
        ClientPlayNetworking.send(SmoothLift.DELETE_AUDIO_CHANNEL, buf);
        stored.remove(name);
        buildUi();
        setStatus("已请求删除：" + truncate(name, 20) + "（底噪与提示音的引用都会解绑）");
    }

    /** 把存档文件夹里的一个文件导入到存档并设为这条扶梯**当前这一头**的提示音（之后删原文件仍可播）。 */
    private void importFolderAudio(String name) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeUtf(name, 128);
        buf.writeBoolean(editIn);
        ClientPlayNetworking.send(SmoothLift.IMPORT_FOLDER_HELP_AUDIO_CHANNEL, buf);
        pending.remove(name);
        buildUi();
        setStatus("正在从文件夹导入并设为「" + endLabel() + "」的提示音：" + truncate(name, 16));
    }

    /** 回到渲染线程刷新反馈文字与设置回显。 */
    private void setStatus(String text) {
        Minecraft.getInstance().execute(() -> {
            this.statusText = text;
            // 【1.41】回显按「当前这一头」刷新（editIn 可能刚被切过）
            refreshEndState();
        });
    }

    @Override
    public void onClose() {
        if (OPEN == this) {
            OPEN = null;
        }
        Minecraft.getInstance().setScreen(new EscalatorSpeedScreen(pos));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        // 只画「分段标题 / 灰色说明」这两种行 —— 可点行由按钮自己画文字。
        // 用裁剪区兜住，滚动到一半的行只露出可见部分，不会压到标题或底部。
        guiGraphics.enableScissor(0, LIST_TOP, this.width, listBottom);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.type != T_HEADER && row.type != T_NOTE) {
                continue;
            }
            int y = rowY(i);
            if (y + ROW_H < LIST_TOP || y > listBottom) {
                continue; // 完全在可视区外
            }
            int color = row.type == T_HEADER ? 0xFFE0E0E0 : 0xFF909090;
            guiGraphics.drawCenteredString(this.font, Component.literal(row.text), this.width / 2, y + 6, color);
        }
        guiGraphics.disableScissor();

        // 滚动条（可滚动时才显示）
        if (maxScroll > 0) {
            int barX = this.width / 2 + BTN_W / 2 + 10;
            int trackTop = LIST_TOP;
            int trackH = Math.max(ROW_H, listBottom - LIST_TOP);
            guiGraphics.fill(barX, trackTop, barX + 4, trackTop + trackH, 0x40000000);
            int thumbH = Math.max(14, trackH * trackH / (rows.size() * ROW_H));
            int thumbY = trackTop + (trackH - thumbH) * scroll / maxScroll;
            guiGraphics.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xFFAAAAAA);
        }

        // 无反馈时这一行显示「当前正在设置的那一头」的设置状态；有反馈时换成黄色反馈文字。
        int statusY = this.height - BOTTOM_RESERVE + 36;
        String info = statusText;
        if (info == null) {
            String prefix = individualAudio ? "单独设置：" : "跟随默认：";
            info = endLabel() + "　" + prefix + truncate(helpAudioLabel(effectiveAudioId), 18)
                    + "　音量 " + boundVolume + "%";
        }
        guiGraphics.drawCenteredString(this.font, Component.literal(info), this.width / 2, statusY,
                statusText == null ? 0x808080 : 0xFFFF55);

        guiGraphics.drawCenteredString(this.font,
                Component.literal("自定义提示音按原速循环播（速率只对「默认提示音」生效）；进 / 出两头各设各的"),
                this.width / 2, statusY + 16, 0x808080);
        guiGraphics.drawCenteredString(this.font,
                Component.literal(maxScroll > 0
                        ? "音频与运行底噪共用同一个库，删除会同时解绑底噪（滚轮可滚动列表）"
                        : "音频与运行底噪共用同一个库，删除会同时解绑底噪"),
                this.width / 2, statusY + 30, 0x808080);
    }

    /**
     * 提示音 ID 在界面上的显示名（与指令 /futihelpmusic 的反馈文案保持一致）。
     *
     * <p>★ 按用户要求，默认提示音这一项只显示「默认提示音」五个字，不带括号说明
     * （与列表首行 {@link #rebuildRows()} 的写法统一）；「它是什么」在界面底部提示里讲。
     */
    private static String helpAudioLabel(String audioId) {
        if (EscalatorSpeedData.HELP_AUDIO_DEFAULT.equals(audioId)) {
            return "默认提示音";
        }
        if (EscalatorSpeedData.HELP_AUDIO_OFF.equals(audioId)) {
            return "不播提示音";
        }
        return audioId;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
