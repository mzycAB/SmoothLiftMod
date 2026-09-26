package smooth.lift.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import smooth.lift.SmoothLift;

/**
 * 【1.55】「同步」弹窗 —— 三个界面右上角那个「同步所有」按钮按下去之后弹出来的小面板。
 *
 * <p>三个按钮：
 * <ul>
 *   <li><b>同步所有</b>：把当前这一项的设置写成**默认** ⇒ 没单独设置过的项都跟着变，
 *       单独设置过的项保留自己的值。等同**不带 {@code -f}** 的指令。</li>
 *   <li><b>强制同步</b>：再额外清掉这一项的单独设置 ⇒ 连修改过的一起变成同一个值。
 *       等同**带 {@code -f}** 的指令。</li>
 *   <li><b>取消</b>：什么都不做，回原来的界面。</li>
 * </ul>
 *
 * <p>★ 按 ESC 与「取消」同义（{@code Screen.keyPressed} 里 ESC 走的就是 {@link #onClose()}）。
 *
 * <p>★ 弹窗是**另开一个 Screen**、而不是在原界面上叠一层：叠一层要处理
 * 「底层控件还吃鼠标点击」这件麻烦事（画上去盖住 ≠ 拦住点击）。代价是原界面会被
 * `setScreen` 重建一次 —— 所以打开弹窗前先把输入框落地
 * （见 {@link #syncButton} 的 {@code beforeOpen}），否则刚填的数字会丢。
 *
 * <p>★ `returnTo` 传的是**原来那个 Screen 实例**：`setScreen` 回来时它的 {@code init()}
 * 会重新跑一遍、但 {@code page} 之类的字段还在 ⇒ 二级菜单返回后仍停在二级菜单。
 *
 * <p>界面里**不写任何说明小字**，只有标题 + 三个按钮（与「预设选择」界面同一套口味）。
 * 同步结果由服务端回一条聊天栏消息，退出界面后能看到这次到底做了什么。
 */
public class SyncPopupScreen extends Screen {

    /** 三个按钮的宽度：与模组其它界面的输入框同宽。 */
    private static final int BTN_W = 200;
    /** 右上角那个入口按钮的宽度：刚好放下「同步所有」四个字 + 余量。 */
    private static final int ENTRY_W = 76;
    /** 面板尺寸。 */
    private static final int PANEL_W = 240;
    private static final int PANEL_H = 112;

    /** 关掉弹窗之后回到哪个界面。 */
    private final Screen returnTo;
    /** 同步的域：{@code esc} 扶梯 / {@code lift} 直梯 / {@code psd} 屏蔽门。 */
    private final String domain;
    /** 射程：0 = 一级菜单，≥1 = 二级菜单的子页编号。 */
    private final int scope;
    /** 当前这一项的身份：扶梯 = BlockPos.asLong，直梯 = 竖井列 key，屏蔽门 = runKey。 */
    private final long key;

    public SyncPopupScreen(Screen returnTo, String domain, int scope, long key) {
        super(Component.literal("同步"));
        this.returnTo = returnTo;
        this.domain = domain;
        this.scope = scope;
        this.key = key;
    }

    /**
     * 【1.55】造出界面右上角那个「同步所有」入口按钮。
     *
     * <p>放在 {@code (width - 4 - 76, 6)} —— 模组现有的控件全在中间与底部，右上角是空的。
     *
     * @param host       宿主界面；也作为弹窗的返回目标
     * @param domain     同步的域：{@code esc} / {@code lift} / {@code psd}
     * @param scope      射程：0 = 一级菜单，≥1 = 二级菜单子页编号
     * @param key        当前这一项的身份
     * @param beforeOpen 打开弹窗**之前**要做的事：把当前页输入框落地。
     *                   传 null 表示这一页没有待落地的输入框。
     *                   ★ 必须落地 —— 弹窗会把宿主界面重建，没落地的手填值会丢，
     *                   而且服务端读「当前这一项的值」时也读不到它。
     */
    public static Button syncButton(Screen host, String domain, int scope, long key, Runnable beforeOpen) {
        return Button.builder(Component.literal("同步所有"), button -> {
                    if (beforeOpen != null) {
                        beforeOpen.run();
                    }
                    Minecraft mc = Minecraft.getInstance();
                    mc.setScreen(new SyncPopupScreen(host, domain, scope, key));
                })
                .bounds(host.width - 4 - ENTRY_W, 6, ENTRY_W, 20)
                .build();
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = panelY() + 30;
        addRenderableWidget(Button.builder(Component.literal("同步所有"), button -> send(false))
                .bounds(cx - BTN_W / 2, y, BTN_W, 20)
                .build());
        y += 26;
        addRenderableWidget(Button.builder(Component.literal("强制同步"), button -> send(true))
                .bounds(cx - BTN_W / 2, y, BTN_W, 20)
                .build());
        y += 26;
        addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
                .bounds(cx - BTN_W / 2, y, BTN_W, 20)
                .build());
    }

    /** buf 顺序必须与服务端 {@code SYNC_SETTINGS_CHANNEL} 的读序一致。 */
    private void send(boolean force) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeUtf(domain, 16);
        buf.writeVarInt(scope);
        buf.writeBoolean(force);
        buf.writeLong(key);
        ClientPlayNetworking.send(SmoothLift.SYNC_SETTINGS_CHANNEL, buf);
        onClose();
    }

    /** 「取消」与 ESC 都走这里：什么都不发，回原来的界面。 */
    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(returnTo == null ? null : returnTo);
    }

    private int panelX() {
        return (this.width - PANEL_W) / 2;
    }

    private int panelY() {
        return (this.height - PANEL_H) / 2;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 【1.20.1 API】Screen.renderBackground 只有 1 个参数（1.20.4 才是 4 个参数）。
        this.renderBackground(guiGraphics);
        int px = panelX();
        int py = panelY();
        // 面板：先描一圈深色边，再填半透明底 —— 与模组其它界面的「黑底白字」一致
        guiGraphics.fill(px - 2, py - 2, px + PANEL_W + 2, py + PANEL_H + 2, 0xFF000000);
        guiGraphics.fill(px, py, px + PANEL_W, py + PANEL_H, 0xF0101010);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, py + 10, 0xFFFFFF);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
