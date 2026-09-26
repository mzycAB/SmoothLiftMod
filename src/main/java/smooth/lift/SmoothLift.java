package smooth.lift;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import smooth.lift.network.Packets;

import java.util.Locale;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import java.util.concurrent.CompletableFuture;
import smooth.lift.network.MbmHelpOpenPacket;
import net.minecraftforge.network.PacketDistributor;

@Mod(SmoothLift.MOD_ID)
public class SmoothLift {
    public static final String MOD_ID = "smooth_lift";

    /** 【1.55】「同步所有」弹窗的域常量（客户端 SyncPopupScreen / 服务端 syncSettings 共用）。 */
    public static final int SYNC_TOP_LEVEL = 0;
    public static final int SYNC_ESC_AUDIO = 1;
    public static final int SYNC_ESC_HELP_AUDIO = 2;
    static final String[] SYNC_LIFT_WHICH = {"up", "down", "chime"};
    static final String[] SYNC_PSD_WHICH = {"open", "close"};
    static final int SYNC_PSD_MIDIUM_PAGE = 3;
    static final int SYNC_PSD_ARRIVE_PAGE = 4;
    /** 【1.56】列车音效五个域的编号（服务端 syncTrain 用）。 */
    public static final int SYNC_TRAIN_RUN = 1;
    public static final int SYNC_TRAIN_TURN = 2;
    public static final int SYNC_TRAIN_SWITCH = 3;
    public static final int SYNC_TRAIN_ARRIVE = 4;
    public static final int SYNC_TRAIN_DEPART = 5;

    /** 【1.53】经典港铁预设：9 + 2 = 11 条。末尾两条把 open/close 的子开关打开（1.58 修）。 */
    private static final String[] PRESET_CLASSIC_MTR = {
            "futimusic -f default",
            "futihelp -f on",
            "lifthelp -f on",
            "lifthelp door -f on",
            "lifthelp up -f on",
            "lifthelp down -f on",
            "pbmclosewait -f 1",
            "pbmmusic open -f default",
            "pbmmusic close -f default-m",
            "pbmmusic open -f on",
            "pbmmusic close -f on",
    };
    /** 【1.58】简单港铁预设：9 + 2 = 11 条（close 用 default-s）。 */
    private static final String[] PRESET_SIMPLE_MTR = {
            "futimusic -f default",
            "futihelp -f off",
            "lifthelp -f on",
            "lifthelp door -f off",
            "lifthelp up -f on",
            "lifthelp down -f on",
            "pbmclosewait -f 1",
            "pbmmusic open -f default",
            "pbmmusic close -f default-s",
            "pbmmusic open -f on",
            "pbmmusic close -f on",
    };
    /** 【1.58】空白预设：9 条，「不播」用正名 none（素材层），绝不写 off（会落到子开关层）。 */
    private static final String[] PRESET_BLANK = {
            "futimusic -f off",
            "futihelp -f off",
            "lifthelp -f off",
            "lifthelp door -f off",
            "lifthelp up -f off",
            "lifthelp down -f off",
            "pbmclosewait -f 1",
            "pbmmusic open -f none",
            "pbmmusic close -f none",
    };

    public SmoothLift() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(Packets::register);
    }

    /**
     * 命令注册：
     *   /futispeed        (无参)   -> 显示当前扶梯运行速度
     *   /futispeed   X            -> 全局扶梯运行速度 = X（阶梯速度一起跟随）
     *   /futispeed   X to Y       -> 只有当前全局运行速度正好是 X 时才改成 Y
     *   /futispeed   -f X         -> 强制游戏内所有扶梯运行速度 = X（不管有没有被改过）
     *   /futispeed   -f X to Y    -> 把所有运行速度为 X 的扶梯（含被改过的）改成 Y
     *
     *   /jietispeed  (无参)   -> 显示当前扶梯阶梯速度
     *   /jietispeed  X        -> 全局扶梯阶梯速度 = X（永远不动运行速度）
     *   /jietispeed  X to Y   -> 只有当前全局阶梯速度正好是 X 时才改成 Y
     *   /jietispeed  -f X     -> 强制所有扶梯阶梯速度 = X
     *   /jietispeed  -f X to Y-> 把所有阶梯速度为 X 的扶梯改成 Y
     *
     *   /futimusic   (无参)          -> 显示当前扶梯播放的音频名
     *   /futimusic   &lt;名字&gt;         -> 默认音频 = 名字（已单独绑过音频的扶梯不变）
     *   /futimusic   &lt;X&gt; to &lt;Y&gt;     -> 默认音频正好是 X 时才改成 Y（单独绑定的一律不动）
     *   /futimusic   -f &lt;名字&gt;      -> 强制游戏内所有扶梯都用这个名字的音频
     *   /futimusic   -f &lt;X&gt; to &lt;Y&gt;  -> 把所有音频为 X 的扶梯（含单独绑定的）改成 Y
     *   （名字可以是 default=内置音频、off=清除默认音频，或玩家上传的文件名如 example.ogg）
     *
     *   /futiloud    (无参)          -> 显示当前扶梯音量
     *   /futiloud    &lt;音量&gt;         -> 默认音量 = 音量（单独设置过音量的扶梯不变）
     *   /futiloud    &lt;X&gt; to &lt;Y&gt;     -> 默认音量正好是 X 时才改成 Y
     *   /futiloud    -f &lt;音量&gt;      -> 强制游戏内所有扶梯都用这个音量（清掉单独设置）
     *   /futiloud    -f &lt;X&gt; to &lt;Y&gt;  -> 把所有音量正好是 X 的扶梯（含单独设置的）改成 Y
     *   （音量范围 1~1000：100 = 原始音量，1000 = 10× 放大）
     *
     * <p>Forge 事件入口：本方法只负责转发给 {@link #registerCommands}（便于离线校验，
     * 见那个方法的注释）。
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    /**
     * 注册全部指令到给定 dispatcher（`/futispeed`、`/jietispeed`、`/futimusic`、`/futiloud`、
     * `/futihelp`、`/futihelploud`、`/futiround`、`/futihelpround`、`/futihelpspeed`、
     * `/futihelpmusic`，以及【1.42】直梯提示音的 `/lifthelp`、`/lifthelpspeed`、【1.43】`/lifthelploud`、
     * 【1.47】`/lifthelpround`、【1.46】`/lifthelpup|down|chime`）。
     *
     * <p>单独抽成一个方法是为了能**脱离游戏环境**直接建一棵 Brigadier 指令树来校验：
     * {@code _tools/CmdTreeCheck.java} 会把整棵树的补全项 dump 出来，确认
     * {@code /lifthelp on to off}、{@code /lifthelploud -f up 200} 这类分支真的可达、Tab 补得全能补得出来。
     * （与 Fabric 参考工程的同名方法同一用途。）
     */
    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("futispeed")
                .executes(SmoothLift::futiShow)
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                        .executes(SmoothLift::futiGlobal)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                                        .executes(SmoothLift::futiFromTo))))
                .then(futiForce("-f"))
        );

        dispatcher.register(Commands.literal("jietispeed")
                .executes(SmoothLift::jietiShow)
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                        .executes(SmoothLift::jietiGlobal)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                                        .executes(SmoothLift::jietiFromTo))))
                .then(jietiForce("-f"))
        );

        // 【1.7】/futimusic：扶梯音频（数据模型与 futispeed 完全对称）
        dispatcher.register(Commands.literal("futimusic")
                .executes(SmoothLift::futiMusicShow)
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(SmoothLift::futiMusicSet)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .executes(SmoothLift::futiMusicFromTo))))
                .then(futiMusicForce("-f"))
        );

        // 【1.12】/futiloud：扶梯音量（数据模型与 futispeed、futimusic 完全对称）
        dispatcher.register(Commands.literal("futiloud")
                .executes(SmoothLift::futiLoudShow)
                .then(Commands.argument("volume", volumeArg())
                        .executes(SmoothLift::futiLoudGlobal)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(SmoothLift::futiLoudFromTo))))
                .then(futiLoudForce("-f"))
        );
        dispatcher.register(Commands.literal("futihelp")
            .executes(SmoothLift::futiHelpShow)
            .then(Commands.literal("on")
                .executes(context -> futiHelpGlobal(context, true))
                .then(Commands.literal("to")
                    .then(Commands.literal("off")
                        .executes(context -> futiHelpFromTo(context, true, false)))))
            .then(Commands.literal("off")
                .executes(context -> futiHelpGlobal(context, false))
                .then(Commands.literal("to")
                    .then(Commands.literal("on")
                        .executes(context -> futiHelpFromTo(context, false, true)))))
            .then(futiHelpForce("-f"))
        );

        // /futihelploud：无障碍**提示音**音量（数据模型与 /futiloud、/futihelp 完全对称）。
        //   （无参数）     -> 显示当前扶梯的提示音音量
        //   <音量>         -> 默认音量 = 音量（单独设置过音量的扶梯不变）
        //   <X> to <Y>     -> 默认音量正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f <音量>      -> 强制游戏内所有扶梯提示音都用这个音量（清掉单独设置）
        //   -f <X> to <Y>  -> 把所有音量正好是 X 的扶梯（含单独设置的）改成 Y
        // 音量范围 1~1000：100 = 原始音量，1000 = 10× 放大。
        // ★ 这与 /futiloud 是两件事：/futiloud = 扶梯**运行底噪**（整条扶梯、射程 16 格）的音量，
        //   本指令 = 无障碍**提示音**（端头单块、射程 4 格）的音量。数据与指令互不影响。
        dispatcher.register(Commands.literal("futihelploud")
            .executes(SmoothLift::futiHelpLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::futiHelpLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::futiHelpLoudFromTo))))
            .then(futiHelpLoudForce("-f"))
        );

        // /futiround：扶梯**运行底噪**（整条扶梯一起响）的淡入淡出范围（单位格，默认 16）。
        //   （无参数）     -> 显示当前扶梯的底噪范围
        //   <范围>         -> 默认范围 = 范围（单独设置过的扶梯不变）
        //   <X> to <Y>     -> 默认范围正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f <范围>      -> 强制游戏内所有扶梯都用这个范围（清掉单独设置）
        //   -f <X> to <Y>  -> 把所有范围正好是 X 的扶梯（含单独设置的）改成 Y
        // 范围 1~128 格。★ 这与 /futihelpround 是两件事：本指令管**整条扶梯**的运行底噪（默认 16 格），
        //   /futihelpround 管端头**单块**的无障碍提示音（默认 4 格）。数据与指令互不影响。
        dispatcher.register(Commands.literal("futiround")
            .executes(SmoothLift::futiRoundShow)
            .then(Commands.argument("round", roundArg())
                .executes(SmoothLift::futiRoundGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", roundArg())
                        .executes(SmoothLift::futiRoundFromTo))))
            .then(futiRoundForce("-f"))
        );

        // /futihelpround：无障碍**提示音**（端头单块）的淡入淡出范围（单位格，默认 4）。
        //   （无参数）     -> 显示当前扶梯的提示音范围
        //   <范围>         -> 默认范围 = 范围（单独设置过的扶梯不变）
        //   <X> to <Y>     -> 默认范围正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f <范围>      -> 强制游戏内所有扶梯提示音都用这个范围（清掉单独设置）
        //   -f <X> to <Y>  -> 把所有范围正好是 X 的扶梯（含单独设置的）改成 Y
        // 范围 1~128 格。★ 与 /futiround 是两件事（见上）。
        dispatcher.register(Commands.literal("futihelpround")
            .executes(SmoothLift::futiHelpRoundShow)
            .then(Commands.argument("round", roundArg())
                .executes(SmoothLift::futiHelpRoundGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", roundArg())
                        .executes(SmoothLift::futiHelpRoundFromTo))))
            .then(futiHelpRoundForce("-f"))
        );

        // /futihelpspeed：无障碍**提示音**的速率（每秒响几次，单位 Hz，范围 1~50）。
        //   （无参数）            -> 显示当前扶梯进 / 出两头的速率
        //   in|out <Hz>           -> 默认速率 = Hz（单独设置过的扶梯不变）
        //   in|out <X> to <Y>     -> 默认速率正好是 X 时才改成 Y（单独设置的一律不动）
        //   -f in|out <Hz>        -> 强制游戏内所有扶梯该端速率 = Hz（清掉单独设置）
        //   -f in|out <X> to <Y>  -> 把所有该端速率正好是 X 的扶梯改成 Y
        // ★ 进 / 出是**两套独立数据**，所以子命令写在 in|out 里（与 /futihelpmusic 完全同形）。
        // 速率范围与素材分档见 EscalatorSpeedData.HELP_SPEED_MIN/MAX 与 EscalatorChimePlayer。
        dispatcher.register(Commands.literal("futihelpspeed")
            .executes(SmoothLift::futiHelpSpeedShow)
            .then(Commands.literal("in")
                .then(Commands.argument("hz", helpSpeedArg())
                    .executes(context -> futiHelpSpeedGlobal(context, true))
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", helpSpeedArg())
                            .executes(context -> futiHelpSpeedFromTo(context, true))))))
            .then(Commands.literal("out")
                .then(Commands.argument("hz", helpSpeedArg())
                    .executes(context -> futiHelpSpeedGlobal(context, false))
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", helpSpeedArg())
                            .executes(context -> futiHelpSpeedFromTo(context, false))))))
            .then(futiHelpSpeedForce("-f"))
        );

        // /futihelpmusic：无障碍**提示音**的音乐（进 / 出各放各的，与运行底噪 /futimusic 是两个东西）。
        //   （无参数）                -> 显示这条扶梯两头当前用的提示音
        //   in|out <名字>             -> 默认提示音 = 名字（单独设置过的扶梯不变）
        //   in|out <X> to <Y>         -> 默认提示音正好是 X 时才改成 Y
        //   -f in|out <名字>          -> 强制游戏内所有扶梯该端提示音 = 名字（清掉单独设置）
        //   -f in|out <X> to <Y>      -> 把所有该端提示音为 X 的扶梯改成 Y
        // 名字：default = 模组原来的提示音（速率见 /futihelpspeed）、off = 这一头不播、
        //       其它 = 音频库里的文件名（与 /futimusic 共用同一个导入文件夹，导入一次两边都能选）。
        dispatcher.register(Commands.literal("futihelpmusic")
            .executes(SmoothLift::futiHelpMusicShow)
            .then(Commands.literal("in")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(context -> futiHelpMusicSet(context, true))
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", StringArgumentType.string())
                            .executes(context -> futiHelpMusicFromTo(context, true))))))
            .then(Commands.literal("out")
                .then(Commands.argument("name", StringArgumentType.string())
                    .executes(context -> futiHelpMusicSet(context, false))
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", StringArgumentType.string())
                            .executes(context -> futiHelpMusicFromTo(context, false))))))
            .then(futiHelpMusicForce("-f"))
        );

        // 【1.42】/lifthelp：**直梯（Lift）**开关门提示音（liftmusic.ogg）开关。
        //   （无参数）       -> 显示当前维度的开关
        //   on | off         -> 本维度开关 = on/off
        //   on to off        -> 本维度开关正好是 on 时才改成 off
        //   -f on | off      -> 强制**所有维度** = on/off
        //   -f on to off     -> 所有维度里开关正好是 on 的改成 off
        // ★ 与 /futihelp 是两件事：那条管的是**扶梯**的无障碍提示音（进/出口「咔啪」声），
        //   本指令管的是**直梯**关门/开门时连播 liftmusic.ogg（关门 4 次、开门 2 次）。
        // ★ 直梯提示音只有「维度默认」一层数据，所以 -f 的含义是「对所有维度」而不是
        //   「对所有直梯」—— 见 EscalatorSpeedManager 里 1.42 那一段。
        // ★ 命令结构：`on`/`off` 在「不带 -f」和「带 -f」两层下面各有一套（与 /futihelp 完全一致）。
        dispatcher.register(Commands.literal("lifthelp")
            .executes(SmoothLift::liftHelpShow)
            .then(Commands.literal("on")
                .executes(context -> liftHelpGlobal(context, true))
                .then(Commands.literal("to")
                    .then(Commands.literal("off")
                        .executes(context -> liftHelpFromTo(context, true, false)))))
            .then(Commands.literal("off")
                .executes(context -> liftHelpGlobal(context, false))
                .then(Commands.literal("to")
                    .then(Commands.literal("on")
                        .executes(context -> liftHelpFromTo(context, false, true)))))
            .then(liftHelpForce("-f"))
        );

        // 【1.46】三提示音独立子开关：形状与 /lifthelp 一致，但分别管 up / down / chime。
        //   /lifthelpup 上楼提示音 / /lifthelpdown 下楼提示音 / /lifthelpchime 开关门提示音
        dispatcher.register(liftToneSwitchCommand("up"));
        dispatcher.register(liftToneSwitchCommand("down"));
        dispatcher.register(liftToneSwitchCommand("chime"));

        // 【1.42】/lifthelpspeed：**直梯**开关门提示音的**倍速**（允许小数，0.5~2.0）。
        //   （无参数）       -> 显示当前维度的倍速
        //   <倍速>           -> 本维度倍速 = 倍速
        //   <X> to <Y>       -> 本维度倍速正好是 X 时才改成 Y
        //   -f <倍速>        -> 强制**所有维度** = 倍速
        //   -f <X> to <Y>    -> 所有维度里倍速正好是 X 的改成 Y
        // 1 = 原速。上限 2 / 下限 0.5 是**原版 SoundEngine.calculatePitch 对 pitch 的硬夹取**
        // （Mth.clamp(pitch, 0.5F, 2.0F)），超出这一段的取值会被悄悄压回去，
        // 所以参数类型直接夹住，玩家填 3 会当场看到「必须在 0.5 和 2.0 之间」而不是「填了没用」。
        // ★ 与 /futihelpspeed（扶梯提示音速率，单位 Hz）是两件完全不同的事。
        dispatcher.register(Commands.literal("lifthelpspeed")
            .executes(SmoothLift::liftHelpSpeedShow)
            .then(Commands.argument("speed", liftHelpSpeedArg())
                .executes(SmoothLift::liftHelpSpeedGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", liftHelpSpeedArg())
                        .executes(SmoothLift::liftHelpSpeedFromTo))))
            .then(liftHelpSpeedForce("-f"))
        );

        // 【1.43】/lifthelploud：**直梯**开关门提示音的**音量**（1~1000，100 = 原始音量，
        //   1000 = 10× 放大，与扶梯那两套音量的区间完全一致）。
        //   （无参数）       -> 显示当前维度的音量
        //   <音量>           -> 本维度音量 = 音量
        //   <X> to <Y>       -> 本维度音量正好是 X 时才改成 Y
        //   -f <音量>        -> 强制**所有维度** = 音量
        //   -f <X> to <Y>    -> 所有维度里音量正好是 X 的改成 Y
        // 命令结构与 /lifthelpspeed 完全一致（只有参数类型不同：整数 vs 小数）。
        // ★ 这是**第三套**互不影响的音量：/futiloud = 扶梯运行底噪（整条、射程 16 格）、
        //   /futihelploud = 扶梯无障碍提示音（端头单块、射程 4 格）、本指令 = 直梯开关门提示音。
        //   三者各存各的，改一个不影响另外两个。
        dispatcher.register(Commands.literal("lifthelploud")
            .executes(SmoothLift::liftHelpLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::liftHelpLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::liftHelpLoudFromTo))))
            // 【1.48】三项提示音各自的音量（不带 -f 的主分支）：
            //   /lifthelploud up 200           本维度上楼提示音音量 = 200
            //   /lifthelploud up 200 to 300    本维度上楼提示音音量正好是 200 时才改成 300
            //   （down = 下楼、door = 开关门即 chime 的别名；没单独调过的项跟随共用默认）
            .then(liftToneLoudCommand("up", "up"))
            .then(liftToneLoudCommand("down", "down"))
            .then(liftToneLoudCommand("door", "chime"))
            // 【1.48】-f 合并成**一个**节点：下面既有共用音量（<音量>），也有三项分支（up|down|door）。
            //   /lifthelploud -f 200        所有维度共用默认音量 = 200
            //   /lifthelploud -f up 200     所有维度上楼提示音音量 = 200
            //   /lifthelploud -f up 200 to 300
            //   （door = chime 的别名，开关门提示音）
            .then(Commands.literal("-f")
                .then(Commands.argument("volume", volumeArg())
                    .executes(SmoothLift::liftHelpLoudForceAll)
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", volumeArg())
                            .executes(SmoothLift::liftHelpLoudForceFromTo))))
                .then(liftToneLoudForceBranch("up", "up"))
                .then(liftToneLoudForceBranch("down", "down"))
                .then(liftToneLoudForceBranch("door", "chime")))
        );

        // 【1.47】/lifthelpround：直梯提示音（上楼 / 下楼 / 开关门，**三项共用一份**）的
        //   淡入淡出范围（格）。首次载入模组默认 4 格。
        //   （无参数）      -> 显示当前维度的范围
        //   <范围>          -> 本维度范围 = 范围（1~128，复用扶梯那组范围常量）
        //   <X> to <Y>      -> 本维度范围正好是 X 时才改成 Y
        //   -f <范围>       -> 强制**所有维度** = 范围
        //   -f <X> to <Y>   -> 所有维度里范围正好是 X 的改成 Y
        // 与 /futiround / /futihelpround（扶梯）是**互不影响**的两件事：这里是直梯那一路。
        dispatcher.register(Commands.literal("lifthelpround")
            .executes(SmoothLift::liftHelpRoundShow)
            .then(Commands.argument("round", roundArg())
                .executes(SmoothLift::liftHelpRoundGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", roundArg())
                        .executes(SmoothLift::liftHelpRoundFromTo))))
            .then(liftHelpRoundForce("-f"))
        );
        // 【1.53】/MBM 总入口（help 预设选择 / music in|delete 批量音频）。大写 + 小写别名。
        dispatcher.register(mbmTree("MBM"));
        dispatcher.register(mbmTree("mbm"));

        dispatcher.register(Commands.literal("pbmmusic")
            .executes(SmoothLift::pbmMusicShow)
            .then(Commands.literal("on")
                .executes(context -> pbmMusicGlobal(context, true))
                .then(Commands.literal("to")
                    .then(Commands.literal("off")
                        .executes(context -> pbmMusicFromTo(context, true, false)))))
            .then(Commands.literal("off")
                .executes(context -> pbmMusicGlobal(context, false))
                .then(Commands.literal("to")
                    .then(Commands.literal("on")
                        .executes(context -> pbmMusicFromTo(context, false, true)))))
            .then(pbmMusicForce("-f"))
            .then(pbmMusicItemCommand("open", "open"))
            .then(pbmMusicItemCommand("close", "close"))
        );

        // 【1.50】/pbmloud：**屏蔽门**开关门提示音的音量（1~1000，100 = 原始音量，1000 = 10×）。
        //   （无参数）              -> 显示当前维度生效的共用音量（并标出两项是否跟随共用）
        //   <音量>                  -> 本维度共用音量 = 音量
        //   <X> to <Y>              -> 本维度共用音量正好是 X 时才改成 Y
        //   -f <音量>               -> 强制**所有维度** = 音量
        //   -f <X> to <Y>           -> 所有维度里共用音量正好是 X 的改成 Y
        //   open|close <音量>       -> 本维度该项自己的音量（没单独调过的项跟随共用默认）
        //   open|close <X> to <Y>   -> 本维度该项音量正好是 X 时才改成 Y
        //   -f open|close <音量>    -> 所有维度该项音量
        //   -f open|close <X> to <Y>
        // ★ 这是与 /futiloud（扶梯底噪）、/futihelploud（扶梯无障碍提示音）、/lifthelploud（直梯）
        //   **互不影响**的第 4 套音量，各存各的。
        dispatcher.register(Commands.literal("pbmloud")
            .executes(SmoothLift::pbmLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::pbmLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::pbmLoudFromTo))))
            .then(pbmLoudItemCommand("open", "open"))
            .then(pbmLoudItemCommand("close", "close"))
            .then(Commands.literal("-f")
                .then(Commands.argument("volume", volumeArg())
                    .executes(SmoothLift::pbmLoudForceAll)
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", volumeArg())
                            .executes(SmoothLift::pbmLoudForceFromTo))))
                .then(pbmLoudItemForceBranch("open", "open"))
                .then(pbmLoudItemForceBranch("close", "close")))
        );

        // 【1.22】/pbmmusicloud：屏蔽门**开关门提示音素材**那一套音量的别名。
        //   与 /pbmloud **完全同构、同一份数据**（新增它只是给"音量"这件事一个与素材指令
        //   /pbmmusic 对称的名字）；/pbmloud 原样保留，行为一个字没改。
        dispatcher.register(Commands.literal("pbmmusicloud")
            .executes(SmoothLift::pbmLoudShow)
            .then(Commands.argument("volume", volumeArg())
                .executes(SmoothLift::pbmLoudGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(SmoothLift::pbmLoudFromTo))))
            .then(pbmLoudItemCommand("open", "open"))
            .then(pbmLoudItemCommand("close", "close"))
            .then(Commands.literal("-f")
                .then(Commands.argument("volume", volumeArg())
                    .executes(SmoothLift::pbmLoudForceAll)
                    .then(Commands.literal("to")
                        .then(Commands.argument("target", volumeArg())
                            .executes(SmoothLift::pbmLoudForceFromTo))))
                .then(pbmLoudItemForceBranch("open", "open"))
                .then(pbmLoudItemForceBranch("close", "close")))
        );

        // 【1.22】/pbmmidiumloud：**到站播报**素材自己的音量（1~1000）。
        //   形状与 /pbmloud 同构，只是"共用 + 两项"变成"这一项"。
        //     （无参数）      -> 显示当前维度生效的到站播报音量
        //     <音量>          -> 本维度 = 音量
        //     <X> to <Y>      -> 本维度正好是 X 时才改成 Y
        //     -f <音量>       -> 强制**所有维度** = 音量
        //     -f <X> to <Y>   -> 所有维度里正好是 X 的改成 Y
        dispatcher.register(Commands.literal("pbmmidiumloud")
            .executes(context -> pbmItemLoudShow(context, "midium"))
            .then(Commands.argument("volume", volumeArg())
                .executes(context -> pbmItemLoudGlobal(context, "midium"))
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(context -> pbmItemLoudFromTo(context, "midium")))))
            .then(pbmItemLoudForce("-f", "midium"))
        );

        // 【1.22】/pbmarriveloud：**进站报站**素材自己的音量（1~1000）。形状同上。
        dispatcher.register(Commands.literal("pbmarriveloud")
            .executes(context -> pbmItemLoudShow(context, "arrive"))
            .then(Commands.argument("volume", volumeArg())
                .executes(context -> pbmItemLoudGlobal(context, "arrive"))
                .then(Commands.literal("to")
                    .then(Commands.argument("target", volumeArg())
                        .executes(context -> pbmItemLoudFromTo(context, "arrive")))))
            .then(pbmItemLoudForce("-f", "arrive"))
        );

        // 【1.50 / 1.23】四条「淡入淡出范围」（格）指令 —— 形状、上下限（1~128）、反馈句式完全一致。
        //   默认都是 16 格（车站尺度，比直梯的 4 格大得多：一列车到站时整排门都要能听见）。
        //   /pbmround        = 屏蔽门开关门提示音（open / close 两项共用一份）
        //   /pbmmusicround   = 同上（用户点名的名字；与 /pbmround 读写同一份数据）
        //   /pbmmidiumround  = 到站播报
        //   /pbmarriveround  = 进站报站
        //   树由 roundCommand 一处产出 —— 想不一致都难。
        dispatcher.register(roundCommand("pbmround", RoundKind.TONE));
        dispatcher.register(roundCommand("pbmmusicround", RoundKind.TONE));
        dispatcher.register(roundCommand("pbmmidiumround", RoundKind.MIDIUM));
        dispatcher.register(roundCommand("pbmarriveround", RoundKind.ARRIVE));

        // 【1.16】/pbmclosewait：**屏蔽门关门提示音**的「强制等待时长」（秒，0~60，默认 5）。
        //
        //   它是一套**兜底**，只在「这一轮的停站时长不够放完整条关门素材」时才生效：
        //     开门音效播完 → 等 N 秒 → 播语音播报 → 门一动（嘀嘀开始）立刻掐断这段人声。
        //   停站**够长**时它被完全忽略（那条路是「整段提前播、结尾落在门上」）。
        //
        //   （无参数）      -> 显示当前维度生效的秒数 + 这条语义的一句话说明
        //   <秒>            -> 本维度 = 秒
        //   <X> to <Y>      -> 本维度正好是 X 时才改成 Y
        //   -f <秒>         -> 强制**所有维度** = 秒
        //   -f <X> to <Y>   -> 所有维度里正好是 X 的改成 Y
        // ★ 与 /pbmround 形状同构（都是「一个整数 + to + -f」），改动时两处对着看。
        dispatcher.register(Commands.literal("pbmclosewait")
            .executes(SmoothLift::pbmCloseWaitShow)
            .then(Commands.argument("seconds", closeWaitArg())
                .executes(SmoothLift::pbmCloseWaitGlobal)
                .then(Commands.literal("to")
                    .then(Commands.argument("target", closeWaitArg())
                        .executes(SmoothLift::pbmCloseWaitFromTo))))
            .then(pbmCloseWaitForce("-f"))
        );

        // 【1.17】/pbmmidium：**到站播报**（列车到站、开门音播完后再等 Y 秒播这一段语音）。
        //   与关门提示音那套互不相干；★ 这段声音**永远不会被掐断**（车出站也照播到完）。
        //   `/pbmmidium`                 -> 显示
        //   `/pbmmidium <名字>`          -> 只改素材（保留当前秒数）
        //   `/pbmmidium <名字> <秒>`     -> 本维度（秒数 0 ~ 正无穷）
        //   `/pbmmidium -f <名字> <秒>`  -> 所有维度
        dispatcher.register(Commands.literal("pbmmidium")
            .executes(SmoothLift::pbmMidiumShow)
            .then(Commands.argument("name", midiumNameArg())
                .suggests(SmoothLift::pbmMidiumNameSuggestions)
                .executes(SmoothLift::pbmMidiumSetNameOnly)
                .then(Commands.argument("seconds", midiumWaitArg())
                    .executes(SmoothLift::pbmMidiumGlobal)))
            .then(pbmMidiumForce("-f"))
        );

        // 【1.21】/pbmarrive：**进站报站**（时刻表里最近的一班车还剩 |X| 秒到站时播这一段语音）。
        //   与到站播报互不相干；★ 这段声音同样**永远不会被掐断**（车进站后也照播到完）。
        //   `/pbmarrive`                 -> 显示
        //   `/pbmarrive <名字>`          -> 只改素材（保留当前秒数）
        //   `/pbmarrive <名字> <X>`      -> 本维度（X ∈ (-∞, 0]）
        //   `/pbmarrive -f <名字> <X>`   -> 所有维度
        dispatcher.register(Commands.literal("pbmarrive")
            .executes(SmoothLift::pbmArriveShow)
            .then(Commands.argument("name", arriveNameArg())
                .suggests(SmoothLift::pbmArriveNameSuggestions)
                .executes(SmoothLift::pbmArriveSetNameOnly)
                .then(Commands.argument("seconds", arriveArg())
                    .executes(SmoothLift::pbmArriveGlobal)))
            .then(pbmArriveForce("-f"))
        );

        }




    /** 服务端兜底：拿着石斧右键扶梯时取消原版交互（正常情况下客户端已拦截，不会发包）。 */
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        // 【1.30】玩家拿着扶梯物品右键 = 正在放/延长扶梯。MTR 是自己在 useOnBlock 里
        // setBlockState 的，不会触发 Forge 的放置事件，所以这里只记下点了哪一格，
        // 下一个服务端刻（见 onServerTick）再去看实际落了哪些方块、把整条链的单独设置补齐。
        //
        // 触发条件放宽到「拿着扶梯物品 **或** 右键点到的就是扶梯方块」：后者覆盖
        // 「拿方块物品把敲掉的那一格补回去」这类路径（此时手上不是扶梯物品，
        // 旧条件会漏掉）。补扫是幂等的 —— 链上本来就一致时 reconcileChain 返回 false，
        // 不写数据也不发包，所以放宽不会带来额外开销。
        if (isEscalatorItem(event.getItemStack())
                || EscalatorUtil.isEscalator(event.getLevel().getBlockState(event.getPos()))) {
            EscalatorSpeedManager.scheduleChainReconcile(event.getLevel(), event.getPos());
        }
        if (event.getEntity().getMainHandItem().is(Items.STONE_AXE)
                && EscalatorUtil.isEscalator(event.getLevel().getBlockState(event.getPos()))) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    /** 手里拿的是不是 MTR 的扶梯物品（按类名判断，避免编译期依赖 MTR）。 */
    private static boolean isEscalatorItem(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && stack.getItem().getClass().getName().toLowerCase(Locale.ROOT).contains("escalator");
    }

    /** 【1.30】服务端刻收尾：处理上一刻记下的「刚放了扶梯方块」的位置。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        EscalatorSpeedManager.tickPendingReconcile(event.getServer());
    }

    /** 开关在指令反馈里的显示名。 */
    private static String helpLabel(boolean enabled) {
        return enabled ? "开" : "关";
    }

    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("on")
                        .executes(context -> futiHelpForceAll(context, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> futiHelpForceFromTo(context, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> futiHelpForceAll(context, false))
                        .then(Commands.literal("to")
                                .then(Commands.literal("on")
                                        .executes(context -> futiHelpForceFromTo(context, false, true)))));
    }

    /** /futihelp（不带参数）—— 显示当前扶梯的无障碍提示音开关（站在扶梯上显示那一条，否则显示默认）。 */
    private static int futiHelpShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean global = EscalatorSpeedManager.getDefaultHelp(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认无障碍提示音：" + helpLabel(global)
                            + "（开 = 进扶梯一端急促咔咔、出扶梯一端缓慢咔咔）"), false);
            return 1;
        }
        boolean enabled = EscalatorSpeedManager.isHelpEnabled(level, pos);
        boolean own = EscalatorSpeedManager.hasOwnHelp(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯无障碍提示音：" + helpLabel(enabled) + "（这条扶梯，共 " + blocks + " 格，"
                        + (own ? "单独设置" : "使用默认开关")
                        + "）；默认开关 " + helpLabel(global)), false);
        return 1;
    }

    /** /futihelp &lt;on|off&gt; —— 设置**默认**开关（已单独设置过的扶梯不变）。 */
    private static int futiHelpGlobal(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultHelp(level, enabled);
        EscalatorSpeedManager.syncHelpToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "默认无障碍提示音已设为 " + helpLabel(enabled)
                        + "；没有单独设置过的扶梯都会用它（单独设置过的不受影响）"), false);
        return 1;
    }

    /** /futihelp &lt;X&gt; to &lt;Y&gt; —— 默认开关正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiHelpFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean current = EscalatorSpeedManager.getDefaultHelp(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "默认无障碍提示音不是 " + helpLabel(from) + "（当前为 " + helpLabel(current)
                            + "），未做修改；单独设置过的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultHelp(level, from, to);
        EscalatorSpeedManager.syncHelpToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "默认无障碍提示音从 " + helpLabel(from) + " 改为 " + helpLabel(to)), false);
        return 1;
    }

    /** /futihelp -f &lt;on|off&gt; —— 强制游戏内**所有**扶梯 = 该开关（清掉单独设置）。 */
    private static int futiHelpForceAll(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = EscalatorSpeedManager.forceDefaultHelp(level, enabled);
        EscalatorSpeedManager.syncHelpToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯的无障碍提示音 = " + helpLabel(enabled)
                        + "（清掉 " + cleared + " 处单独设置）"), false);
        return 1;
    }

    /** /futihelp -f &lt;X&gt; to &lt;Y&gt; —— 把所有开关正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpForceFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceHelpFromTo(level, from, to);
        EscalatorSpeedManager.syncHelpToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有无障碍提示音为 " + helpLabel(from) + " 的扶梯，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有无障碍提示音为 " + helpLabel(from) + " 的扶梯改成 "
                        + helpLabel(to) + "（共 " + changed + " 处）"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /futihelploud （无障碍**提示音**音量，数据模型与 /futiloud、/futihelp 完全对称）
    //
    //   注意与 /futiloud 的区别：/futiloud 管的是「扶梯运行底噪」的音量（整条扶梯、射程 16 格）；
    //   /futihelploud 管的是「无障碍提示音」的音量（装在端头单块方块上、射程 4 格）。
    //   两者是**两套独立数据**，改一个不影响另一个。
    // ------------------------------------------------------------------

    /** 注册 `/futihelploud` 的 `-f` 分支：`-f <音量>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpLoudForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(SmoothLift::futiHelpLoudForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(SmoothLift::futiHelpLoudForceFromTo))));
    }

    /** /futihelploud（不带参数）—— 显示当前扶梯的无障碍提示音音量。 */
    private static int futiHelpLoudShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int global = EscalatorSpeedManager.getDefaultHelpVolume(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认无障碍提示音音量：" + global + "（100 = 原始音量）"), false);
            return 1;
        }
        int volume = EscalatorSpeedManager.getHelpVolume(level, pos);
        boolean own = EscalatorSpeedManager.hasOwnHelpVolume(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯无障碍提示音音量：" + volume + "（这条扶梯，共 " + blocks + " 格，"
                        + (own ? "单独设置" : "使用默认音量")
                        + "）；默认音量 " + global), false);
        return 1;
    }

    /** /futihelploud &lt;音量&gt; —— 设置**默认**提示音音量（单独设置过的扶梯不变）。 */
    private static int futiHelpLoudGlobal(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultHelpVolume(level, volume);
        EscalatorSpeedManager.syncHelpVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpVolume(level);
        source.sendSuccess(() -> Component.literal(
                "默认无障碍提示音音量已设为 " + applied + "（100 = 原始音量）；"
                        + "没有单独设置过音量的扶梯都会用它（单独设置过的不受影响）"), false);
        return 1;
    }

    /** /futihelploud &lt;X&gt; to &lt;Y&gt; —— 默认音量正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiHelpLoudFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getDefaultHelpVolume(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "默认无障碍提示音音量不是 " + from + "（当前为 " + current + "），未做修改；"
                            + "单独设置过音量的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultHelpVolume(level, from, to);
        EscalatorSpeedManager.syncHelpVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpVolume(level);
        source.sendSuccess(() -> Component.literal(
                "默认无障碍提示音音量从 " + from + " 改为 " + applied), false);
        return 1;
    }

    /** /futihelploud -f &lt;音量&gt; —— 强制游戏内**所有**扶梯提示音都用这个音量（清掉单独设置）。 */
    private static int futiHelpLoudForceAll(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = EscalatorSpeedManager.forceDefaultHelpVolume(level, volume);
        EscalatorSpeedManager.syncHelpVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpVolume(level);
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯的无障碍提示音音量 = " + applied + "（清掉 " + cleared + " 处单独设置）"), false);
        return 1;
    }

    /** /futihelploud -f &lt;X&gt; to &lt;Y&gt; —— 把所有音量正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpLoudForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceHelpVolumeFromTo(level, from, to);
        EscalatorSpeedManager.syncHelpVolumeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有无障碍提示音音量正好是 " + from + " 的扶梯，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有无障碍提示音音量正好是 " + from + " 的扶梯改成 " + to + "（共 " + changed + " 处）"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.24】/futiround —— 扶梯运行底噪的淡入淡出范围（默认 16 格）
    //
    //   与 /futiloud（音量）是**两件事**：音量决定「多响」，范围决定「多远还听得见」。
    //   仅指令可改，**没有石斧界面控件**。
    // ------------------------------------------------------------------

    /** /futiround 的范围参数：1~128 格。 */
    private static IntegerArgumentType roundArg() {
        return IntegerArgumentType.integer(EscalatorSpeedData.ROUND_MIN, EscalatorSpeedData.ROUND_MAX);
    }

    /** 注册 `/futiround` 的 `-f` 分支：`-f <范围>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiRoundForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("round", roundArg())
                        .executes(SmoothLift::futiRoundForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", roundArg())
                                        .executes(SmoothLift::futiRoundForceFromTo))));
    }

    /** /futiround（不带参数）—— 显示当前扶梯运行音效的淡入淡出范围。 */
    private static int futiRoundShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int global = EscalatorSpeedManager.getDefaultRound(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认扶梯音效淡入淡出范围：" + global + " 格"), false);
            return 1;
        }
        int round = EscalatorSpeedManager.getRound(level, pos);
        boolean own = EscalatorSpeedManager.hasOwnRound(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯音效淡入淡出范围：" + round + " 格（这条扶梯，共 " + blocks + " 格，"
                        + (own ? "单独设置" : "使用默认范围")
                        + "）；默认范围 " + global + " 格"), false);
        return 1;
    }

    /** /futiround &lt;范围&gt; —— 设置**默认**范围（单独设置过的扶梯不变）。 */
    private static int futiRoundGlobal(CommandContext<CommandSourceStack> context) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultRound(level, round);
        EscalatorSpeedManager.syncRoundToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultRound(level);
        source.sendSuccess(() -> Component.literal(
                "默认扶梯音效淡入淡出范围已设为 " + applied + " 格；"
                        + "没有单独设置过范围的扶梯都会用它（单独设置过的不受影响）"), false);
        return 1;
    }

    /** /futiround &lt;X&gt; to &lt;Y&gt; —— 默认范围正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiRoundFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getDefaultRound(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "默认扶梯音效淡入淡出范围不是 " + from + " 格（当前为 " + current + " 格），未做修改；"
                            + "单独设置过范围的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultRound(level, from, to);
        EscalatorSpeedManager.syncRoundToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultRound(level);
        source.sendSuccess(() -> Component.literal(
                "默认扶梯音效淡入淡出范围从 " + from + " 格改为 " + applied + " 格"), false);
        return 1;
    }

    /** /futiround -f &lt;范围&gt; —— 强制游戏内**所有**扶梯音效都用这个范围（清掉单独设置）。 */
    private static int futiRoundForceAll(CommandContext<CommandSourceStack> context) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = EscalatorSpeedManager.forceDefaultRound(level, round);
        EscalatorSpeedManager.syncRoundToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultRound(level);
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯音效的淡入淡出范围 = " + applied + " 格（清掉 " + cleared + " 处单独设置）"), false);
        return 1;
    }

    /** /futiround -f &lt;X&gt; to &lt;Y&gt; —— 把所有范围正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiRoundForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceRoundFromTo(level, from, to);
        EscalatorSpeedManager.syncRoundToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有扶梯音效淡入淡出范围正好是 " + from + " 格的扶梯，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有扶梯音效淡入淡出范围正好是 " + from + " 格的扶梯改成 " + to + " 格（共 " + changed + " 处）"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 【1.24】/futihelpround —— 无障碍提示音的淡入淡出范围（默认 4 格）
    //
    //   ★ 与 /futiround 是**两件事**：本指令管的是端头**单块**那块提示音（默认 4 格），
    //     /futiround 管的是整条扶梯一起响的运行底噪（默认 16 格）。数据与指令互不影响。
    // ------------------------------------------------------------------

    /** 注册 `/futihelpround` 的 `-f` 分支：`-f <范围>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpRoundForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("round", roundArg())
                        .executes(SmoothLift::futiHelpRoundForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", roundArg())
                                        .executes(SmoothLift::futiHelpRoundForceFromTo))));
    }

    /** /futihelpround（不带参数）—— 显示当前扶梯无障碍提示音的淡入淡出范围。 */
    private static int futiHelpRoundShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int global = EscalatorSpeedManager.getDefaultHelpRound(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认无障碍提示音淡入淡出范围：" + global + " 格"), false);
            return 1;
        }
        int round = EscalatorSpeedManager.getHelpRound(level, pos);
        boolean own = EscalatorSpeedManager.hasOwnHelpRound(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前无障碍提示音淡入淡出范围：" + round + " 格（这条扶梯，共 " + blocks + " 格，"
                        + (own ? "单独设置" : "使用默认范围")
                        + "）；默认范围 " + global + " 格"), false);
        return 1;
    }

    /** /futihelpround &lt;范围&gt; —— 设置**默认**范围（单独设置过的扶梯不变）。 */
    private static int futiHelpRoundGlobal(CommandContext<CommandSourceStack> context) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultHelpRound(level, round);
        EscalatorSpeedManager.syncHelpRoundToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpRound(level);
        source.sendSuccess(() -> Component.literal(
                "默认无障碍提示音淡入淡出范围已设为 " + applied + " 格；"
                        + "没有单独设置过范围的扶梯都会用它（单独设置过的不受影响）"), false);
        return 1;
    }

    /** /futihelpround &lt;X&gt; to &lt;Y&gt; —— 默认范围正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiHelpRoundFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getDefaultHelpRound(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "默认无障碍提示音淡入淡出范围不是 " + from + " 格（当前为 " + current + " 格），未做修改；"
                            + "单独设置过范围的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultHelpRound(level, from, to);
        EscalatorSpeedManager.syncHelpRoundToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpRound(level);
        source.sendSuccess(() -> Component.literal(
                "默认无障碍提示音淡入淡出范围从 " + from + " 格改为 " + applied + " 格"), false);
        return 1;
    }

    /** /futihelpround -f &lt;范围&gt; —— 强制游戏内**所有**提示音都用这个范围（清掉单独设置）。 */
    private static int futiHelpRoundForceAll(CommandContext<CommandSourceStack> context) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = EscalatorSpeedManager.forceDefaultHelpRound(level, round);
        EscalatorSpeedManager.syncHelpRoundToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultHelpRound(level);
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯无障碍提示音的淡入淡出范围 = " + applied + " 格（清掉 " + cleared + " 处单独设置）"), false);
        return 1;
    }

    /** /futihelpround -f &lt;X&gt; to &lt;Y&gt; —— 把所有范围正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpRoundForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceHelpRoundFromTo(level, from, to);
        EscalatorSpeedManager.syncHelpRoundToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有无障碍提示音淡入淡出范围正好是 " + from + " 格的扶梯，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有无障碍提示音淡入淡出范围正好是 " + from + " 格的扶梯改成 " + to + " 格（共 " + changed + " 处）"), false);
        return 1;
    }


    // ------------------------------------------------------------------
    // 【1.31】/futihelpspeed in|out <Hz>：无障碍提示音的**速率**（每秒响几次）
    // 【1.41】/futihelpmusic in|out <名字>：无障碍提示音的**音乐**（进 / 出各放各的）
    //
    // 与 /futiloud、/futihelpround 同一套写法（显示 / 设默认 / X to Y / -f）。
    // 指令树形状也刻意与 /futihelpspeed 完全一致（各 14 层），便于同时记两条。
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 【1.31】/futihelpspeed —— 无障碍提示音的**速率**（每秒响几次，单位 Hz）
    //
    //   与上面三个提示音指令凑成完整一套、互不影响：
    //     /futihelp      开关    -> 响不响
    //     /futihelploud  音量    -> 多响
    //     /futihelpround 范围    -> 多远还听得见
    //     本指令         速率    -> 响得多快
    //   ★ 与 /futiround（整条扶梯的运行底噪）是两件事：本指令只管端头**单块**那路提示音。
    //   ★ 有 `in`（进入扶梯 / 上客端）与 `out`（离开扶梯 / 落客端）两个子命令，各是一套数据。
    //   ★ 速率靠「换素材 + 调 pitch」实现（原版把 pitch 夹在 [0.5,2.0]），
    //     所以区间是 1~100 Hz（【1.34】上限由 20 提到 100），见 EscalatorSpeedData#HELP_SPEED_MAX。
    // ------------------------------------------------------------------

    /** /futihelpspeed 的速率参数：{@link EscalatorSpeedData#HELP_SPEED_MIN}~{@link EscalatorSpeedData#HELP_SPEED_MAX} Hz（每秒响几次）。 */
    private static IntegerArgumentType helpSpeedArg() {
        return IntegerArgumentType.integer(EscalatorSpeedData.HELP_SPEED_MIN, EscalatorSpeedData.HELP_SPEED_MAX);
    }

    /** 指令文案里用的端头名（/futihelpspeed 与 /futihelpmusic 共用）。 */
    public static String helpEndLabel(boolean in) {
        return in ? "进入扶梯（上客端）" : "离开扶梯（落客端）";
    }

    /** 注册 `/futihelpspeed` 的 `-f` 分支：`-f in|out <Hz>` 与 `-f in|out <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpSpeedForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("in")
                        .then(Commands.argument("hz", helpSpeedArg())
                                .executes(context -> futiHelpSpeedForceAll(context, true))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", helpSpeedArg())
                                                .executes(context -> futiHelpSpeedForceFromTo(context, true))))))
                .then(Commands.literal("out")
                        .then(Commands.argument("hz", helpSpeedArg())
                                .executes(context -> futiHelpSpeedForceAll(context, false))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", helpSpeedArg())
                                                .executes(context -> futiHelpSpeedForceFromTo(context, false))))));
    }

    /** /futihelpspeed（不带参数）—— 显示当前扶梯两头的无障碍提示音速率。 */
    private static int futiHelpSpeedShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int globalIn = EscalatorSpeedManager.getDefaultHelpSpeedIn(level);
        int globalOut = EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认无障碍提示音速率：进入扶梯 " + globalIn + " 次/秒、离开扶梯 "
                            + globalOut + " 次/秒"), false);
            return 1;
        }
        int in = EscalatorSpeedManager.getHelpSpeedIn(level, pos);
        int out = EscalatorSpeedManager.getHelpSpeedOut(level, pos);
        boolean ownIn = EscalatorSpeedManager.hasOwnHelpSpeedIn(level, pos);
        boolean ownOut = EscalatorSpeedManager.hasOwnHelpSpeedOut(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前无障碍提示音速率：进入扶梯 " + in + " 次/秒（" + (ownIn ? "单独设置" : "使用默认速率")
                        + "）、离开扶梯 " + out + " 次/秒（" + (ownOut ? "单独设置" : "使用默认速率")
                        + "）（这条扶梯，共 " + blocks + " 格）；默认 进入 " + globalIn + "、离开 "
                        + globalOut + " 次/秒"), false);
        return 1;
    }

    /** /futihelpspeed in|out &lt;Hz&gt; —— 设置**默认**速率（单独设置过的扶梯不变）。 */
    private static int futiHelpSpeedGlobal(CommandContext<CommandSourceStack> context, boolean in) {
        int hz = IntegerArgumentType.getInteger(context, "hz");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (in) {
            EscalatorSpeedManager.setDefaultHelpSpeedIn(level, hz);
        } else {
            EscalatorSpeedManager.setDefaultHelpSpeedOut(level, hz);
        }
        EscalatorSpeedManager.syncHelpSpeedToAll(source.getServer());
        int applied = in ? EscalatorSpeedManager.getDefaultHelpSpeedIn(level)
                : EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        source.sendSuccess(() -> Component.literal(
                "默认" + helpEndLabel(in) + "无障碍提示音速率已设为 " + applied + " 次/秒；"
                        + "没有单独设置过速率的扶梯都会用它（单独设置过的不受影响）"), false);
        return 1;
    }

    /** /futihelpspeed in|out &lt;X&gt; to &lt;Y&gt; —— 默认速率正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiHelpSpeedFromTo(CommandContext<CommandSourceStack> context, boolean in) {
        int from = IntegerArgumentType.getInteger(context, "hz");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = in ? EscalatorSpeedManager.getDefaultHelpSpeedIn(level)
                : EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "默认" + helpEndLabel(in) + "无障碍提示音速率不是 " + from + " 次/秒（当前为 " + current
                            + " 次/秒），未做修改；单独设置过速率的扶梯不受本指令影响"), false);
            return 0;
        }
        if (in) {
            EscalatorSpeedManager.replaceDefaultHelpSpeedIn(level, from, to);
        } else {
            EscalatorSpeedManager.replaceDefaultHelpSpeedOut(level, from, to);
        }
        EscalatorSpeedManager.syncHelpSpeedToAll(source.getServer());
        int applied = in ? EscalatorSpeedManager.getDefaultHelpSpeedIn(level)
                : EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        source.sendSuccess(() -> Component.literal(
                "默认" + helpEndLabel(in) + "无障碍提示音速率从 " + from + " 次/秒改为 " + applied + " 次/秒"), false);
        return 1;
    }

    /** /futihelpspeed -f in|out &lt;Hz&gt; —— 强制**所有**扶梯这一头都用该速率（清掉单独设置）。 */
    private static int futiHelpSpeedForceAll(CommandContext<CommandSourceStack> context, boolean in) {
        int hz = IntegerArgumentType.getInteger(context, "hz");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = in ? EscalatorSpeedManager.forceDefaultHelpSpeedIn(level, hz)
                : EscalatorSpeedManager.forceDefaultHelpSpeedOut(level, hz);
        EscalatorSpeedManager.syncHelpSpeedToAll(source.getServer());
        int applied = in ? EscalatorSpeedManager.getDefaultHelpSpeedIn(level)
                : EscalatorSpeedManager.getDefaultHelpSpeedOut(level);
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯" + helpEndLabel(in) + "的无障碍提示音速率 = " + applied + " 次/秒（清掉 "
                        + cleared + " 处单独设置）"), false);
        return 1;
    }

    /** /futihelpspeed -f in|out &lt;X&gt; to &lt;Y&gt; —— 把速率正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpSpeedForceFromTo(CommandContext<CommandSourceStack> context, boolean in) {
        int from = IntegerArgumentType.getInteger(context, "hz");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = in ? EscalatorSpeedManager.forceReplaceHelpSpeedInFromTo(level, from, to)
                : EscalatorSpeedManager.forceReplaceHelpSpeedOutFromTo(level, from, to);
        EscalatorSpeedManager.syncHelpSpeedToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有" + helpEndLabel(in) + "无障碍提示音速率正好是 " + from + " 次/秒的扶梯，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有" + helpEndLabel(in) + "无障碍提示音速率正好是 " + from + " 次/秒的扶梯改成 " + to
                        + " 次/秒（共 " + changed + " 处）"), false);
        return 1;
    }

    /** 提示音音乐 ID 在指令反馈里的显示名。 */
    private static String helpAudioLabel(String audioId) {
        if (audioId == null) {
            return "无（静音）";
        }
        if (EscalatorSpeedData.HELP_AUDIO_DEFAULT.equals(audioId)) {
            return "模组原来的提示音（default，速率见 /futihelpspeed）";
        }
        if (EscalatorSpeedData.HELP_AUDIO_OFF.equals(audioId)) {
            return "不播提示音（off）";
        }
        return audioLabel(audioId);
    }

    /** /futihelpmusic（不带参数）—— 显示这条扶梯**两头**当前用的无障碍提示音。 */
    private static int futiHelpMusicShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String defaultIn = EscalatorSpeedManager.getDefaultHelpAudio(level, true);
        String defaultOut = EscalatorSpeedManager.getDefaultHelpAudio(level, false);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认无障碍提示音：进入扶梯 " + helpAudioLabel(defaultIn)
                            + "、离开扶梯 " + helpAudioLabel(defaultOut)), false);
            return 1;
        }
        String inId = EscalatorSpeedManager.effectiveHelpAudioId(level, pos, true);
        String outId = EscalatorSpeedManager.effectiveHelpAudioId(level, pos, false);
        boolean ownIn = EscalatorSpeedManager.hasIndividualHelpAudio(level, pos, true);
        boolean ownOut = EscalatorSpeedManager.hasIndividualHelpAudio(level, pos, false);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯的无障碍提示音：进入扶梯 " + helpAudioLabel(inId)
                        + "（" + (ownIn ? "单独设置" : "使用默认") + "）、离开扶梯 " + helpAudioLabel(outId)
                        + "（" + (ownOut ? "单独设置" : "使用默认") + "）（这条扶梯，共 " + blocks + " 格；开关 "
                        + (EscalatorSpeedManager.isHelpEnabled(level, pos) ? "开" : "关") + "）"), false);
        return 1;
    }

    /** /futihelpmusic in|out &lt;名字&gt; —— 设置这一头的**默认**提示音（已单独设置的扶梯不变）。 */
    private static int futiHelpMusicSet(CommandContext<CommandSourceStack> context, boolean in) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveHelpAudioName(level, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal(arg.error()));
            return 0;
        }
        EscalatorSpeedManager.setDefaultHelpAudio(level, arg.id(), in);
        EscalatorSpeedManager.syncHelpAudioToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "默认" + helpEndLabel(in) + "的无障碍提示音已设为 " + helpAudioLabel(arg.id())
                        + "；没有单独设置过的扶梯都会用它（已单独设置的不受影响）"), false);
        return 1;
    }

    /** /futihelpmusic in|out &lt;X&gt; to &lt;Y&gt; —— 这一头默认提示音正好是 X 时才改成 Y（单独设置的按兵不动）。 */
    private static int futiHelpMusicFromTo(CommandContext<CommandSourceStack> context, boolean in) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveHelpAudioName(level, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal(from.error()));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveHelpAudioName(level, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal(to.error()));
            return 0;
        }
        String current = EscalatorSpeedManager.getDefaultHelpAudio(level, in);
        if (!java.util.Objects.equals(current, from.id())) {
            source.sendSuccess(() -> Component.literal(
                    "默认" + helpEndLabel(in) + "的无障碍提示音不是 " + helpAudioLabel(from.id())
                            + "，未做修改（当前为 " + helpAudioLabel(current)
                            + "）；已单独设置的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultHelpAudio(level, from.id(), to.id(), in);
        EscalatorSpeedManager.syncHelpAudioToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "默认" + helpEndLabel(in) + "的无障碍提示音从 " + helpAudioLabel(from.id()) + " 改为 "
                        + helpAudioLabel(to.id())), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /futihelpmusic （无障碍提示音「音乐」，数据模型与 /futimusic 完全对称）
    //
    // 与 /futimusic 的唯一语义差别：
    //   `default` = **模组原来的提示音**（五档「咔啪」素材 + /futihelpspeed 速率），
    //   而不是内置运行底噪；另外多一个 `off` = 这一头不播提示音。
    // 导入文件夹与音频库**与运行底噪共用**：导入一次，两边都能选。
    //
    // ★【1.41】有 `in`（进入扶梯 / 上客端）与 `out`（离开扶梯 / 落客端）两个子命令，
    //   各是一套**互不影响**的数据（形状与 /futihelpspeed 的 in|out 完全一致）：
    //     /futihelpmusic                       -> 显示这条扶梯两头当前的提示音
    //     /futihelpmusic in|out <名字>         -> 默认提示音 = 名字（已单独设置的扶梯不变）
    //     /futihelpmusic in|out <X> to <Y>     -> 默认提示音正好是 X 时才改成 Y
    //     /futihelpmusic -f in|out <名字>      -> 强制所有扶梯这一头都用它（清掉这一头的单独设置）
    //     /futihelpmusic -f in|out <X> to <Y>  -> 把这一头提示音为 X 的扶梯（含单独设置的）改成 Y
    //   ★ 所以「进站播一段、出站播另一端」不用改素材：给两头各设一段即可；
    //     连 `off` 都细到了单头 —— 可以只让某一头不响、另一头照常响。
    //   ★ 别把 in/out 挪到 -f 之外：与 /futihelpspeed 一样，in/out 在「带 -f」和「不带 -f」
    //     两层下各有一个，位置对齐才好记。
    // ------------------------------------------------------------------

    /** 注册 `-f` 分支：`-f in|out <名字>` 与 `-f in|out <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiHelpMusicForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("in")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> futiHelpMusicForceSet(context, true))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .executes(context -> futiHelpMusicForceFromTo(context, true))))))
                .then(Commands.literal("out")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> futiHelpMusicForceSet(context, false))
                                .then(Commands.literal("to")
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .executes(context -> futiHelpMusicForceFromTo(context, false))))));
    }

    /** /futihelpmusic -f in|out &lt;名字&gt; —— 强制游戏内**所有**扶梯这一头的提示音都用这一段。 */
    private static int futiHelpMusicForceSet(CommandContext<CommandSourceStack> context, boolean in) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveHelpAudioName(level, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal(arg.error()));
            return 0;
        }
        int cleared = EscalatorSpeedManager.forceDefaultHelpAudio(level, arg.id(), in);
        EscalatorSpeedManager.syncHelpAudioToAll(source.getServer());
        final int clearedCount = cleared;
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯" + helpEndLabel(in) + "的无障碍提示音为 " + helpAudioLabel(arg.id())
                        + "（清掉 " + clearedCount + " 处这一头的单独设置）"), false);
        return 1;
    }

    /** /futihelpmusic -f in|out &lt;X&gt; to &lt;Y&gt; —— 把这一头提示音为 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiHelpMusicForceFromTo(CommandContext<CommandSourceStack> context, boolean in) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveHelpAudioName(level, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal(from.error()));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveHelpAudioName(level, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal(to.error()));
            return 0;
        }
        int changed = EscalatorSpeedManager.forceReplaceHelpAudioFromTo(level, from.id(), to.id(), in);
        EscalatorSpeedManager.syncHelpAudioToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有" + helpEndLabel(in) + "提示音为 " + helpAudioLabel(from.id()) + " 的扶梯，未做修改"), false);
            return 0;
        }
        final int changedCount = changed;
        source.sendSuccess(() -> Component.literal(
                "已把所有" + helpEndLabel(in) + "提示音为 " + helpAudioLabel(from.id()) + " 的扶梯换成 "
                        + helpAudioLabel(to.id()) + "（共 " + changedCount + " 条）"), false);
        return 1;
    }


    /** 玩家进入游戏时同步全部数据（速度 + 音频 + 音量 + 提示音 + 范围），客户端还会主动请求一次。 */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            EscalatorSpeedManager.syncToAll(player.getServer());
            EscalatorSpeedManager.syncAudioToAll(player.getServer());
            EscalatorSpeedManager.syncVolumeToAll(player.getServer());
            // 【1.16/1.18/1.24】提示音开关 / 提示音音量 / 两个可闻范围
            EscalatorSpeedManager.syncHelpToAll(player.getServer());
            EscalatorSpeedManager.syncHelpVolumeToAll(player.getServer());
            EscalatorSpeedManager.syncRoundToAll(player.getServer());
            EscalatorSpeedManager.syncHelpRoundToAll(player.getServer());
            // 【1.31/1.41】提示音速率 / 提示音音乐（各一只小包）
            EscalatorSpeedManager.syncHelpSpeedToAll(player.getServer());
            EscalatorSpeedManager.syncHelpAudioToAll(player.getServer());
            // 【1.42/1.43/1.46/1.47/1.48】直梯开关门提示音（开关 + 倍速 + 音量 + 三子开关
            //   + 淡入淡出范围 + 三项各自音量）—— 同样要补发，否则刚进世界按默认值响。
            EscalatorSpeedManager.syncLiftChimeToAll(player.getServer());
            // 【1.45】直梯楼层轨道提示音（石斧右键楼层轨道设置的三列表）
            EscalatorSpeedManager.syncLiftToneToAll(player.getServer());
        }
    }

    /** 服务端启动时确保存档音频来源文件夹存在（没有就自动新建）。 */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        EscalatorSpeedManager.ensureAudioFolder(event.getServer().overworld());
    }

    /** 扶梯方块被破坏时清除对应记录（速度 / 阶梯速度 / 音量 / 音频绑定）。 */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!EscalatorUtil.isEscalator(event.getState())) {
            return;
        }
        // 有记录被清除才广播，避免拆没有配置过的扶梯也重发全量同步包。
        if (!EscalatorSpeedManager.removeSpeed(level, event.getPos())) {
            return;
        }
        // 速度 / 音频绑定 / 底噪音量 / 提示音开关 / 提示音音量 / 两个可闻范围 / 提示音速率
        // 八类数据都要同步，否则客户端会残留旧的速度与声音。
        EscalatorSpeedManager.syncToAll(level.getServer());
        EscalatorSpeedManager.syncAudioToAll(level.getServer());
        EscalatorSpeedManager.syncVolumeToAll(level.getServer());
        EscalatorSpeedManager.syncHelpToAll(level.getServer());
        EscalatorSpeedManager.syncHelpVolumeToAll(level.getServer());
        EscalatorSpeedManager.syncRoundToAll(level.getServer());
        EscalatorSpeedManager.syncHelpRoundToAll(level.getServer());
        EscalatorSpeedManager.syncHelpSpeedToAll(level.getServer());
    }

    // ------------------------------------------------------------------
    // /futispeed
    // ------------------------------------------------------------------

    /**
     * /futispeed（不带参数）—— 显示当前扶梯速度。
     *
     * <p>能定位到「玩家当前所在的扶梯」（脚下/身上 → 准星 → 附近最近）就显示那一条的速度，
     * 并标出它是单独设置还是跟随全局；否则显示全局默认值。
     */
    private static int futiShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        double global = EscalatorSpeedManager.getGlobalRunSpeed(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "当前扶梯速度：" + EscalatorSpeedData.format(global) + " 格/秒（全局默认）"), false);
            return 1;
        }
        double speed = EscalatorSpeedManager.getSpeed(level, pos);
        boolean individual = EscalatorSpeedManager.getIndividualRunSpeed(level, pos) != null;
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯速度：" + EscalatorSpeedData.format(speed) + " 格/秒（这条扶梯，共 " + blocks + " 格，"
                        + (individual ? "单独设置" : "跟随全局")
                        + "）；全局默认 " + EscalatorSpeedData.format(global) + " 格/秒"), false);
        return 1;
    }

    /** 注册 `-f` 分支：`-f X` 与 `-f X to Y`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                        .executes(SmoothLift::futiForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                                        .executes(SmoothLift::futiForceFromTo))));
    }

    /** /futispeed X —— 只改全局扶梯的运行速度（阶梯速度一起跟随）。 */
    private static int futiGlobal(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setGlobalRunSpeed(level, speed);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("全局扶梯速度改为" + EscalatorSpeedData.format(speed)),
                false);
        return 1;
    }

    /** /futispeed X to Y —— 只有当前全局运行速度正好是 X 时才改成 Y。 */
    private static int futiFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        double current = EscalatorSpeedManager.getGlobalRunSpeed(level);
        if (!EscalatorSpeedManager.same(current, from)) {
            source.sendSuccess(
                    () -> Component.literal("全局扶梯速度没有" + EscalatorSpeedData.format(from)
                            + "，未做修改（当前为 " + EscalatorSpeedData.format(current) + "）"),
                    false);
            return 0;
        }
        EscalatorSpeedManager.setGlobalRunSpeed(level, to);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("全局扶梯速度从" + EscalatorSpeedData.format(from)
                        + "改为" + EscalatorSpeedData.format(to)),
                false);
        return 1;
    }

    /** /futispeed -f X —— 强制游戏内所有扶梯运行速度 = X。 */
    private static int futiForceAll(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.forceGlobalRunSpeed(level, speed);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("所有扶梯速度改为" + EscalatorSpeedData.format(speed)),
                false);
        return 1;
    }

    /** /futispeed -f X to Y —— 把所有运行速度为 X 的扶梯改成 Y。 */
    private static int futiForceFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean globalMatched = EscalatorSpeedManager.same(EscalatorSpeedManager.getGlobalRunSpeed(level), from);
        int changed = EscalatorSpeedManager.forceRunFromTo(level, from, to);
        EscalatorSpeedManager.syncToAll(source.getServer());
        if (!globalMatched && changed == 0) {
            source.sendSuccess(
                    () -> Component.literal("没有速度为" + EscalatorSpeedData.format(from) + "的扶梯，未做修改"),
                    false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal("所有扶梯速度从" + EscalatorSpeedData.format(from)
                        + "改为" + EscalatorSpeedData.format(to)),
                false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /jietispeed （只动阶梯速度，绝不动运行速度）
    // ------------------------------------------------------------------

    /**
     * /jietispeed（不带参数）—— 显示当前阶梯速度。
     * 与 {@link #futiShow} 同一套「当前扶梯」定位规则。
     */
    private static int jietiShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean enabled = EscalatorSpeedManager.isStepEnabled(level);
        double global = EscalatorSpeedManager.getGlobalStepSpeed(level);
        String globalNote = enabled
                ? "全局阶梯速度 " + EscalatorSpeedData.format(global) + " 格/秒"
                : "全局阶梯速度未单独设置，跟随扶梯速度（" + EscalatorSpeedData.format(global) + " 格/秒）";
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal("当前阶梯速度：" + globalNote), false);
            return 1;
        }
        double step = EscalatorSpeedManager.getAnimationSpeed(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前阶梯速度：" + EscalatorSpeedData.format(step) + " 格/秒（这条扶梯，共 " + blocks + " 格）；"
                        + globalNote), false);
        return 1;
    }

    /** 注册 `-f` 分支：`-f X` 与 `-f X to Y`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> jietiForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("speed", FloatArgumentType.floatArg(0.0f))
                        .executes(SmoothLift::jietiForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", FloatArgumentType.floatArg(0.0f))
                                        .executes(SmoothLift::jietiForceFromTo))));
    }

    /** /jietispeed X —— 只改全局扶梯的阶梯速度。 */
    private static int jietiGlobal(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setGlobalStepSpeed(level, speed);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("全局扶梯阶梯速度改为" + EscalatorSpeedData.format(speed)),
                false);
        return 1;
    }

    /** /jietispeed X to Y —— 只有当前全局阶梯速度正好是 X 时才改成 Y。 */
    private static int jietiFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        double current = EscalatorSpeedManager.getGlobalStepSpeed(level);
        if (!EscalatorSpeedManager.same(current, from)) {
            source.sendSuccess(
                    () -> Component.literal("全局扶梯阶梯速度没有" + EscalatorSpeedData.format(from)
                            + "，未做修改（当前为 " + EscalatorSpeedData.format(current) + "）"),
                    false);
            return 0;
        }
        EscalatorSpeedManager.setGlobalStepSpeed(level, to);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("全局扶梯阶梯速度从" + EscalatorSpeedData.format(from)
                        + "改为" + EscalatorSpeedData.format(to)),
                false);
        return 1;
    }

    /** /jietispeed -f X —— 强制所有扶梯阶梯速度 = X。 */
    private static int jietiForceAll(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.forceGlobalStepSpeed(level, speed);
        EscalatorSpeedManager.syncToAll(source.getServer());
        source.sendSuccess(
                () -> Component.literal("所有扶梯阶梯速度改为" + EscalatorSpeedData.format(speed)),
                false);
        return 1;
    }

    /** /jietispeed -f X to Y —— 把所有阶梯速度为 X 的扶梯改成 Y。 */
    private static int jietiForceFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean globalMatched = EscalatorSpeedManager.isStepEnabled(level)
                && EscalatorSpeedManager.same(EscalatorSpeedManager.getStepValue(level), from);
        int changed = EscalatorSpeedManager.forceStepFromTo(level, from, to);
        EscalatorSpeedManager.syncToAll(source.getServer());
        if (!globalMatched && changed == 0) {
            source.sendSuccess(
                    () -> Component.literal("没有阶梯速度为" + EscalatorSpeedData.format(from) + "的扶梯，未做修改"),
                    false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal("所有扶梯阶梯速度从" + EscalatorSpeedData.format(from)
                        + "改为" + EscalatorSpeedData.format(to)),
                false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /futimusic （扶梯音频，数据模型与 /futispeed 完全对称）
    // ------------------------------------------------------------------

    /** 注册 `-f` 分支：`-f <名字>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiMusicForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("name", StringArgumentType.string())
                        .executes(SmoothLift::futiMusicForceSet)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", StringArgumentType.string())
                                        .executes(SmoothLift::futiMusicForceFromTo))));
    }

    /** 音频 ID 在指令反馈里的显示名：内置音频显示中文名 + ID，玩家上传的直接显示文件名。 */
    private static String audioLabel(String audioId) {
        if (audioId == null) {
            return "无（静音）";
        }
        String name = EscalatorSpeedManager.displayName(audioId);
        return name.equals(audioId) ? audioId : name + "（" + audioId + "）";
    }

    /** /futimusic（不带参数）—— 显示当前扶梯播放的音频名。 */
    private static int futiMusicShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        String defaultAudio = EscalatorSpeedManager.getDefaultAudio(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认扶梯音频：" + audioLabel(defaultAudio)), false);
            return 1;
        }
        String id = EscalatorSpeedManager.effectiveAudioId(level, pos);
        boolean individual = EscalatorSpeedManager.hasIndividualAudio(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        final boolean own = individual;
        if (id == null) {
            source.sendSuccess(() -> Component.literal(
                    "当前扶梯（共 " + blocks + " 格）没有音频：没有单独绑定，也没有设置默认音频"), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "当前扶梯音频：" + audioLabel(id) + "（这条扶梯，共 " + blocks + " 格，"
                            + (own ? "单独绑定" : "使用默认音频") + "）"), false);
        }
        return 1;
    }

    /** /futimusic &lt;名字&gt; —— 设置**默认**扶梯音频（已单独绑定音频的扶梯不变）。 */
    private static int futiMusicSet(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveAudioName(level, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal(arg.error()));
            return 0;
        }
        EscalatorSpeedManager.setDefaultAudio(level, arg.id());
        EscalatorSpeedManager.syncAudioToAll(source.getServer());
        if (arg.off()) {
            source.sendSuccess(() -> Component.literal(
                    "已清除默认扶梯音频：没有单独绑定音频的扶梯将静音（已单独绑定的不受影响）"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                "默认扶梯音频已设为 " + audioLabel(arg.id())
                        + "；没有单独绑定音频的扶梯都会播放它（已单独绑定的不受影响）"), false);
        return 1;
    }

    /** /futimusic &lt;X&gt; to &lt;Y&gt; —— 默认音频正好是 X 时才改成 Y（单独绑定的不动）。 */
    private static int futiMusicFromTo(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveAudioName(level, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal(from.error()));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveAudioName(level, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal(to.error()));
            return 0;
        }
        String current = EscalatorSpeedManager.getDefaultAudio(level);
        if (!java.util.Objects.equals(current, from.id())) {
            source.sendSuccess(() -> Component.literal(
                    "默认扶梯音频不是 " + audioLabel(from.id()) + "，未做修改（当前为 "
                            + audioLabel(current) + "）；已单独绑定音频的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultAudio(level, from.id(), to.id());
        EscalatorSpeedManager.syncAudioToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "默认扶梯音频从 " + audioLabel(from.id()) + " 改为 " + audioLabel(to.id())), false);
        return 1;
    }

    /** /futimusic -f &lt;名字&gt; —— 强制游戏内**所有**扶梯都用这个音频。 */
    private static int futiMusicForceSet(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveAudioName(level, name);
        if (!arg.ok()) {
            source.sendFailure(Component.literal(arg.error()));
            return 0;
        }
        int cleared = EscalatorSpeedManager.forceDefaultAudio(level, arg.id());
        EscalatorSpeedManager.syncAudioToAll(source.getServer());
        final int clearedCount = cleared;
        if (arg.off()) {
            source.sendSuccess(() -> Component.literal(
                    "已强制所有扶梯静音（清掉 " + clearedCount + " 处单独绑定）"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯播放 " + audioLabel(arg.id()) + "（清掉 " + clearedCount + " 处单独绑定）"), false);
        return 1;
    }

    /** /futimusic -f &lt;X&gt; to &lt;Y&gt; —— 把所有音频为 X 的扶梯（含单独绑定的）改成 Y。 */
    private static int futiMusicForceFromTo(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "name");
        String targetName = StringArgumentType.getString(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveAudioName(level, name);
        if (!from.ok()) {
            source.sendFailure(Component.literal(from.error()));
            return 0;
        }
        EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveAudioName(level, targetName);
        if (!to.ok()) {
            source.sendFailure(Component.literal(to.error()));
            return 0;
        }
        int changed = EscalatorSpeedManager.forceReplaceAudioFromTo(level, from.id(), to.id());
        EscalatorSpeedManager.syncAudioToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有音频为 " + audioLabel(from.id()) + " 的扶梯，未做修改"), false);
            return 0;
        }
        final int changedCount = changed;
        source.sendSuccess(() -> Component.literal(
                "已把所有音频为 " + audioLabel(from.id()) + " 的扶梯换成 "
                        + audioLabel(to.id()) + "（共 " + changedCount + " 条）"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // /futiloud （扶梯音量，数据模型与 /futispeed、/futimusic 完全对称）
    // ------------------------------------------------------------------

    /** /futiloud 的音量参数：1~1000（100 = 原始音量，1000 = 10× 放大）。 */
    private static IntegerArgumentType volumeArg() {
        return IntegerArgumentType.integer(EscalatorSpeedData.AUDIO_VOLUME_MIN,
                EscalatorSpeedData.AUDIO_VOLUME_MAX);
    }

    /** 注册 `-f` 分支：`-f <音量>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> futiLoudForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(SmoothLift::futiLoudForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(SmoothLift::futiLoudForceFromTo))));
    }

    /** /futiloud（不带参数）—— 显示当前扶梯音量。 */
    private static int futiLoudShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int global = EscalatorSpeedManager.getDefaultVolume(level);
        ServerPlayer player = source.getPlayer();
        BlockPos pos = player == null ? null : EscalatorSpeedManager.currentEscalator(player);
        if (pos == null) {
            source.sendSuccess(() -> Component.literal(
                    "没有站在扶梯上。默认扶梯音量：" + global + "（100 = 原始音量）"), false);
            return 1;
        }
        int volume = EscalatorSpeedManager.getVolume(level, pos);
        boolean individual = EscalatorSpeedManager.hasIndividualVolume(level, pos);
        int blocks = EscalatorUtil.countChainSteps(level, pos);
        source.sendSuccess(() -> Component.literal(
                "当前扶梯音量：" + volume + "（这条扶梯，共 " + blocks + " 格，"
                        + (individual ? "单独设置" : "使用默认音量")
                        + "）；默认音量 " + global), false);
        return 1;
    }

    /** /futiloud &lt;音量&gt; —— 设置**默认**扶梯音量（单独设置过音量的扶梯不变）。 */
    private static int futiLoudGlobal(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultVolume(level, volume);
        EscalatorSpeedManager.syncVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultVolume(level);
        source.sendSuccess(() -> Component.literal(
                "默认扶梯音量已设为 " + applied + "（100 = 原始音量）；"
                        + "没有单独设置过音量的扶梯都会用它（单独设置过的不受影响）"), false);
        return 1;
    }

    /** /futiloud &lt;X&gt; to &lt;Y&gt; —— 默认音量正好是 X 时才改成 Y（单独设置的不动）。 */
    private static int futiLoudFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getDefaultVolume(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "默认扶梯音量不是 " + from + "（当前为 " + current + "），未做修改；"
                            + "单独设置过音量的扶梯不受本指令影响"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultVolume(level, from, to);
        EscalatorSpeedManager.syncVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultVolume(level);
        source.sendSuccess(() -> Component.literal(
                "默认扶梯音量从 " + from + " 改为 " + applied), false);
        return 1;
    }

    /** /futiloud -f &lt;音量&gt; —— 强制游戏内**所有**扶梯都用这个音量（清掉单独设置）。 */
    private static int futiLoudForceAll(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int cleared = EscalatorSpeedManager.forceDefaultVolume(level, volume);
        EscalatorSpeedManager.syncVolumeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getDefaultVolume(level);
        source.sendSuccess(() -> Component.literal(
                "已强制所有扶梯音量 = " + applied + "（清掉 " + cleared + " 处单独设置）"), false);
        return 1;
    }

    /** /futiloud -f &lt;X&gt; to &lt;Y&gt; —— 把所有音量正好是 X 的扶梯（含单独设置的）改成 Y。 */
    private static int futiLoudForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int changed = EscalatorSpeedManager.forceReplaceVolumeFromTo(level, from, to);
        EscalatorSpeedManager.syncVolumeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有音量正好是 " + from + " 的扶梯，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有音量正好是 " + from + " 的扶梯改成 " + to + "（共 " + changed + " 处）"), false);
        return 1;
    }
    // ==================================================================
    // 【1.45】直梯提示音的两端共用判据 / 文案工具
    //
    //   ★ 这些必须是 public static：不仅指令用，{@code smooth.lift.network} 里那几只
    //     直梯提示音数据包（SetLiftTonePacket / ImportFolderLiftTonePacket）也要用
    //     —— 判据/文案只此一份，别在数据包里再抄一遍（抄一份就有走样的风险）。
    // ==================================================================

    /**
     * 【1.45】判定：方块注册名以 {@code lift_track_floor} 开头（MTR3 / MTR4 的「楼层轨道」
     * 注册名都是这个前缀，竖轨 {@code lift_track_vertical_*} 不会误命中）。
     * 服务端和客户端共用这一个判据（石斧右键时两侧都要拦默认交互）。
     */
    public static boolean isLiftTrackFloor(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.startsWith("lift_track_floor");
    }

    /**
     * 【1.45】直梯提示音三项的中文名（石斧界面 / 指令反馈共用同一套词）。
     * {@code which} 不是 up/down/chime → 返回「提示音」兜底。
     */
    public static String liftToneLabel(String which, String audioId) {
        return switch (which) {
            case "up" -> "上楼提示音";
            case "down" -> "下楼提示音";
            case "chime" -> "开关门提示音";
            default -> "提示音";
        };
    }

    /** 【1.45】反馈文案里的名字截断（界面 / 指令都用 20 字符上限），过长直接截。 */
    public static String truncateForMsg(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }

    // ==================================================================
    // 【1.42】直梯（Lift）开关门提示音 liftmusic.ogg
    //
    //   /lifthelp        显示 / on|off / on to off / -f on|off / -f on to off
    //   /lifthelpspeed   显示 / <倍速> / <X> to <Y> / -f <倍速> / -f <X> to <Y>
    //
    //   ★ 为什么是 lifthelp / lifthelpspeed 而不是需求原文里的 futihelp / futihelpspeed：
    //     那两条指令**早就存在**、管的是**扶梯**的无障碍提示音（进/出口「咔啪」声），
    //     直接复用会把扶梯那套设置顶掉。直梯提示音是完全另一件事，所以另开两条指令，
    //     扶梯的 futihelp / futihelpspeed 一个字节都不动。
    //
    //   ★ 直梯提示音只有「维度默认」一层数据（没有单条直梯的单独设置），所以 `-f`
    //     取「**对所有维度**强制」的含义 —— 见 EscalatorSpeedManager 里那一段的说明。
    // ==================================================================

    /** 直梯提示音倍速的取值参数（0.5~2.0，允许小数；上限来自原版 pitch 夹取，见 EscalatorSpeedData）。 */
    private static FloatArgumentType liftHelpSpeedArg() {
        return FloatArgumentType.floatArg(
                EscalatorSpeedData.LIFT_HELP_SPEED_MIN, EscalatorSpeedData.LIFT_HELP_SPEED_MAX);
    }

    /** 倍速在指令反馈里的显示（两位小数就够，1.0 显示成 1）。 */
    private static String liftSpeedLabel(float speed) {
        if (speed == Math.round(speed)) {
            return String.valueOf((int) Math.round(speed));
        }
        return String.format(Locale.ROOT, "%.2f", speed);
    }

    /** /lifthelp（不带参数）—— 显示当前维度生效的直梯提示音开关。 */
    private static int liftHelpShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean enabled = EscalatorSpeedManager.isLiftHelpEnabled(level);
        float speed = EscalatorSpeedManager.getLiftHelpSpeed(level);
        source.sendSuccess(() -> Component.literal(
                "当前维度直梯开关门提示音：" + helpLabel(enabled) + "，倍速 " + liftSpeedLabel(speed)
                        + "（关门连播 " + EscalatorSpeedData.LIFT_HELP_CLOSE_REPEATS + " 次、开门连播 "
                        + EscalatorSpeedData.LIFT_HELP_OPEN_REPEATS + " 次；倍速范围 "
                        + liftSpeedLabel(EscalatorSpeedData.LIFT_HELP_SPEED_MIN) + "~"
                        + liftSpeedLabel(EscalatorSpeedData.LIFT_HELP_SPEED_MAX) + "）"), false);
        return 1;
    }

    /** /lifthelp &lt;on|off&gt; —— 设置**本维度**的开关。 */
    private static int liftHelpGlobal(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftHelp(level, enabled);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度（" + level.dimension().location() + "）直梯开关门提示音已设为 " + helpLabel(enabled)
                        + "；其它维度不变（要对所有维度生效用 /lifthelp -f " + (enabled ? "on" : "off") + "）"), false);
        return 1;
    }

    /** /lifthelp &lt;X&gt; to &lt;Y&gt; —— 本维度开关正好是 X 时才改成 Y。 */
    private static int liftHelpFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultLiftHelp(level, from, to)) {
            boolean current = EscalatorSpeedManager.isLiftHelpEnabled(level);
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯开关门提示音不是 " + helpLabel(from) + "（当前为 " + helpLabel(current)
                            + "），未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度直梯开关门提示音从 " + helpLabel(from) + " 改为 " + helpLabel(to)), false);
        return 1;
    }

    /** /lifthelp -f &lt;on|off&gt; —— **所有维度**都设成该开关。 */
    private static int liftHelpForceAll(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftHelpAll(source.getServer(), enabled);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯开关门提示音 = " + helpLabel(enabled) + "（改动 " + changed + " 个维度）"), false);
        return 1;
    }

    /** /lifthelp -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里开关正好是 X 的那些改成 Y。 */
    private static int liftHelpForceFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftHelpAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的直梯开关门提示音是 " + helpLabel(from) + "，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有直梯开关门提示音为 " + helpLabel(from) + " 的维度改成 " + helpLabel(to)
                        + "（共 " + changed + " 个维度）"), false);
        return 1;
    }

    /** /lifthelpspeed（不带参数）—— 显示当前维度生效的倍速。 */
    private static int liftHelpSpeedShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        float speed = EscalatorSpeedManager.getLiftHelpSpeed(level);
        boolean enabled = EscalatorSpeedManager.isLiftHelpEnabled(level);
        source.sendSuccess(() -> Component.literal(
                "当前维度直梯提示音倍速：" + liftSpeedLabel(speed) + "（提示音：" + helpLabel(enabled)
                        + "；范围 " + liftSpeedLabel(EscalatorSpeedData.LIFT_HELP_SPEED_MIN) + "~"
                        + liftSpeedLabel(EscalatorSpeedData.LIFT_HELP_SPEED_MAX)
                        + "，1 = 原速。上限 2 是原版对播放速率的硬夹取）"), false);
        return 1;
    }

    /** /lifthelpspeed &lt;倍速&gt; —— 设置**本维度**的倍速。 */
    private static int liftHelpSpeedGlobal(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftHelpSpeed(level, speed);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        float applied = EscalatorSpeedManager.getLiftHelpSpeed(level);
        source.sendSuccess(() -> Component.literal(
                "本维度（" + level.dimension().location() + "）直梯提示音倍速已设为 "
                        + liftSpeedLabel(applied) + "；其它维度不变"), false);
        return 1;
    }

    /** /lifthelpspeed &lt;X&gt; to &lt;Y&gt; —— 本维度倍速正好是 X 时才改成 Y。 */
    private static int liftHelpSpeedFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultLiftHelpSpeed(level, from, to)) {
            float current = EscalatorSpeedManager.getLiftHelpSpeed(level);
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯提示音倍速不是 " + liftSpeedLabel(from) + "（当前为 "
                            + liftSpeedLabel(current) + "），未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        float applied = EscalatorSpeedManager.getLiftHelpSpeed(level);
        source.sendSuccess(() -> Component.literal(
                "本维度直梯提示音倍速从 " + liftSpeedLabel(from) + " 改为 "
                        + liftSpeedLabel(applied)), false);
        return 1;
    }

    /** /lifthelpspeed -f &lt;倍速&gt; —— **所有维度**都设成该倍速。 */
    private static int liftHelpSpeedForceAll(CommandContext<CommandSourceStack> context) {
        float speed = FloatArgumentType.getFloat(context, "speed");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftHelpSpeedAll(source.getServer(), speed);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        float applied = EscalatorSpeedData.clampLiftHelpSpeed(speed);
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯提示音倍速 = " + liftSpeedLabel(applied)
                        + "（改动 " + changed + " 个维度）"), false);
        return 1;
    }

    /** /lifthelpspeed -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里倍速正好是 X 的那些改成 Y。 */
    private static int liftHelpSpeedForceFromTo(CommandContext<CommandSourceStack> context) {
        float from = FloatArgumentType.getFloat(context, "speed");
        float to = FloatArgumentType.getFloat(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftHelpSpeedAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的直梯提示音倍速是 " + liftSpeedLabel(from) + "，未做修改"), false);
            return 0;
        }
        float applied = EscalatorSpeedData.clampLiftHelpSpeed(to);
        source.sendSuccess(() -> Component.literal(
                "已把所有直梯提示音倍速为 " + liftSpeedLabel(from) + " 的维度改成 " + liftSpeedLabel(applied)
                        + "（共 " + changed + " 个维度）"), false);
        return 1;
    }

    /** /lifthelploud（不带参数）—— 显示当前维度生效的直梯提示音音量。 */
    private static int liftHelpLoudShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int volume = EscalatorSpeedManager.getLiftHelpVolume(level);
        boolean enabled = EscalatorSpeedManager.isLiftHelpEnabled(level);
        source.sendSuccess(() -> Component.literal(
                "当前维度直梯提示音音量：" + volume + "（提示音：" + helpLabel(enabled)
                        + "；范围 " + EscalatorSpeedData.HELP_VOLUME_MIN + "~"
                        + EscalatorSpeedData.HELP_VOLUME_MAX
                        + "，100 = 原始音量、1000 = 10× 放大）"), false);
        return 1;
    }

    /** /lifthelploud &lt;音量&gt; —— 设置**本维度**的音量。 */
    private static int liftHelpLoudGlobal(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftHelpVolume(level, volume);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftHelpVolume(level);
        source.sendSuccess(() -> Component.literal(
                "本维度（" + level.dimension().location() + "）直梯提示音音量已设为 " + applied
                        + "（100 = 原始音量）；其它维度不变（要对所有维度生效用 /lifthelploud -f "
                        + applied + "）"), false);
        return 1;
    }

    /** /lifthelploud &lt;X&gt; to &lt;Y&gt; —— 本维度音量正好是 X 时才改成 Y。 */
    private static int liftHelpLoudFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultLiftHelpVolume(level, from, to)) {
            int current = EscalatorSpeedManager.getLiftHelpVolume(level);
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯提示音音量不是 " + from + "（当前为 " + current + "），未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftHelpVolume(level);
        source.sendSuccess(() -> Component.literal(
                "本维度直梯提示音音量从 " + from + " 改为 " + applied), false);
        return 1;
    }

    /** /lifthelploud -f &lt;音量&gt; —— **所有维度**都设成该音量。 */
    private static int liftHelpLoudForceAll(CommandContext<CommandSourceStack> context) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftHelpVolumeAll(source.getServer(), volume);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedData.clampLiftHelpVolume(volume);
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯提示音音量 = " + applied + "（改动 " + changed + " 个维度）"), false);
        return 1;
    }

    /** /lifthelploud -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里音量正好是 X 的那些改成 Y。 */
    private static int liftHelpLoudForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftHelpVolumeAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的直梯提示音音量是 " + from + "，未做修改"), false);
            return 0;
        }
        int applied = EscalatorSpeedData.clampLiftHelpVolume(to);
        source.sendSuccess(() -> Component.literal(
                "已把所有直梯提示音音量为 " + from + " 的维度改成 " + applied
                        + "（共 " + changed + " 个维度）"), false);
        return 1;
    }

    /** 注册 `/lifthelploud` 的 `-f` 分支：`-f <音量>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftHelpLoudForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(SmoothLift::liftHelpLoudForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(SmoothLift::liftHelpLoudForceFromTo))));
    }

    // ------------------------------------------------------------------
    // 【1.48】/lifthelploud up|down|door：三项提示音**各自的**音量
    //   up = 上楼 / down = 下楼 / door = 开关门（chime 的别名）。
    //   形状与「共用默认音量」一致（<音量> / <X> to <Y> / -f <音量> / -f <X> to <Y>），
    //   只是改的是对应那一项；没单独调过的项跟随共用默认。
    // ------------------------------------------------------------------

    /** 注册某一项（up/down/chime）的音量子命令树。{@code literal} = 界面用名，{@code which} = 数据用名。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftToneLoudCommand(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> liftToneLoudGlobal(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> liftToneLoudFromTo(context, which)))));
    }

    /** 【1.48】`-f` 节点下的单项分支：{@code up|down|door <音量>} 与 {@code up|down|door <X> to <Y>}。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftToneLoudForceBranch(String literal, String which) {
        return Commands.literal(literal)
                .then(Commands.argument("volume", volumeArg())
                        .executes(context -> liftToneLoudForceAll(context, which))
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", volumeArg())
                                        .executes(context -> liftToneLoudForceFromTo(context, which)))));
    }

    /** /lifthelploud up|down|door &lt;音量&gt; —— 设置**本维度**这一项的音量。 */
    private static int liftToneLoudGlobal(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftToneVolume(level, which, volume);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftToneVolume(level, which);
        source.sendSuccess(() -> Component.literal(
                "本维度（" + level.dimension().location() + "）直梯"
                        + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量已设为 " + applied
                        + "（100 = 原始音量；其它维度不变，要对所有维度生效用 /lifthelploud "
                        + literalName(which) + " -f " + applied + "）"), false);
        return 1;
    }

    /** /lifthelploud up|down|door &lt;X&gt; to &lt;Y&gt; —— 本维度这一项音量正好是 X 时才改成 Y。 */
    private static int liftToneLoudFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getLiftToneVolume(level, which);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量不是 " + from
                            + "（当前为 " + current + "），未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultLiftToneVolume(level, which, from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftToneVolume(level, which);
        source.sendSuccess(() -> Component.literal(
                "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量从 " + from
                        + " 改为 " + applied), false);
        return 1;
    }

    /** /lifthelploud -f up|down|door &lt;音量&gt; —— **所有维度**这一项都设成该音量。 */
    private static int liftToneLoudForceAll(CommandContext<CommandSourceStack> context, String which) {
        int volume = IntegerArgumentType.getInteger(context, "volume");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftToneVolumeAll(source.getServer(), which, volume);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedData.clampLiftToneVolume(volume);
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量 = "
                        + applied + "（改动 " + changed + " 个维度）"), false);
        return 1;
    }

    /** /lifthelploud -f up|down|door &lt;X&gt; to &lt;Y&gt; —— 所有维度里这一项音量正好是 X 的改成 Y。 */
    private static int liftToneLoudForceFromTo(CommandContext<CommandSourceStack> context, String which) {
        int from = IntegerArgumentType.getInteger(context, "volume");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftToneVolumeAll(source.getServer(), which, from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量是 "
                            + from + "，未做修改"), false);
            return 0;
        }
        int applied = EscalatorSpeedData.clampLiftToneVolume(to);
        source.sendSuccess(() -> Component.literal(
                "已把所有直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "音量为 " + from
                        + " 的维度改成 " + applied + "（共 " + changed + " 个维度）"), false);
        return 1;
    }

    /** 【1.48】数据用名（up/down/chime）→ 指令里的字面名（door 是 chime 的别名）。 */
    private static String literalName(String which) {
        return switch (which) {
            case "chime" -> "door";
            default -> which;
        };
    }

    // ------------------------------------------------------------------
    // 【1.47】/lifthelpround：直梯提示音（三项共用）淡入淡出范围
    // ------------------------------------------------------------------

    /** 注册 `/lifthelpround` 的 `-f` 分支：`-f <范围>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftHelpRoundForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("round", roundArg())
                        .executes(SmoothLift::liftHelpRoundForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", roundArg())
                                        .executes(SmoothLift::liftHelpRoundForceFromTo))));
    }

    /** /lifthelpround（不带参数）—— 显示当前维度生效的淡入淡出范围。 */
    private static int liftHelpRoundShow(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int round = EscalatorSpeedManager.getLiftHelpRound(level);
        source.sendSuccess(() -> Component.literal(
                "当前维度直梯提示音（上楼 / 下楼 / 开关门共用）淡入淡出范围：" + round + " 格"
                        + "（范围 "
                        + EscalatorSpeedData.LIFT_HELP_ROUND_MIN + "~"
                        + EscalatorSpeedData.LIFT_HELP_ROUND_MAX + "）"), false);
        return 1;
    }

    /** /lifthelpround &lt;范围&gt; —— 设置**本维度**的范围。 */
    private static int liftHelpRoundGlobal(CommandContext<CommandSourceStack> context) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftHelpRound(level, round);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftHelpRound(level);
        source.sendSuccess(() -> Component.literal(
                "本维度（" + level.dimension().location() + "）直梯提示音淡入淡出范围已设为 " + applied + " 格；"
                        + "其它维度不变（要对所有维度生效用 /lifthelpround -f " + applied + "）"), false);
        return 1;
    }

    /** /lifthelpround &lt;X&gt; to &lt;Y&gt; —— 本维度范围正好是 X 时才改成 Y。 */
    private static int liftHelpRoundFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        int current = EscalatorSpeedManager.getLiftHelpRound(level);
        if (current != from) {
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯提示音淡入淡出范围不是 " + from + " 格（当前为 " + current + " 格），未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.replaceDefaultLiftHelpRound(level, from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftHelpRound(level);
        source.sendSuccess(() -> Component.literal(
                "本维度直梯提示音淡入淡出范围从 " + from + " 格改为 " + applied + " 格"), false);
        return 1;
    }

    /** /lifthelpround -f &lt;范围&gt; —— **所有维度**都设成该范围。 */
    private static int liftHelpRoundForceAll(CommandContext<CommandSourceStack> context) {
        int round = IntegerArgumentType.getInteger(context, "round");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftHelpRoundAll(source.getServer(), round);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        int applied = EscalatorSpeedManager.getLiftHelpRound(source.getLevel());
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯提示音淡入淡出范围 = " + applied + " 格（改动 " + changed + " 个维度）"), false);
        return 1;
    }

    /** /lifthelpround -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里范围正好是 X 的那些改成 Y。 */
    private static int liftHelpRoundForceFromTo(CommandContext<CommandSourceStack> context) {
        int from = IntegerArgumentType.getInteger(context, "round");
        int to = IntegerArgumentType.getInteger(context, "target");
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftHelpRoundAll(source.getServer(), from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的直梯提示音淡入淡出范围是 " + from + " 格，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有直梯提示音淡入淡出范围为 " + from + " 格的维度改成 " + to + " 格（共 " + changed + " 个维度）"), false);
        return 1;
    }

    /** 注册 `/lifthelp` 的 `-f` 分支：`-f on|off` 与 `-f on to off` / `-f off to on`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftHelpForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.literal("on")
                        .executes(context -> liftHelpForceAll(context, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> liftHelpForceFromTo(context, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> liftHelpForceAll(context, false))
                        .then(Commands.literal("to")
                                .then(Commands.literal("on")
                                        .executes(context -> liftHelpForceFromTo(context, false, true)))));
    }

    // ------------------------------------------------------------------
    // 【1.46】三提示音独立子开关：/lifthelpup / /lifthelpdown / /lifthelpchime
    //   形状与 /lifthelp 完全一致（无参显示 / on|off / X to Y / -f 全维度），
    //   只是把「总开关」换成「up / down / chime 各自的子开关」。总开关与子开关是「与」的关系。
    // ------------------------------------------------------------------

    /** /lifthelpup|down|chime（不带参数）—— 显示当前维度这项子开关。 */
    private static int liftToneSwitchShow(CommandContext<CommandSourceStack> context, String which) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        boolean enabled = EscalatorSpeedManager.isLiftToneEnabled(level, which);
        source.sendSuccess(() -> Component.literal(
                "当前维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "：" + helpLabel(enabled)),
                false);
        return 1;
    }

    /** /lifthelpup|down|chime &lt;on|off&gt; —— 设置**本维度**这项子开关。 */
    private static int liftToneSwitchGlobal(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        EscalatorSpeedManager.setDefaultLiftToneEnabled(level, which, enabled);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度（" + level.dimension().location() + "）直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which)
                        + "已设为 " + helpLabel(enabled)
                        + "；其它维度不变（要对所有维度生效用 /lifthelp" + which + " -f " + (enabled ? "on" : "off") + "）"), false);
        return 1;
    }

    /** /lifthelpup|down|chime &lt;X&gt; to &lt;Y&gt; —— 本维度这项子开关正好是 X 时才改成 Y。 */
    private static int liftToneSwitchFromTo(CommandContext<CommandSourceStack> context, String which, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        if (!EscalatorSpeedManager.replaceDefaultLiftToneEnabled(level, which, from, to)) {
            boolean current = EscalatorSpeedManager.isLiftToneEnabled(level, which);
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "不是 " + helpLabel(from)
                            + "（当前为 " + helpLabel(current) + "），未做修改"), false);
            return 0;
        }
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "从 " + helpLabel(from)
                        + " 改为 " + helpLabel(to)), false);
        return 1;
    }

    /** /lifthelpup|down|chime -f &lt;on|off&gt; —— **所有维度**这项子开关都设成该值。 */
    private static int liftToneSwitchForceAll(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.setDefaultLiftToneEnabledAll(source.getServer(), which, enabled);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        source.sendSuccess(() -> Component.literal(
                "已强制**所有维度**的直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + " = " + helpLabel(enabled)
                        + "（改动 " + changed + " 个维度）"), false);
        return 1;
    }

    /** /lifthelpup|down|chime -f &lt;X&gt; to &lt;Y&gt; —— 所有维度里这项子开关正好是 X 的那些改成 Y。 */
    private static int liftToneSwitchForceFromTo(CommandContext<CommandSourceStack> context, String which, boolean from, boolean to) {
        CommandSourceStack source = context.getSource();
        int changed = EscalatorSpeedManager.replaceDefaultLiftToneEnabledAll(source.getServer(), which, from, to);
        EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
        if (changed == 0) {
            source.sendSuccess(() -> Component.literal(
                    "没有任何维度的直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "是 " + helpLabel(from)
                            + "，未做修改"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已把所有直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "为 " + helpLabel(from)
                        + " 的维度改成 " + helpLabel(to) + "（共 " + changed + " 个维度）"), false);
        return 1;
    }

    /** 注册某条子开关指令的完整树（含 `-f` 分支）。{@code which} ∈ {"up","down","chime"}。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftToneSwitchCommand(String which) {
        return Commands.literal("lifthelp" + which)
                .executes(context -> liftToneSwitchShow(context, which))
                .then(Commands.literal("on")
                        .executes(context -> liftToneSwitchGlobal(context, which, true))
                        .then(Commands.literal("to")
                                .then(Commands.literal("off")
                                        .executes(context -> liftToneSwitchFromTo(context, which, true, false)))))
                .then(Commands.literal("off")
                        .executes(context -> liftToneSwitchGlobal(context, which, false))
                        .then(Commands.literal("to")
                                .then(Commands.literal("on")
                                        .executes(context -> liftToneSwitchFromTo(context, which, false, true)))))
                .then(Commands.literal("-f")
                        .then(Commands.literal("on")
                                .executes(context -> liftToneSwitchForceAll(context, which, true))
                                .then(Commands.literal("to")
                                        .then(Commands.literal("off")
                                                .executes(context -> liftToneSwitchForceFromTo(context, which, true, false)))))
                        .then(Commands.literal("off")
                                .executes(context -> liftToneSwitchForceAll(context, which, false))
                                .then(Commands.literal("to")
                                        .then(Commands.literal("on")
                                                .executes(context -> liftToneSwitchForceFromTo(context, which, false, true))))));
    }

    /** 注册 `/lifthelpspeed` 的 `-f` 分支：`-f <倍速>` 与 `-f <X> to <Y>`。 */
    private static LiteralArgumentBuilder<CommandSourceStack> liftHelpSpeedForce(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("speed", liftHelpSpeedArg())
                        .executes(SmoothLift::liftHelpSpeedForceAll)
                        .then(Commands.literal("to")
                                .then(Commands.argument("target", liftHelpSpeedArg())
                                        .executes(SmoothLift::liftHelpSpeedForceFromTo))));
    }


    private static String[] allVolumeCommands(int volume) {
            String v = String.valueOf(volume);
            return new String[]{
                    "futiloud -f " + v,
                    "futihelploud -f " + v,
                    "lifthelploud -f " + v,
                    "lifthelploud -f up " + v,
                    "lifthelploud -f down " + v,
                    "lifthelploud -f door " + v,
                    "pbmloud -f " + v,
                    "pbmloud -f open " + v,
                    "pbmloud -f close " + v,
                    "pbmmidiumloud -f " + v,
                    "pbmarriveloud -f " + v,
            };
        
    }

    public static int applyAllVolumes(ServerPlayer player, int volume) {
            int clamped = EscalatorSpeedData.clampHelpVolume(volume);
            runCommandBatch(player, allVolumeCommands(clamped));
            return clamped;
        
    }

    public static int applyPreset(ServerPlayer player, String presetId) {
            String[] commands = presetCommands(presetId);
            if (commands == null) {
                return 0;
            }
            return runCommandBatch(player, commands);
        
    }

    private static IntegerArgumentType arriveArg() {
            return IntegerArgumentType.integer(Integer.MIN_VALUE, EscalatorSpeedData.PSD_ARRIVE_SECONDS_MAX);
        
    }

    private static StringArgumentType arriveNameArg() {
            return StringArgumentType.string();
        
    }

    private static IntegerArgumentType closeWaitArg() {
            return IntegerArgumentType.integer(
                    EscalatorSpeedData.PSD_CLOSE_WAIT_MIN, EscalatorSpeedData.PSD_CLOSE_WAIT_MAX);
        
    }

    private static int dtMusicDeleteAll(CommandContext<CommandSourceStack> context) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedData data = EscalatorSpeedManager.getServerData(level);
            java.util.List<String> ids = new java.util.ArrayList<>(data.audioLibrary.keySet());
            if (ids.isEmpty()) {
                source.sendSuccess(() -> Component.literal(
                        "存档音频库里没有已导入的音频"), false);
                return 0;
            }
            int removed = 0;
            int midiumCleared = 0;
            int arriveCleared = 0;
            for (String id : ids) {
                if (EscalatorSpeedManager.deleteAudio(level, id)) {
                    removed++;
                }
                // 与单条删除通道相同的两路兜底：到站播报 / 进站报站指向已删音频的按各自语义回落。
                midiumCleared += EscalatorSpeedManager.clearPsdMidiumIfRemoved(level.getServer(), id);
                arriveCleared += EscalatorSpeedManager.clearPsdArriveIfRemoved(level.getServer(), id);
            }
            String msg = "已从存档删除 " + removed + " 条音频"
                    + (midiumCleared > 0 ? "；" + midiumCleared + " 扇门的到站播报已一并改成「不播」" : "")
                    + (arriveCleared > 0 ? "；" + arriveCleared + " 扇门的进站报站已一并改成「不播」" : "");
            source.sendSuccess(() -> Component.literal(msg), false);
            if (removed > 0) {
                EscalatorSpeedManager.syncAudioToAll(level.getServer());
                EscalatorSpeedManager.syncHelpAudioToAll(level.getServer());
                EscalatorSpeedManager.syncPsdChimeToAll(level.getServer());
                if (source.getPlayer() != null) {
                    EscalatorSpeedManager.sendAudioSyncTo(source.getPlayer(), level);
                }
            }
            return removed;
        
    }

    private static int dtMusicImportAll(CommandContext<CommandSourceStack> context) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            java.util.Map<String, byte[]> folder = EscalatorSpeedManager.scanAudioFiles(level);
            if (folder.isEmpty()) {
                source.sendSuccess(() -> Component.literal(
                        EscalatorSpeedManager.AUDIO_FOLDER + " 文件夹里没有可导入的 .ogg"), false);
                return 0;
            }
            int ok = 0;
            int skipped = 0;
            for (String name : folder.keySet()) {
                if (EscalatorSpeedManager.importAudioToStore(level, name) == null) {
                    ok++;
                } else {
                    skipped++;
                }
            }
            String msg = "已导入 " + ok + " 条音频到存档"
                    + (skipped > 0 ? "，跳过 " + skipped + " 条" : "");
            source.sendSuccess(() -> Component.literal(msg), false);
            if (ok > 0 && source.getPlayer() != null) {
                // 必须补发音频库同步包：客户端的「已导入」列表（右列）就是从它来的。
                EscalatorSpeedManager.sendAudioSyncTo(source.getPlayer(), level);
            }
            return ok;
        
    }

    private static CompletableFuture<Suggestions> futiMusicNameSuggestions(
            final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {
            ServerLevel level = context.getSource().getLevel();
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            seen.add("default");
            seen.add("off");
            seen.addAll(EscalatorSpeedManager.builtinAudioIds());
            for (java.util.Map.Entry<String, byte[]> e : EscalatorSpeedManager.getServerData(level).audioLibrary.entrySet()) {
                seen.add(e.getKey());
            }
            for (String s : seen) {
                if (s.startsWith(builder.getRemainingLowerCase())) {
                    builder.suggest(s);
                }
            }
            return builder.buildFuture();
        
    }

    public static boolean isMtrRail(BlockState state) {
            if (state == null) {
                return false;
            }
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return "mtr".equals(id.getNamespace()) && "rail".equals(id.getPath());
        
    }

    public static boolean isPsdDoor(BlockState state) {
            if (state == null) {
                return false;
            }
            String path = registryPathOf(state);
            return path.startsWith("psd_door") || path.startsWith("apg_door");
        
    }

    private static int liftToneAudioForceFromTo(CommandContext<CommandSourceStack> context, String which) {
            String name = StringArgumentType.getString(context, "name");
            String targetName = StringArgumentType.getString(context, "target");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveLiftToneName(level, name);
            if (!from.ok()) {
                source.sendFailure(Component.literal(from.error()));
                return 0;
            }
            EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveLiftToneName(level, targetName);
            if (!to.ok()) {
                source.sendFailure(Component.literal(to.error()));
                return 0;
            }
            int changed = EscalatorSpeedManager.replaceDefaultLiftToneAudioAll(
                    source.getServer(), which, from.id(), to.id());
            EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
            EscalatorSpeedManager.syncLiftToneToAll(source.getServer());
            String label = EscalatorSpeedManager.liftToneEnabledLabel(which);
            if (changed == 0) {
                source.sendSuccess(() -> Component.literal(
                        "没有任何维度的直梯" + label + "默认素材是 " + liftToneAudioLabel(from.id())
                                + "，未做修改"), false);
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                    "已把所有直梯" + label + "默认素材为 " + liftToneAudioLabel(from.id()) + " 的维度改成 "
                            + liftToneAudioLabel(to.id())), false);
            return 1;
        
    }

    private static int liftToneAudioForceSet(CommandContext<CommandSourceStack> context, String literal, String which) {
            String name = StringArgumentType.getString(context, "name");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveLiftToneName(level, name);
            if (!arg.ok()) {
                source.sendFailure(Component.literal(arg.error()));
                return 0;
            }
            int changed = EscalatorSpeedManager.setDefaultLiftToneAudioAll(source.getServer(), which, arg.id());
            EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
            EscalatorSpeedManager.syncLiftToneToAll(source.getServer());
            String label = EscalatorSpeedManager.liftToneEnabledLabel(which);
            source.sendSuccess(() -> Component.literal(
                    "已强制**所有维度**的直梯" + label + "默认素材 = " + liftToneAudioLabel(arg.id())
                            + "，并清掉按直梯的单独设置"), false);
            return 1;
        
    }

    private static int liftToneAudioFromTo(CommandContext<CommandSourceStack> context, String literal, String which) {
            String name = StringArgumentType.getString(context, "name");
            String targetName = StringArgumentType.getString(context, "target");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolveLiftToneName(level, name);
            if (!from.ok()) {
                source.sendFailure(Component.literal(from.error()));
                return 0;
            }
            EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolveLiftToneName(level, targetName);
            if (!to.ok()) {
                source.sendFailure(Component.literal(to.error()));
                return 0;
            }
            if (!EscalatorSpeedManager.replaceDefaultLiftToneAudio(level, which, from.id(), to.id())) {
                String current = EscalatorSpeedManager.getLiftToneAudio(level, which);
                // 「不是 X 就没改」按惯例用 sendSuccess（不是错误，只是没命中），与 /futimusic X to Y 一致
                source.sendSuccess(() -> Component.literal(
                        "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "的默认素材不是 "
                                + liftToneAudioLabel(from.id()) + "，未做修改"), false);
                return 0;
            }
            EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "的默认素材从 "
                            + liftToneAudioLabel(from.id()) + " 改为 " + liftToneAudioLabel(to.id())), false);
            return 1;
        
    }

    private static String liftToneAudioLabel(String audioId) {
            if (audioId == null) {
                return "无";
            }
            if (EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(audioId)) {
                return "default";
            }
            if (EscalatorSpeedData.LIFT_TONE_OFF.equals(audioId)) {
                return "none";
            }
            return "「" + audioId + "」";
        
    }

    private static int liftToneAudioSet(CommandContext<CommandSourceStack> context, String literal, String which) {
            String name = StringArgumentType.getString(context, "name");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolveLiftToneName(level, name);
            if (!arg.ok()) {
                source.sendFailure(Component.literal(arg.error()));
                return 0;
            }
            EscalatorSpeedManager.setDefaultLiftToneAudio(level, which, arg.id());
            EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
            String label = EscalatorSpeedManager.liftToneEnabledLabel(which);
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯" + label + "的默认素材已设为 "
                            + liftToneAudioLabel(arg.id())
                            + "；没有单独设置过这项的直梯都会用它"), false);
            return 1;
        
    }

    private static LiteralArgumentBuilder<CommandSourceStack> liftToneBranch(String literal, String which) {
            return Commands.literal(literal)
                    .executes(context -> liftToneShow(context, literal, which))
                    // ★ 顺序即优先级：字面量必须排在字符串参数前面（详见本节开头那段说明）。
                    .then(Commands.literal("on")
                            .executes(context -> liftToneSwitchGlobal(context, literal, which, true))
                            .then(Commands.literal("to")
                                    .then(Commands.literal("off")
                                            .executes(context -> liftToneSwitchFromTo(context, which, true, false)))))
                    .then(Commands.literal("off")
                            .executes(context -> liftToneSwitchGlobal(context, literal, which, false))
                            .then(Commands.literal("to")
                                    .then(Commands.literal("on")
                                            .executes(context -> liftToneSwitchFromTo(context, which, false, true)))))
                    .then(Commands.literal("-f")
                            .then(Commands.literal("on")
                                    .executes(context -> liftToneSwitchForceAll(context, which, true))
                                    .then(Commands.literal("to")
                                            .then(Commands.literal("off")
                                                    .executes(context -> liftToneSwitchForceFromTo(context, which, true, false)))))
                            .then(Commands.literal("off")
                                    .executes(context -> liftToneSwitchForceAll(context, which, false))
                                    .then(Commands.literal("to")
                                            .then(Commands.literal("on")
                                                    .executes(context -> liftToneSwitchForceFromTo(context, which, false, true)))))
                            .then(Commands.argument("name", StringArgumentType.string())
                                    .suggests(SmoothLift::liftToneNameSuggestions)
                                    .executes(context -> liftToneAudioForceSet(context, literal, which))
                                    .then(Commands.literal("to")
                                            .then(Commands.argument("target", StringArgumentType.string())
                                                    .suggests(SmoothLift::liftToneNameSuggestions)
                                                    .executes(context -> liftToneAudioForceFromTo(context, which))))))
                    .then(Commands.argument("name", StringArgumentType.string())
                            .suggests(SmoothLift::liftToneNameSuggestions)
                            .executes(context -> liftToneAudioSet(context, literal, which))
                            .then(Commands.literal("to")
                                    .then(Commands.argument("target", StringArgumentType.string())
                                            .suggests(SmoothLift::liftToneNameSuggestions)
                                            .executes(context -> liftToneAudioFromTo(context, literal, which)))));
        
    }

    private static CompletableFuture<Suggestions> liftToneNameSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
            CommandSourceStack source = context.getSource();
            if (source == null) {
                return builder.buildFuture();
            }
            String typed = builder.getRemainingLowerCase();
            for (String candidate : EscalatorSpeedManager.liftToneNameCandidates(source.getLevel())) {
                if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(candidate);
                }
            }
            return builder.buildFuture();
        
    }

    private static int liftToneShow(CommandContext<CommandSourceStack> context, String literal, String which) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            boolean enabled = EscalatorSpeedManager.isLiftToneEnabled(level, which);
            String audio = EscalatorSpeedManager.getLiftToneAudio(level, which);
            source.sendSuccess(() -> Component.literal(
                    "当前维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which) + "：开关 " + helpLabel(enabled)
                            + "，默认素材 " + liftToneAudioLabel(audio)), false);
            return 1;
        
    }

    private static int liftToneSwitchGlobal(CommandContext<CommandSourceStack> context, String literal,
                                            String which, boolean enabled) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.setDefaultLiftToneEnabled(level, which, enabled);
            EscalatorSpeedManager.syncLiftChimeToAll(source.getServer());
            source.sendSuccess(() -> Component.literal(
                    "本维度直梯" + EscalatorSpeedManager.liftToneEnabledLabel(which)
                            + "已设为 " + helpLabel(enabled)
                            + "；其它维度不变"), false);
            return 1;
        
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mbmTree(String literal) {
            return Commands.literal(literal)
                    .executes(SmoothLift::mbmOpenHelp)
                    .then(Commands.literal("help")
                            .executes(SmoothLift::mbmOpenHelp))
                    .then(Commands.literal("music")
                            .then(Commands.literal("in")
                                    .executes(SmoothLift::dtMusicImportAll))
                            .then(Commands.literal("delete")
                                    .executes(SmoothLift::dtMusicDeleteAll)));
        
    }

    private static StringArgumentType midiumNameArg() {
            return StringArgumentType.string();
        
    }

    private static IntegerArgumentType midiumWaitArg() {
            return IntegerArgumentType.integer(EscalatorSpeedData.PSD_MIDIUM_WAIT_MIN);
        
    }

    private static int pbmArriveApply(CommandSourceStack source, ServerLevel level,
                                      String name, int seconds, String suffix) {
            String resolved = EscalatorSpeedManager.resolvePsdArriveName(level, name);
            if (resolved == null) {
                sendUnknownArriveName(source, name);
                return 0;
            }
            EscalatorSpeedManager.setDefaultPsdArrive(level, resolved, seconds);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedManager.getPsdArriveSeconds(level);
            boolean off = EscalatorSpeedData.isPsdArriveOff(resolved);
            String tail = suffix == null ? "" : suffix;
            source.sendSuccess(() -> Component.literal(
                    "本维度进站报站已设为 "
                            + (off ? "不播" : "「" + resolved + "」")
                            + "、到站前 " + (-applied) + " 秒" + tail), false);
            return 1;
        
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pbmArriveForce(String literal) {
            return Commands.literal(literal)
                    .then(Commands.argument("name", arriveNameArg())
                            .suggests(SmoothLift::pbmArriveNameSuggestions)
                            .then(Commands.argument("seconds", arriveArg())
                                    .executes(SmoothLift::pbmArriveForceAll)));
        
    }

    private static int pbmArriveForceAll(CommandContext<CommandSourceStack> context) {
            String name = StringArgumentType.getString(context, "name");
            int seconds = IntegerArgumentType.getInteger(context, "seconds");
            CommandSourceStack source = context.getSource();
            String resolved = EscalatorSpeedManager.resolvePsdArriveName(source.getLevel(), name);
            if (resolved == null) {
                sendUnknownArriveName(source, name);
                return 0;
            }
            int changed = EscalatorSpeedManager.setDefaultPsdArriveAll(source.getServer(), resolved, seconds);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedManager.getPsdArriveSeconds(source.getLevel());
            boolean off = EscalatorSpeedData.isPsdArriveOff(resolved);
            source.sendSuccess(() -> Component.literal(
                    "已把所有维度的进站报站设为 " + (off ? "不播" : "「" + resolved + "」")
                            + "、到站前 " + (-applied) + " 秒"), false);
            return 1;
        
    }

    private static int pbmArriveGlobal(CommandContext<CommandSourceStack> context) {
            String name = StringArgumentType.getString(context, "name");
            int seconds = IntegerArgumentType.getInteger(context, "seconds");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            return pbmArriveApply(source, level, name, seconds, "；其它维度不变");
        
    }

    private static CompletableFuture<Suggestions> pbmArriveNameSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
            CommandSourceStack source = context.getSource();
            if (source == null) {
                return builder.buildFuture();
            }
            String typed = builder.getRemainingLowerCase();
            for (String candidate : EscalatorSpeedManager.psdArriveSuggestions(source.getLevel())) {
                if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(candidate);
                }
            }
            return builder.buildFuture();
        
    }

    private static int pbmArriveSetNameOnly(CommandContext<CommandSourceStack> context) {
            String name = StringArgumentType.getString(context, "name");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            int keep = EscalatorSpeedManager.getPsdArriveSeconds(level);
            return pbmArriveApply(source, level, name, keep, null);
        
    }

    private static int pbmArriveShow(CommandContext<CommandSourceStack> context) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            String id = EscalatorSpeedManager.getPsdArriveAudio(level);
            int seconds = EscalatorSpeedManager.getPsdArriveSeconds(level);
            boolean off = EscalatorSpeedData.isPsdArriveOff(id);
            source.sendSuccess(() -> Component.literal(
                    "当前维度进站报站：" + (off ? "不播" : "「" + id + "」")
                            + "，到站前 " + (-seconds) + " 秒"
                            + "。含义：**读 MTR 时刻表**，这个站台「最近的一班列车还剩这么多秒到站」时开始播报"
                            + "；"
                            + "这段播报**不会被掐断**。"
                            + "秒数允许 (-∞, 0]"), false);
            return 1;
        
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pbmCloseWaitForce(String literal) {
            return Commands.literal(literal)
                    .then(Commands.argument("seconds", closeWaitArg())
                            .executes(SmoothLift::pbmCloseWaitForceAll)
                            .then(Commands.literal("to")
                                    .then(Commands.argument("target", closeWaitArg())
                                            .executes(SmoothLift::pbmCloseWaitForceFromTo))));
        
    }

    private static int pbmCloseWaitForceAll(CommandContext<CommandSourceStack> context) {
            int seconds = IntegerArgumentType.getInteger(context, "seconds");
            CommandSourceStack source = context.getSource();
            int changed = EscalatorSpeedManager.setDefaultPsdCloseWaitSecondsAll(source.getServer(), seconds);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedManager.getPsdCloseWaitSeconds(source.getLevel());
            source.sendSuccess(() -> Component.literal(
                    "已强制**所有维度**的关门提示音强制等待时长 = " + applied
                            + " 秒"), false);
            return 1;
        
    }

    private static int pbmCloseWaitForceFromTo(CommandContext<CommandSourceStack> context) {
            int from = IntegerArgumentType.getInteger(context, "seconds");
            int to = IntegerArgumentType.getInteger(context, "target");
            CommandSourceStack source = context.getSource();
            int changed = EscalatorSpeedManager.replaceDefaultPsdCloseWaitSecondsAll(source.getServer(), from, to);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            if (changed == 0) {
                source.sendSuccess(() -> Component.literal(
                        "没有任何维度的关门提示音强制等待时长是 " + from + " 秒，未做修改"), false);
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                    "已把所有关门提示音强制等待时长为 " + from + " 秒的维度改成 " + to
                            + " 秒"), false);
            return 1;
        
    }

    private static int pbmCloseWaitFromTo(CommandContext<CommandSourceStack> context) {
            int from = IntegerArgumentType.getInteger(context, "seconds");
            int to = IntegerArgumentType.getInteger(context, "target");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            int current = EscalatorSpeedManager.getPsdCloseWaitSeconds(level);
            if (current != from) {
                source.sendSuccess(() -> Component.literal(
                        "本维度关门提示音强制等待时长不是 " + from + " 秒，未做修改"), false);
                return 0;
            }
            EscalatorSpeedManager.replaceDefaultPsdCloseWaitSeconds(level, from, to);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedManager.getPsdCloseWaitSeconds(level);
            source.sendSuccess(() -> Component.literal(
                    "本维度关门提示音强制等待时长从 " + from + " 秒改为 " + applied + " 秒"), false);
            return 1;
        
    }

    private static int pbmCloseWaitGlobal(CommandContext<CommandSourceStack> context) {
            int seconds = IntegerArgumentType.getInteger(context, "seconds");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.setDefaultPsdCloseWaitSeconds(level, seconds);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedManager.getPsdCloseWaitSeconds(level);
            source.sendSuccess(() -> Component.literal(
                    "本维度关门提示音强制等待时长已设为 " + applied
                            + " 秒；其它维度不变"), false);
            return 1;
        
    }

    private static int pbmCloseWaitShow(CommandContext<CommandSourceStack> context) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            int seconds = EscalatorSpeedManager.getPsdCloseWaitSeconds(level);
            source.sendSuccess(() -> Component.literal(
                    "当前维度关门提示音强制等待时长：" + seconds + " 秒"
                            + "。只在「停站时长不够放完整条关门素材」时生效："
                            + "开门音播完后等这么多秒再放人声，门一动就把人声掐断；"
                            + "停站够长时这个值被完全忽略"), false);
            return 1;
        
    }

    private static String pbmItemCommand(String which) {
            return "midium".equals(which) ? "/pbmmidiumloud" : "/pbmarriveloud";
        
    }

    private static String pbmItemLabel(String which) {
            return "midium".equals(which) ? "到站播报" : "进站报站";
        
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pbmItemLoudForce(String literal, String which) {
            return Commands.literal(literal)
                    .then(Commands.argument("volume", volumeArg())
                            .executes(context -> pbmItemLoudForceAll(context, which))
                            .then(Commands.literal("to")
                                    .then(Commands.argument("target", volumeArg())
                                            .executes(context -> pbmItemLoudForceFromTo(context, which)))));
        
    }

    private static int pbmItemLoudForceAll(CommandContext<CommandSourceStack> context, String which) {
            int volume = IntegerArgumentType.getInteger(context, "volume");
            CommandSourceStack source = context.getSource();
            int changed = "midium".equals(which)
                    ? EscalatorSpeedManager.setDefaultPsdMidiumVolumeAll(source.getServer(), volume)
                    : EscalatorSpeedManager.setDefaultPsdArriveVolumeAll(source.getServer(), volume);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedData.clampPsdToneVolume(volume);
            source.sendSuccess(() -> Component.literal(
                    "已强制**所有维度**的屏蔽门" + pbmItemLabel(which)
                            + "音量 = " + applied + "，改动 " + changed + " 个维度"), false);
            return 1;
        
    }

    private static int pbmItemLoudForceFromTo(CommandContext<CommandSourceStack> context, String which) {
            int from = IntegerArgumentType.getInteger(context, "volume");
            int to = IntegerArgumentType.getInteger(context, "target");
            CommandSourceStack source = context.getSource();
            int changed = "midium".equals(which)
                    ? EscalatorSpeedManager.replaceDefaultPsdMidiumVolumeAll(source.getServer(), from, to)
                    : EscalatorSpeedManager.replaceDefaultPsdArriveVolumeAll(source.getServer(), from, to);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            if (changed == 0) {
                source.sendSuccess(() -> Component.literal(
                        "没有任何维度的屏蔽门" + pbmItemLabel(which)
                                + "音量是 " + from + "，未做修改"), false);
                return 0;
            }
            int applied = EscalatorSpeedData.clampPsdToneVolume(to);
            source.sendSuccess(() -> Component.literal(
                    "已把所有屏蔽门" + pbmItemLabel(which) + "音量为 " + from
                            + " 的维度改成 " + applied + "，共 " + changed + " 个维度"),
                    false);
            return 1;
        
    }

    private static int pbmItemLoudFromTo(CommandContext<CommandSourceStack> context, String which) {
            int from = IntegerArgumentType.getInteger(context, "volume");
            int to = IntegerArgumentType.getInteger(context, "target");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            int current = EscalatorSpeedManager.getPsdItemVolume(level, which);
            if (current != from) {
                source.sendSuccess(() -> Component.literal(
                        "本维度屏蔽门" + pbmItemLabel(which) + "音量不是 " + from
                                + "，当前为 " + current + "，未做修改"), false);
                return 0;
            }
            if ("midium".equals(which)) {
                EscalatorSpeedManager.replaceDefaultPsdMidiumVolume(level, from, to);
            } else {
                EscalatorSpeedManager.replaceDefaultPsdArriveVolume(level, from, to);
            }
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedManager.getPsdItemVolume(level, which);
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门" + pbmItemLabel(which) + "音量从 " + from
                            + " 改为 " + applied), false);
            return 1;
        
    }

    private static int pbmItemLoudGlobal(CommandContext<CommandSourceStack> context, String which) {
            int volume = IntegerArgumentType.getInteger(context, "volume");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            if ("midium".equals(which)) {
                EscalatorSpeedManager.setDefaultPsdMidiumVolume(level, volume);
            } else {
                EscalatorSpeedManager.setDefaultPsdArriveVolume(level, volume);
            }
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedManager.getPsdItemVolume(level, which);
            source.sendSuccess(() -> Component.literal(
                    "本维度 " + level.dimension().location() + " 屏蔽门" + pbmItemLabel(which)
                            + "音量已设为 " + applied
                            + "；其它维度不变，要对所有维度生效用 "
                            + pbmItemCommand(which) + " -f " + applied), false);
            return 1;
        
    }

    private static int pbmItemLoudShow(CommandContext<CommandSourceStack> context, String which) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            int v = EscalatorSpeedManager.getPsdItemVolume(level, which);
            boolean own = EscalatorSpeedManager.hasOwnPsdItemVolume(level, which);
            source.sendSuccess(() -> Component.literal(
                    "当前维度屏蔽门" + pbmItemLabel(which) + "音量：" + v
                            + (own ? "" : " 跟随共用")
                            + "；范围 " + EscalatorSpeedData.AUDIO_VOLUME_MIN + "~"
                            + EscalatorSpeedData.AUDIO_VOLUME_MAX), false);
            return 1;
        
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pbmLoudForce(String literal) {
            return Commands.literal(literal)
                    .then(Commands.argument("volume", volumeArg())
                            .executes(SmoothLift::pbmLoudForceAll)
                            .then(Commands.literal("to")
                                    .then(Commands.argument("target", volumeArg())
                                            .executes(SmoothLift::pbmLoudForceFromTo))));
        
    }

    private static int pbmLoudForceAll(CommandContext<CommandSourceStack> context) {
            int volume = IntegerArgumentType.getInteger(context, "volume");
            CommandSourceStack source = context.getSource();
            int changed = EscalatorSpeedManager.setDefaultPsdHelpVolumeAll(source.getServer(), volume);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedData.clampLiftHelpVolume(volume);
            source.sendSuccess(() -> Component.literal(
                    "已强制**所有维度**的屏蔽门提示音共用音量 = " + applied), false);
            return 1;
        
    }

    private static int pbmLoudForceFromTo(CommandContext<CommandSourceStack> context) {
            int from = IntegerArgumentType.getInteger(context, "volume");
            int to = IntegerArgumentType.getInteger(context, "target");
            CommandSourceStack source = context.getSource();
            int changed = EscalatorSpeedManager.replaceDefaultPsdHelpVolumeAll(source.getServer(), from, to);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            if (changed == 0) {
                source.sendSuccess(() -> Component.literal(
                        "没有任何维度的屏蔽门提示音共用音量是 " + from + "，未做修改"), false);
                return 0;
            }
            int applied = EscalatorSpeedData.clampLiftHelpVolume(to);
            source.sendSuccess(() -> Component.literal(
                    "已把所有屏蔽门提示音共用音量为 " + from + " 的维度改成 " + applied), false);
            return 1;
        
    }

    private static int pbmLoudFromTo(CommandContext<CommandSourceStack> context) {
            int from = IntegerArgumentType.getInteger(context, "volume");
            int to = IntegerArgumentType.getInteger(context, "target");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            int current = EscalatorSpeedManager.getPsdHelpVolume(level);
            if (current != from) {
                source.sendSuccess(() -> Component.literal(
                        "本维度屏蔽门提示音共用音量不是 " + from + "，未做修改"), false);
                return 0;
            }
            EscalatorSpeedManager.replaceDefaultPsdHelpVolume(level, from, to);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedManager.getPsdHelpVolume(level);
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门提示音共用音量从 " + from + " 改为 " + applied), false);
            return 1;
        
    }

    private static int pbmLoudGlobal(CommandContext<CommandSourceStack> context) {
            int volume = IntegerArgumentType.getInteger(context, "volume");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.setDefaultPsdHelpVolume(level, volume);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedManager.getPsdHelpVolume(level);
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门提示音共用音量已设为 " + applied
                            + "；其它维度不变"),
                    false);
            return 1;
        
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pbmLoudItemCommand(String literal, String which) {
            return Commands.literal(literal)
                    .then(Commands.argument("volume", volumeArg())
                            .executes(context -> pbmLoudItemGlobal(context, which))
                            .then(Commands.literal("to")
                                    .then(Commands.argument("target", volumeArg())
                                            .executes(context -> pbmLoudItemFromTo(context, which)))));
        
    }

    private static int pbmLoudItemForceAll(CommandContext<CommandSourceStack> context, String which) {
            int volume = IntegerArgumentType.getInteger(context, "volume");
            CommandSourceStack source = context.getSource();
            int changed = EscalatorSpeedManager.setDefaultPsdToneVolumeAll(source.getServer(), which, volume);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedData.clampPsdToneVolume(volume);
            source.sendSuccess(() -> Component.literal(
                    "已强制**所有维度**的屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音音量 = "
                            + applied), false);
            return 1;
        
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pbmLoudItemForceBranch(String literal, String which) {
            return Commands.literal(literal)
                    .then(Commands.argument("volume", volumeArg())
                            .executes(context -> pbmLoudItemForceAll(context, which))
                            .then(Commands.literal("to")
                                    .then(Commands.argument("target", volumeArg())
                                            .executes(context -> pbmLoudItemForceFromTo(context, which)))));
        
    }

    private static int pbmLoudItemForceFromTo(CommandContext<CommandSourceStack> context, String which) {
            int from = IntegerArgumentType.getInteger(context, "volume");
            int to = IntegerArgumentType.getInteger(context, "target");
            CommandSourceStack source = context.getSource();
            int changed = EscalatorSpeedManager.replaceDefaultPsdToneVolumeAll(source.getServer(), which, from, to);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            if (changed == 0) {
                source.sendSuccess(() -> Component.literal(
                        "没有任何维度的屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音音量是 "
                                + from + "，未做修改"), false);
                return 0;
            }
            int applied = EscalatorSpeedData.clampPsdToneVolume(to);
            source.sendSuccess(() -> Component.literal(
                    "已把所有屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音音量为 " + from
                            + " 的维度改成 " + applied), false);
            return 1;
        
    }

    private static int pbmLoudItemFromTo(CommandContext<CommandSourceStack> context, String which) {
            int from = IntegerArgumentType.getInteger(context, "volume");
            int to = IntegerArgumentType.getInteger(context, "target");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            int current = EscalatorSpeedManager.getPsdToneVolume(level, which);
            if (current != from) {
                source.sendSuccess(() -> Component.literal(
                        "本维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音音量不是 " + from
                                + "，未做修改"), false);
                return 0;
            }
            EscalatorSpeedManager.replaceDefaultPsdToneVolume(level, which, from, to);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedManager.getPsdToneVolume(level, which);
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音音量从 " + from
                            + " 改为 " + applied), false);
            return 1;
        
    }

    private static int pbmLoudItemGlobal(CommandContext<CommandSourceStack> context, String which) {
            int volume = IntegerArgumentType.getInteger(context, "volume");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.setDefaultPsdToneVolume(level, which, volume);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedManager.getPsdToneVolume(level, which);
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门「"
                            + EscalatorSpeedData.psdToneLabel(which) + "」提示音音量已设为 " + applied), false);
            return 1;
        
    }

    private static int pbmLoudShow(CommandContext<CommandSourceStack> context) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            int volume = EscalatorSpeedManager.getPsdHelpVolume(level);
            source.sendSuccess(() -> Component.literal(
                    "当前维度屏蔽门提示音共用音量：" + volume
                            + "，100 = 原始音量；开=" + pbmVolumeText(level, "open")
                            + "、关=" + pbmVolumeText(level, "close")
                            + "，范围 " + EscalatorSpeedData.AUDIO_VOLUME_MIN + "~"
                            + EscalatorSpeedData.AUDIO_VOLUME_MAX), false);
            return 1;
        
    }

    private static int pbmMidiumApply(CommandSourceStack source, ServerLevel level,
                                      String name, int seconds, String suffix) {
            String resolved = EscalatorSpeedManager.resolvePsdMidiumName(level, name);
            if (resolved == null) {
                sendUnknownMidiumName(source, name);
                return 0;
            }
            EscalatorSpeedManager.setDefaultPsdMidium(level, resolved, seconds);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedManager.getPsdMidiumWaitSeconds(level);
            boolean off = EscalatorSpeedData.isPsdMidiumOff(resolved);
            String tail = suffix == null ? "" : suffix;
            source.sendSuccess(() -> Component.literal(
                    "本维度到站播报已设为 "
                            + (off ? "不播" : "「" + resolved + "」")
                            + "、等待 " + applied + " 秒" + tail), false);
            return 1;
        
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pbmMidiumForce(String literal) {
            return Commands.literal(literal)
                    .then(Commands.argument("name", midiumNameArg())
                            .suggests(SmoothLift::pbmMidiumNameSuggestions)
                            .then(Commands.argument("seconds", midiumWaitArg())
                                    .executes(SmoothLift::pbmMidiumForceAll)));
        
    }

    private static int pbmMidiumForceAll(CommandContext<CommandSourceStack> context) {
            String name = StringArgumentType.getString(context, "name");
            int seconds = IntegerArgumentType.getInteger(context, "seconds");
            CommandSourceStack source = context.getSource();
            String resolved = EscalatorSpeedManager.resolvePsdMidiumName(source.getLevel(), name);
            if (resolved == null) {
                sendUnknownMidiumName(source, name);
                return 0;
            }
            int changed = EscalatorSpeedManager.setDefaultPsdMidiumAll(source.getServer(), resolved, seconds);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = EscalatorSpeedManager.getPsdMidiumWaitSeconds(source.getLevel());
            boolean off = EscalatorSpeedData.isPsdMidiumOff(resolved);
            source.sendSuccess(() -> Component.literal(
                    "已把所有维度的到站播报设为 " + (off ? "不播" : "「" + resolved + "」")
                            + "、等待 " + applied + " 秒"), false);
            return 1;
        
    }

    private static int pbmMidiumGlobal(CommandContext<CommandSourceStack> context) {
            String name = StringArgumentType.getString(context, "name");
            int seconds = IntegerArgumentType.getInteger(context, "seconds");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            return pbmMidiumApply(source, level, name, seconds, "；其它维度不变");
        
    }

    private static CompletableFuture<Suggestions> pbmMidiumNameSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
            CommandSourceStack source = context.getSource();
            if (source == null) {
                return builder.buildFuture();
            }
            String typed = builder.getRemainingLowerCase();
            for (String candidate : EscalatorSpeedManager.psdMidiumSuggestions(source.getLevel())) {
                if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(candidate);
                }
            }
            return builder.buildFuture();
        
    }

    private static int pbmMidiumSetNameOnly(CommandContext<CommandSourceStack> context) {
            String name = StringArgumentType.getString(context, "name");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            int keep = EscalatorSpeedManager.getPsdMidiumWaitSeconds(level);
            return pbmMidiumApply(source, level, name, keep, null);
        
    }

    private static int pbmMidiumShow(CommandContext<CommandSourceStack> context) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            String id = EscalatorSpeedManager.getPsdMidiumAudio(level);
            int seconds = EscalatorSpeedManager.getPsdMidiumWaitSeconds(level);
            boolean off = EscalatorSpeedData.isPsdMidiumOff(id);
            source.sendSuccess(() -> Component.literal(
                    "当前维度到站播报：" + (off ? "不播" : "「" + id + "」")
                            + "，等待 " + seconds + " 秒"
                            + "。含义：列车到站、开门音播完后再等这么多秒开始播报；"
                            + "这段播报**不会被掐断**。"
                            + "等待秒数允许 0 ~ 正无穷"), false);
            return 1;
        
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pbmMusicForce(String literal) {
            return Commands.literal(literal)
                    .then(Commands.literal("on")
                            .executes(context -> pbmMusicForceAll(context, true))
                            .then(Commands.literal("to")
                                    .then(Commands.literal("off")
                                            .executes(context -> pbmMusicForceFromTo(context, true, false)))))
                    .then(Commands.literal("off")
                            .executes(context -> pbmMusicForceAll(context, false))
                            .then(Commands.literal("to")
                                    .then(Commands.literal("on")
                                            .executes(context -> pbmMusicForceFromTo(context, false, true)))));
        
    }

    private static int pbmMusicForceAll(CommandContext<CommandSourceStack> context, boolean enabled) {
            CommandSourceStack source = context.getSource();
            int changed = EscalatorSpeedManager.setDefaultPsdHelpAll(source.getServer(), enabled);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            source.sendSuccess(() -> Component.literal(
                    "已强制**所有维度**的屏蔽门开关门提示音 = " + helpLabel(enabled)), false);
            return 1;
        
    }

    private static int pbmMusicForceFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
            CommandSourceStack source = context.getSource();
            int changed = EscalatorSpeedManager.replaceDefaultPsdHelpAll(source.getServer(), from, to);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            if (changed == 0) {
                source.sendSuccess(() -> Component.literal(
                        "没有任何维度的屏蔽门开关门提示音是 " + helpLabel(from) + "，未做修改"), false);
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                    "已把所有屏蔽门开关门提示音为 " + helpLabel(from) + " 的维度改成 " + helpLabel(to)), false);
            return 1;
        
    }

    private static int pbmMusicFromTo(CommandContext<CommandSourceStack> context, boolean from, boolean to) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            if (!EscalatorSpeedManager.replaceDefaultPsdHelp(level, from, to)) {
                boolean current = EscalatorSpeedManager.isPsdHelpEnabled(level);
                source.sendSuccess(() -> Component.literal(
                        "本维度屏蔽门开关门提示音不是 " + helpLabel(from) + "，未做修改"), false);
                return 0;
            }
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门开关门提示音从 " + helpLabel(from) + " 改为 " + helpLabel(to)), false);
            return 1;
        
    }

    private static int pbmMusicGlobal(CommandContext<CommandSourceStack> context, boolean enabled) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.setDefaultPsdHelp(level, enabled);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门开关门提示音已设为 " + helpLabel(enabled)
                            + "；其它维度不变"), false);
            return 1;
        
    }

    private static int pbmMusicItemAudioForceFromTo(CommandContext<CommandSourceStack> context, String which) {
            String name = StringArgumentType.getString(context, "name");
            String targetName = StringArgumentType.getString(context, "target");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolvePsdToneName(level, name);
            if (!from.ok()) {
                source.sendFailure(Component.literal(from.error()));
                return 0;
            }
            EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolvePsdToneName(level, targetName);
            if (!to.ok()) {
                source.sendFailure(Component.literal(to.error()));
                return 0;
            }
            int changed = EscalatorSpeedManager.replaceDefaultPsdToneAudioAll(
                    source.getServer(), which, from.id(), to.id());
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            if (changed == 0) {
                source.sendSuccess(() -> Component.literal(
                        "没有任何维度的屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」默认素材是 "
                                + psdToneAudioLabel(from.id()) + "，未做修改"), false);
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                    "已把所有屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」默认素材为 "
                            + psdToneAudioLabel(from.id()) + " 的维度改成 " + psdToneAudioLabel(to.id())), false);
            return 1;
        
    }

    private static int pbmMusicItemAudioForceSet(CommandContext<CommandSourceStack> context, String literal, String which) {
            String name = StringArgumentType.getString(context, "name");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolvePsdToneName(level, name);
            if (!arg.ok()) {
                source.sendFailure(Component.literal(arg.error()));
                return 0;
            }
            int changed = EscalatorSpeedManager.setDefaultPsdToneAudioAll(source.getServer(), which, arg.id());
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            EscalatorSpeedManager.syncPsdToneToAll(source.getServer());
            source.sendSuccess(() -> Component.literal(
                    "已强制**所有维度**的屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」默认素材 = "
                            + psdToneAudioLabel(arg.id())
                            + "，并清掉按扇门的单独设置"), false);
            return 1;
        
    }

    private static int pbmMusicItemAudioFromTo(CommandContext<CommandSourceStack> context, String literal, String which) {
            String name = StringArgumentType.getString(context, "name");
            String targetName = StringArgumentType.getString(context, "target");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.AudioArg from = EscalatorSpeedManager.resolvePsdToneName(level, name);
            if (!from.ok()) {
                source.sendFailure(Component.literal(from.error()));
                return 0;
            }
            EscalatorSpeedManager.AudioArg to = EscalatorSpeedManager.resolvePsdToneName(level, targetName);
            if (!to.ok()) {
                source.sendFailure(Component.literal(to.error()));
                return 0;
            }
            if (!EscalatorSpeedManager.replaceDefaultPsdToneAudio(level, which, from.id(), to.id())) {
                String current = EscalatorSpeedManager.getPsdToneAudio(level, which);
                // 「不是 X 就没改」按惯例用 sendSuccess（不是错误，只是没命中），与直梯那套一致
                source.sendSuccess(() -> Component.literal(
                        "本维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」的默认素材不是 "
                                + psdToneAudioLabel(from.id()) + "，未做修改"), false);
                return 0;
            }
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」的默认素材从 "
                            + psdToneAudioLabel(from.id()) + " 改为 " + psdToneAudioLabel(to.id())), false);
            return 1;
        
    }

    private static int pbmMusicItemAudioSet(CommandContext<CommandSourceStack> context, String literal, String which) {
            String name = StringArgumentType.getString(context, "name");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.AudioArg arg = EscalatorSpeedManager.resolvePsdToneName(level, name);
            if (!arg.ok()) {
                source.sendFailure(Component.literal(arg.error()));
                return 0;
            }
            EscalatorSpeedManager.setDefaultPsdToneAudio(level, which, arg.id());
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门「"
                            + EscalatorSpeedData.psdToneLabel(which) + "」的默认素材已设为 "
                            + psdToneAudioLabel(arg.id())
                            + "；没有单独设置过这一项的门都会用它"), false);
            return 1;
        
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pbmMusicItemCommand(String literal, String which) {
            return Commands.literal(literal)
                    .executes(context -> pbmMusicItemShow(context, which))
                    .then(Commands.literal("on")
                            .executes(context -> pbmMusicItemGlobal(context, which, true))
                            .then(Commands.literal("to")
                                    .then(Commands.literal("off")
                                            .executes(context -> pbmMusicItemFromTo(context, which, true, false)))))
                    .then(Commands.literal("off")
                            .executes(context -> pbmMusicItemGlobal(context, which, false))
                            .then(Commands.literal("to")
                                    .then(Commands.literal("on")
                                            .executes(context -> pbmMusicItemFromTo(context, which, false, true)))))
                    .then(Commands.literal("-f")
                            .then(Commands.literal("on")
                                    .executes(context -> pbmMusicItemForceAll(context, which, true))
                                    .then(Commands.literal("to")
                                            .then(Commands.literal("off")
                                                    .executes(context -> pbmMusicItemForceFromTo(context, which, true, false)))))
                            .then(Commands.literal("off")
                                    .executes(context -> pbmMusicItemForceAll(context, which, false))
                                    .then(Commands.literal("to")
                                            .then(Commands.literal("on")
                                                    .executes(context -> pbmMusicItemForceFromTo(context, which, false, true)))))
                            // 【1.15】-f <名字> / -f <X> to <Y>
                            .then(Commands.argument("name", StringArgumentType.string())
                                    .suggests(SmoothLift::psdToneNameSuggestions)
                                    .executes(context -> pbmMusicItemAudioForceSet(context, literal, which))
                                    .then(Commands.literal("to")
                                            .then(Commands.argument("target", StringArgumentType.string())
                                                    .suggests(SmoothLift::psdToneNameSuggestions)
                                                    .executes(context -> pbmMusicItemAudioForceFromTo(context, which))))))
                    // 【1.15】<名字> / <X> to <Y>（不带 -f = 只改本维度）
                    .then(Commands.argument("name", StringArgumentType.string())
                            .suggests(SmoothLift::psdToneNameSuggestions)
                            .executes(context -> pbmMusicItemAudioSet(context, literal, which))
                            .then(Commands.literal("to")
                                    .then(Commands.argument("target", StringArgumentType.string())
                                            .suggests(SmoothLift::psdToneNameSuggestions)
                                            .executes(context -> pbmMusicItemAudioFromTo(context, literal, which)))));
        
    }

    private static int pbmMusicItemForceAll(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
            CommandSourceStack source = context.getSource();
            int changed = EscalatorSpeedManager.setDefaultPsdToneEnabledAll(source.getServer(), which, enabled);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            source.sendSuccess(() -> Component.literal(
                    "已强制**所有维度**的屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音 = "
                            + helpLabel(enabled)), false);
            return 1;
        
    }

    private static int pbmMusicItemForceFromTo(CommandContext<CommandSourceStack> context, String which,
                                               boolean from, boolean to) {
            CommandSourceStack source = context.getSource();
            int changed = EscalatorSpeedManager.replaceDefaultPsdToneEnabledAll(source.getServer(), which, from, to);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            if (changed == 0) {
                source.sendSuccess(() -> Component.literal(
                        "没有任何维度的屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音是 "
                                + helpLabel(from) + "，未做修改"), false);
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                    "已把所有屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音为 " + helpLabel(from)
                            + " 的维度改成 " + helpLabel(to)), false);
            return 1;
        
    }

    private static int pbmMusicItemFromTo(CommandContext<CommandSourceStack> context, String which,
                                          boolean from, boolean to) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            if (!EscalatorSpeedManager.replaceDefaultPsdToneEnabled(level, which, from, to)) {
                boolean current = EscalatorSpeedManager.isPsdToneEnabled(level, which);
                source.sendSuccess(() -> Component.literal(
                        "本维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音不是 " + helpLabel(from)
                                + "，未做修改"), false);
                return 0;
            }
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音从 " + helpLabel(from)
                            + " 改为 " + helpLabel(to)), false);
            return 1;
        
    }

    private static int pbmMusicItemGlobal(CommandContext<CommandSourceStack> context, String which, boolean enabled) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            EscalatorSpeedManager.setDefaultPsdToneEnabled(level, which, enabled);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            source.sendSuccess(() -> Component.literal(
                    "本维度屏蔽门「"
                            + EscalatorSpeedData.psdToneLabel(which) + "」提示音已设为 " + helpLabel(enabled)
                            + "；其它维度不变"), false);
            return 1;
        
    }

    private static int pbmMusicItemShow(CommandContext<CommandSourceStack> context, String which) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            boolean enabled = EscalatorSpeedManager.isPsdToneEnabled(level, which);
            String audio = EscalatorSpeedManager.getPsdToneAudio(level, which);
            source.sendSuccess(() -> Component.literal(
                    "当前维度屏蔽门「" + EscalatorSpeedData.psdToneLabel(which) + "」提示音：开关 " + helpLabel(enabled)
                            + "，默认素材 " + psdToneAudioLabel(audio)), false);
            return 1;
        
    }

    private static int pbmMusicShow(CommandContext<CommandSourceStack> context) {
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            source.sendSuccess(() -> Component.literal(
                    "当前维度屏蔽门开关门提示音：" + helpLabel(EscalatorSpeedManager.isPsdHelpEnabled(level))
                            + "；音量 " + EscalatorSpeedManager.getPsdHelpVolume(level)
                            + "、范围 " + EscalatorSpeedManager.getPsdHelpRound(level) + " 格"), false);
            return 1;
        
    }

    private static String pbmVolumeText(ServerLevel level, String which) {
            int v = EscalatorSpeedManager.getPsdToneVolume(level, which);
            return EscalatorSpeedManager.hasOwnPsdToneVolume(level, which) ? String.valueOf(v) : v + "跟随共用";
        
    }

    private static String[] presetCommands(String presetId) {
            if ("classic".equals(presetId)) {
                return PRESET_CLASSIC_MTR;
            }
            if ("simple".equals(presetId)) {
                return PRESET_SIMPLE_MTR;
            }
            if ("blank".equals(presetId)) {
                return PRESET_BLANK;
            }
            return null;
        
    }

    public static String presetLabel(String presetId) {
            if ("classic".equals(presetId)) {
                return "「经典港铁预设」";
            }
            if ("simple".equals(presetId)) {
                return "「简单港铁预设」";
            }
            if ("blank".equals(presetId)) {
                return "「空白预设」";
            }
            return "「" + presetId + "」";
        
    }

    public static String psdToneAudioLabel(String audioId) {
            if (audioId == null) {
                return "无";
            }
            if (EscalatorSpeedData.PSD_TONE_BUILTIN_OPEN.equals(audioId)) {
                return "default";
            }
            if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE.equals(audioId)) {
                return "default-c";
            }
            if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_M.equals(audioId)) {
                return "default-m";
            }
            if (EscalatorSpeedData.PSD_TONE_BUILTIN_CLOSE_S.equals(audioId)) {
                return "default-s：同素材但不播语音播报段，只播嘀嘀";
            }
            if (EscalatorSpeedData.PSD_TONE_OFF.equals(audioId)) {
                return "none";
            }
            return "「" + audioId + "」";
        
    }

    private static CompletableFuture<Suggestions> psdToneNameSuggestions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
            CommandSourceStack source = context.getSource();
            if (source == null) {
                return builder.buildFuture();
            }
            String typed = builder.getRemainingLowerCase();
            for (String candidate : EscalatorSpeedManager.psdNameCandidates(source.getLevel())) {
                if (candidate.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(candidate);
                }
            }
            return builder.buildFuture();
        
    }

    public static String registryPathOf(BlockState state) {
            if (state == null) {
                return "";
            }
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        
    }

    private static boolean replaceRound(ServerLevel level, RoundKind kind, int from, int to) {
            return switch (kind) {
                case MIDIUM -> EscalatorSpeedManager.replaceDefaultPsdMidiumRound(level, from, to);
                case ARRIVE -> EscalatorSpeedManager.replaceDefaultPsdArriveRound(level, from, to);
                default -> EscalatorSpeedManager.replaceDefaultPsdHelpRound(level, from, to);
            };
        
    }

    private static int replaceRoundAll(MinecraftServer server, RoundKind kind, int from, int to) {
            return switch (kind) {
                case MIDIUM -> EscalatorSpeedManager.replaceDefaultPsdMidiumRoundAll(server, from, to);
                case ARRIVE -> EscalatorSpeedManager.replaceDefaultPsdArriveRoundAll(server, from, to);
                default -> EscalatorSpeedManager.replaceDefaultPsdHelpRoundAll(server, from, to);
            };
        
    }

    private static LiteralArgumentBuilder<CommandSourceStack> roundCommand(String literal, RoundKind kind) {
            return Commands.literal(literal)
                    .executes(context -> roundShow(context, kind))
                    .then(Commands.argument("round", roundArg())
                            .executes(context -> roundGlobal(context, kind))
                            .then(Commands.literal("to")
                                    .then(Commands.argument("target", roundArg())
                                            .executes(context -> roundFromTo(context, kind)))))
                    .then(roundForce(kind));
        
    }

    private static LiteralArgumentBuilder<CommandSourceStack> roundForce(RoundKind kind) {
            return Commands.literal("-f")
                    .then(Commands.argument("round", roundArg())
                            .executes(context -> roundForceAll(context, kind))
                            .then(Commands.literal("to")
                                    .then(Commands.argument("target", roundArg())
                                            .executes(context -> roundForceFromTo(context, kind)))));
        
    }

    private static int roundForceAll(CommandContext<CommandSourceStack> context, RoundKind kind) {
            int round = IntegerArgumentType.getInteger(context, "round");
            CommandSourceStack source = context.getSource();
            setRoundAll(source.getServer(), kind, round);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = roundOf(source, kind);
            source.sendSuccess(() -> Component.literal(
                    "已强制**所有维度**的" + kind.label + "淡入淡出范围 = " + applied
                            + " 格"), false);
            return 1;
        
    }

    private static int roundForceFromTo(CommandContext<CommandSourceStack> context, RoundKind kind) {
            int from = IntegerArgumentType.getInteger(context, "round");
            int to = IntegerArgumentType.getInteger(context, "target");
            CommandSourceStack source = context.getSource();
            int changed = replaceRoundAll(source.getServer(), kind, from, to);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            if (changed == 0) {
                source.sendSuccess(() -> Component.literal(
                        "没有任何维度的" + kind.label + "淡入淡出范围是 " + from + " 格，未做修改"), false);
                return 0;
            }
            source.sendSuccess(() -> Component.literal(
                    "已把所有" + kind.label + "淡入淡出范围为 " + from + " 格的维度改成 " + to
                            + " 格"), false);
            return 1;
        
    }

    private static int roundFromTo(CommandContext<CommandSourceStack> context, RoundKind kind) {
            int from = IntegerArgumentType.getInteger(context, "round");
            int to = IntegerArgumentType.getInteger(context, "target");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            if (!replaceRound(level, kind, from, to)) {
                source.sendSuccess(() -> Component.literal(
                        "本维度" + kind.label + "淡入淡出范围不是 " + from + " 格，未做修改"), false);
                return 0;
            }
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = roundOf(source, kind);
            source.sendSuccess(() -> Component.literal(
                    "本维度" + kind.label + "淡入淡出范围从 " + from + " 格改为 " + applied + " 格"), false);
            return 1;
        
    }

    private static int roundGlobal(CommandContext<CommandSourceStack> context, RoundKind kind) {
            int round = IntegerArgumentType.getInteger(context, "round");
            CommandSourceStack source = context.getSource();
            ServerLevel level = source.getLevel();
            setRound(level, kind, round);
            EscalatorSpeedManager.syncPsdChimeToAll(source.getServer());
            int applied = roundOf(source, kind);
            source.sendSuccess(() -> Component.literal(
                    "本维度" + kind.label + "淡入淡出范围已设为 " + applied
                            + " 格；其它维度不变"), false);
            return 1;
        
    }

    private static int roundOf(CommandSourceStack source, RoundKind kind) {
            return switch (kind) {
                case MIDIUM -> EscalatorSpeedManager.getPsdMidiumRound(source.getLevel());
                case ARRIVE -> EscalatorSpeedManager.getPsdArriveRound(source.getLevel());
                default -> EscalatorSpeedManager.getPsdHelpRound(source.getLevel());
            };
        
    }

    private static int roundShow(CommandContext<CommandSourceStack> context, RoundKind kind) {
            CommandSourceStack source = context.getSource();
            int round = roundOf(source, kind);
            source.sendSuccess(() -> Component.literal(
                    "当前维度" + kind.label + "淡入淡出范围：" + round + " 格"), false);
            return 1;
        
    }

    private static int runCommandBatch(ServerPlayer player, String[] commands) {
            CommandSourceStack base = player.createCommandSourceStack().withSuppressedOutput();
            int ok = 0;
            for (String command : commands) {
                // performPrefixedCommand 内部会吃掉解析失败（只记录异常），不会把整批打断。
                player.getServer().getCommands().performPrefixedCommand(base, command);
                ok++;
            }
            return ok;
        
    }

    private static void sendUnknownArriveName(CommandSourceStack source, String name) {
            java.util.List<String> have = EscalatorSpeedManager.psdArriveSuggestions(source.getLevel());
            String list = String.join("、", have);
            source.sendFailure(Component.literal(
                    "找不到名为「" + name + "」的音频。"
                            + "已有的：" + (list.isEmpty() ? "" : list)));
        
    }

    private static void sendUnknownMidiumName(CommandSourceStack source, String name) {
            java.util.List<String> have = EscalatorSpeedManager.psdMidiumSuggestions(source.getLevel());
            String list = String.join("、", have);
            source.sendFailure(Component.literal(
                    "找不到名为「" + name + "」的音频。"
                            + "已有的：" + (list.isEmpty() ? "" : list)));
        
    }

    private static void setRound(ServerLevel level, RoundKind kind, int round) {
            switch (kind) {
                case MIDIUM -> EscalatorSpeedManager.setDefaultPsdMidiumRound(level, round);
                case ARRIVE -> EscalatorSpeedManager.setDefaultPsdArriveRound(level, round);
                default -> EscalatorSpeedManager.setDefaultPsdHelpRound(level, round);
            }
        
    }

    private static int setRoundAll(MinecraftServer server, RoundKind kind, int round) {
            return switch (kind) {
                case MIDIUM -> EscalatorSpeedManager.setDefaultPsdMidiumRoundAll(server, round);
                case ARRIVE -> EscalatorSpeedManager.setDefaultPsdArriveRoundAll(server, round);
                default -> EscalatorSpeedManager.setDefaultPsdHelpRoundAll(server, round);
            };
        
    }

    private static String syncEscalator(MinecraftServer server, ServerLevel level,
                                        int scope, boolean force, BlockPos pos) {
            if (scope == 0) {
                // 五个值各读一次「这一条扶梯此刻生效的值」= 单独设置优先、否则维度默认
                double run = EscalatorSpeedManager.getSpeed(level, pos);
                double step = EscalatorSpeedManager.getAnimationSpeed(level, pos);
                int volume = EscalatorSpeedManager.getVolumeForScreen(level, pos);
                int helpVolume = EscalatorSpeedManager.getHelpVolume(level, pos);
                boolean help = EscalatorSpeedManager.isHelpEnabled(level, pos);
                if (force) {
                    EscalatorSpeedManager.forceGlobalRunSpeed(level, run);
                    EscalatorSpeedManager.forceGlobalStepSpeed(level, step);
                    EscalatorSpeedManager.forceDefaultVolume(level, volume);
                    EscalatorSpeedManager.forceDefaultHelpVolume(level, helpVolume);
                    EscalatorSpeedManager.forceDefaultHelp(level, help);
                } else {
                    EscalatorSpeedManager.setGlobalRunSpeed(level, run);
                    EscalatorSpeedManager.setGlobalStepSpeed(level, step);
                    EscalatorSpeedManager.setDefaultVolume(level, volume);
                    EscalatorSpeedManager.setDefaultHelpVolume(level, helpVolume);
                    EscalatorSpeedManager.setDefaultHelp(level, help);
                }
                // 速度走全量包，另外四项各有专用包 —— 与「拆扶梯」那条清理路径同一组
                EscalatorSpeedManager.syncToAll(server);
                EscalatorSpeedManager.syncVolumeToAll(server);
                EscalatorSpeedManager.syncHelpToAll(server);
                EscalatorSpeedManager.syncHelpVolumeToAll(server);
                String values = "速度 " + EscalatorSpeedData.format(run)
                        + " · 音量 " + volume + " · 提示音音量 " + helpVolume
                        + " · 无障碍" + (help ? "开" : "关");
                return force
                        ? "已强制同步扶梯：" + values + " —— 所有扶梯都改成这一套，单独设置过的也一起改"
                        : "已同步扶梯：" + values + " 已设为默认 —— 单独设置过的扶梯保持不动";
            }
            if (scope == SYNC_ESC_AUDIO) {
                String id = EscalatorSpeedManager.getBlockAudioId(level, pos);
                if (id == null) {
                    id = EscalatorSpeedManager.getDefaultAudio(level);
                }
                if (force) {
                    EscalatorSpeedManager.forceDefaultAudio(level, id);
                } else {
                    EscalatorSpeedManager.setDefaultAudio(level, id);
                }
                EscalatorSpeedManager.syncAudioToAll(server);
                return force
                        ? "已强制同步扶梯声音：所有扶梯都改用 " + audioLabel(id) + "，单独绑定过的也一起改"
                        : "已同步扶梯声音：" + audioLabel(id) + " 已设为默认 —— 单独绑定过的扶梯保持不动";
            }
            if (scope == SYNC_ESC_HELP_AUDIO) {
                // 进 / 出两端各一个默认字段，都要同步
                String in = EscalatorSpeedManager.getHelpAudioForScreen(level, pos, true);
                String out = EscalatorSpeedManager.getHelpAudioForScreen(level, pos, false);
                if (force) {
                    EscalatorSpeedManager.forceDefaultHelpAudio(level, in, true);
                    EscalatorSpeedManager.forceDefaultHelpAudio(level, out, false);
                } else {
                    EscalatorSpeedManager.setDefaultHelpAudio(level, in, true);
                    EscalatorSpeedManager.setDefaultHelpAudio(level, out, false);
                }
                EscalatorSpeedManager.syncHelpAudioToAll(server);
                return force
                        ? "已强制同步扶梯提示音素材：两端都改成 进 " + audioLabel(in)
                            + " / 出 " + audioLabel(out) + "，所有扶梯都照此"
                        : "已同步扶梯提示音素材为默认：进 " + audioLabel(in)
                            + " / 出 " + audioLabel(out) + " —— 单独设置过的扶梯保持不动";
            }
            return "同步失败：未知的扶梯页 " + scope;
        
    }

    private static String syncLift(MinecraftServer server, ServerLevel level,
                                   int scope, boolean force, long key) {
            String[] whichs;
            if (scope == 0) {
                whichs = SYNC_LIFT_WHICH;
            } else if (scope >= 1 && scope <= SYNC_LIFT_WHICH.length) {
                whichs = new String[]{SYNC_LIFT_WHICH[scope - 1]};
            } else {
                return "同步失败：未知的直梯页 " + scope;
            }
            for (String which : whichs) {
                String id = EscalatorSpeedManager.toneField(
                        EscalatorSpeedManager.getServerLiftTone(level, key), which);
                if (id == null || EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(id)) {
                    // 这一项写着「跟维度默认」⇒ 它此刻生效的就是维度默认本身，原样取回来
                    id = EscalatorSpeedManager.getLiftToneAudio(level, which);
                }
                if (force) {
                    EscalatorSpeedManager.setDefaultLiftToneAudioAll(server, which, id);
                } else {
                    EscalatorSpeedManager.setDefaultLiftToneAudio(level, which, id);
                }
            }
            EscalatorSpeedManager.syncLiftToneToAll(server);
            if (scope == 0) {
                return force
                        ? "已强制同步直梯提示音素材：所有直梯的上楼 / 下楼 / 开关门都改用这条直梯的设置"
                        : "已同步直梯提示音素材为默认：没单独设置过的直梯跟着变，单独设置过的保持不动";
            }
            String label = EscalatorSpeedManager.liftToneEnabledLabel(whichs[0]);
            return force
                    ? "已强制同步直梯" + label + "素材：所有直梯都改用这条直梯的设置"
                    : "已同步直梯" + label + "素材为默认：单独设置过的直梯保持不动";
        
    }

    private static String syncPsd(MinecraftServer server, ServerLevel level,
                                  int scope, boolean force, long key) {
            EscalatorSpeedData data = EscalatorSpeedManager.getServerData(level);
            if (scope == 0) {
                int closeWait = EscalatorSpeedManager.getDoorPsdCloseWaitSeconds(level, key);
                int openVolume = EscalatorSpeedManager.getDoorPsdToneVolume(level, key, "open");
                int closeVolume = EscalatorSpeedManager.getDoorPsdToneVolume(level, key, "close");
                int midiumWait = EscalatorSpeedManager.getDoorPsdMidiumWaitSeconds(level, key);
                int midiumVolume = EscalatorSpeedManager.getDoorPsdMidiumVolume(level, key);
                int arriveSeconds = EscalatorSpeedManager.getDoorPsdArriveSeconds(level, key);
                int arriveVolume = EscalatorSpeedManager.getDoorPsdArriveVolume(level, key);
                // 到站 / 进站的「素材 + 等待秒数」挤在同一只 API 里 ⇒ 把当前默认素材原样写回去，
                // 素材不变、只改秒数。素材值必须在开始写之前读，否则会被自己改掉。
                String midiumAudio = data.defaultPsdMidiumAudio;
                String arriveAudio = data.defaultPsdArriveAudio;
                if (force) {
                    EscalatorSpeedManager.setDefaultPsdCloseWaitSecondsAll(server, closeWait);
                    EscalatorSpeedManager.setDefaultPsdToneVolumeAll(server, "open", openVolume);
                    EscalatorSpeedManager.setDefaultPsdToneVolumeAll(server, "close", closeVolume);
                    EscalatorSpeedManager.setDefaultPsdMidiumAll(server, midiumAudio, midiumWait);
                    EscalatorSpeedManager.setDefaultPsdMidiumVolumeAll(server, midiumVolume);
                    EscalatorSpeedManager.setDefaultPsdArriveAll(server, arriveAudio, arriveSeconds);
                    EscalatorSpeedManager.setDefaultPsdArriveVolumeAll(server, arriveVolume);
                } else {
                    EscalatorSpeedManager.setDefaultPsdCloseWaitSeconds(level, closeWait);
                    EscalatorSpeedManager.setDefaultPsdToneVolume(level, "open", openVolume);
                    EscalatorSpeedManager.setDefaultPsdToneVolume(level, "close", closeVolume);
                    EscalatorSpeedManager.setDefaultPsdMidium(level, midiumAudio, midiumWait);
                    EscalatorSpeedManager.setDefaultPsdMidiumVolume(level, midiumVolume);
                    EscalatorSpeedManager.setDefaultPsdArrive(level, arriveAudio, arriveSeconds);
                    EscalatorSpeedManager.setDefaultPsdArriveVolume(level, arriveVolume);
                }
                EscalatorSpeedManager.syncPsdChimeToAll(server);
                String values = "关门等待 " + closeWait + "s · 开门音量 " + openVolume
                        + " · 关门音量 " + closeVolume + " · 到站等待 " + midiumWait
                        + "s · 到站音量 " + midiumVolume + " · 进站提前 " + arriveSeconds
                        + "s · 进站音量 " + arriveVolume;
                String tail = " ● 开门等待没有默认值，未同步";
                return force
                        ? "已强制同步屏蔽门：" + values + " —— 所有门串都改成这一套" + tail
                        : "已同步屏蔽门：" + values + " 已设为默认 —— 单独设置过的门串保持不动" + tail;
            }
            if (scope >= 1 && scope <= SYNC_PSD_WHICH.length) {
                String which = SYNC_PSD_WHICH[scope - 1];
                EscalatorSpeedData.PsdToneAudio record = EscalatorSpeedManager.psdDoorRecord(level, key);
                String id = "open".equals(which) ? record.open() : record.close();
                if (id == null || EscalatorSpeedData.PSD_TONE_DEFAULT.equals(id)) {
                    // 这一项写着「跟维度默认」⇒ 它此刻生效的就是维度默认本身
                    id = EscalatorSpeedManager.getPsdToneAudio(level, which);
                }
                if (force) {
                    EscalatorSpeedManager.setDefaultPsdToneAudioAll(server, which, id);
                } else {
                    EscalatorSpeedManager.setDefaultPsdToneAudio(level, which, id);
                }
                EscalatorSpeedManager.syncPsdToneToAll(server);
                String name = "open".equals(which) ? "开门" : "关门";
                return force
                        ? "已强制同步屏蔽门" + name + "提示音：" + audioLabel(id) + " —— 所有门串都照此"
                        : "已同步屏蔽门" + name + "提示音：" + audioLabel(id)
                            + " 已设为默认 —— 单独设置过的门串保持不动";
            }
            if (scope == SYNC_PSD_MIDIUM_PAGE) {
                String id = EscalatorSpeedManager.getDoorPsdMidiumAudio(level, key);
                if (force) {
                    EscalatorSpeedManager.setDefaultPsdMidiumAll(server, id, data.defaultPsdMidiumWaitSeconds);
                } else {
                    EscalatorSpeedManager.setDefaultPsdMidium(level, id, data.defaultPsdMidiumWaitSeconds);
                }
                EscalatorSpeedManager.syncPsdToneToAll(server);
                return force
                        ? "已强制同步到站播报：" + audioLabel(id) + " —— 所有门串都照此"
                        : "已同步到站播报：" + audioLabel(id) + " 已设为默认 —— 单独设置过的门串保持不动";
            }
            if (scope == SYNC_PSD_ARRIVE_PAGE) {
                String id = EscalatorSpeedManager.getDoorPsdArriveAudio(level, key);
                if (force) {
                    EscalatorSpeedManager.setDefaultPsdArriveAll(server, id, data.defaultPsdArriveSeconds);
                } else {
                    EscalatorSpeedManager.setDefaultPsdArrive(level, id, data.defaultPsdArriveSeconds);
                }
                EscalatorSpeedManager.syncPsdToneToAll(server);
                return force
                        ? "已强制同步进站报站：" + audioLabel(id) + " —— 所有门串都照此"
                        : "已同步进站报站：" + audioLabel(id) + " 已设为默认 —— 单独设置过的门串保持不动";
            }
            return "同步失败：未知的屏蔽门页 " + scope;
        
    }

    public static String syncSettings(MinecraftServer server, ServerLevel level,
                                       String domain, int scope, boolean force, long key) {
            return switch (domain) {
                case "esc" -> syncEscalator(server, level, scope, force, BlockPos.of(key));
                case "lift" -> syncLift(server, level, scope, force, key);
                case "psd" -> syncPsd(server, level, scope, force, key);
                case "train" -> syncTrain(server, level, scope, force, key);
                default -> "同步失败：未知的范围 " + domain;
            };
        
    }

    private static String syncTrain(MinecraftServer server, ServerLevel level,
                                    int scope, boolean force, long key) {
            String which = switch (scope) {
                case SYNC_TRAIN_RUN -> "列车运行音效";
                case SYNC_TRAIN_TURN -> "列车转弯音效";
                case SYNC_TRAIN_SWITCH -> "列车道岔音效";
                case SYNC_TRAIN_ARRIVE -> "列车进站音效";
                case SYNC_TRAIN_DEPART -> "列车出站音效";
                default -> "列车音效";
            };
            return which + "还没接数据层，暂时没有可同步的设置";
        
    }

    /** 【1.53】/MBM help（及裸 /MBM）→ 服务端发空包让客户端打开「预设选择」界面。 */
    private static int mbmOpenHelp(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("这条指令需要由玩家执行"));
            return 0;
        }
        Packets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new MbmHelpOpenPacket());
        return 1;
    }

    /** 【1.24】三个「范围」指令（/pbmround 等）的类型标签。 */
    private enum RoundKind {
        /** 屏蔽门开关门提示音（{@code /pbmround}、{@code /pbmmusicround}）。 */
        TONE("屏蔽门提示音"),
        /** 到站播报（{@code /pbmmidiumround}）。 */
        MIDIUM("到站播报"),
        /** 进站报站（{@code /pbmarriveround}）。 */
        ARRIVE("进站报站");

        /** 反馈文案里的那一项名字（与 /pbmloud 那一套用词一致）。 */
        private final String label;

        RoundKind(String label) {
            this.label = label;
        }
    }

}
