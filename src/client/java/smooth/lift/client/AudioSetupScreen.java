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
 * 石斧界面里的「选择扶梯音乐」子界面。列表从上到下三段：
 * <ol>
 *   <li>存档文件夹 {@code smoothlift_audio} 里的 OGG：点=导入存档并绑定（删原文件仍可播）。</li>
 *   <li><b>模组内置音频</b>：随模组 jar 一起分发，装了模组就自带（{@code assets/smoothlift/sounds/audio/*.ogg}，
 *       由模组自己的 {@code sounds.json} 注册）。点一下即绑定，<b>不需要玩家准备任何文件、也不需要 ffmpeg</b>。</li>
 *   <li>已存入存档的音频：点名字=绑定此扶梯；删除=从存档移除。</li>
 * </ol>
 * 【1.38】段与段之间的说明文字已按用户要求删掉（只保留最上面那一行「文件夹待导入」提示），
 * 三段的区别靠行本身的形态区分：待导入=歌名、内置=显示名、已存入=右边多一个「删除」按钮。
 * 行数可能超过一屏，支持鼠标滚轮滚动；列表右侧有滚动条。
 * 界面在按钮点击后保持打开，只在按 ESC 或「返回」时回到设置界面。
 */
public class AudioSetupScreen extends Screen {

    private static final int ROW_H = 22;          // 每行固定高度（含行间距）
    private static final int LIST_TOP = 50;       // 列表可视区顶部
    private static final int BOTTOM_RESERVE = 76;  // 底部固定区（解绑按钮 + 状态 + 提示）占用的高度
    private static final int BTN_W = 200;         // 单列按钮宽度

    // 行类型
    private static final int T_HEADER = 0;   // 分段标题（不可点）
    private static final int T_NOTE = 1;     // 灰色说明（不可点）
    private static final int T_BUILTIN = 2;  // 内置音频：点=绑定
    private static final int T_STORED = 3;   // 存档音频：点=绑定 / 删除
    private static final int T_PENDING = 4;  // 待导入：点=导入并绑定

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

    /** 模组内置音频的绑定 ID（带 builtin: 前缀），顺序固定。 */
    private final List<String> builtin = new ArrayList<>();
    /** 已存入存档的音频 ID（文件名）。 */
    private final List<String> stored = new ArrayList<>();
    /** 存档文件夹里尚未入库、待导入的 OGG 文件名。 */
    private final List<String> pending = new ArrayList<>();
    /** 三段拼成的扁平行列表（每次刷新重建）。 */
    private final List<Row> rows = new ArrayList<>();

    /** 这条扶梯**实际使用**的音频ID（单独绑定 &gt; 默认音频）；null 表示真的不会出声。 */
    private String boundAudioId;
    /**
     * 【1.14】上面那个 ID 的来源：true = 本扶梯单独绑定的；false = 来自「默认音频」层
     * （{@code /futimusic -f default}）。只影响文案（「当前绑定」/「使用默认音乐」），不影响播放。
     */
    private boolean individualAudio;
    /** 【1.9】这条扶梯当前的声音音量（1~1000；100 = 原始音量），仅用于回显。 */
    private int boundVolume = EscalatorSpeedData.DEFAULT_AUDIO_VOLUME;
    /** 最近一次操作的反馈文字；非空时在底部用黄色显示。 */
    private String statusText;

    /** 列表滚动像素偏移 / 最大可滚动量 / 列表可视区底部。 */
    private int scroll;
    private int maxScroll;
    private int listBottom;

    /** 当前打开的实例；服务端数据同步回来时由 {@link #notifyAudioDataChanged()} 回调刷新。 */
    private static volatile AudioSetupScreen OPEN;

    public AudioSetupScreen(BlockPos pos) {
        super(Component.literal("选择扶梯音乐"));
        this.pos = pos;
    }

    /**
     * 服务端音频数据同步完成（导入/绑定/删除/刷新应答）后调用：刷新列表、
     * 并清除“正在导入…”这类占位反馈，让界面回退显示真实的绑定状态。
     */
    public static void notifyAudioDataChanged() {
        AudioSetupScreen s = OPEN;
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
        // 【1.14】回显「这条扶梯实际会播什么」，而不是「本方块自己绑了什么」。
        // 以前只看 getBlockAudioId(pos)：用 /futimusic -f default 把单独绑定清掉、改走默认音频后，
        // 这里会错写成「未绑定（扶梯保持静音）」—— 其实声音照放。改成：
        //   effectiveAudioId = 单独绑定（链上任意方块）优先，其次默认音频；
        //   individualAudio  = 这个结果是不是「单独绑定」来的，用来区分文案。
        boundAudioId = mc.level == null ? null : EscalatorSpeedManager.effectiveAudioId(mc.level, pos);
        individualAudio = mc.level != null && EscalatorSpeedManager.hasIndividualAudio(mc.level, pos);
        boundVolume = mc.level == null
                ? EscalatorSpeedData.DEFAULT_AUDIO_VOLUME
                : EscalatorSpeedManager.getVolumeForScreen(mc.level, pos);

        builtin.clear();
        // 内置音频随模组分发，永远可用（不依赖存档/文件夹/同步）。
        builtin.addAll(EscalatorSpeedManager.builtinAudioIds());

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
            if (row.type == T_BUILTIN) {
                addRenderableWidget(Button.builder(Component.literal(truncate(row.text, 28)), button -> bindAudio(row.id))
                        .bounds(this.width / 2 - BTN_W / 2, y, BTN_W, 20)
                        .build());
            } else if (row.type == T_STORED) {
                addRenderableWidget(Button.builder(Component.literal(truncate(row.text, 22)), button -> bindAudio(row.id))
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

        // 底部：解绑此扶梯
        addRenderableWidget(Button.builder(Component.literal("解绑此扶梯"), button -> {
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeBlockPos(pos);
            ClientPlayNetworking.send(SmoothLift.UNBIND_AUDIO_CHANNEL, buf);
            setStatus("已请求解除这段声音（若绑定中则不再播放）");
        }).bounds(this.width / 2 - 60, this.height - BOTTOM_RESERVE + 6, 120, 20).build());
    }

    /**
     * 把三段列表拼成扁平行列表，并重算滚动范围。
     *
     * <p>【1.38d】行顺序按用户要求再调：**「默认音乐」永远排在最顶端，它下面才是导入的音乐**。
     * 「默认音乐」= {@link #builtin}（模组内置音频，装完模组就有、不依赖存档 / 文件夹 / 同步），
     * 它自己一条就是玩家能立刻点的那一项；「导入的音乐」= {@link #pending}（存档文件夹里待导入）
     * + {@link #stored}（已存入存档）。
     *
     * <p>【1.38】的调整仍然保留：删掉了「① 模组内置音频…」「② 已存入存档的音频…」两段介绍文字
     * （行本身的形态已经能区分三段：待导入=歌名、内置=显示名、已存入=右边多一个「删除」按钮）。
     * ★ 拼装机制没变：三种行类型（{@code T_BUILTIN} / {@code T_STORED} / {@code T_PENDING}）
     * 与各自的点击行为仍各管各的（见 {@link #buildUi()}）。
     */
    private void rebuildRows() {
        rows.clear();

        // 第一段（最上端、位置固定）：模组内置音频 =「默认音乐」，永远可用。
        for (String id : builtin) {
            rows.add(new Row(T_BUILTIN, id, EscalatorSpeedManager.displayName(id)));
        }

        // 第二段：存档文件夹里的 OGG（玩家自己导入的音乐），点=导入并绑定。
        rows.add(new Row(T_HEADER, null, "存档文件夹 smoothlift_audio 待导入（点=导入并绑定）"));
        if (pending.isEmpty()) {
            rows.add(new Row(T_NOTE, null, "（暂无）"));
        } else {
            for (String id : pending) {
                rows.add(new Row(T_PENDING, id, id));
            }
        }

        // 第三段：已存入存档的音频（点名字=绑定；右边的「删除」=从存档移除）。
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

    /** 点击一个音频（内置或已存档）：绑定到这条扶梯。 */
    private void bindAudio(String id) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeUtf(id, 128);
        ClientPlayNetworking.send(SmoothLift.BIND_AUDIO_CHANNEL, buf);
        setStatus("已选择：" + truncate(EscalatorSpeedManager.displayName(id), 20));
    }

    /** 删除一条已存入存档的音频（服务端会同时解绑引用它的扶梯）。本地乐观移除便于立刻看到。 */
    private void deleteAudio(String name) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(name, 128);
        ClientPlayNetworking.send(SmoothLift.DELETE_AUDIO_CHANNEL, buf);
        stored.remove(name);
        buildUi();
        setStatus("已请求删除：" + truncate(name, 20));
    }

    /** 把存档文件夹里的一个文件导入到存档并绑定到这条扶梯（之后删原文件仍可播）。 */
    private void importFolderAudio(String name) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeUtf(name, 128);
        ClientPlayNetworking.send(SmoothLift.IMPORT_FOLDER_AUDIO_CHANNEL, buf);
        pending.remove(name);
        buildUi();
        setStatus("正在从文件夹导入并绑定：" + truncate(name, 20));
    }

    /** 回到渲染线程刷新反馈文字与绑定回显。 */
    private void setStatus(String text) {
        Minecraft.getInstance().execute(() -> {
            this.statusText = text;
            Minecraft mc = Minecraft.getInstance();
            this.boundAudioId = mc.level == null ? null : EscalatorSpeedManager.effectiveAudioId(mc.level, pos);
            this.individualAudio = mc.level != null && EscalatorSpeedManager.hasIndividualAudio(mc.level, pos);
            this.boundVolume = mc.level == null
                    ? EscalatorSpeedData.DEFAULT_AUDIO_VOLUME
                    : EscalatorSpeedManager.getVolumeForScreen(mc.level, pos);
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

        // 无反馈时这一行显示绑定状态；有反馈时换成黄色反馈文字。
        int statusY = this.height - BOTTOM_RESERVE + 30;
        String info = statusText;
        if (info == null) {
            if (boundAudioId == null) {
                info = "未绑定（扶梯保持静音）";
            } else {
                // 单独绑定 → 「当前绑定」；走默认音频（/futimusic -f default 之后）→ 写明「默认音乐」，
                // 否则玩家会以为绑定丢了（其实有声音）。
                String prefix = individualAudio ? "当前绑定：" : "使用默认音乐：";
                info = prefix + truncate(EscalatorSpeedManager.displayName(boundAudioId), 18)
                        + "　音量 " + boundVolume + "%";
            }
        }
        guiGraphics.drawCenteredString(this.font, Component.literal(info), this.width / 2, statusY,
                statusText == null ? 0x808080 : 0xFFFF55);

        guiGraphics.drawCenteredString(this.font,
                Component.literal("内置音频开箱即用；自备文件必须是 Ogg Vorbis(.ogg)，MP3 改名 / Opus 都不会响"),
                this.width / 2, statusY + 16, 0x808080);
        guiGraphics.drawCenteredString(this.font,
                Component.literal(maxScroll > 0
                        ? "音量（底噪/提示音）都在上一页「扶梯设置」里调（滚轮可滚动列表）；底噪离开整条扶梯 16 格内才听得见"
                        : "音量（底噪/提示音）都在上一页「扶梯设置」里调；底噪离开整条扶梯 16 格内才听得见"),
                this.width / 2, statusY + 30, 0x808080);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
