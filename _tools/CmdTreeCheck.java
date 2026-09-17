package smooth.lift;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.commands.CommandSourceStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 临时校验工具（放在 _tools，不参与打包）：脱离游戏环境把真·指令树（{@link SmoothLift#registerCommands}）
 * 建出来，然后 dump 出各层级的 Tab 补全项，确认 `/futihelp`、`/futihelploud`、`/futiround`、
 * `/futihelpround`、`/futihelpspeed`、`/futihelpmusic`（【1.39】）的每个分支都真的可达。
 *
 * <p>用法见 _tools/check-command-tree.sh。核心手法是给 {@code dispatcher.parse(input, null)}
 * 传一个 <b>null source</b>：Brigadier 解析与补全只用到指令树本身，不会去碰 source，
 * 所以不需要真的开一个 MC 服务器。
 *
 * <p>⚠️ 两条已知的「不算失败」的 Brigadier 行为，都放在对照区（probe）：
 * <ol>
 *   <li><b>数值参数不给补全项</b> —— {@code futiround 20 to } 的期望是 {@code []} 而不是 {@code [0]}；</li>
 *   <li><b>根节点自带执行器的指令，后面多打一个词会停在根节点、不报异常</b> ——
 *       {@code /futihelp bogus} 与 {@code /futihelpspeed 5} 都是既有的
 *       「不带参数 = 显示当前值」语义的副作用，别写成 {@code expectNotParsed}。</li>
 * </ol>
 */
public final class CmdTreeCheck {

    private CmdTreeCheck() {
    }

    public static void main(String[] args) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        SmoothLift.registerCommands(dispatcher);

        int failures = 0;
        System.out.println("==================== /futihelp 指令树 ====================");
        failures += dump(dispatcher, "/", "根（全部指令）");
        failures += dump(dispatcher, "futihelp ", "futihelp 的直接子节点");
        failures += dump(dispatcher, "futihelp on ", "futihelp on 的下一层");
        failures += dump(dispatcher, "futihelp on to ", "futihelp on to 的下一层");
        failures += dump(dispatcher, "futihelp off ", "futihelp off 的下一层");
        failures += dump(dispatcher, "futihelp off to ", "futihelp off to 的下一层");
        failures += dump(dispatcher, "futihelp -f ", "futihelp -f 的下一层");
        failures += dump(dispatcher, "futihelp -f on ", "futihelp -f on 的下一层");
        failures += dump(dispatcher, "futihelp -f on to ", "futihelp -f on to 的下一层");
        failures += dump(dispatcher, "futihelp -f off ", "futihelp -f off 的下一层");
        failures += dump(dispatcher, "futihelp -f off to ", "futihelp -f off to 的下一层");
        System.out.println("-------------------- /futihelploud 指令树 --------------------");
        failures += dump(dispatcher, "futihelploud ", "futihelploud 的直接子节点");
        failures += dump(dispatcher, "futihelploud 200 ", "futihelploud <音量> 的下一层");
        failures += dump(dispatcher, "futihelploud 200 to ", "futihelploud <X> to 的下一层");
        failures += dump(dispatcher, "futihelploud -f ", "futihelploud -f 的下一层");
        failures += dump(dispatcher, "futihelploud -f 200 ", "futihelploud -f <音量> 的下一层");
        failures += dump(dispatcher, "futihelploud -f 200 to ", "futihelploud -f <X> to 的下一层");
        System.out.println("-------------------- /futiround 指令树 --------------------");
        failures += dump(dispatcher, "futiround ", "futiround 的直接子节点");
        failures += dump(dispatcher, "futiround 20 ", "futiround <范围> 的下一层");
        failures += dump(dispatcher, "futiround 20 to ", "futiround <X> to 的下一层");
        failures += dump(dispatcher, "futiround -f ", "futiround -f 的下一层");
        failures += dump(dispatcher, "futiround -f 20 ", "futiround -f <范围> 的下一层");
        failures += dump(dispatcher, "futiround -f 20 to ", "futiround -f <X> to 的下一层");
        System.out.println("-------------------- /futihelpround 指令树 --------------------");
        failures += dump(dispatcher, "futihelpround ", "futihelpround 的直接子节点");
        failures += dump(dispatcher, "futihelpround 4 ", "futihelpround <范围> 的下一层");
        failures += dump(dispatcher, "futihelpround 4 to ", "futihelpround <X> to 的下一层");
        failures += dump(dispatcher, "futihelpround -f ", "futihelpround -f 的下一层");
        failures += dump(dispatcher, "futihelpround -f 4 ", "futihelpround -f <范围> 的下一层");
        failures += dump(dispatcher, "futihelpround -f 4 to ", "futihelpround -f <X> to 的下一层");
        System.out.println("-------------------- /futihelpspeed 指令树 --------------------");
        failures += dump(dispatcher, "futihelpspeed ", "futihelpspeed 的直接子节点（应有 -f / in / out）");
        failures += dump(dispatcher, "futihelpspeed in ", "futihelpspeed in 的下一层（数值参数，无补全）");
        failures += dump(dispatcher, "futihelpspeed in 5 ", "futihelpspeed in <Hz> 的下一层");
        failures += dump(dispatcher, "futihelpspeed in 5 to ", "futihelpspeed in <X> to 的下一层");
        failures += dump(dispatcher, "futihelpspeed out ", "futihelpspeed out 的下一层（数值参数，无补全）");
        failures += dump(dispatcher, "futihelpspeed out 1 ", "futihelpspeed out <Hz> 的下一层");
        failures += dump(dispatcher, "futihelpspeed -f ", "futihelpspeed -f 的下一层（应有 in / out）");
        failures += dump(dispatcher, "futihelpspeed -f in 5 ", "futihelpspeed -f in <Hz> 的下一层");
        failures += dump(dispatcher, "futihelpspeed -f in 5 to ", "futihelpspeed -f in <X> to 的下一层");
        failures += dump(dispatcher, "futihelpspeed -f out 1 ", "futihelpspeed -f out <Hz> 的下一层");

        System.out.println();
        System.out.println("==================== /futihelpmusic 指令树（【1.39】与 /futimusic 对齐） ====================");
        failures += dump(dispatcher, "futihelpmusic ", "futihelpmusic 的直接子节点（字符串参数不补全，只有 -f）");
        failures += dump(dispatcher, "futihelpmusic default ", "futihelpmusic <名字> 的下一层");
        failures += dump(dispatcher, "futihelpmusic default to ", "futihelpmusic <X> to 的下一层");
        failures += dump(dispatcher, "futihelpmusic -f ", "futihelpmusic -f 的下一层");
        failures += dump(dispatcher, "futihelpmusic -f default ", "futihelpmusic -f <名字> 的下一层");
        failures += dump(dispatcher, "futihelpmusic -f default to ", "futihelpmusic -f <X> to 的下一层");

        System.out.println();
        System.out.println("==================== 期望的补全项 ====================");
        failures += expect(dispatcher, "futihelp ", "-f", "off", "on");  // Brigadier 补全按字典序
        failures += expect(dispatcher, "futihelp on ", "to");
        failures += expect(dispatcher, "futihelp on to ", "off");
        failures += expect(dispatcher, "futihelp off ", "to");
        failures += expect(dispatcher, "futihelp off to ", "on");
        failures += expect(dispatcher, "futihelp -f ", "off", "on");
        failures += expect(dispatcher, "futihelp -f on ", "to");
        failures += expect(dispatcher, "futihelp -f on to ", "off");
        failures += expect(dispatcher, "futihelp -f off ", "to");
        failures += expect(dispatcher, "futihelp -f off to ", "on");
        failures += expect(dispatcher, "jietispeed ", "-f");   // 数值参数不补全，-f 可补全
        failures += expect(dispatcher, "futiloud ", "-f");
        failures += expect(dispatcher, "futihelploud ", "-f");   // 数值参数不补全，-f 可补全
        failures += expect(dispatcher, "futiround ", "-f");      // 【1.24】
        failures += expect(dispatcher, "futiround 20 ", "to");
        failures += expect(dispatcher, "futiround 20 to ");        // 数值参数不给补全项（既有指令同理）
        failures += expect(dispatcher, "futihelpround ", "-f");  // 【1.24】
        failures += expect(dispatcher, "futihelpround 4 ", "to");
        failures += expect(dispatcher, "futihelpround 4 to ");
        failures += expect(dispatcher, "futihelpround -f 4 ", "to");
        // 【1.31】/futihelpspeed：顶层有 -f / in / out（Brigadier 字面量按字典序，'-' < 'i' < 'o'）
        failures += expect(dispatcher, "futihelpspeed ", "-f", "in", "out");
        failures += expect(dispatcher, "futihelpspeed in ");
        failures += expect(dispatcher, "futihelpspeed in 5 ", "to");
        failures += expect(dispatcher, "futihelpspeed in 5 to ");
        failures += expect(dispatcher, "futihelpspeed out 1 ", "to");
        failures += expect(dispatcher, "futihelpspeed -f ", "in", "out");
        failures += expect(dispatcher, "futihelpspeed -f in 5 ", "to");
        failures += expect(dispatcher, "futihelpspeed -f in 5 to ");
        failures += expect(dispatcher, "futihelpspeed -f out 1 ", "to");

        // 【1.39】/futihelpmusic：结构与 /futimusic 逐层对齐（名字是字符串参数 → 不补全，
        // 所以顶层只能补出 -f；`to` 之后的 target 同理）。
        // ★ 这里没有 expectNotParsed：「名字」是 StringArgumentType.string()，会把任何词（含 -f、to）
        //   都当成名字吃掉，所以不存在「打错词就报错」的分支 —— 与 /futimusic 完全一致。
        failures += expect(dispatcher, "futihelpmusic ", "-f");
        failures += expect(dispatcher, "futihelpmusic default ", "to");
        failures += expect(dispatcher, "futihelpmusic default to ");
        failures += expect(dispatcher, "futihelpmusic -f ");
        failures += expect(dispatcher, "futihelpmusic -f default ", "to");
        failures += expect(dispatcher, "futihelpmusic -f default to ");

        System.out.println();
        System.out.println("==================== 每条完整指令都可执行 ====================");
        failures += expectExecutable(dispatcher, "futihelp");
        failures += expectExecutable(dispatcher, "futihelp on");
        failures += expectExecutable(dispatcher, "futihelp off");
        failures += expectExecutable(dispatcher, "futihelp on to off");
        failures += expectExecutable(dispatcher, "futihelp off to on");
        failures += expectExecutable(dispatcher, "futihelp -f on");
        failures += expectExecutable(dispatcher, "futihelp -f off");
        failures += expectExecutable(dispatcher, "futihelp -f on to off");
        failures += expectExecutable(dispatcher, "futihelp -f off to on");
        failures += expectExecutable(dispatcher, "futispeed");
        failures += expectExecutable(dispatcher, "futispeed -f 2");
        failures += expectExecutable(dispatcher, "futispeed -f 2 to 3");
        failures += expectExecutable(dispatcher, "jietispeed");
        failures += expectExecutable(dispatcher, "futiloud");
        failures += expectExecutable(dispatcher, "futiloud -f 200");
        failures += expectExecutable(dispatcher, "futiloud -f 200 to 300");
        failures += expectExecutable(dispatcher, "futihelploud");
        failures += expectExecutable(dispatcher, "futihelploud 200");
        failures += expectExecutable(dispatcher, "futihelploud 200 to 300");
        failures += expectExecutable(dispatcher, "futihelploud -f 200");
        failures += expectExecutable(dispatcher, "futihelploud -f 200 to 300");
        failures += expectExecutable(dispatcher, "futimusic");
        // 【1.24】两个「淡入淡出范围」指令
        failures += expectExecutable(dispatcher, "futiround");
        failures += expectExecutable(dispatcher, "futiround 20");
        failures += expectExecutable(dispatcher, "futiround 20 to 30");
        failures += expectExecutable(dispatcher, "futiround -f 20");
        failures += expectExecutable(dispatcher, "futiround -f 20 to 30");
        failures += expectExecutable(dispatcher, "futihelpround");
        failures += expectExecutable(dispatcher, "futihelpround 4");
        failures += expectExecutable(dispatcher, "futihelpround 4 to 6");
        failures += expectExecutable(dispatcher, "futihelpround -f 6");
        failures += expectExecutable(dispatcher, "futihelpround -f 6 to 8");
        // 【1.31】无障碍提示音速率（入口 / 出口各一套，含 -f 与 to）
        failures += expectExecutable(dispatcher, "futihelpspeed");
        failures += expectExecutable(dispatcher, "futihelpspeed in 5");
        failures += expectExecutable(dispatcher, "futihelpspeed in 5 to 8");
        failures += expectExecutable(dispatcher, "futihelpspeed out 3");
        failures += expectExecutable(dispatcher, "futihelpspeed out 3 to 4");
        failures += expectExecutable(dispatcher, "futihelpspeed -f in 5");
        failures += expectExecutable(dispatcher, "futihelpspeed -f in 5 to 8");
        failures += expectExecutable(dispatcher, "futihelpspeed -f out 1");
        failures += expectExecutable(dispatcher, "futihelpspeed -f out 1 to 2");
        // 【1.39】/futihelpmusic：5 种形状都能执行（default / off / 文件名 / to / -f）
        failures += expectExecutable(dispatcher, "futihelpmusic");
        failures += expectExecutable(dispatcher, "futihelpmusic default");
        failures += expectExecutable(dispatcher, "futihelpmusic off");
        failures += expectExecutable(dispatcher, "futihelpmusic example.ogg");
        failures += expectExecutable(dispatcher, "futihelpmusic default to off");
        failures += expectExecutable(dispatcher, "futihelpmusic example.ogg to default");
        failures += expectExecutable(dispatcher, "futihelpmusic -f default");
        failures += expectExecutable(dispatcher, "futihelpmusic -f off");
        failures += expectExecutable(dispatcher, "futihelpmusic -f default to example.ogg");
        failures += expectExecutable(dispatcher, "futihelpmusic -f example.ogg to off");

        System.out.println();
        System.out.println("==================== 不该存在的分支 ====================");
        failures += expectNotParsed(dispatcher, "futihelp on to on", "on to on 应无此分支");
        failures += expectNotParsed(dispatcher, "futihelp off to off", "off to off 应无此分支");
        failures += expectNotParsed(dispatcher, "futihelp -f on to on", "-f on to on 应无此分支");
        // 【1.24】范围参数越界（合法区间 1~128）必须被 Brigadier 直接拒绝
        failures += expectNotParsed(dispatcher, "futiround 0", "范围下限是 1");
        failures += expectNotParsed(dispatcher, "futiround 200", "范围上限是 128");
        failures += expectNotParsed(dispatcher, "futihelpround 0", "范围下限是 1");
        failures += expectNotParsed(dispatcher, "futihelpround 999", "范围上限是 128");
        // 【1.31】速率参数越界（【1.34】起 1~100 Hz，【1.38】起 1~50 Hz）必须被 Brigadier 直接拒绝。
        // ★ 探针要贴着**当前**上限：1.34~1.37 期间上限是 100，这里写的是 101；1.38 把上限压回 50 后
        //   必须同步改成 51，否则「51~100 又被放行」这种回归根本探不到。同时补两条正例守住上界。
        failures += expectNotParsed(dispatcher, "futihelpspeed in 0", "速率下限是 1 Hz");
        failures += expectNotParsed(dispatcher, "futihelpspeed out 51", "速率上限是 50 Hz");
        failures += expectNotParsed(dispatcher, "futihelpspeed -f in 0", "-f 分支同样下限 1 Hz");
        failures += expectNotParsed(dispatcher, "futihelpspeed -f out 51", "-f 分支同样上限 50 Hz");
        failures += expectExecutable(dispatcher, "futihelpspeed out 50");
        failures += expectExecutable(dispatcher, "futihelpspeed -f out 50");
        // 说明：「futihelp on off」这类「已匹配到可执行节点后再多打一个词」的输入，Brigadier 会停在
        // 已匹配的节点上、不报异常（下面的 probe 对照可以看到 /futispeed 2 3、/futiloud 200 300
        // 这些**既有**指令行为完全一致），所以这里不算失败项，只在对照区打出来看。

        System.out.println();
        System.out.println("==================== 尾部多余输入的对照（对照既有指令，判断是不是 Brigadier 固有行为） ====================");
        probe(dispatcher, "futihelp on off");
        probe(dispatcher, "futispeed 2 3");
        probe(dispatcher, "jietispeed 2 3");
        probe(dispatcher, "futiloud 200 300");
        probe(dispatcher, "futihelploud 200 300");
        probe(dispatcher, "futihelp bogus");
        // 【1.31】「根节点自带执行器」的指令，后面多打一个词都会停在根节点、不报异常
        // （和不带参数执行显示当前速率是同一个语义），所以这两条只能放对照区、不能算失败。
        probe(dispatcher, "futihelpspeed 5");
        probe(dispatcher, "futihelpspeed bogus");
        // 【1.39】/futihelpmusic 名字也是字符串参数：随便一个词都会被当成名字，报错发生在
        // resolveHelpAudioName 里（"存档里没有叫…的音频"），不是解析期 —— 所以同样只能放对照区。
        probe(dispatcher, "futihelpmusic bogus");
        probe(dispatcher, "futihelpmusic -f");
        probe(dispatcher, "futihelpmusic default to");

        System.out.println();
        if (failures == 0) {
            System.out.println("结果：全部通过 ✅");
        } else {
            System.out.println("结果：有 " + failures + " 项不符 ❌");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 打印一次解析的最后节点 / 是否有执行器 / 异常表，用来和既有指令对照行为。 */
    private static void probe(CommandDispatcher<CommandSourceStack> dispatcher, String input) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(input, null);
        var nodes = parsed.getContext().getNodes();
        String last = nodes.isEmpty() ? "(空)" : nodes.get(nodes.size() - 1).getNode().getName();
        boolean hasExecutor = nodes.isEmpty() || nodes.get(nodes.size() - 1).getNode().getCommand() != null;
        System.out.println("  「" + input + "」 最后节点=" + last + " 有执行器=" + hasExecutor
                + " 已消费到=" + parsed.getReader().getCursor() + "/" + input.length()
                + " 异常=" + parsed.getExceptions().keySet());
    }

    private static int dump(CommandDispatcher<CommandSourceStack> dispatcher, String input, String what) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(input, null);
        List<String> names = completionNames(dispatcher, parsed);
        System.out.println(pad(what) + " 「" + input + "」 -> " + names);
        return 0;
    }

    private static int expect(CommandDispatcher<CommandSourceStack> dispatcher, String input, String... expected) {
        List<String> actual = completionNames(dispatcher, dispatcher.parse(input, null));
        List<String> want = new ArrayList<>();
        for (String e : expected) {
            if (!e.isEmpty()) {
                want.add(e);
            }
        }
        boolean ok = actual.equals(want);
        System.out.println((ok ? "  OK   " : "  FAIL ") + "「" + input + "」 期望 " + want + " 实际 " + actual);
        return ok ? 0 : 1;
    }

    private static int expectExecutable(CommandDispatcher<CommandSourceStack> dispatcher, String input) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(input, null);
        boolean ok = !parsed.getContext().getNodes().isEmpty()
                && parsed.getContext().getNodes().get(parsed.getContext().getNodes().size() - 1).getNode().getCommand() != null
                && parsed.getExceptions().isEmpty();
        System.out.println((ok ? "  OK   " : "  FAIL ") + "「" + input + "」 可执行 = " + ok
                + (parsed.getExceptions().isEmpty() ? "" : " 异常=" + parsed.getExceptions().keySet()));
        return ok ? 0 : 1;
    }

    private static int expectNotParsed(CommandDispatcher<CommandSourceStack> dispatcher, String input, String why) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(input, null);
        boolean ok = !parsed.getExceptions().isEmpty()
                || parsed.getContext().getNodes().get(parsed.getContext().getNodes().size() - 1).getNode().getCommand() == null;
        if (!ok) {
            System.out.println("       调试：exceptions=" + parsed.getExceptions()
                    + " readerCursor=" + parsed.getReader().getCursor()
                    + " totalLen=" + input.length());
        }
        System.out.println((ok ? "  OK   " : "  FAIL ") + "「" + input + "」 应当无法执行（" + why + "）");
        return ok ? 0 : 1;
    }

    private static List<String> completionNames(CommandDispatcher<CommandSourceStack> dispatcher,
                                                ParseResults<CommandSourceStack> parsed) {
        Suggestions suggestions = dispatcher.getCompletionSuggestions(parsed).join();
        List<String> out = new ArrayList<>();
        for (Suggestion s : suggestions.getList()) {
            out.add(s.getText());
        }
        StringRange range = suggestions.getRange();
        // 递归展开「子节点文面完全相同」时 Brigadier 会用公共前缀折叠补全项，这里只关心名字，不处理。
        return out;
    }

    private static String pad(String s) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 26) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
