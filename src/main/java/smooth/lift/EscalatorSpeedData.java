package smooth.lift;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.datafix.DataFixTypes;
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

    // ------------------------------------------------------------------
    // 【1.42】直梯（Lift）开关门提示音 liftmusic.ogg
    //
    // 这是**与扶梯完全无关**的另一件事：MTR 直梯在**关门**时连播 4 次 liftmusic.ogg、
    // **开门**时连播 2 次（固定间隔，见客户端 LiftChimePlayer）；「哪条直梯」由客户端按
    // 「离玩家最近的直梯」现算，**不需要在这里存任何按扶梯方块索引的数据** ——
    // 所以这套数据只有「维度默认」一层，没有 blockXxx 那张表，比上面所有设置都轻。
    //
    // ★ 这两条设置**按维度**存（和其它设置一样，一份 ServerLevel 一份 SavedData）。
    //   指令里的 `-f` 因此是「**对所有维度**强制」（见 SmoothLift 里的 lifthelp / lifthelpspeed），
    //   而不是「对这条直梯强制」—— 直梯没有单条粒度的设置。
    // ------------------------------------------------------------------

    /**
     * 【1.42】直梯开关门提示音的**倍速**区间。
     *
     * <p>为什么上限就是 **2.0**、下限就是 **0.5**：倍速是直接写进
     * {@code SoundInstance.pitch} 的，而原版 {@code SoundEngine.calculatePitch} 把它
     * **硬夹在 [0.5F, 2.0F]**（与提示音速率那边的素材分档是同一个限制）。
     * 也就是说填 3.0 只会被悄悄压回 2.0，不如在这里就夹住并如实告诉玩家 ——
     * 否则玩家会得到「填了 3 但听起来和 2 一样」这种最难查的反馈。
     */
    public static final float LIFT_HELP_SPEED_MIN = 0.5f;
    public static final float LIFT_HELP_SPEED_MAX = 2.0f;

    /** 【1.42】默认倍速 = 1.0（原速，和直接听 liftmusic.ogg 完全一样）。 */
    public static final float DEFAULT_LIFT_HELP_SPEED = 1.0f;

    /**
     * 【1.42】同一条提示音**连播时相邻两次的间隔**（秒）。
     *
     * <p>素材本身长 0.859 秒；这里取 0.8 秒（略短于素材）是用户选定的「固定间隔连播」：
     * 4 次关门音 ≈ 2.4 秒、2 次开门音 ≈ 0.8 秒，**不随门运动时长伸缩**。
     * 实际间隔 = {@code 本值 / 倍速}（倍速越快，连播也越密），见客户端 {@code LiftChimePlayer}。
     */
    public static final double LIFT_HELP_INTERVAL_SECONDS = 0.8;

    /** 【1.42】关门时连播几次。 */
    public static final int LIFT_HELP_CLOSE_REPEATS = 4;

    /** 【1.42】开门时连播几次。 */
    public static final int LIFT_HELP_OPEN_REPEATS = 2;

    /**
     * 【1.43】直梯开关门提示音的**默认音量**（{@code /lifthelploud} 设置）。
     *
     * <p>取值区间直接复用扶梯那两套音量的
     * [{@link #HELP_VOLUME_MIN}, {@link #HELP_VOLUME_MAX}]（= 1~1000，100 = 原始音量、
     * 1000 = 10× 放大）—— 引用同一组常量而不是另写一份数字，保证「运行底噪 / 无障碍提示音 /
     * 直梯提示音」三套音量永远不会因为改了其中一处而悄悄不一致。
     *
     * <p>&gt;100 的放大和另外两套一样，需要客户端成对做两件事
     * （实例实现 {@code GainManagedSound} 让 {@code SoundEngineVolumeMixin} 放行 [0,1] 夹取，
     * 以及开播时把该 OpenAL 源的 {@code AL_MAX_GAIN} 抬到
     * {@code EscalatorAudioPlayer#MAX_GAIN}）—— 只做一件仍然最多 1.0×，见 {@code LiftChimePlayer}。
     */
    public static final int DEFAULT_LIFT_HELP_VOLUME = DEFAULT_HELP_VOLUME;

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

    /**
     * 【1.42】直梯开关门提示音开关（{@code /lifthelp} 设置）。
     *
     * <p>{@code true} = 直梯关门连播 {@link #LIFT_HELP_CLOSE_REPEATS} 次、开门连播
     * {@link #LIFT_HELP_OPEN_REPEATS} 次 liftmusic.ogg；{@code false} = 完全不播。
     * 旧存档没有这个字段 → 读到默认 {@code true}（功能默认开）。
     */
    public boolean defaultLiftHelp = true;

    /**
     * 【1.42】直梯开关门提示音的**倍速**（{@code /lifthelpspeed} 设置，允许小数）。
     * 取值被 {@link #clampLiftHelpSpeed} 夹到
     * [{@link #LIFT_HELP_SPEED_MIN}, {@link #LIFT_HELP_SPEED_MAX}]。旧存档缺字段 → 1.0（原速）。
     */
    public float defaultLiftHelpSpeed = DEFAULT_LIFT_HELP_SPEED;

    /**
     * 【1.43】直梯开关门提示音的**音量**（{@code /lifthelploud} 设置）。
     * 取值被 {@link #clampLiftHelpVolume} 夹到 [{@link #HELP_VOLUME_MIN}, {@link #HELP_VOLUME_MAX}]
     * （1~1000，100 = 原始音量、1000 = 10× 放大）。旧存档缺字段 → {@link #DEFAULT_LIFT_HELP_VOLUME}
     * （= 100），也就是「和 1.42 一样响」。
     *
     * <p>和开关 / 倍速一样**只有维度默认一层**（没有按直梯索引的表 —— 直梯是按「离玩家最近的
     * 那一条」现算的，没有方块粒度可言）。
     */
    public int defaultLiftHelpVolume = DEFAULT_LIFT_HELP_VOLUME;

    /**
     * 【1.48】三项提示音**各自的音量**（维度默认；{@link #defaultLiftHelpVolume} 是「共用默认」，
     * 这三个是「某项单独调过」之后的覆盖值）。
     *
     * <ul>
     *   <li>{@code up} = 上楼提示音（准备向上移动那声）；</li>
     *   <li>{@code down} = 下楼提示音（准备向下移动那声）；</li>
     *   <li>{@code chime} = 开关门提示音（开门 2 次 / 关门 4 次连播）。</li>
     * </ul>
     * 取值：**{@link #LIFT_TONE_VOLUME_UNSET}（-1）= 该项没单独调过，跟随 {@link #defaultLiftHelpVolume}**；
     * 否则 = 该项自己的音量（1~1000，100 = 原始音量、1000 = 10×）。旧存档没有这三个字段 → -1
     * （全部跟随共用默认，与 1.47 及以前行为一致）。
     */
    public int defaultLiftToneVolumeUp = LIFT_TONE_VOLUME_UNSET;
    public int defaultLiftToneVolumeDown = LIFT_TONE_VOLUME_UNSET;
    public int defaultLiftToneVolumeChime = LIFT_TONE_VOLUME_UNSET;

    /** 【1.48】「该项没单独调过」的哨兵（不是合法音量，合法区间是 1~1000）。 */
    public static final int LIFT_TONE_VOLUME_UNSET = -1;

    /**
     * 【1.46】三提示音的**独立子开关**（维度默认；{@link #defaultLiftHelp} 是总开关，这三个是「总开关
     * 开着的时候各自还能不能再关一层」）。
     *
     * <ul>
     *   <li>{@code up} = 上楼提示音（准备向上移动那声）；</li>
     *   <li>{@code down} = 下楼提示音（准备向下移动那声）；</li>
     *   <li>{@code chime} = 开关门提示音（开门 2 次 / 关门 4 次连播）。</li>
     * </ul>
     * 三者独立，缺省全开（{@code true}）。旧存档没有这三个字段 → 读到 {@code true}，
     * 与 1.45 之前的行为完全一致。
     */
    public boolean defaultLiftToneUpEnabled = true;
    public boolean defaultLiftToneDownEnabled = true;
    public boolean defaultLiftToneChimeEnabled = true;

    /**
     * 【1.47】直梯提示音（上楼 / 下楼 / 开关门，三项共用一份）的**淡入淡出范围**（格）。
     * 由 {@code /lifthelpround} 设置；首次载入模组默认 {@link #DEFAULT_LIFT_HELP_ROUND} = 4 格
     * （与扶梯无障碍提示音的默认射程一致 —— 点状音源贴块，不是 16 格那种整条环境音）。
     * 取值被 {@link #clampLiftHelpRound} 夹到 [{@link #LIFT_HELP_ROUND_MIN}, {@link #LIFT_HELP_ROUND_MAX}]
     * （1~128，复用扶梯那组范围常量）。旧存档缺字段 → 4（与「第一次载入」同行为）。
     */
    public int defaultLiftHelpRound = DEFAULT_LIFT_HELP_ROUND;

    /** 【1.47】直梯提示音淡入淡出范围下限。 */
    public static final int LIFT_HELP_ROUND_MIN = 1;
    /** 【1.47】直梯提示音淡入淡出范围上限（与扶梯同一组上限：128）。 */
    public static final int LIFT_HELP_ROUND_MAX = 128;
    /** 【1.47】默认 4 格（用户点名「第一次载入模组，默认4格」）。 */
    public static final int DEFAULT_LIFT_HELP_ROUND = 4;

    /**
     * 【1.45】石斧右键直梯楼层轨道换的提示音：竖井列打包坐标 → {@link LiftToneAudio}。
     *
     * <p>「哪条直梯」没有稳定 ID（跨重启会变），所以用「楼层轨道所在竖井的那一列」当身份：
     * 一条直梯的所有楼层轨道共享同一个 (X, Z)，只有 Y 不同 ⇒ key = {@code BlockPos.asLong(x, 0, z)}
     * （Y 固定为 0）。石斧右键任意一层轨道都定位到同一个 key。
     *
     * <p>值：{@code up} = 准备向上移动（up.ogg）、{@code down} = 准备向下移动（down.ogg）、
     * {@code chime} = 开关门（liftmusic.ogg）。三者互不冲突，可分别选。
     * 每个字段取值有两种语义：
     * <ul>
     *   <li>{@link #LIFT_TONE_DEFAULT} = 用模组内置素材（原始开关门/up/down 提示音）；</li>
     *   <li>{@link #LIFT_TONE_OFF} = 这一条不播提示音；</li>
     *   <li>其它 = 音频库里的文件名（从 {@code smoothlift_audio} 导入过的那份）。</li>
     * </ul>
     * 旧存档没有这张表 → 空表（全部走默认素材，与旧行为一致）。
     */
    public final Map<Long, LiftToneAudio> liftToneAudio = new HashMap<>();

    /** 【1.45】「默认素材」的哨兵值。 */
    public static final String LIFT_TONE_DEFAULT = "default";
    /** 【1.45】「这条直梯不播」的哨兵值。 */
    public static final String LIFT_TONE_OFF = "off";
    /** 【1.45】「设置不存在」的哨兵值（与默认素材同义，旧存档读到它 = 默认）。 */
    public static final String LIFT_TONE_MISSING = "";

    /** 维度默认阶梯动画速度：未单独设置阶梯动画的扶梯使用它。 */
    public double defaultStepSpeed() {
        return stepEnabled ? stepValue : VANILLA_STEP;
    }

    /** 该方块所在的扶梯是否被单独设置了阶梯动画速度。 */
    public boolean hasIndividualStep(BlockPos pos) {
        return stepSpeeds.containsKey(pos);
    }

    public static final SavedData.Factory<EscalatorSpeedData> FACTORY =
            new SavedData.Factory<>(EscalatorSpeedData::new, EscalatorSpeedData::fromTag, DataFixTypes.SAVED_DATA_MAP_DATA);

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
        // 【1.42】直梯开关门提示音
        //   旧存档没有这两个字段 → 保持字段初始值（开 / 倍速 1.0），也就是「功能默认打开、原速」。
        if (tag.contains("defaultLiftHelp")) {
            data.defaultLiftHelp = tag.getBoolean("defaultLiftHelp");
        }
        if (tag.contains("defaultLiftHelpSpeed")) {
            data.defaultLiftHelpSpeed = clampLiftHelpSpeed(tag.getFloat("defaultLiftHelpSpeed"));
        }
        // 【1.43】音量：旧存档（含只有 1.42 字段的）缺它 → 保持初始值 100 = 原始音量
        if (tag.contains("defaultLiftHelpVolume")) {
            data.defaultLiftHelpVolume = clampLiftHelpVolume(tag.getInt("defaultLiftHelpVolume"));
        }
        // 【1.46】三提示音独立子开关：旧存档缺字段 → 保持 true（与 1.45 之前行为一致）
        if (tag.contains("defaultLiftToneUpEnabled")) {
            data.defaultLiftToneUpEnabled = tag.getBoolean("defaultLiftToneUpEnabled");
        }
        if (tag.contains("defaultLiftToneDownEnabled")) {
            data.defaultLiftToneDownEnabled = tag.getBoolean("defaultLiftToneDownEnabled");
        }
        if (tag.contains("defaultLiftToneChimeEnabled")) {
            data.defaultLiftToneChimeEnabled = tag.getBoolean("defaultLiftToneChimeEnabled");
        }
        // 【1.47】直梯提示音淡入淡出范围：旧存档缺字段 → 默认 4 格（同「第一次载入」）
        if (tag.contains("defaultLiftHelpRound")) {
            data.defaultLiftHelpRound = clampLiftHelpRound(tag.getInt("defaultLiftHelpRound"));
        }
        // 【1.48】三项各自音量：旧存档缺字段 → -1（跟随共用默认）
        if (tag.contains("defaultLiftToneVolumeUp")) {
            data.defaultLiftToneVolumeUp = clampLiftToneVolume(tag.getInt("defaultLiftToneVolumeUp"));
        }
        if (tag.contains("defaultLiftToneVolumeDown")) {
            data.defaultLiftToneVolumeDown = clampLiftToneVolume(tag.getInt("defaultLiftToneVolumeDown"));
        }
        if (tag.contains("defaultLiftToneVolumeChime")) {
            data.defaultLiftToneVolumeChime = clampLiftToneVolume(tag.getInt("defaultLiftToneVolumeChime"));
        }
        // 【1.45】直梯楼层轨道提示音：key → {up, down, chime} 三个音频 id。
        //   旧存档没有这张表 → 空表（全部走默认素材）。格式：
        //   ListTag，每个元素是 `{ "key": <long>, "up": <str>, "down": <str>, "chime": <str> }`。
        if (tag.contains("liftToneAudio", 9)) {
            for (net.minecraft.nbt.Tag item : tag.getList("liftToneAudio", 10)) {
                CompoundTag entry = (CompoundTag) item;
                long key = entry.getLong("key");
                String up = entry.contains("up") ? entry.getString("up") : "";
                String down = entry.contains("down") ? entry.getString("down") : "";
                String chime = entry.contains("chime") ? entry.getString("chime") : "";
                if (key != 0L && !(up.isEmpty() && down.isEmpty() && chime.isEmpty())) {
                    data.liftToneAudio.put(key, new LiftToneAudio(up, down, chime));
                }
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
        // 【1.42】直梯开关门提示音（只有维度默认一层，没有按方块索引的表）
        tag.putBoolean("defaultLiftHelp", defaultLiftHelp);
        tag.putFloat("defaultLiftHelpSpeed", defaultLiftHelpSpeed);
        // 【1.43】直梯提示音音量（同上，只有维度默认一层）
        tag.putInt("defaultLiftHelpVolume", defaultLiftHelpVolume);
        // 【1.46】三提示音独立子开关（维度默认一层）
        tag.putBoolean("defaultLiftToneUpEnabled", defaultLiftToneUpEnabled);
        tag.putBoolean("defaultLiftToneDownEnabled", defaultLiftToneDownEnabled);
        tag.putBoolean("defaultLiftToneChimeEnabled", defaultLiftToneChimeEnabled);
        // 【1.47】直梯提示音淡入淡出范围（三项共用）
        tag.putInt("defaultLiftHelpRound", defaultLiftHelpRound);
        // 【1.48】三项各自音量（-1 = 跟随共用默认）
        tag.putInt("defaultLiftToneVolumeUp", defaultLiftToneVolumeUp);
        tag.putInt("defaultLiftToneVolumeDown", defaultLiftToneVolumeDown);
        tag.putInt("defaultLiftToneVolumeChime", defaultLiftToneVolumeChime);
        // 【1.45】直梯楼层轨道提示音（竖井列 → 三音频 id）
        ListTag toneList = new ListTag();
        for (Map.Entry<Long, LiftToneAudio> entry : liftToneAudio.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putLong("key", entry.getKey());
            LiftToneAudio v = entry.getValue();
            t.putString("up", v.up);
            t.putString("down", v.down);
            t.putString("chime", v.chime);
            toneList.add(t);
        }
        tag.put("liftToneAudio", toneList);
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

    /**
     * 【1.42】把任意输入夹到合法**直梯提示音倍速**
     * （{@link #LIFT_HELP_SPEED_MIN}~{@link #LIFT_HELP_SPEED_MAX}）。
     *
     * <p>非有限值（NaN / ±Inf）一律当作 {@link #DEFAULT_LIFT_HELP_SPEED} 处理 ——
     * {@code Math.max/min} 遇到 NaN 会把 NaN 原样传下去，而一个 NaN 的 pitch 会让
     * OpenAL 那一路直接失效（表现是「设完之后再也没声音」），必须在这里堵掉。
     */
    public static float clampLiftHelpSpeed(float speed) {
        if (Float.isNaN(speed) || Float.isInfinite(speed)) {
            return DEFAULT_LIFT_HELP_SPEED;
        }
        return Math.max(LIFT_HELP_SPEED_MIN, Math.min(LIFT_HELP_SPEED_MAX, speed));
    }

    /**
     * 【1.43】把任意输入夹到合法**直梯提示音音量**
     * （{@link #HELP_VOLUME_MIN}~{@link #HELP_VOLUME_MAX}，即 1~1000）。
     *
     * <p>和 {@link #clampHelpVolume} 是同一套区间，只是各自对应一套独立数据。
     * 夹在数据层而不是只靠指令参数类型，是为了让**存档里被外部改坏的值**（NBT 手改、
     * 旧版本写进来的越界值）在装载时就被纠正 —— 否则一个 100000 会被原版音量夹取
     * 悄悄压回上限，玩家看到的是「填了没用」而不是报错。
     */
    public static int clampLiftHelpVolume(int volume) {
        return Math.max(HELP_VOLUME_MIN, Math.min(HELP_VOLUME_MAX, volume));
    }

    /** 【1.47】直梯提示音淡入淡出范围：夹到 [1, 128]（复用扶梯那组范围常量）。 */
    public static int clampLiftHelpRound(int round) {
        return Math.max(LIFT_HELP_ROUND_MIN, Math.min(LIFT_HELP_ROUND_MAX, round));
    }

    /**
     * 【1.48】单项音量的夹取：{@link #LIFT_TONE_VOLUME_UNSET}（-1 = 跟随共用默认）原样放行，
     * 其余夹到 [{@link #HELP_VOLUME_MIN}, {@link #HELP_VOLUME_MAX}]（1~1000）。
     */
    public static int clampLiftToneVolume(int volume) {
        if (volume == LIFT_TONE_VOLUME_UNSET) {
            return LIFT_TONE_VOLUME_UNSET;
        }
        return Math.max(HELP_VOLUME_MIN, Math.min(HELP_VOLUME_MAX, volume));
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
        // 【1.45】直梯楼层轨道提示音也可能引用过这段（共用同一个音频库）：引用它的那一项
        //   退化成「默认素材」而不是留着指向已删除的文件（否则播放端查到库里没有 → 静默不响）。
        //   ★ LiftToneAudio 是 record（字段 final），不能改字段，只能整体替换。
        Map<Long, LiftToneAudio> tones = new HashMap<>();
        for (Map.Entry<Long, LiftToneAudio> entry : liftToneAudio.entrySet()) {
            LiftToneAudio t = entry.getValue();
            String up = audioId.equals(t.up()) ? LIFT_TONE_DEFAULT : t.up();
            String down = audioId.equals(t.down()) ? LIFT_TONE_DEFAULT : t.down();
            String chime = audioId.equals(t.chime()) ? LIFT_TONE_DEFAULT : t.chime();
            if (!(LIFT_TONE_DEFAULT.equals(up) && LIFT_TONE_DEFAULT.equals(down)
                    && LIFT_TONE_DEFAULT.equals(chime))) {
                tones.put(entry.getKey(), new LiftToneAudio(up, down, chime));
            }
        }
        liftToneAudio.clear();
        liftToneAudio.putAll(tones);
    }

    /**
     * 【1.45】一条直梯的三项提示音设置（可独立选择各自素材，互不冲突）。
     *
     * <p>每个字段的取值语义见 {@link #liftToneAudio}：{@link #LIFT_TONE_DEFAULT} 内置素材 /
     * {@link #LIFT_TONE_OFF} 不播 / 其它 = 音频库文件名。
     */
    public record LiftToneAudio(String up, String down, String chime) {
        public static final LiftToneAudio NONE = new LiftToneAudio(
                LIFT_TONE_DEFAULT, LIFT_TONE_DEFAULT, LIFT_TONE_DEFAULT);
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