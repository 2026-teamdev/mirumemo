import com.change_vision.jude.api.inf.AstahAPI;
import com.change_vision.jude.api.inf.editor.*;
import com.change_vision.jude.api.inf.model.*;
import com.change_vision.jude.api.inf.presentation.*;
import com.change_vision.jude.api.inf.project.ProjectAccessor;

import java.awt.geom.Point2D;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * PlantUML で書かれたシーケンス図（シーケンス図（仮）.puml）を astah の .asta に変換する。
 * 1行1メッセージの素直な写し取りで、alt / opt / note / ref もそのまま持っていく。
 *
 *   usage: java GenPuml <入力.puml> <出力.asta>
 */
public class GenPuml {

    static final double START_Y   = 120;   // 最初のメッセージの位置
    static final double STEP      = 40;    // メッセージ1本分の高さ
    static final double FRAG_HEAD = 46;    // 複合フラグメントのガード表記の行（1本目のメッセージ名と重ねない）
    static final double FRAG_PAD  = 20;    // 分岐1つの下余白
    static final double FRAG_GAP  = 14;    // フラグメントの下端と次のメッセージの間
    static final double REF_H     = 72;    // ref（相互作用使用）1つ分の高さ
    static final double LIFELINE_GAP = 90; // 一番右のライフラインからフラグメント右端まで
    static final double NEST_INSET = 16;   // 入れ子のフラグメントを内側に寄せる幅

    // ---------- 読み取った図の中身 ----------

    static class Part {
        String alias, name, kind;          // kind: actor / boundary / control / entity
    }

    abstract static class Item {
        abstract double height();
    }

    static class MsgItem extends Item {
        String from, to, label;
        boolean dashed;                    // puml の --> （astah では普通のメッセージとして引く）
        double height() { return STEP; }
    }

    static class RefItem extends Item {
        String label;
        List<String> span = new ArrayList<>();
        double height() { return REF_H; }
    }

    static class Branch {
        String guard;
        List<Item> items = new ArrayList<>();
        double height() {
            double h = FRAG_HEAD + FRAG_PAD;
            for (Item i : items) h += i.height();
            return h;
        }
    }

    static class FragItem extends Item {
        String type;                       // alt / opt
        List<Branch> branches = new ArrayList<>();
        /** 枠そのものの高さ（各分岐の帯の合計） */
        double boxHeight() {
            double h = 0;
            for (Branch b : branches) h += b.height();
            return h;
        }
        double height() { return boxHeight() + FRAG_GAP; }
    }

    static class NoteItem {
        List<String> span = new ArrayList<>();
        List<String> lines = new ArrayList<>();
    }

    static class Dgm {
        String name;
        List<Part> parts = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        List<NoteItem> notes = new ArrayList<>();
    }

    // ---------- 入口 ----------

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
        Path src = Paths.get(args[0]);
        String out = args[1];
        List<Dgm> dgms = parse(src);

        ProjectAccessor pa = AstahAPI.getAstahAPI().getProjectAccessor();
        pa.create(out);
        ITransactionManager tm = pa.getTransactionManager();
        BasicModelEditor me = pa.getModelEditorFactory().getBasicModelEditor();
        SequenceDiagramEditor se = pa.getDiagramEditorFactory().getSequenceDiagramEditor();
        IModel project = pa.getProject();

        tm.beginTransaction();
        project.setName("シーケンス図（仮）");
        Map<String, IPackage> pkgs = new LinkedHashMap<>();
        for (String p : new String[]{"アクター", "バウンダリ", "コントロール", "エンティティ"}) {
            pkgs.put(p, me.createPackage(project, p));
        }
        Map<String, IClass> classes = new HashMap<>();
        for (Dgm d : dgms) {
            new Builder(d, se, me, project, pkgs, classes).build();
        }
        tm.endTransaction();

        pa.save();
        pa.close();
        System.out.println("generated " + dgms.size() + " sequence diagrams -> " + out);
    }

    // ---------- puml の読み取り ----------

    static List<Dgm> parse(Path file) throws Exception {
        List<Dgm> dgms = new ArrayList<>();
        Dgm cur = null;
        String id = null;
        Deque<List<Item>> containers = new ArrayDeque<>();   // いま行を足していく先
        Deque<FragItem> frags = new ArrayDeque<>();
        NoteItem note = null;

        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (note != null) {                              // note の本文は end note まで素通し
                if (line.equals("end note")) { cur.notes.add(note); note = null; }
                else note.lines.add(line);
                continue;
            }
            if (line.isEmpty() || line.startsWith("'")) continue;

            if (line.startsWith("@startuml")) {
                cur = new Dgm();
                id = line.length() > 9 ? line.substring(9).trim() : "";
                containers.clear();
                frags.clear();
                containers.push(cur.items);
                dgms.add(cur);
            } else if (line.startsWith("@enduml")) {
                cur = null;
            } else if (line.startsWith("title ")) {
                cur.name = diagramName(id, line.substring(6).trim());
            } else if (line.startsWith("actor ") || line.startsWith("participant ") || line.startsWith("database ")) {
                cur.parts.add(part(line));
            } else if (line.startsWith("alt ") || line.startsWith("opt ")) {
                FragItem f = new FragItem();
                f.type = line.substring(0, 3);
                Branch b = new Branch();
                b.guard = line.substring(4).trim();
                f.branches.add(b);
                containers.peek().add(f);
                frags.push(f);
                containers.push(b.items);
            } else if (line.startsWith("else")) {
                Branch b = new Branch();
                b.guard = line.length() > 4 ? line.substring(5).trim() : "";
                frags.peek().branches.add(b);
                containers.pop();
                containers.push(b.items);
            } else if (line.equals("end")) {
                containers.pop();
                frags.pop();
            } else if (line.startsWith("note over ")) {
                note = new NoteItem();
                for (String s : line.substring("note over ".length()).split(",")) note.span.add(s.trim());
            } else if (line.startsWith("ref over ")) {
                RefItem r = new RefItem();
                String rest = line.substring("ref over ".length());
                int colon = rest.indexOf(':');
                for (String s : rest.substring(0, colon).split(",")) r.span.add(s.trim());
                r.label = rest.substring(colon + 1).trim();
                containers.peek().add(r);
            } else {
                MsgItem m = message(line);
                if (m != null) containers.peek().add(m);
                else System.out.println("skip: " + line);
            }
        }
        return dgms;
    }

    /** 「UC0001_会員登録をする」＋ title から図の名前を作る */
    static String diagramName(String id, String title) {
        int us = id.indexOf('_');
        return (us > 0 ? id.substring(0, us) + " " : "") + title;
    }

    static Part part(String line) {
        Part p = new Part();
        int sp = line.indexOf(' ');
        String head = line.substring(0, sp);
        String rest = line.substring(sp + 1).trim();
        int as = rest.lastIndexOf(" as ");
        p.name = rest.substring(0, as).trim().replaceAll("^\"|\"$", "");
        p.alias = rest.substring(as + 4).trim();
        if (head.equals("actor")) p.kind = "actor";
        else if (head.equals("database")) p.kind = "entity";
        else if (p.name.endsWith("画面")) p.kind = "boundary";
        else p.kind = "control";                 // 〜機能 / 〜DM
        return p;
    }

    static MsgItem message(String line) {
        int arrow = line.indexOf("-->");
        boolean dashed = arrow >= 0;
        int len = 3;
        if (!dashed) { arrow = line.indexOf("->"); len = 2; }
        if (arrow < 0) return null;
        int colon = line.indexOf(':', arrow);
        if (colon < 0) return null;
        MsgItem m = new MsgItem();
        m.from = line.substring(0, arrow).trim();
        m.to = line.substring(arrow + len, colon).trim();
        m.label = line.substring(colon + 1).trim();
        m.dashed = dashed;
        return m;
    }

    // ---------- 図の組み立て ----------

    static class Builder {
        final Dgm d;
        final SequenceDiagramEditor se;
        final BasicModelEditor me;
        final IModel project;
        final Map<String, IPackage> pkgs;
        final Map<String, IClass> classes;
        final Map<String, INodePresentation> lifelines = new LinkedHashMap<>();
        final Map<String, Double> xs = new LinkedHashMap<>();
        double lastX = 60;

        Builder(Dgm d, SequenceDiagramEditor se, BasicModelEditor me, IModel project,
                Map<String, IPackage> pkgs, Map<String, IClass> classes) {
            this.d = d; this.se = se; this.me = me; this.project = project;
            this.pkgs = pkgs; this.classes = classes;
        }

        void build() throws Exception {
            ISequenceDiagram sd = se.createSequenceDiagram(project, d.name);
            se.setDiagram(sd);

            double x = 60;
            for (Part p : d.parts) {
                IClass c = classes.get(p.name);
                if (c == null) {
                    c = me.createClass(pkgs.get(pkgName(p.kind)), p.name);
                    c.addStereotype(p.kind);
                    classes.put(p.name, c);
                }
                INodePresentation ll = se.createLifeline("", x);
                ((ILifeline) ll.getModel()).setBase(c);
                lifelines.put(p.alias, ll);
                xs.put(p.alias, x);
                lastX = x;
                x += 130 + 15 * p.name.length();
            }

            double bottom = place(d.items, START_Y, 0);
            placeNotes(bottom);
            trim(bottom);
        }

        /** items を y から下へ並べ、次に空く y を返す */
        double place(List<Item> items, double y, int depth) throws Exception {
            for (Item it : items) {
                if (it instanceof MsgItem) {
                    MsgItem m = (MsgItem) it;
                    INodePresentation src = lifelines.get(m.from);
                    INodePresentation dst = lifelines.get(m.to);
                    if (src == null || dst == null) {
                        throw new IllegalStateException("[" + d.name + "] 知らないライフライン: " + m.from + " -> " + m.to);
                    }
                    se.createMessage(m.label, src, dst, y);
                } else if (it instanceof RefItem) {
                    RefItem r = (RefItem) it;
                    // ref over A,B はその2者の範囲だけを覆う
                    double l = xs.get(r.span.get(0)) - 30;
                    double rr = xs.get(r.span.get(r.span.size() - 1)) + 90;
                    se.createInteractionUse(r.label, "", null,
                            new Point2D.Double(l, y), rr - l, REF_H - 30);
                } else {
                    placeFragment((FragItem) it, y, depth);
                }
                y += it.height();
            }
            return y;
        }

        void placeFragment(FragItem f, double top, int depth) throws Exception {
            INodePresentation cf = se.createCombinedFragment("", f.type,
                    new Point2D.Double(fragLeft(depth), top), fragWidth(depth), f.boxHeight());
            ICombinedFragment cfm = (ICombinedFragment) cf.getModel();
            cfm.getInteractionOperands()[0].setGuard(f.branches.get(0).guard);
            for (int i = 1; i < f.branches.size(); i++) {
                cfm.addInteractionOperand("", f.branches.get(i).guard);
            }
            // 分岐の帯の高さは presentation の property でしか指定できない（API に setter が無い）
            for (int i = 0; i < f.branches.size(); i++) {
                cf.setProperty("operand." + (i + 1) + ".length", String.valueOf(f.branches.get(i).height()));
            }
            double y = top;
            for (Branch b : f.branches) {
                place(b.items, y + FRAG_HEAD, depth + 1);
                y += b.height();
            }
        }

        /**
         * ライフラインは既定だと中身よりかなり長く伸びる（図の下に大きな余白が残る）。
         * 長さは presentation の property でしか指定できないので、最後の要素に合わせて揃える。
         */
        void trim(double bottom) throws Exception {
            for (INodePresentation ll : lifelines.values()) {
                ll.setProperty("lifeline_length", String.valueOf(bottom + 40 - ll.getLocation().getY()));
            }
        }

        void placeNotes(double bottom) throws Exception {
            double y = bottom + 30;
            for (NoteItem n : d.notes) {
                Double nx = xs.get(n.span.get(0));
                // note over A,B は、その範囲の真下に置くことで対象を示す
                // （アンカー線を引くと図を横断してメッセージと紛らわしくなるため引かない）
                se.createNote(String.join("\n", n.lines), new Point2D.Double(nx == null ? 60 : nx - 20, y));
                y += 30 + 18 * n.lines.size();
            }
        }

        double fragLeft(int depth) { return 30 + NEST_INSET * depth; }

        double fragWidth(int depth) { return lastX + LIFELINE_GAP - 30 - 2 * NEST_INSET * depth; }
    }

    static String pkgName(String kind) {
        switch (kind) {
            case "actor":    return "アクター";
            case "boundary": return "バウンダリ";
            case "control":  return "コントロール";
            default:         return "エンティティ";
        }
    }
}
