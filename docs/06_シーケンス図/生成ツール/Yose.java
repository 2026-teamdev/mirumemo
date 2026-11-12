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
 * 既存の .asta の中の指定した図だけを、.puml から作り直す。
 *
 * GenPuml.java が「puml を1行1メッセージでそのまま写し取る」のに対して、
 * こちらは手直し済みの UC0001 から読み取った作法に寄せて引き直す。
 *
 *   1. puml の `->`（要求）は同期メッセージ。
 *      「アクター → 画面」の直後に続く「画面 → 機能」は、前者の実行仕様の中に入れ子にする（深さ1まで）。
 *      機能の自己メッセージ・機能→DM・DM→DB は入れ子にしない。
 *   2. puml の `-->`（返り）は、まだ返していない直近の逆向きの呼び出しに紐づけて、
 *      astah の返信メッセージ（破線・無名）として引く。
 *   3. 紐づく呼び出しが無い `-->`（同じ呼び出しに2回返している箇所）は、
 *      返信にすると縦位置が1本目と重なるため、名前付きの非同期メッセージとして引く。
 *
 *   usage: java Yose <対象.asta> <入力.puml> <作り直さない図のID（カンマ区切り）>
 *   例:    java Yose ../シーケンス図（仮）.asta ../シーケンス図（仮）.puml UC0001
 */
public class Yose {

    static final double START_Y   = 120;   // 最初のメッセージの位置
    static final double STEP      = 40;    // メッセージ1本分の高さ
    static final double RET_STEP  = 25;    // 返信メッセージ1本分（astah が自動で置く間隔に合わせる）
    static final double FRAG_HEAD = 46;    // 複合フラグメントのガード表記の行
    static final double FRAG_PAD  = 20;    // 分岐1つの下余白
    static final double FRAG_GAP  = 14;    // フラグメントの下端と次のメッセージの間
    static final double REF_H     = 72;    // ref（相互作用使用）1つ分の高さ
    static final double LIFELINE_GAP = 90; // 一番右のライフラインからフラグメント右端まで
    static final double NEST_INSET = 16;   // 入れ子のフラグメントを内側に寄せる幅

    // ---------- 読み取った図の中身（GenPuml と同じ） ----------

    static class Part {
        String alias, name, kind;          // kind: actor / boundary / func / dm / entity
    }

    abstract static class Item {
        abstract double height();
    }

    /** 1本のメッセージをどう引くか */
    enum Role {
        CALL,      // 要求（同期メッセージ）
        RET,       // 返信メッセージ（破線・無名）
        ASYNC      // 名前付きの非同期メッセージ（返信にできなかった返り）
    }

    static class MsgItem extends Item {
        String from, to, label;
        boolean dashed;                    // puml の -->
        Role role = Role.CALL;
        MsgItem retOf;                     // role==RET のとき、返す相手の呼び出し
        MsgItem nestIn;                    // この線を出す実行仕様の持ち主（null なら入れ子にしない）
        /** 返信は astah が呼び出しの実行仕様の終わりに自動で置くので、確保する高さも実際の間隔に合わせる */
        double height() { return role == Role.RET ? RET_STEP : STEP; }
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
        String id;                         // UC0002_会員情報を管理する
        String name;                       // UC0002 会員情報を管理する
        List<Part> parts = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        List<NoteItem> notes = new ArrayList<>();
        String ucId() {                    // UC0002
            int us = id.indexOf('_');
            return us > 0 ? id.substring(0, us) : id;
        }
    }

    static int nMsg, nRet, nAsync, nFrag, nOperand, nNote, nRef, nNest;

    // ---------- 入口 ----------

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        String asta = args[0];
        Path puml  = Paths.get(args[1]);
        Set<String> keep = new LinkedHashSet<>();
        if (args.length > 2 && !args[2].isEmpty()) {
            keep.addAll(Arrays.asList(args[2].split(",")));
        }

        List<Dgm> dgms = parse(puml);
        for (Dgm d : dgms) classify(d);

        ProjectAccessor pa = AstahAPI.getAstahAPI().getProjectAccessor();
        pa.open(asta);
        ITransactionManager tm = pa.getTransactionManager();
        BasicModelEditor me = pa.getModelEditorFactory().getBasicModelEditor();
        SequenceDiagramEditor se = pa.getDiagramEditorFactory().getSequenceDiagramEditor();
        IModel project = pa.getProject();

        // 既にある図とクラスを拾う（クラスは作り直さず使い回す）
        List<ISequenceDiagram> existing = new ArrayList<>();
        collectDiagrams(project, existing);
        Map<String, IClass> classes = new HashMap<>();
        collectClasses(project, classes);
        Map<String, IPackage> pkgs = new LinkedHashMap<>();
        collectPackages(project, pkgs);

        tm.beginTransaction();

        // 作り直す図を消す（keep に挙げた図は触らない）
        int deleted = 0;
        for (ISequenceDiagram d : existing) {
            String uc = d.getName().split(" ")[0];
            if (keep.contains(uc)) {
                System.out.println("keep   : " + d.getName());
                continue;
            }
            System.out.println("delete : " + d.getName());
            me.delete(d);
            deleted++;
        }

        for (String p : new String[]{"アクター", "バウンダリ", "コントロール", "エンティティ"}) {
            if (!pkgs.containsKey(p)) pkgs.put(p, me.createPackage(project, p));
        }

        int built = 0;
        for (Dgm d : dgms) {
            if (keep.contains(d.ucId())) continue;
            new Builder(d, se, me, project, pkgs, classes).build();
            built++;
        }

        tm.endTransaction();
        pa.save();
        pa.close();

        System.out.println();
        System.out.println("消した図      : " + deleted);
        System.out.println("作り直した図  : " + built);
        System.out.println("要求（同期）  : " + nMsg + "（うち入れ子 " + nNest + "）");
        System.out.println("返信メッセージ: " + nRet);
        System.out.println("非同期（返りに紐づく呼び出しが無かったもの）: " + nAsync);
        System.out.println("メッセージ合計: " + (nMsg + nRet + nAsync));
        System.out.println("フラグメント  : " + nFrag + "（オペランド " + nOperand + "）");
        System.out.println("注記          : " + nNote + " / 相互作用使用: " + nRef);
    }

    // ---------- どう引くかを先に決める ----------

    /**
     * 図を組み立てる前に、1本ごとの役割を決めておく。
     * 返信メッセージは astah が呼び出しの直後に自動で置くので、
     * 「どれが返信になるか」を先に知らないと、確保する縦位置が実際とずれて線が交差する。
     */
    static void classify(Dgm d) {
        Map<String, String> kinds = new HashMap<>();
        for (Part p : d.parts) kinds.put(p.alias, p.kind);
        walk(d.items, kinds, new ArrayList<>());
    }

    /** alias が受け取っている呼び出しのうち、まだ返していない一番内側のもの */
    static MsgItem innermostOn(List<MsgItem> openCalls, String alias) {
        for (int i = openCalls.size() - 1; i >= 0; i--) {
            if (openCalls.get(i).to.equals(alias)) return openCalls.get(i);
        }
        return null;
    }

    static void walk(List<Item> items, Map<String, String> kinds, List<MsgItem> openCalls) {
        for (Item it : items) {
            if (it instanceof FragItem) {
                // 分岐はどれか1つしか通らないので、各分岐は入口の状態から始める。
                // 分岐の中で開いた呼び出しは分岐の外に持ち出さない。
                // （持ち出すと、分岐の後ろの返りが別の分岐の呼び出しに紐づき、
                //   返信がその分岐の中――図のずっと上――に描かれてしまう）
                for (Branch b : ((FragItem) it).branches) {
                    walk(b.items, kinds, new ArrayList<>(openCalls));
                }
                continue;
            }
            if (!(it instanceof MsgItem)) continue;
            MsgItem m = (MsgItem) it;

            if (!m.dashed) {
                // 要求は、送信元でまだ開いている一番内側の呼び出しの中から出す。
                // これで ユーザ→画面→機能→DM→DB が 1 → 1.1 → 1.1.1 → … と連なる。
                m.role = Role.CALL;
                m.nestIn = innermostOn(openCalls, m.from);
                openCalls.add(m);
                continue;
            }
            // 返りは、まだ返していない逆向きの呼び出しに紐づけて返信メッセージにする
            int idx = -1;
            for (int i = openCalls.size() - 1; i >= 0; i--) {
                MsgItem c = openCalls.get(i);
                if (c.from.equals(m.to) && c.to.equals(m.from)) { idx = i; break; }
            }
            if (idx < 0) {
                // 同じ呼び出しに2回返している箇所。返信にすると1本目と縦位置が重なるので非同期にする
                m.role = Role.ASYNC;
                m.nestIn = innermostOn(openCalls, m.from);
                continue;
            }
            m.role = Role.RET;
            m.retOf = openCalls.get(idx);
            while (openCalls.size() > idx) openCalls.remove(openCalls.size() - 1);
        }
    }

    // ---------- puml の読み取り（GenPuml.java と同じ） ----------

    static List<Dgm> parse(Path file) throws Exception {
        List<Dgm> dgms = new ArrayList<>();
        Dgm cur = null;
        Deque<List<Item>> containers = new ArrayDeque<>();
        Deque<FragItem> frags = new ArrayDeque<>();
        NoteItem note = null;

        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (note != null) {
                if (line.equals("end note")) { cur.notes.add(note); note = null; }
                else note.lines.add(line);
                continue;
            }
            if (line.isEmpty() || line.startsWith("'")) continue;

            if (line.startsWith("@startuml")) {
                cur = new Dgm();
                cur.id = line.length() > 9 ? line.substring(9).trim() : "";
                containers.clear();
                frags.clear();
                containers.push(cur.items);
                dgms.add(cur);
            } else if (line.startsWith("@enduml")) {
                cur = null;
            } else if (line.startsWith("title ")) {
                cur.name = diagramName(cur.id, line.substring(6).trim());
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
        else if (p.name.endsWith("DM")) p.kind = "dm";
        else p.kind = "func";
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
        final Map<String, String> kinds = new HashMap<>();              // alias -> kind
        final Map<MsgItem, ILinkPresentation> links = new HashMap<>();   // 呼び出し -> 引いた線（返信の紐づけ用）
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
                    c.addStereotype(stereotype(p.kind));
                    classes.put(p.name, c);
                }
                INodePresentation ll = se.createLifeline("", x);
                ((ILifeline) ll.getModel()).setBase(c);
                lifelines.put(p.alias, ll);
                xs.put(p.alias, x);
                kinds.put(p.alias, p.kind);
                lastX = x;
                x += 130 + 15 * p.name.length();
            }

            // 返信メッセージは呼び出しの直後に自動で置かれるので、こちらが確保した y より上に詰まる。
            // ライフラインは中身に合わせて伸びるため、そのままだと下に置くフラグメントが
            // 「ライフラインを覆っていない」と弾かれる。先に長さを確保しておき、最後に trim で詰める。
            double need = START_Y + 200;
            for (Item it : d.items) need += it.height();
            for (INodePresentation ll : lifelines.values()) {
                ll.setProperty("lifeline_length", String.valueOf(need));
            }

            double bottom = place(d.items, START_Y, 0);
            placeNotes(bottom);
            trim(bottom);
        }

        /** items を y から下へ並べ、次に空く y を返す */
        double place(List<Item> items, double y, int depth) throws Exception {
            for (Item it : items) {
                if (it instanceof MsgItem) {
                    draw((MsgItem) it, y);
                } else if (it instanceof RefItem) {
                    RefItem r = (RefItem) it;
                    double l = xs.get(r.span.get(0)) - 30;
                    double rr = xs.get(r.span.get(r.span.size() - 1)) + 90;
                    se.createInteractionUse(r.label, "", null,
                            new Point2D.Double(l, y), rr - l, REF_H - 30);
                    nRef++;
                } else {
                    placeFragment((FragItem) it, y, depth);
                }
                y += it.height();
            }
            return y;
        }

        /** 役割に応じて1本引く */
        void draw(MsgItem m, double y) throws Exception {
            if (m.role == Role.RET) {
                ILinkPresentation call = links.get(m.retOf);
                if (call == null) throw new IllegalStateException("[" + d.name + "] 返す相手が見つからない: " + m.label);
                se.createReturnMessage("", call);
                nRet++;
                return;
            }
            ILinkPresentation link = se.createMessage(m.label, source(m), lifeline(m.to), y);
            if (m.role == Role.ASYNC) {
                ((IMessage) link.getModel()).setAsynchronous(true);
                nAsync++;
                System.out.println("  async : [" + d.ucId() + "] " + m.from + " --> " + m.to + " : " + m.label);
                return;
            }
            links.put(m, link);
            nMsg++;
        }

        /** 入れ子にするなら、親の呼び出しが宛先側に作った実行仕様から出す */
        INodePresentation source(MsgItem m) {
            if (m.nestIn == null) return lifeline(m.from);
            ILinkPresentation parent = links.get(m.nestIn);
            if (parent == null) return lifeline(m.from);
            nNest++;
            return (INodePresentation) parent.getTargetEnd();
        }

        INodePresentation lifeline(String alias) {
            INodePresentation ll = lifelines.get(alias);
            if (ll == null) throw new IllegalStateException("[" + d.name + "] 知らないライフライン: " + alias);
            return ll;
        }

        void placeFragment(FragItem f, double top, int depth) throws Exception {
            INodePresentation cf = se.createCombinedFragment("", f.type,
                    new Point2D.Double(fragLeft(depth), top), fragWidth(depth), f.boxHeight());
            ICombinedFragment cfm = (ICombinedFragment) cf.getModel();
            cfm.getInteractionOperands()[0].setGuard(f.branches.get(0).guard);
            for (int i = 1; i < f.branches.size(); i++) {
                cfm.addInteractionOperand("", f.branches.get(i).guard);
            }
            for (int i = 0; i < f.branches.size(); i++) {
                cf.setProperty("operand." + (i + 1) + ".length", String.valueOf(f.branches.get(i).height()));
            }
            nFrag++;
            nOperand += f.branches.size();
            double y = top;
            for (Branch b : f.branches) {
                place(b.items, y + FRAG_HEAD, depth + 1);
                y += b.height();
            }
        }

        void trim(double bottom) throws Exception {
            for (INodePresentation ll : lifelines.values()) {
                ll.setProperty("lifeline_length", String.valueOf(bottom + 40 - ll.getLocation().getY()));
            }
        }

        void placeNotes(double bottom) throws Exception {
            double y = bottom + 30;
            for (NoteItem n : d.notes) {
                Double nx = xs.get(n.span.get(0));
                se.createNote(String.join("\n", n.lines), new Point2D.Double(nx == null ? 60 : nx - 20, y));
                nNote++;
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
            case "entity":   return "エンティティ";
            default:         return "コントロール";      // func / dm
        }
    }

    /** ステレオタイプは既にある .asta と揃える（機能・DM はどちらも control） */
    static String stereotype(String kind) {
        switch (kind) {
            case "actor":    return "actor";
            case "boundary": return "boundary";
            case "entity":   return "entity";
            default:         return "control";
        }
    }

    // ---------- 既にある .asta から拾う ----------

    static void collectDiagrams(IPackage p, List<ISequenceDiagram> out) throws Exception {
        for (IDiagram d : p.getDiagrams()) if (d instanceof ISequenceDiagram) out.add((ISequenceDiagram) d);
        for (INamedElement e : p.getOwnedElements()) {
            if (e instanceof IPackage) collectDiagrams((IPackage) e, out);
            else for (IDiagram d : e.getDiagrams()) if (d instanceof ISequenceDiagram) out.add((ISequenceDiagram) d);
        }
    }

    static void collectClasses(IPackage p, Map<String, IClass> out) throws Exception {
        for (INamedElement e : p.getOwnedElements()) {
            if (e instanceof IPackage) collectClasses((IPackage) e, out);
            else if (e instanceof IClass) out.put(e.getName(), (IClass) e);
        }
    }

    static void collectPackages(IPackage p, Map<String, IPackage> out) throws Exception {
        for (INamedElement e : p.getOwnedElements()) {
            if (e instanceof IPackage) { out.put(e.getName(), (IPackage) e); collectPackages((IPackage) e, out); }
        }
    }
}
