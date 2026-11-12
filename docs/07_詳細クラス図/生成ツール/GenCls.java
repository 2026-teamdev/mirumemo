import com.change_vision.jude.api.inf.AstahAPI;
import com.change_vision.jude.api.inf.editor.*;
import com.change_vision.jude.api.inf.model.*;
import com.change_vision.jude.api.inf.presentation.*;
import com.change_vision.jude.api.inf.project.ProjectAccessor;

import java.awt.geom.Point2D;
import java.util.*;

/**
 * シーケンス図と概念クラス図から詳細クラス図を .asta として生成する。
 * 変換規則は 参考資料 07詳細クラス図の書き方.pdf に従う。
 *
 * シーケンス図は seq.spec（テキスト）ではなく、**みるメモシーケンス図.asta そのもの**を読む。
 * 図が正なので、astah で図を直したらこのツールを回し直せば詳細クラス図が追従する。
 * 逆に seq.spec を直しても、06 の Gen.java で .asta を作り直すまでは効かない。
 *
 *   usage: java GenCls <みるメモシーケンス図.asta> <概念クラス図.asta> <output .asta>
 */
public class GenCls {

    static final double COL = 380;      // バウンダリ／コントロールの列の間隔
    static final double Y_BOUNDARY = 0;
    static final double Y_CONTROL = 220;
    static final double Y_DM = 560;
    static final double Y_ENTITY_1 = 1220;
    static final double Y_ENTITY_2 = 1520;
    static final double ENT_COL = 320;

    static final String DM = "DM";

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
        List<Dgm> dgms = readSeq(args[0]);
        Concept concept = readConcept(args[1]);
        build(dgms, concept, args[2]);
    }

    // ---------- シーケンス図（.asta）の読み込み ----------

    static class Part {
        String kind;   // actor / boundary / control / entity
        String name;   // 図に出る名前（＝ライフラインのベースクラス名）
        String key;    // 引き当て用。ライフラインの ID を使う
    }

    static class Msg {
        String from, to, label;
        boolean create;
    }

    static class Dgm {
        String name;
        List<Part> parts = new ArrayList<>();
        List<Msg> msgs = new ArrayList<>();

        Part find(String key) {
            for (Part p : parts) if (p.key.equals(key)) return p;
            return null;
        }
        /** この図が扱っている画面のコントロール（DM は除く） */
        String control() {
            for (Part p : parts) if (p.kind.equals("control") && !p.name.equals(DM)) return p.name;
            return null;
        }
    }

    static List<Dgm> readSeq(String path) throws Exception {
        ProjectAccessor pa = AstahAPI.getAstahAPI().getProjectAccessor();
        pa.open(path, true, false, true);          // ロックを取らずに読むだけ
        List<ISequenceDiagram> found = new ArrayList<>();
        collectSeq(pa.getProject(), found);        // 図の順はプロジェクトの要素順（＝図を作った順）

        List<Dgm> dgms = new ArrayList<>();
        for (ISequenceDiagram sd : found) {
            Dgm d = new Dgm();
            d.name = sd.getName();

            List<INodePresentation> lifelines = new ArrayList<>();
            List<ILinkPresentation> msgs = new ArrayList<>();
            for (IPresentation p : sd.getPresentations()) {
                if (p instanceof INodePresentation && p.getModel() instanceof ILifeline) {
                    lifelines.add((INodePresentation) p);
                } else if (p instanceof ILinkPresentation && p.getModel() instanceof IMessage) {
                    msgs.add((ILinkPresentation) p);
                }
            }

            // ライフラインは左から右の順に持つ（control() が画面のコントロールを先に拾えるように）
            lifelines.sort(Comparator.comparingDouble(np -> np.getLocation().getX()));
            for (INodePresentation np : lifelines) {
                ILifeline ll = (ILifeline) np.getModel();
                Part part = new Part();
                part.key  = ll.getId();            // 同名のライフライン（アクターのユーザとエンティティのユーザ）を
                part.name = baseName(ll);          // 取り違えないよう、名前ではなく ID で引く
                part.kind = kindOf(ll, sd.getName());
                d.parts.add(part);
            }

            // メッセージは上から下の順。返信（破線）は受信側のメソッドにしないので落とす
            msgs.sort(Comparator.comparingDouble(GenCls::y));
            for (ILinkPresentation lp : msgs) {
                IMessage m = (IMessage) lp.getModel();
                if (m.isReturnMessage()) continue;
                Msg msg = new Msg();
                msg.from   = lifelineId(m.getSource());
                msg.to     = lifelineId(m.getTarget());
                msg.label  = m.getName() == null ? "" : m.getName().trim();
                msg.create = m.isCreateMessage();
                d.msgs.add(msg);
            }
            dgms.add(d);
        }
        pa.close();
        return dgms;
    }

    static void collectSeq(IPackage p, List<ISequenceDiagram> out) throws Exception {
        for (IDiagram d : p.getDiagrams()) if (d instanceof ISequenceDiagram) out.add((ISequenceDiagram) d);
        for (INamedElement e : p.getOwnedElements()) {
            if (e instanceof IPackage) collectSeq((IPackage) e, out);
            else for (IDiagram d : e.getDiagrams()) if (d instanceof ISequenceDiagram) out.add((ISequenceDiagram) d);
        }
    }

    static double y(ILinkPresentation lp) {
        Point2D[] pts = lp.getAllPoints();
        return pts.length == 0 ? 0 : pts[0].getY();
    }

    static String lifelineId(INamedElement e) {
        return e == null ? "" : e.getId();
    }

    static String baseName(ILifeline ll) {
        IClass b = ll.getBase();
        String n = b != null ? b.getName() : ll.getName();
        return n == null ? "" : n;
    }

    /**
     * 層は、ライフラインのベースクラスに付いたステレオタイプで決める。
     * 06 の Gen.java がライフラインを作るときに actor / boundary / control / entity を付けている。
     * 付いていないライフラインは、どの層に置くか機械的に決められないので黙って流さず止める。
     */
    static String kindOf(ILifeline ll, String dgmName) {
        IClass b = ll.getBase();
        if (b != null) {
            for (String st : b.getStereotypes()) {
                switch (st) {
                    case "actor": case "boundary": case "control": case "entity": return st;
                }
            }
        }
        throw new IllegalStateException(
                "ライフラインに層のステレオタイプが無い: 図=" + dgmName + " ライフライン=" + baseName(ll)
              + " / astah で <<boundary>> <<control>> <<entity>> <<actor>> のどれかを付けてください");
    }

    // ---------- 概念クラス図の読み込み ----------

    static class AttrDef { String name, type; }

    static class EndDef { String type, mult; boolean composite, aggregate; }

    static class AssocDef { EndDef a, b; }

    static class Concept {
        Map<String, List<AttrDef>> attrs = new LinkedHashMap<>();
        List<AssocDef> assocs = new ArrayList<>();
    }

    static Concept readConcept(String path) throws Exception {
        ProjectAccessor pa = AstahAPI.getAstahAPI().getProjectAccessor();
        pa.open(path, true, false, true);
        Concept c = new Concept();
        Set<IAssociation> seen = new LinkedHashSet<>();
        collect(pa.getProject(), c, seen);
        for (IAssociation as : seen) {
            IAttribute[] ends = as.getMemberEnds();
            if (ends.length != 2) continue;
            AssocDef d = new AssocDef();
            d.a = end(ends[0]);
            d.b = end(ends[1]);
            if (d.a.type == null || d.b.type == null) continue;
            c.assocs.add(d);
        }
        pa.close();
        return c;
    }

    static EndDef end(IAttribute ae) {
        EndDef e = new EndDef();
        e.type = ae.getType() != null ? ae.getType().getName() : null;
        e.mult = ae.getMultiplicityRangeString();
        e.composite = ae.isComposite();
        e.aggregate = ae.isAggregate();
        return e;
    }

    static void collect(INamedElement e, Concept c, Set<IAssociation> seen) {
        if (e instanceof IClass) {
            IClass cls = (IClass) e;
            List<AttrDef> list = new ArrayList<>();
            for (IAttribute a : cls.getAttributes()) {
                if (a.getAssociation() != null) {
                    seen.add(a.getAssociation());
                    continue;
                }
                AttrDef ad = new AttrDef();
                ad.name = a.getName();
                ad.type = a.getTypeExpression();
                list.add(ad);
            }
            c.attrs.put(cls.getName(), list);
        } else if (e instanceof IPackage) {
            for (INamedElement ch : ((IPackage) e).getOwnedElements()) collect(ch, c, seen);
        }
    }

    // ---------- 詳細クラス図の組み立て ----------

    static void build(List<Dgm> dgms, Concept concept, String out) throws Exception {
        ProjectAccessor pa = AstahAPI.getAstahAPI().getProjectAccessor();
        pa.create(out);
        ITransactionManager tm = pa.getTransactionManager();
        BasicModelEditor me = pa.getModelEditorFactory().getBasicModelEditor();
        ClassDiagramEditor cde = pa.getDiagramEditorFactory().getClassDiagramEditor();
        IModel project = pa.getProject();

        tm.beginTransaction();
        project.setName("みるメモ詳細クラス図");
        IClassDiagram cd = cde.createClassDiagram(project, "みるメモ詳細クラス図");
        cde.setDiagram(cd);

        Map<String, IPackage> pkgs = new LinkedHashMap<>();
        for (String p : new String[]{"バウンダリ", "コントロール", "DM", "エンティティ"}) {
            pkgs.put(p, me.createPackage(project, p));
        }

        // 受信したメッセージを、受信側クラスのメソッドとして集める（出現順・重複なし）
        Map<String, LinkedHashSet<String>> ops = new LinkedHashMap<>();
        List<String> entityOrder = new ArrayList<>();
        for (Dgm d : dgms) {
            for (Msg m : d.msgs) {
                Part to = d.find(m.to);
                if (to == null || to.kind.equals("actor")) continue;   // アクターはシステムの外
                String op;
                if (m.create) {                                        // 生成メッセージ（<<create>>）
                    if (!to.kind.equals("entity")) continue;           // 画面の生成＝画面遷移。メソッドにしない
                    op = to.name + "情報の登録要求";
                } else {
                    op = m.label;
                }
                ops.computeIfAbsent(to.name, k -> new LinkedHashSet<>()).add(op);
            }
            for (Part p : d.parts) {
                if (p.kind.equals("entity") && !entityOrder.contains(p.name)) entityOrder.add(p.name);
            }
        }

        Map<String, IClass> classes = new LinkedHashMap<>();
        Map<String, INodePresentation> nodes = new LinkedHashMap<>();

        // バウンダリ層／コントロール層（画面1列ずつ）
        double totalW = COL * dgms.size();
        for (int i = 0; i < dgms.size(); i++) {
            Dgm d = dgms.get(i);
            double x = COL * i;
            IClass b = mkClass(me, cde, pkgs.get("バウンダリ"), d.name, ops, classes, nodes, x, Y_BOUNDARY);
            IClass c = mkClass(me, cde, pkgs.get("コントロール"), d.control(), ops, classes, nodes, x, Y_CONTROL);
            link(me, cde, b, c, "1", "1", nodes);
        }

        // DM層
        double dmX = totalW / 2 - 100;
        IClass dm = mkClass(me, cde, pkgs.get("DM"), DM, ops, classes, nodes, dmX, Y_DM);
        for (Dgm d : dgms) link(me, cde, classes.get(d.control()), dm, "1", "1", nodes);

        // エンティティ層
        List<String> hubs = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String name : entityOrder) {
            if (name.equals("ユーザ") || name.equals("作品") || name.equals("ジャンル・カテゴリ")) hubs.add(name);
            else rest.add(name);
        }
        double x0 = dmX - ENT_COL * rest.size() / 2;
        for (int i = 0; i < hubs.size(); i++) {
            double x = x0 + ENT_COL * rest.size() * (i + 0.5) / hubs.size();
            mkEntity(me, cde, pkgs.get("エンティティ"), hubs.get(i), ops, concept, classes, nodes, x, Y_ENTITY_1);
        }
        for (int i = 0; i < rest.size(); i++) {
            mkEntity(me, cde, pkgs.get("エンティティ"), rest.get(i), ops, concept, classes, nodes,
                     x0 + ENT_COL * i, Y_ENTITY_2);
        }
        for (String name : entityOrder) link(me, cde, dm, classes.get(name), "1", "1", nodes);

        // エンティティ間の関連は概念クラス図から写す
        for (AssocDef a : concept.assocs) {
            IClass c1 = classes.get(a.a.type);
            IClass c2 = classes.get(a.b.type);
            if (c1 == null || c2 == null) continue;
            IAssociation as = me.createAssociation(c1, c2, "", "", "");
            for (IAttribute ae : as.getMemberEnds()) {
                EndDef src = ae.getType() == c1 ? a.a : a.b;
                if (!src.mult.isEmpty()) ae.setMultiplicityString(src.mult);
                if (src.composite) ae.setComposite();
                else if (src.aggregate) ae.setAggregation();
            }
            cde.createLinkPresentation(as, nodes.get(a.a.type), nodes.get(a.b.type));
        }

        tm.endTransaction();
        pa.save();
        pa.close();
        System.out.println("generated " + classes.size() + " classes -> " + out);
    }

    static IClass mkClass(BasicModelEditor me, ClassDiagramEditor cde, IPackage pkg, String name,
                          Map<String, LinkedHashSet<String>> ops,
                          Map<String, IClass> classes, Map<String, INodePresentation> nodes,
                          double x, double y) throws Exception {
        IClass c = me.createClass(pkg, name);
        for (String op : ops.getOrDefault(name, new LinkedHashSet<>())) {
            IOperation o = me.createOperation(c, op, "void");
            o.setVisibility(INamedElement.PUBLIC_VISIBILITY);
        }
        classes.put(name, c);
        nodes.put(name, cde.createNodePresentation(c, new Point2D.Double(x, y)));
        return c;
    }

    static void mkEntity(BasicModelEditor me, ClassDiagramEditor cde, IPackage pkg, String name,
                         Map<String, LinkedHashSet<String>> ops, Concept concept,
                         Map<String, IClass> classes, Map<String, INodePresentation> nodes,
                         double x, double y) throws Exception {
        IClass c = me.createClass(pkg, name);
        for (AttrDef a : concept.attrs.getOrDefault(name, Collections.emptyList())) {
            IAttribute at = me.createAttribute(c, a.name, a.type);
            at.setVisibility(INamedElement.PRIVATE_VISIBILITY);
        }
        for (String op : ops.getOrDefault(name, new LinkedHashSet<>())) {
            IOperation o = me.createOperation(c, op, "void");
            o.setVisibility(INamedElement.PUBLIC_VISIBILITY);
        }
        classes.put(name, c);
        nodes.put(name, cde.createNodePresentation(c, new Point2D.Double(x, y)));
    }

    static void link(BasicModelEditor me, ClassDiagramEditor cde, IClass a, IClass b,
                     String multA, String multB, Map<String, INodePresentation> nodes) throws Exception {
        IAssociation as = me.createAssociation(a, b, "", "", "");
        for (IAttribute ae : as.getMemberEnds()) {
            ae.setMultiplicityString(ae.getType() == a ? multA : multB);
        }
        cde.createLinkPresentation(as, nodes.get(a.getName()), nodes.get(b.getName()));
    }
}
