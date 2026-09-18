package smooth.lift;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 每个维度一份，随世界存档自动加载/保存（存在 <世界>/<维度>/data/smoothlift_speeds.dat）。
 *
 * defaultSpeed：本维度未单独调速的扶梯使用的默认运行速度。
 * speeds：每个扶梯方块的运行速度（石斧设置）。
 *
 * 【1.6 起】阶梯动画速度以「每条扶梯单独设置」为主：
 * stepSpeeds：每条扶梯（链上每个方块）单独设置的阶梯动画速度（石斧设置）。
 *             没有单独设置的扶梯回退到「维度默认阶梯动画速度」。
 * axeModified：石斧设置过阶梯动画的扶梯方块集合（用于同步与 /jietispeed 的 f 覆盖）。
 * stepEnabled / stepValue：维度默认阶梯动画速度（未单独设置的扶梯使用）；
 *             stepEnabled=false 表示用 MTR 原版动画（{@link #VANILLA_STEP}）。
 *
 * 【1.7 起】自定义扶梯声音：
 * audioLibrary：音频ID → OGG 文件字节。音频按内容哈希去重存一份，天然支持"一个音频复用多条扶梯"。
 * blockAudio：扶梯方块（x,y,z）→ 音频ID。未绑定的扶梯不播放声音（静音）。
 *             受 NBT 读取大小限制（NbtIo.readCompressed 带 NbtSizeTracker），单音频不超过
 *             {@link #MAX_AUDIO_BYTES}（12MB），超出时服务端拒绝接收。
 *
 * 【1.9 起】扶梯声音音量：
 * blockVolume：扶梯方块（x,y,z）→ 音量（1~1000；【1.12】100 = 原始音量，1000 = 10× 放大）。
 *             只记录被调过的（≠100）方块，未记录的按默认 100 处理，旧存档缺这一段也能正常读。
 *
 * 【1.11 起】默认扶梯音频：
 * defaultAudio：未单独绑定音频的扶梯使用的默认音频 ID（/futimusic 设置）。
 *              null 表示不发声（未绑定的扶梯静音，与 1.10 之前的行为一致）。
 *
 * 【1.16 起】无障碍提示音开关（香港式「视障人士提升音」，见客户端 EscalatorChimePlayer）：
 * defaultHelp：本维度未单独设置的扶梯是否播放提示音（/futihelp on|off 设置）。默认 true = 开。
 * blockHelp：扶梯方块（x,y,z）→ 该条扶梯是否播放提示音（石斧界面里的「无障碍提示音」开关）。
 *             只记录与 {@link #defaultHelp} **不同**的项，未记录的按默认处理，旧存档缺这一段也能正常读
 *             （缺省即 true = 开，与 1.15 的「一直响」行为一致）。
 *             同一维度的默认值 + 单独设置，与 /futispeed、/futimusic、/futiloud 完全对称。
 *
 * 【1.18 起】无障碍提示音**音量**（/futihelploud 与石斧界面设置）：
 * defaultHelpVolume：本维度未单独设置的扶梯，提示音音量用多少（/futihelploud &lt;音量&gt; 设置）。默认 100 = 原始音量。
 * blockHelpVolume：扶梯方块（x,y,z）→ 提示音音量。只记录与 {@link #defaultHelpVolume} **不同**的项，
 *             未记录的按默认处理，旧存档缺这一段也能正常读（缺省即 100 = 原始音量，与 1.17 的「固定音量」一致）。
 *             注意这与 {@link #defaultVolume} / {@link #blockVolume}（扶梯**运行底噪**的音量）是**两件不同的事**：
 *             底噪声作用在整条扶梯上、射程 16 格；提示音装在端头**单块**方块上、射程 4 格（见 EscalatorChimePlayer）。
 *             两套音量的数据与指令（/futiloud vs /futihelploud）互不影响。
 *
 * 【1.24 起】两个「淡入淡出范围」（单位格）——把两个音源的作用半径做成可调的：
 * defaultRound / blockRound：**扶梯运行底噪**（整条扶梯一起响那路）可闻的距离，
 *             /futiround 设置，默认 {@link #DEFAULT_ROUND} = 16 格（整条扶梯一起响的环境音）。
 * defaultHelpRound / blockHelpRound：**无障碍提示音**（端头单块那路）可闻的距离，
 *             /futihelpround 设置，默认 {@link #DEFAULT_HELP_ROUND} = 4 格（点状音源）。
 *             两者与 1.17 / 1.18 定的「16 : 4」语义一致，只是现在**可调**了。
 *             与音量一样只记录与默认**不同**的项，旧存档缺这一段就按默认处理。
 *             <b>这两个范围是「淡入淡出」的半径而不是硬截断</b>：底噪在半径内线性衰减到 0、
 *             提示音在半径内平方衰减到 0，所以「听得见的最远距离」就等于它。
 *             注意 1.24 **没有给它们加石斧界面控件**（用户要求），只能靠指令改。
 *
 * <p>至此，每一个「可调项」都统一是「维度默认 + 每条扶梯单独设置」两层：
 * 速度（defaultSpeed）、阶梯动画（stepValue/stepSpeeds）、音频（defaultAudio/blockAudio）、
 * 底噪音量（defaultVolume/blockVolume）、提示音开关（defaultHelp/blockHelp）、
 * 提示音音量（defaultHelpVolume/blockHelpVolume）、
 * 底噪范围（defaultRound/blockRound）、提示音范围（defaultHelpRound/blockHelpRound）、
 * 提示音速率（defaultHelpSpeedIn/Out + blockHelpSpeedIn/Out）、
 * 提示音音乐（defaultHelpAudioIn/Out + blockHelpAudioIn/Out）。
 *
 * 【1.31 起】无障碍提示音的**速率**（每秒响几次，单位 Hz）——把 1.25~1.27 定死的
 * 「入口 10 Hz / 出口 1 Hz」做成可调（{@code /futihelpspeed in|out <Hz>}）：
 * defaultHelpSpeedIn / blockHelpSpeedIn：**进入扶梯（上客端）**那一路的速率，默认 10 Hz。
 * defaultHelpSpeedOut / blockHelpSpeedOut：**离开扶梯（落客端）**那一路的速率，默认 1 Hz。
 *             两者与开关（defaultHelp）、音量（defaultHelpVolume）、范围（defaultHelpRound）
 *             是**四套互不影响**的数据，都作用在同一路提示音上：
 *             开关决定「响不响」、音量「多响」、范围「多远还听得见」、速率「响得多快」。
 *             也只记录与默认**不同**的项，旧存档缺这一段就按 10 / 1 Hz 处理
 *             （正好等于 1.27 定版音色，等于没改过）。
 *             注意「速率」是靠**换素材 + 调 pitch** 实现的（原版把 pitch 夹在 [0.5,2.0]），
 *             细节见 {@code EscalatorChimePlayer}；取值范围 {@link #HELP_SPEED_MIN}~{@link #HELP_SPEED_MAX}。
 *
 * 【1.39 起】无障碍提示音的**音乐**（用哪段声音当提示音）：
 * defaultHelpAudioIn/Out / blockHelpAudioIn/Out：与 {@code /futimusic} 的运行底噪**完全对称**的
 *             第二套「音频绑定」，但**共用同一个导入文件夹与同一个 {@link #audioLibrary}**
 *             （导入一次，两边都能选）。
 *             {@link #HELP_AUDIO_DEFAULT} = 模组原来的提示音（初始值，旧存档缺字段也是它）；
 *             {@link #HELP_AUDIO_OFF} = 这一头不播提示音；其它值 = 音频库里的文件名。
 *             ★ 速率（/futihelpspeed）只对 {@link #HELP_AUDIO_DEFAULT} 生效 ——
 *             自定义音频按原速循环播（素材是玩家自己的，没法按 1/4/10/25/50 Hz 分档）。
 *
 * 【1.41 起】提示音**音乐**也分成**进入 / 离开**两套（形状同 /futihelpspeed 的 in|out）：
 *             进扶梯那一头（上客端）与出扶梯那一头（落客端）可以各放各的声音，
 *             于是「进站播一段、出站播另一段」这种需求不用再靠改素材实现。
 *             1.39 的单一字段（{@code defaultHelpAudio} / {@code blockHelpAudio}）在
 *             {@link #fromTag} 里被**同时**当作两头初值读入 ⇒ 旧存档听感逐字节不变；
 *             保存时再把它当**兼容镜像**写回（= 进扶梯那一头），供回退版本读取。
 *             ★ `off` 的粒度也跟着细了：现在可以只让**这一头**不响、另一头照常响
 *             （在 {@code EscalatorChimePlayer} 里按端头分别拦，见那里 1.41 段）。
 *
 */
public class EscalatorSpeedData extends SavedData {
    public static final String DATA_NAME = "smoothlift_speeds";
    public static final double DEFAULT_SPEED = 1.0;
    public static final double MAX_SPEED = 50.0;

    /** MTR 原版阶梯贴图动画对应的运行速度标定值。阶移动画基准：把速度除以它得到倍率。 */
    public static final double VANILLA_STEP = 0.625;

    /** 单个自定义音频的大小上限（字节）。防止存档 NBT 超限，且避免拖慢声音解码。 */
    public static final int MAX_AUDIO_BYTES = 12 * 1024 * 1024;

    /**
     * 【1.9】扶梯声音音量：界面输入范围 1~1000。
     * <p>【1.12】100 = **原始音量（1.0×）**，1000 = **10× 放大**。
     * 原版 {@code SoundEngine.calculateVolume} 会把增益夹到 [0,1]，所以 &gt;100 的放大由
     * 客户端 Mixin（{@code SoundEngineVolumeMixin}）放开上限实现，详见 {@code EscalatorAudioPlayer}。
     */
    public static final int AUDIO_VOLUME_MIN = 1;
    public static final int AUDIO_VOLUME_MAX = 1000;
    /** 默认（= 原始 1.0×）音量。只记录与该值不同的项，未记录即按 100 处理。 */
    public static final int DEFAULT_AUDIO_VOLUME = 100;

    /**
     * 【1.18】无障碍**提示音**音量：范围与 {@link #AUDIO_VOLUME_MIN}~{@link #AUDIO_VOLUME_MAX} 完全一致
     * （1~1000，100 = 原始音量，1000 = 10× 放大）。这里直接引用音频那三个常量，
     * 保证两套音量永远不会因为改了一处而悄悄不一致。
     *
     * <p>提示音的实际增益 = {@code 距离衰减（4 格内平方衰减）× 这个百分比}；
     * &gt;100 的放大同样由客户端 {@code SoundEngineVolumeMixin} 放开 [0,1] 夹取来实现。
     */
    public static final int HELP_VOLUME_MIN = AUDIO_VOLUME_MIN;
    public static final int HELP_VOLUME_MAX = AUDIO_VOLUME_MAX;
    /** 默认提示音音量 = 100（原始音量）。只记录与该值不同的项。 */
    public static final int DEFAULT_HELP_VOLUME = DEFAULT_AUDIO_VOLUME;

    /**
     * 【1.24】两个「淡入淡出范围」的取值区间（单位：格）。
     *
     * <p>上限取 128 是因为扫描半径是按它算的（AABB 预筛 / 逐格 abs 比较），
     * 128 格对任何车站都够用，而代价只有「附近没有扶梯时多扫一点」——
     * 那一段本来就在未加载区块里，开销可以忽略。
     */
    public static final int ROUND_MIN = 1;
    public static final int ROUND_MAX = 128;

    /**
     * 【1.24】**扶梯运行底噪**（{@code EscalatorAudioPlayer}，整条扶梯一起响）的默认可闻范围 = 16 格。
     * 与 1.9 起一直沿用的 {@code MAX_DISTANCE = 16.0} 一致，只是现在可以被 /futiround 改。
     */
    public static final int DEFAULT_ROUND = 16;

    /**
     * 【1.24】**无障碍提示音**（{@code EscalatorChimePlayer}，端头单块）的默认可闻范围 = 4 格。
     * 与 1.17 定的 {@code RANGE = 4.0} 一致。两者**故意不同**（16 : 4），别再混。
     */
    public static final int DEFAULT_HELP_ROUND = 4;

    /**
     * 【1.31】无障碍提示音**速率**的取值区间，单位 **Hz（每秒响几次）**。
     *
     * <p>播放侧靠「**多个素材 × pitch**」拼速率：原版 {@code SoundEngine.calculatePitch} 把 pitch
     * 夹在 <b>[0.5, 2.0]</b>（字节码：{@code Mth.clamp(getPitch(), 0.5F, 2.0F)}），一个素材只覆盖
     * 4 倍的速率区间，所以用 1 / 4 / 10 Hz 三个素材的 {@code [0.5×, 2×]} 区间首尾相接，
     * 合起来正好覆盖 <b>[0.5, 20] Hz</b>。
     *
     * <p><b>【1.34】上限从 20 提到 100</b>（用户反馈「1-20 太小了」）：同时新增了 <b>25 Hz</b> 与
     * <b>50 Hz</b> 两个素材，一度开到 <b>[1, 100] Hz</b>。
     *
     * <p><b>【1.38】上限回落 100 → 50</b>（用户要求「无障碍提示频率范围从 1-1000 改为 1-50」，
     * 经确认指的就是本参数）：高段（50~100 Hz）听感上已经是「一片连续电流声」而不是「一响一响」，
     * 无障碍提示的语义（让人数得清、跟得上扶梯进出）反而丢了，所以砍掉。
     * <b>素材不需要动</b>：tier5 是 50 Hz 原始素材，pitch 钳在 [0.5, 2.0] ⇒ 单独就能覆盖
     * [25, 50] Hz，正好接上 tier4（25 Hz，覆盖 [12.5, 25]）—— 加素材才覆盖得到 100，
     * 而 50 以内不需要，所以这次只改常量即可。
     * ★ 这个常量**必须与素材一起改**：只放宽常量而不加素材，超出的部分会被 pitch 钳制
     * 悄悄压回去，表现是「调 50 跟调 20 一模一样」（见 {@code EscalatorChimePlayer#chimeEventFor}）。
     */
    public static final int HELP_SPEED_MIN = 1;
    public static final int HELP_SPEED_MAX = 50;

    /**
     * 【1.31】**进入扶梯（上客端）**无障碍提示音的默认速率 = **10 Hz**（1/10 秒一响）。
     *
     * <p>与 1.25~1.27 由用户真机听感定下的「入口 1/10 秒一次」完全一致；
     * 它也正好等于 10 Hz 素材的原始速率（pitch = 1.0），所以**默认档不改动任何既有音色**。
     */
    public static final int DEFAULT_HELP_SPEED_IN = 10;

    /**
     * 【1.31】**离开扶梯（落客端）**无障碍提示音的默认速率 = **1 Hz**（1 秒一响）。
     *
     * <p>与 1.27 定下的「出口 1 秒一次」一致，也正好等于 1 Hz 素材的原始速率（pitch = 1.0）。
     */
    public static final int DEFAULT_HELP_SPEED_OUT = 1;

    /**
     * 【1.39】无障碍提示音「音乐」的哨兵 ID：**模组原来的提示音**（五档「咔啪」素材 + 速率分档）。
     *
     * <p>这是 {@link #defaultHelpAudioIn} / {@link #defaultHelpAudioOut} 的初始值，
     * 也是旧存档缺字段时的取值 ⇒ **1.38 及之前的行为逐字节不变**
     * （进扶梯端 10 Hz、出扶梯端 1 Hz 那套）。
     *
     * <p>它和 {@code /futimusic} 的 {@code default}（内置运行底噪）是**两件不同的事**：
     * 那个是整条扶梯 23 秒的环境音，这个是端头 2 秒一循环的定位提示音。
     */
    public static final String HELP_AUDIO_DEFAULT = "default";

    /**
     * 【1.39】无障碍提示音「音乐」的哨兵 ID：**这条扶梯不播提示音**（比 {@code /futihelp off} 更细 ——
     * 可以只让某一条扶梯哑掉，而不动维度默认与其它扶梯）。
     */
    public static final String HELP_AUDIO_OFF = "off";

    public double defaultSpeed = DEFAULT_SPEED;
    public final Map<BlockPos, Double> speeds = new HashMap<>();

    /** 石斧自定义过阶梯动画的扶梯方块集合（/jietispeed on|off 默认忽略它们）。 */
    public final Set<BlockPos> axeModified = new HashSet<>();
    /** 石斧给这些扶梯单独设置的阶梯动画速度（仅 stepSpeeds 里的方块有效）。 */
    public final Map<BlockPos, Double> stepSpeeds = new HashMap<>();
    /** 维度默认阶梯动画速度开关：false = MTR 原版动画；true = 使用 stepValue。 */
    public boolean stepEnabled = false;
    /** 维度默认阶梯动画速度值（最后一次 /jietispeed X 设定的值）。 */
    public double stepValue = DEFAULT_SPEED;

    /** 音频ID → OGG 文件字节（按内容哈希去重，一个音频可被多条扶梯复用）。 */
    public final Map<String, byte[]> audioLibrary = new HashMap<>();
    /** 扶梯方块（x,y,z）→ 音频ID。未绑定音频的扶梯不播放声音。 */
    public final Map<BlockPos, String> blockAudio = new HashMap<>();

    /**
     * 【1.9】扶梯方块（x,y,z）→ 声音音量（1~1000，100 = 原始音量，可放大到 1000 = 10×）。
     * 只记录与 {@link #defaultVolume} **不同**的项（默认情况即 100），未记录的按默认音量处理，
     * 这样 NBT 不会膨胀。
     */
    public final Map<BlockPos, Integer> blockVolume = new HashMap<>();

    /**
     * 【1.12】默认扶梯音量（/futiloud 设置）：**没有单独设置音量**的扶梯使用它。
     * 默认 100（= 原始音量）。与 /futispeed 的 defaultSpeed、/futimusic 的 defaultAudio 完全对称。
     */
    public int defaultVolume = DEFAULT_AUDIO_VOLUME;

    /**
     * 【1.11】默认扶梯音频 ID（/futimusic 设置）：**没有单独绑定音频**的扶梯使用它。
     * 内置音频存 {@code builtin:<key>}，玩家上传的存文件名（如 {@code example.ogg}）。
     * {@code null} = 没有默认音频，未绑定的扶梯保持静音（与 1.10 之前一致，旧存档也兼容）。
     */
    public String defaultAudio;

    /**
     * 【1.16】维度默认无障碍提示音开关（/futihelp on|off）：**没有单独设置**的扶梯是否播提示音。
     * 默认 true = 开（旧存档缺这一段也是开，与 1.15 的「一直响」行为一致）。
     */
    public boolean defaultHelp = true;

    /**
     * 【1.16】扶梯方块（x,y,z）→ 这条扶梯是否播放无障碍提示音。
     * 只记录与 {@link #defaultHelp} **不同**的项（同一开关，多数情况为空），NBT 不会膨胀。
     */
    public final Map<BlockPos, Boolean> blockHelp = new HashMap<>();

    /**
     * 【1.18】维度默认无障碍提示音音量（/futihelploud 设置）：**没有单独设置**的扶梯使用它。
     * 默认 100（= 原始音量）。与 {@link #defaultVolume}（底噪音量）是两套互不影响的数据。
     */
    public int defaultHelpVolume = DEFAULT_HELP_VOLUME;

    /**
     * 【1.18】扶梯方块（x,y,z）→ 这条扶梯的无障碍提示音音量（1~1000，100 = 原始音量）。
     * 只记录与 {@link #defaultHelpVolume} **不同**的项，未记录的按默认音量处理，旧存档缺这一段也能正常读。
     */
    public final Map<BlockPos, Integer> blockHelpVolume = new HashMap<>();

    /**
     * 【1.24】维度默认**扶梯运行底噪**的可闻范围（/futiround 设置，单位格）。
     * 默认 {@link #DEFAULT_ROUND} = 16。与 {@link #defaultHelpRound}（提示音）是两套互不影响的数据。
     */
    public int defaultRound = DEFAULT_ROUND;

    /**
     * 【1.24】扶梯方块（x,y,z）→ 这条扶梯运行底噪的可闻范围（单位格）。
     * 只记录与 {@link #defaultRound} **不同**的项。1.24 **没有石斧界面控件**，所以正常情况下这里是空的，
     * 只有 {@code /futiround -f <X> to <Y>} 在「先把个别扶梯改成别的值」时才会用到（留给以后加 UI）。
     */
    public final Map<BlockPos, Integer> blockRound = new HashMap<>();

    /**
     * 【1.24】维度默认**无障碍提示音**的可闻范围（/futihelpround 设置，单位格）。
     * 默认 {@link #DEFAULT_HELP_ROUND} = 4。与 {@link #defaultRound}（底噪）是两套互不影响的数据。
     */
    public int defaultHelpRound = DEFAULT_HELP_ROUND;

    /**
     * 【1.24】扶梯方块（x,y,z）→ 这条扶梯无障碍提示音的可闻范围（单位格）。
     * 只记录与 {@link #defaultHelpRound} **不同**的项；同样没有界面控件。
     */
    public final Map<BlockPos, Integer> blockHelpRound = new HashMap<>();

    /**
     * 【1.31】维度默认**进入扶梯（上客端）**提示音的速率（/futihelpspeed in 设置，单位 Hz）。
     * 默认 {@link #DEFAULT_HELP_SPEED_IN} = 10（1/10 秒一响）。
     * 与 {@link #defaultHelpSpeedOut}（落客端）是两套互不影响的数据。
     */
    public int defaultHelpSpeedIn = DEFAULT_HELP_SPEED_IN;

    /**
     * 【1.31】扶梯方块（x,y,z）→ 这条扶梯**上客端**提示音的速率（Hz）。
     * 只记录与 {@link #defaultHelpSpeedIn} **不同**的项。没有石斧界面控件，正常情况下这里是空的，
     * 只有 `-f <X> to <Y>` 在「先把个别扶梯改成别的值」时才会用到（留给以后加 UI）。
     */
    public final Map<BlockPos, Integer> blockHelpSpeedIn = new HashMap<>();

    /**
     * 【1.31】维度默认**离开扶梯（落客端）**提示音的速率（/futihelpspeed out 设置，单位 Hz）。
     * 默认 {@link #DEFAULT_HELP_SPEED_OUT} = 1（1 秒一响）。
     */
    public int defaultHelpSpeedOut = DEFAULT_HELP_SPEED_OUT;

    /**
     * 【1.31】扶梯方块（x,y,z）→ 这条扶梯**落客端**提示音的速率（Hz）。
     * 只记录与 {@link #defaultHelpSpeedOut} **不同**的项。
     */
    public final Map<BlockPos, Integer> blockHelpSpeedOut = new HashMap<>();

    /**
     * 【1.41】维度默认**进入扶梯（上客端）**的无障碍提示音「音乐」（{@code /futihelpmusic in} 设置）：
     * 没有单独设置过的扶梯用它。
     *
     * <p>取值只有三种：
     * <ul>
     *   <li>{@link #HELP_AUDIO_DEFAULT}（初始值）= 模组原来的提示音；</li>
     *   <li>{@link #HELP_AUDIO_OFF} = 这一头不播提示音；</li>
     *   <li>玩家导入的音频文件名（如 {@code example.ogg}）= 在这一头循环播放这段音频。</li>
     * </ul>
     *
     * <p>音频字节**不重复存**：用的就是 {@link #audioLibrary}（与 {@code /futimusic} 的
     * 运行底噪共用同一个导入文件夹与同一个库），这里只记「选了哪一个」。
     * 与 {@link #defaultAudio}（运行底噪）是两套互不影响的数据。
     *
     * <p>★【1.41】起「进入扶梯」与「离开扶梯」是**两套独立数据**（形状同
     * {@code /futihelpspeed in|out}），所以进、出两头可以各放各的声音。
     * 1.39 那个单一的 {@code defaultHelpAudio} 字段在读取时**同时**喂给两头
     * （见 {@link #fromTag}），保证旧存档听起来**逐字节不变**。
     */
    public String defaultHelpAudioIn = HELP_AUDIO_DEFAULT;

    /**
     * 【1.41】扶梯方块（x,y,z）→ 这条扶梯**进入扶梯（上客端）**的提示音「音乐」ID（单独设置层）。
     * 只记录与 {@link #defaultHelpAudioIn} **不同的**项；旧存档缺这一段也能正常读（= 跟随默认）。
     */
    public final Map<BlockPos, String> blockHelpAudioIn = new HashMap<>();

    /**
     * 【1.41】维度默认**离开扶梯（落客端）**的无障碍提示音「音乐」（{@code /futihelpmusic out} 设置）。
     * 取值与 {@link #defaultHelpAudioIn} 相同，但**互不影响**。
     */
    public String defaultHelpAudioOut = HELP_AUDIO_DEFAULT;

    /**
     * 【1.41】扶梯方块（x,y,z）→ 这条扶梯**离开扶梯（落客端）**的提示音「音乐」ID（单独设置层）。
     * 只记录与 {@link #defaultHelpAudioOut} **不同的**项。
     */
    public final Map<BlockPos, String> blockHelpAudioOut = new HashMap<>();

    /** 维度默认阶梯动画速度：未单独设置阶梯动画的扶梯使用它。 */
    public double defaultStepSpeed() {
        return stepEnabled ? stepValue : VANILLA_STEP;
    }

    /** 该方块所在的扶梯是否被单独设置了阶梯动画速度。 */
    public boolean hasIndividualStep(BlockPos pos) {
        return stepSpeeds.containsKey(pos);
    }

    // 【1.20.1 API】SavedData.Factory 是 1.20.2+ 才有的；1.20.1 仍在调用点用
    // DimensionDataStorage.computeIfAbsent(fromTag, ctor, name) 三参形式（见 EscalatorSpeedManager）。
    public static EscalatorSpeedData fromTag(CompoundTag tag) {
        EscalatorSpeedData data = new EscalatorSpeedData();
        if (tag.contains("default")) {
            data.defaultSpeed = clamp(tag.getDouble("default"));
        }
        data.speeds.putAll(readDoubleMap(tag.getCompound("speeds")));
        data.stepSpeeds.putAll(readDoubleMap(tag.getCompound("step")));
        for (String key : tag.getCompound("axe").getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                data.axeModified.add(pos);
            }
        }
        if (tag.contains("stepEnabled")) {
            data.stepEnabled = tag.getBoolean("stepEnabled");
        }
        if (tag.contains("stepValue")) {
            data.stepValue = clamp(tag.getDouble("stepValue"));
        }
        // 【1.7】音频库与扶梯-音频绑定
        CompoundTag audioTag = tag.getCompound("audioLibrary");
        for (String key : audioTag.getAllKeys()) {
            byte[] bytes = audioTag.getByteArray(key);
            if (bytes.length > 0 && bytes.length <= MAX_AUDIO_BYTES) {
                data.audioLibrary.put(key, bytes);
            }
        }
        for (Map.Entry<String, String> entry : readStringMap(tag.getCompound("blockAudio")).entrySet()) {
            BlockPos pos = parsePos(entry.getKey());
            if (pos != null) {
                data.blockAudio.put(pos, entry.getValue());
            }
        }
        // 【1.9】扶梯声音音量（旧存档没有这一段 → 全部按默认 100 处理）
        data.blockVolume.putAll(readIntMap(tag.getCompound("blockVolume")));
        // 【1.12】默认扶梯音量（旧存档没有这一段 → 100 = 原始音量）
        if (tag.contains("defaultVolume")) {
            data.defaultVolume = clampVolume(tag.getInt("defaultVolume"));
        }
        // 【1.11】默认扶梯音频（旧存档没有这一段 → null = 未绑定的扶梯静音）
        if (tag.contains("defaultAudio")) {
            String id = tag.getString("defaultAudio");
            if (!id.isEmpty()) {
                data.defaultAudio = id;
            }
        }
        // 【1.16】无障碍提示音开关（旧存档没有这一段 → true = 开，与 1.15 的「一直响」一致）
        if (tag.contains("defaultHelp")) {
            data.defaultHelp = tag.getBoolean("defaultHelp");
        }
        data.blockHelp.putAll(readBoolMap(tag.getCompound("blockHelp")));
        // 【1.18】无障碍提示音音量（旧存档没有这一段 → 全部按默认 100 处理，与 1.17 的固定音量一致）
        data.blockHelpVolume.putAll(readHelpVolumeMap(tag.getCompound("blockHelpVolume")));
        if (tag.contains("defaultHelpVolume")) {
            data.defaultHelpVolume = clampHelpVolume(tag.getInt("defaultHelpVolume"));
        }
        // 【1.24】两个淡入淡出范围（旧存档没有这一段 → 底噪 16 格、提示音 4 格，与 1.23 的行为完全一致）
        data.blockRound.putAll(readRoundMap(tag.getCompound("blockRound")));
        if (tag.contains("defaultRound")) {
            data.defaultRound = clampRound(tag.getInt("defaultRound"));
        }
        data.blockHelpRound.putAll(readHelpRoundMap(tag.getCompound("blockHelpRound")));
        if (tag.contains("defaultHelpRound")) {
            data.defaultHelpRound = clampHelpRound(tag.getInt("defaultHelpRound"));
        }
        // 【1.31】无障碍提示音速率（旧存档没有这一段 → 入口 10 Hz、出口 1 Hz，与 1.27 定版音色一致）
        data.blockHelpSpeedIn.putAll(readHelpSpeedInMap(tag.getCompound("blockHelpSpeedIn")));
        if (tag.contains("defaultHelpSpeedIn")) {
            data.defaultHelpSpeedIn = clampHelpSpeed(tag.getInt("defaultHelpSpeedIn"));
        }
        data.blockHelpSpeedOut.putAll(readHelpSpeedOutMap(tag.getCompound("blockHelpSpeedOut")));
        if (tag.contains("defaultHelpSpeedOut")) {
            data.defaultHelpSpeedOut = clampHelpSpeed(tag.getInt("defaultHelpSpeedOut"));
        }
        // 【1.39→1.41】无障碍提示音「音乐」
        // ① 1.39 的旧字段（单一 defaultHelpAudio / blockHelpAudio，两头共用一段声音）
        //    读进来**同时**当作「进 / 出」两头的初值 ⇒ 旧存档听起来逐字节不变；
        // ② 随后用 1.41 的 in / out 字段覆盖各自那一头（新存档两边都写，以新字段为准）。
        for (Map.Entry<String, String> entry : readStringMap(tag.getCompound("blockHelpAudio")).entrySet()) {
            BlockPos pos = parsePos(entry.getKey());
            if (pos != null) {
                data.blockHelpAudioIn.put(pos, entry.getValue());
                data.blockHelpAudioOut.put(pos, entry.getValue());
            }
        }
        if (tag.contains("defaultHelpAudio")) {
            String id = tag.getString("defaultHelpAudio");
            if (!id.isEmpty()) {
                data.defaultHelpAudioIn = id;
                data.defaultHelpAudioOut = id;
            }
        }
        for (Map.Entry<String, String> entry : readStringMap(tag.getCompound("blockHelpAudioIn")).entrySet()) {
            BlockPos pos = parsePos(entry.getKey());
            if (pos != null) {
                data.blockHelpAudioIn.put(pos, entry.getValue());
            }
        }
        if (tag.contains("defaultHelpAudioIn")) {
            String id = tag.getString("defaultHelpAudioIn");
            if (!id.isEmpty()) {
                data.defaultHelpAudioIn = id;
            }
        }
        for (Map.Entry<String, String> entry : readStringMap(tag.getCompound("blockHelpAudioOut")).entrySet()) {
            BlockPos pos = parsePos(entry.getKey());
            if (pos != null) {
                data.blockHelpAudioOut.put(pos, entry.getValue());
            }
        }
        if (tag.contains("defaultHelpAudioOut")) {
            String id = tag.getString("defaultHelpAudioOut");
            if (!id.isEmpty()) {
                data.defaultHelpAudioOut = id;
            }
        }
        return data;
    }

    private static Map<BlockPos, Double> readDoubleMap(CompoundTag compound) {
        Map<BlockPos, Double> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                out.put(pos, clamp(compound.getDouble(key)));
            }
        }
        return out;
    }

    private static Map<String, String> readStringMap(CompoundTag compound) {
        Map<String, String> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            out.put(key, compound.getString(key));
        }
        return out;
    }

    /** 【1.41】把「方块 → 字符串」写成 NBT（键 = {@code x,y,z}）。 */
    private static CompoundTag writeStringMap(Map<BlockPos, String> map) {
        CompoundTag out = new CompoundTag();
        for (Map.Entry<BlockPos, String> entry : map.entrySet()) {
            BlockPos pos = entry.getKey();
            out.putString(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        return out;
    }

    /** 【1.9】读「方块 → 音量」，顺手把越界值夹回 1~1000（防止手改 NBT 后出怪值）。 */
    private static Map<BlockPos, Integer> readIntMap(CompoundTag compound) {
        Map<BlockPos, Integer> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                out.put(pos, clampVolume(compound.getInt(key)));
            }
        }
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putDouble("default", defaultSpeed);
        tag.put("speeds", writeDoubleMap(speeds));
        CompoundTag axe = new CompoundTag();
        for (BlockPos pos : axeModified) {
            axe.putString(pos.getX() + "," + pos.getY() + "," + pos.getZ(), "1");
        }
        tag.put("axe", axe);
        tag.putBoolean("stepEnabled", stepEnabled);
        tag.putDouble("stepValue", stepValue);
        tag.put("step", writeDoubleMap(stepSpeeds));
        // 【1.7】音频库与扶梯-音频绑定
        CompoundTag audioTag = new CompoundTag();
        for (Map.Entry<String, byte[]> entry : audioLibrary.entrySet()) {
            audioTag.putByteArray(entry.getKey(), entry.getValue());
        }
        tag.put("audioLibrary", audioTag);
        CompoundTag bindTag = new CompoundTag();
        for (Map.Entry<BlockPos, String> entry : blockAudio.entrySet()) {
            BlockPos pos = entry.getKey();
            bindTag.putString(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        tag.put("blockAudio", bindTag);
        // 【1.9】扶梯声音音量
        tag.put("blockVolume", writeIntMap(blockVolume));
        // 【1.12】默认扶梯音量
        tag.putInt("defaultVolume", defaultVolume);
        // 【1.11】默认扶梯音频
        if (defaultAudio != null && !defaultAudio.isEmpty()) {
            tag.putString("defaultAudio", defaultAudio);
        }
        // 【1.16】无障碍提示音开关
        tag.putBoolean("defaultHelp", defaultHelp);
        tag.put("blockHelp", writeBoolMap(blockHelp));
        // 【1.18】无障碍提示音音量
        tag.putInt("defaultHelpVolume", defaultHelpVolume);
        tag.put("blockHelpVolume", writeIntMap(blockHelpVolume));
        // 【1.24】两个淡入淡出范围（底噪 / 提示音）
        tag.putInt("defaultRound", defaultRound);
        tag.put("blockRound", writeIntMap(blockRound));
        tag.putInt("defaultHelpRound", defaultHelpRound);
        tag.put("blockHelpRound", writeIntMap(blockHelpRound));
        // 【1.31】无障碍提示音速率（入口 / 出口各一套）
        tag.putInt("defaultHelpSpeedIn", defaultHelpSpeedIn);
        tag.put("blockHelpSpeedIn", writeIntMap(blockHelpSpeedIn));
        tag.putInt("defaultHelpSpeedOut", defaultHelpSpeedOut);
        tag.put("blockHelpSpeedOut", writeIntMap(blockHelpSpeedOut));
        // 【1.41】无障碍提示音「音乐」（进 / 出各一套；默认值与音频库共用，这里只存 ID）
        tag.putString("defaultHelpAudioIn", defaultHelpAudioIn);
        tag.put("blockHelpAudioIn", writeStringMap(blockHelpAudioIn));
        tag.putString("defaultHelpAudioOut", defaultHelpAudioOut);
        tag.put("blockHelpAudioOut", writeStringMap(blockHelpAudioOut));
        // ★ 旧字段（1.39，单一值）作为**向后兼容镜像**写一份，值 = **进入扶梯**那一头。
        //   目的只有一个：万一这份存档被回退版本（或不认 in/out 的构建）打开，
        //   至少还能看到进扶梯那套设置，而不是「提示音全没了」。
        //   反过来旧版一旦保存，它会把这一个值当成两头的唯一值写回（out 那份设置丢失）——
        //   这是旧版不认识 out 的必然结果，可以接受。
        tag.putString("defaultHelpAudio", defaultHelpAudioIn);
        tag.put("blockHelpAudio", writeStringMap(blockHelpAudioIn));
        return tag;
    }

    /** 【1.16】无障碍提示音开关：该扶梯**生效**是否播放提示音（单独设置优先，其次维度默认）。 */
    public boolean isHelpEnabled(BlockPos pos) {
        Boolean own = blockHelp.get(pos);
        return own != null ? own : defaultHelp;
    }

    /** 【1.16】该扶梯方块**单独设置**的提示音开关；没单独设置返回 null（用维度默认值）。 */
    public Boolean getIndividualHelp(BlockPos pos) {
        return blockHelp.get(pos);
    }

    /**
     * 【1.16】设置某个扶梯方块的提示音开关。
     * 值等于**维度默认值**（{@link #defaultHelp}）时**删掉记录**，让 NBT 只保留真正被单独设置过的扶梯。
     */
    public void setHelp(BlockPos pos, boolean enabled) {
        if (enabled == defaultHelp) {
            blockHelp.remove(pos);
        } else {
            blockHelp.put(pos, enabled);
        }
    }


    private static CompoundTag writeBoolMap(Map<BlockPos, Boolean> map) {
        CompoundTag compound = new CompoundTag();
        for (Map.Entry<BlockPos, Boolean> entry : map.entrySet()) {
            BlockPos pos = entry.getKey();
            compound.putBoolean(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        return compound;
    }

    /** 【1.16】读「方块 → 提示音开关」；键不是合法坐标的条目直接跳过。 */
    private static Map<BlockPos, Boolean> readBoolMap(CompoundTag compound) {
        Map<BlockPos, Boolean> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                out.put(pos, compound.getBoolean(key));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 【1.18】无障碍提示音音量（1~1000，100 = 原始音量）
    //
    //   与上面的 blockHelp（开关）成对：开关决定“响不响”，音量决定“多响”。
    //   注意与 blockVolume（扶梯**运行底噪**的音量）是两套互不影响的数据。
    // ------------------------------------------------------------------

    /** 【1.18】把任意输入夹到合法提示音音量范围（与音频音量同一区间 1~1000）。 */
    public static int clampHelpVolume(int volume) {
        return Math.max(HELP_VOLUME_MIN, Math.min(HELP_VOLUME_MAX, volume));
    }

    /** 【1.18】该扶梯方块的提示音音量；没单独设置过就是维度默认（{@link #defaultHelpVolume}，初始 100）。 */
    public int getHelpVolume(BlockPos pos) {
        Integer v = blockHelpVolume.get(pos);
        return v != null ? v : defaultHelpVolume;
    }

    /** 【1.18】该扶梯方块**单独设置**的提示音音量；没单独设置返回 null（用维度默认音量）。 */
    public Integer getIndividualHelpVolume(BlockPos pos) {
        return blockHelpVolume.get(pos);
    }

    /**
     * 【1.18】设置某个扶梯方块的提示音音量。
     * 值等于**维度默认音量**（{@link #defaultHelpVolume}）时**删掉记录**，让 NBT 只保留真正被调过的扶梯。
     */
    public void setHelpVolume(BlockPos pos, int volume) {
        int v = clampHelpVolume(volume);
        if (v == defaultHelpVolume) {
            blockHelpVolume.remove(pos);
        } else {
            blockHelpVolume.put(pos, v);
        }
    }

    /** 【1.18】读「方块 → 提示音音量」，顺手把越界值夹回（防止手改 NBT 后出怪值）。 */
    private static Map<BlockPos, Integer> readHelpVolumeMap(CompoundTag compound) {
        Map<BlockPos, Integer> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                out.put(pos, clampHelpVolume(compound.getInt(key)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 【1.24】两个「淡入淡出范围」（单位：格）
    //
    //   底噪（/futiround，默认 16 格）与提示音（/futihelpround，默认 4 格）是**两套**数据，
    //   与上面两套音量一一对应，互不影响：
    //     音量决定「多响」，范围决定「多远还听得见」。
    //   注意 1.24 **没给它们加石斧界面控件**，所以「单独设置」这一层目前只能由
    //   `-f <X> to <Y>` 间接产生；数据模型与其它 7 个可调项保持完全一致，方便以后再补 UI。
    // ------------------------------------------------------------------

    /** 【1.24】把任意输入夹到合法**底噪范围**（1~128 格）。 */
    public static int clampRound(int round) {
        return Math.max(ROUND_MIN, Math.min(ROUND_MAX, round));
    }

    /** 【1.24】把任意输入夹到合法**提示音范围**（1~128 格）。 */
    public static int clampHelpRound(int round) {
        return Math.max(ROUND_MIN, Math.min(ROUND_MAX, round));
    }

    /** 【1.24】这条扶梯运行底噪的生效范围；没单独设置过就是维度默认（初始 16 格）。 */
    public int getRound(BlockPos pos) {
        Integer v = blockRound.get(pos);
        return v != null ? v : defaultRound;
    }

    /** 【1.24】这条扶梯**单独设置**的底噪范围；没单独设置返回 null（用维度默认）。 */
    public Integer getIndividualRound(BlockPos pos) {
        return blockRound.get(pos);
    }

    /**
     * 【1.24】设置某条扶梯运行底噪的范围。
     * 值等于**维度默认**（{@link #defaultRound}）时**删掉记录**，让 NBT 只保留真正被调过的扶梯。
     */
    public void setRound(BlockPos pos, int round) {
        int v = clampRound(round);
        if (v == defaultRound) {
            blockRound.remove(pos);
        } else {
            blockRound.put(pos, v);
        }
    }

    /** 【1.24】这条扶梯无障碍提示音的生效范围；没单独设置过就是维度默认（初始 4 格）。 */
    public int getHelpRound(BlockPos pos) {
        Integer v = blockHelpRound.get(pos);
        return v != null ? v : defaultHelpRound;
    }

    /** 【1.24】这条扶梯**单独设置**的提示音范围；没单独设置返回 null（用维度默认）。 */
    public Integer getIndividualHelpRound(BlockPos pos) {
        return blockHelpRound.get(pos);
    }

    /** 【1.24】设置某条扶梯无障碍提示音的范围；等于维度默认时删掉记录。 */
    public void setHelpRound(BlockPos pos, int round) {
        int v = clampHelpRound(round);
        if (v == defaultHelpRound) {
            blockHelpRound.remove(pos);
        } else {
            blockHelpRound.put(pos, v);
        }
    }

    // ------------------------------------------------------------------
    // 【1.31】无障碍提示音的**速率**（每秒响几次，单位 Hz）
    //
    //   与「上客端 / 落客端」一一对应，所以是**两套**数据：
    //     /futihelpspeed in  <Hz> -> defaultHelpSpeedIn  / blockHelpSpeedIn
    //     /futihelpspeed out <Hz> -> defaultHelpSpeedOut / blockHelpSpeedOut
    //   与开关 / 音量 / 范围一样只记录与默认**不同**的项。
    //   实现上「速率」= 换素材 + 调 pitch（见 EscalatorChimePlayer），
    //   所以默认的 10 / 1 Hz 正好是素材原始速率，等于没改。
    // ------------------------------------------------------------------

    /** 【1.31】把任意输入夹到合法**提示音速率**（{@link #HELP_SPEED_MIN}~{@link #HELP_SPEED_MAX} Hz）。 */
    public static int clampHelpSpeed(int speed) {
        return Math.max(HELP_SPEED_MIN, Math.min(HELP_SPEED_MAX, speed));
    }

    /** 【1.31】这条扶梯**上客端（进入扶梯）**提示音的生效速率（Hz）；没单独设置过就是维度默认（初始 10）。 */
    public int getHelpSpeedIn(BlockPos pos) {
        Integer v = blockHelpSpeedIn.get(pos);
        return v != null ? v : defaultHelpSpeedIn;
    }

    /** 【1.31】这条扶梯**单独设置**的上客端速率；没单独设置返回 null（用维度默认）。 */
    public Integer getIndividualHelpSpeedIn(BlockPos pos) {
        return blockHelpSpeedIn.get(pos);
    }

    /** 【1.31】设置某条扶梯上客端提示音速率；等于维度默认时删掉记录。 */
    public void setHelpSpeedIn(BlockPos pos, int speed) {
        int v = clampHelpSpeed(speed);
        if (v == defaultHelpSpeedIn) {
            blockHelpSpeedIn.remove(pos);
        } else {
            blockHelpSpeedIn.put(pos, v);
        }
    }

    /** 【1.31】这条扶梯**落客端（离开扶梯）**提示音的生效速率（Hz）；没单独设置过就是维度默认（初始 1）。 */
    public int getHelpSpeedOut(BlockPos pos) {
        Integer v = blockHelpSpeedOut.get(pos);
        return v != null ? v : defaultHelpSpeedOut;
    }

    /** 【1.31】这条扶梯**单独设置**的落客端速率；没单独设置返回 null（用维度默认）。 */
    public Integer getIndividualHelpSpeedOut(BlockPos pos) {
        return blockHelpSpeedOut.get(pos);
    }

    /** 【1.31】设置某条扶梯落客端提示音速率；等于维度默认时删掉记录。 */
    public void setHelpSpeedOut(BlockPos pos, int speed) {
        int v = clampHelpSpeed(speed);
        if (v == defaultHelpSpeedOut) {
            blockHelpSpeedOut.remove(pos);
        } else {
            blockHelpSpeedOut.put(pos, v);
        }
    }

    /** 【1.31】读「方块 → 上客端速率」，顺手夹回合法区间。 */
    private static Map<BlockPos, Integer> readHelpSpeedInMap(CompoundTag compound) {
        return readClampedIntMap(compound, EscalatorSpeedData::clampHelpSpeed);
    }

    /** 【1.31】读「方块 → 落客端速率」，顺手夹回合法区间。 */
    private static Map<BlockPos, Integer> readHelpSpeedOutMap(CompoundTag compound) {
        return readClampedIntMap(compound, EscalatorSpeedData::clampHelpSpeed);
    }

    /** 【1.24】读「方块 → 底噪范围」，顺手夹回合法区间。 */
    private static Map<BlockPos, Integer> readRoundMap(CompoundTag compound) {
        return readClampedIntMap(compound, EscalatorSpeedData::clampRound);
    }

    /** 【1.24】读「方块 → 提示音范围」，顺手夹回合法区间。 */
    private static Map<BlockPos, Integer> readHelpRoundMap(CompoundTag compound) {
        return readClampedIntMap(compound, EscalatorSpeedData::clampHelpRound);
    }

    /** 「方块 → 整数」通用读取：键不是合法坐标的条目跳过，值按给定规则夹取。 */
    private static Map<BlockPos, Integer> readClampedIntMap(CompoundTag compound, java.util.function.IntUnaryOperator clamp) {
        Map<BlockPos, Integer> out = new HashMap<>();
        for (String key : compound.getAllKeys()) {
            BlockPos pos = parsePos(key);
            if (pos != null) {
                out.put(pos, clamp.applyAsInt(compound.getInt(key)));
            }
        }
        return out;
    }

    /** 该扶梯方块是否绑定了自定义音频。 */
    public boolean hasAudio(BlockPos pos) {
        return blockAudio.containsKey(pos);
    }

    /** 返回该扶梯方块绑定的音频ID；未绑定时返回 null。 */
    public String getAudioId(BlockPos pos) {
        return blockAudio.get(pos);
    }

    /** 把音频ID绑定到扶梯方块。存在性校验由调用方（EscalatorSpeedManager）负责。 */
    public void bindAudio(BlockPos pos, String audioId) {
        if (audioId != null) {
            blockAudio.put(pos, audioId);
        }
    }

    /** 解绑扶梯方块的音频（之后该扶梯静音）。 */
    public void unbindAudio(BlockPos pos) {
        blockAudio.remove(pos);
    }

    /** 删除音频库中的一份音频，同时解绑所有引用它的扶梯（运行底噪与无障碍提示音两套引用一起解）。 */
    public void removeAudio(String audioId) {
        audioLibrary.remove(audioId);
        blockAudio.entrySet().removeIf(entry -> entry.getValue().equals(audioId));
        blockHelpAudioIn.entrySet().removeIf(entry -> entry.getValue().equals(audioId));
        blockHelpAudioOut.entrySet().removeIf(entry -> entry.getValue().equals(audioId));
    }

    // ------------------------------------------------------------------
    // 【1.41】无障碍提示音「音乐」（两层：维度默认 /futihelpmusic in|out + 每条扶梯单独设置）
    //
    // 与运行底噪那套（blockAudio / defaultAudio）**完全对称**，但数据独立：
    // 同一段导入的 OGG 可以「底噪播它、提示音也播它」，也可以只用在一边。
    // 「进入扶梯（上客端）」与「离开扶梯（落客端）」是**两套独立数据**（形状同 /futihelpspeed），
    // 所以每条扶梯最多 2 条记录（一头一条）。读取一律走 EscalatorSpeedManager 的顺链查找
    // （同一条扶梯上任意一块设过就整条算设过）。
    // ------------------------------------------------------------------

    /** 该扶梯方块是否**单独设置**过提示音音乐；{@code in} 为 true = 看进入扶梯（上客端）那一头。 */
    public boolean hasHelpAudio(BlockPos pos, boolean in) {
        return (in ? blockHelpAudioIn : blockHelpAudioOut).containsKey(pos);
    }

    /** 返回该扶梯方块**单独设置**的提示音音乐 ID；没单独设置返回 null（= 跟随维度默认）。 */
    public String getHelpAudioId(BlockPos pos, boolean in) {
        return (in ? blockHelpAudioIn : blockHelpAudioOut).get(pos);
    }

    /** 把提示音音乐 ID 单独设到该扶梯方块。存在性校验由调用方（EscalatorSpeedManager）负责。 */
    public void bindHelpAudio(BlockPos pos, String audioId, boolean in) {
        if (audioId != null) {
            (in ? blockHelpAudioIn : blockHelpAudioOut).put(pos, audioId);
        }
    }

    /** 清掉该扶梯方块的提示音音乐单独设置（回到维度默认）。 */
    public void unbindHelpAudio(BlockPos pos, boolean in) {
        (in ? blockHelpAudioIn : blockHelpAudioOut).remove(pos);
    }

    /** 取该维度的「单独设置」表：{@code in} 为 true = 进入扶梯那一头。 */
    public Map<BlockPos, String> helpAudioOverrides(boolean in) {
        return in ? blockHelpAudioIn : blockHelpAudioOut;
    }

    // ------------------------------------------------------------------
    // 【1.9】扶梯声音音量（1~1000，100 = 原始音量）
    // ------------------------------------------------------------------

    /** 把任意输入夹到合法音量范围 1~1000。 */
    public static int clampVolume(int volume) {
        return Math.max(AUDIO_VOLUME_MIN, Math.min(AUDIO_VOLUME_MAX, volume));
    }

    /** 该扶梯方块的声音音量；没单独设置过就是维度默认音量（{@link #defaultVolume}，初始 100）。 */
    public int getVolume(BlockPos pos) {
        Integer v = blockVolume.get(pos);
        return v != null ? v : defaultVolume;
    }

    /** 该扶梯方块**单独设置**的音量；没单独设置返回 null（用维度默认音量）。 */
    public Integer getIndividualVolume(BlockPos pos) {
        return blockVolume.get(pos);
    }

    /**
     * 设置某个扶梯方块的声音音量。
     * 值等于**维度默认音量**（{@link #defaultVolume}）时**删掉记录**，让 NBT 只保留真正被调过的扶梯
     * （含放大 &gt;100 的）。
     */
    public void setVolume(BlockPos pos, int volume) {
        int v = clampVolume(volume);
        if (v == defaultVolume) {
            blockVolume.remove(pos);
        } else {
            blockVolume.put(pos, v);
        }
    }

    private static CompoundTag writeIntMap(Map<BlockPos, Integer> map) {
        CompoundTag compound = new CompoundTag();
        for (Map.Entry<BlockPos, Integer> entry : map.entrySet()) {
            BlockPos pos = entry.getKey();
            compound.putInt(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        return compound;
    }

    private static CompoundTag writeDoubleMap(Map<BlockPos, Double> map) {
        CompoundTag compound = new CompoundTag();
        for (Map.Entry<BlockPos, Double> entry : map.entrySet()) {
            BlockPos pos = entry.getKey();
            compound.putDouble(pos.getX() + "," + pos.getY() + "," + pos.getZ(), entry.getValue());
        }
        return compound;
    }

    public static double clamp(double speed) {
        return Math.max(0.0, Math.min(MAX_SPEED, speed));
    }

    /** 把 2.0 显示成 2、1.5 显示成 1.5。 */
    public static String format(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static BlockPos parsePos(String key) {
        String[] parts = key.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}