import com.change_vision.jude.api.inf.AstahAPI;
import com.change_vision.jude.api.inf.editor.*;
import com.change_vision.jude.api.inf.model.*;
import com.change_vision.jude.api.inf.presentation.*;
import com.change_vision.jude.api.inf.project.ProjectAccessor;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 画面遷移図・ユースケース定義書をもとにしたシーケンス図を astah の .asta として生成する。
 * 仕様は seq.spec（テキスト）に書き、ここでは組み立てだけを行う。
 *
 *   usage: java Gen <spec file> <output .asta> [--no-create]
 *     --no-create : 画面遷移（次画面の <<create>> とその表示メッセージ）を出力しない
 */
public class Gen {

    static final double START_Y = 120;
    static final double STEP = 40;
    static final double RETURN_GAP = 25;

    static boolean withCreate = true;

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        Path spec = Paths.get(args[0]);
        String out = args[1];
        for (int i = 2; i < args.length; i++) {
            if ("--no-create".equals(args[i])) withCreate = false;
        }

        ProjectAccessor pa = AstahAPI.getAstahAPI().getProjectAccessor();
        pa.create(out);
        ITransactionManager tm = pa.getTransactionManager();
        BasicModelEditor me = pa.getModelEditorFactory().getBasicModelEditor();
        SequenceDiagramEditor se = pa.getDiagramEditorFactory().getSequenceDiagramEditor();
        IModel project = pa.getProject();

        tm.beginTransaction();
        project.setName("みるメモシーケンス図");
        Map<String, IPackage> pkgs = new LinkedHashMap<>();
        for (String p : new String[]{"アクター", "バウンダリ", "コントロール", "エンティティ"}) {
            pkgs.put(p, me.createPackage(project, p));
        }
        Map<String, IClass> classes = new HashMap<>();

        List<Dgm> dgms = parse(spec);
        for (Dgm d : dgms) {
            build(d, se, me, project, pkgs, classes);
        }
        tm.endTransaction();

        pa.save();
        pa.close();
        System.out.println("generated " + dgms.size() + " sequence diagrams -> " + out);
    }

    // ---------- spec ----------

    static class Part {
        String kind;   // actor / boundary / control / entity
        String name;   // 図に出る名前
        String key;    // 仕様の中で参照する名前（既定は name と同じ）
    }

    static class Msg {
        int depth;
        String from, to, label;
        boolean create, destroy, ret;
    }

    static class Dgm {
        String name;
        List<Part> parts = new ArrayList<>();
        List<Msg> msgs = new ArrayList<>();
    }

    static List<Dgm> parse(Path file) throws Exception {
        List<Dgm> dgms = new ArrayList<>();
        Dgm cur = null;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("@diagram ")) {
                cur = new Dgm();
                cur.name = line.substring("@diagram ".length()).trim();
                dgms.add(cur);
            } else if (line.startsWith("@")) {
                Part p = new Part();
                int sp = line.indexOf(' ');
                p.kind = line.substring(1, sp);
                String rest = line.substring(sp + 1).trim();
                int as = rest.indexOf(" as ");
                if (as >= 0) {
                    p.name = rest.substring(0, as).trim();
                    p.key = rest.substring(as + 4).trim();
                } else {
                    p.name = rest;
                    p.key = rest;
                }
                cur.parts.add(p);
            } else {
                Msg m = new Msg();
                int sp = line.indexOf(' ');
                m.depth = Integer.parseInt(line.substring(0, sp));
                String rest = line.substring(sp + 1);
                int arrow = rest.indexOf("->");
                int colon = rest.lastIndexOf(':');
                m.from = rest.substring(0, arrow).trim();
                m.to = rest.substring(arrow + 2, colon).trim();
                String label = rest.substring(colon + 1).trim();
                for (String flag : new String[]{"create", "destroy", "ret"}) {
                    if (label.endsWith(" " + flag)) {
                        label = label.substring(0, label.length() - flag.length() - 1).trim();
                        if (flag.equals("create")) m.create = true;
                        if (flag.equals("destroy")) m.destroy = true;
                        if (flag.equals("ret")) m.ret = true;
                    }
                }
                m.label = label;
                cur.msgs.add(m);
            }
        }
        return dgms;
    }

    // ---------- build ----------

    static void build(Dgm d, SequenceDiagramEditor se, BasicModelEditor me, IModel project,
                      Map<String, IPackage> pkgs, Map<String, IClass> classes) throws Exception {
        ISequenceDiagram sd = se.createSequenceDiagram(project, d.name);
        se.setDiagram(sd);

        // 画面遷移を出さない場合は create とその直下のメッセージを落とす
        List<Msg> msgs = new ArrayList<>();
        int skipDeeperThan = -1;
        for (Msg m : d.msgs) {
            if (skipDeeperThan >= 0) {
                if (m.depth > skipDeeperThan) continue;
                skipDeeperThan = -1;
            }
            if (!withCreate && m.create && isScreen(d, m.to)) {
                skipDeeperThan = m.depth;
                continue;
            }
            msgs.add(m);
        }

        Set<String> used = new HashSet<>();
        for (Msg m : msgs) { used.add(m.from); used.add(m.to); }

        Map<String, INodePresentation> lifelines = new LinkedHashMap<>();
        double x = 60;
        for (Part p : d.parts) {
            if (!used.contains(p.key)) continue;
            IClass c = classes.get(p.kind + "/" + p.name);
            if (c == null) {
                c = me.createClass(pkgs.get(pkgName(p.kind)), p.name);
                c.addStereotype(p.kind.equals("actor") ? "actor" : p.kind);
                classes.put(p.kind + "/" + p.name, c);
            }
            INodePresentation ll = se.createLifeline("", x);
            ((ILifeline) ll.getModel()).setBase(c);
            lifelines.put(p.key, ll);
            x += 130 + 15 * p.name.length();
        }

        ILinkPresentation[] stack = new ILinkPresentation[16];
        List<ILinkPresentation> returns = new ArrayList<>();
        // <<create>> で生成したライフラインは、生成メッセージより下でないとメッセージを受け取れない
        Map<String, INodePresentation> createdAt = new HashMap<>();
        boolean[] retOpen = new boolean[16];   // 返信メッセージを引く予定のメッセージ（その分の高さを空ける）
        double cursor = START_Y;

        for (Msg m : msgs) {
            // 呼び出しから戻ってくる階層では、返信メッセージ用の高さを空けてから次を置く
            for (int i = m.depth; i < retOpen.length; i++) {
                if (retOpen[i]) {
                    cursor += RETURN_GAP;
                    retOpen[i] = false;
                }
            }
            INodePresentation src;
            if (m.depth == 0) {
                src = lifelines.get(m.from);
            } else {
                ILinkPresentation parent = stack[m.depth - 1];
                src = (INodePresentation) parent.getTargetEnd();
            }
            INodePresentation dst = lifelines.get(m.to);
            if (src == null || dst == null) {
                throw new IllegalStateException("[" + d.name + "] unknown lifeline: " + m.from + " -> " + m.to);
            }
            double y = cursor;
            INodePresentation ca = createdAt.get(m.to);
            if (ca != null) {
                // <<create>> された画面／エンティティは、生成メッセージより下でないと受け取れない
                double need = ca.getLocation().getY() + ca.getHeight() + 1;
                if (y < need) y = need;
            }
            ILinkPresentation link;
            try {
                if (m.create) {
                    link = se.createCreateMessage(m.label, src, dst, y);
                } else if (m.destroy) {
                    link = se.createDestroyMessage(m.label, src, dst, y);
                } else {
                    link = se.createMessage(m.label, src, dst, y);
                }
            } catch (Exception e) {
                System.out.println("FAIL [" + d.name + "] depth=" + m.depth + " " + m.from + " -> " + m.to + " : " + m.label
                    + " y=" + y + " cursor=" + cursor + " srcType=" + src.getType()
                    + " srcRect=" + src.getLocation() + "/h" + src.getHeight()
                    + " dstLoc=" + dst.getLocation() + " : " + e.getMessage());
                throw e;
            }
            if (m.create) createdAt.put(m.to, (INodePresentation) link.getTargetEnd());
            stack[m.depth] = link;
            for (int i = m.depth + 1; i < stack.length; i++) stack[i] = null;
            if (m.ret) {
                returns.add(link);
                retOpen[m.depth] = true;
            }
            // 消滅記号（×）はメッセージの下に出るので、その分だけ余計に空ける
            cursor = y + STEP + (m.destroy ? RETURN_GAP : 0);
        }

        for (ILinkPresentation link : returns) {
            se.createReturnMessage("", link);
        }
    }

    static boolean isScreen(Dgm d, String key) {
        for (Part p : d.parts) {
            if (p.key.equals(key)) return p.kind.equals("boundary");
        }
        return false;
    }

    static String pkgName(String kind) {
        switch (kind) {
            case "actor": return "アクター";
            case "boundary": return "バウンダリ";
            case "control": return "コントロール";
            default: return "エンティティ";
        }
    }
}
