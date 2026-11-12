import com.change_vision.jude.api.inf.AstahAPI;
import com.change_vision.jude.api.inf.editor.*;
import com.change_vision.jude.api.inf.model.*;
import com.change_vision.jude.api.inf.presentation.*;
import com.change_vision.jude.api.inf.project.ProjectAccessor;

import java.awt.geom.Point2D;
import java.util.*;

/**
 * 概念クラス図だけを入力に、エンティティ層＋DM の詳細クラス図を .asta として生成する。
 * シーケンス図に出てこなかった操作も含めて、全エンティティに CRUD の4つを機械的に与える。
 * （シーケンス図に忠実な版は GenCls.java が作る）
 *
 *   usage: java GenEnt <概念クラス図.asta> <output .asta>
 */
public class GenEnt {

    static final double Y_DM = 0;
    static final double Y_ENTITY_1 = 1080;
    static final double Y_ENTITY_2 = 1380;
    static final double ENT_COL = 320;

    static final String[] CRUD = {"取得", "登録", "更新", "削除"};
    /** 概念クラス図に型として置かれているだけのクラス */
    static final Set<String> TYPES = new HashSet<>(Arrays.asList(
            "String", "Date", "int", "float", "boolean", "void"));

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
        GenCls.Concept concept = GenCls.readConcept(args[0]);
        List<String> entities = entitiesOf(concept);

        ProjectAccessor pa = AstahAPI.getAstahAPI().getProjectAccessor();
        pa.create(args[1]);
        ITransactionManager tm = pa.getTransactionManager();
        BasicModelEditor me = pa.getModelEditorFactory().getBasicModelEditor();
        ClassDiagramEditor cde = pa.getDiagramEditorFactory().getClassDiagramEditor();
        IModel project = pa.getProject();

        tm.beginTransaction();
        project.setName("みるメモ詳細クラス図_エンティティ層補完版");
        IClassDiagram cd = cde.createClassDiagram(project, "みるメモ詳細クラス図_エンティティ層補完版");
        cde.setDiagram(cd);

        IPackage dmPkg = me.createPackage(project, "DM");
        IPackage entPkg = me.createPackage(project, "エンティティ");

        Map<String, IClass> classes = new LinkedHashMap<>();
        Map<String, INodePresentation> nodes = new LinkedHashMap<>();

        // 並べ方は GenCls と同じ（ハブを上段、残りを下段）
        List<String> hubs = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String name : entities) {
            if (name.equals("ユーザ") || name.equals("作品") || name.equals("ジャンル・カテゴリ")) hubs.add(name);
            else rest.add(name);
        }
        double x0 = 0;
        double dmX = x0 + ENT_COL * rest.size() / 2 - 100;

        // DM層：全エンティティ × CRUD
        IClass dm = me.createClass(dmPkg, "DM");
        for (String name : entities) {
            for (String crud : CRUD) op(me, dm, name + "情報を" + crud + "する");
        }
        classes.put("DM", dm);
        nodes.put("DM", cde.createNodePresentation(dm, new Point2D.Double(dmX, Y_DM)));

        // エンティティ層：属性は概念クラス図から、操作は CRUD の4つ
        for (int i = 0; i < hubs.size(); i++) {
            double x = x0 + ENT_COL * rest.size() * (i + 0.5) / hubs.size();
            mkEntity(me, cde, entPkg, hubs.get(i), concept, classes, nodes, x, Y_ENTITY_1);
        }
        for (int i = 0; i < rest.size(); i++) {
            mkEntity(me, cde, entPkg, rest.get(i), concept, classes, nodes, x0 + ENT_COL * i, Y_ENTITY_2);
        }

        for (String name : entities) link(me, cde, dm, classes.get(name), nodes);

        // エンティティ間の関連は概念クラス図から写す
        for (GenCls.AssocDef a : concept.assocs) {
            IClass c1 = classes.get(a.a.type);
            IClass c2 = classes.get(a.b.type);
            if (c1 == null || c2 == null) continue;
            IAssociation as = me.createAssociation(c1, c2, "", "", "");
            for (IAttribute ae : as.getMemberEnds()) {
                GenCls.EndDef src = ae.getType() == c1 ? a.a : a.b;
                if (!src.mult.isEmpty()) ae.setMultiplicityString(src.mult);
                if (src.composite) ae.setComposite();
                else if (src.aggregate) ae.setAggregation();
            }
            cde.createLinkPresentation(as, nodes.get(a.a.type), nodes.get(a.b.type));
        }

        tm.endTransaction();
        pa.save();
        pa.close();
        System.out.println("generated " + entities.size() + " entities + DM -> " + args[1]);
    }

    /** 関連を持つか属性を持つクラスをエンティティとみなす（String / Date などの型は外す） */
    static List<String> entitiesOf(GenCls.Concept concept) {
        Set<String> linked = new HashSet<>();
        for (GenCls.AssocDef a : concept.assocs) {
            linked.add(a.a.type);
            linked.add(a.b.type);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<GenCls.AttrDef>> e : concept.attrs.entrySet()) {
            if (TYPES.contains(e.getKey())) continue;
            if (linked.contains(e.getKey()) || !e.getValue().isEmpty()) out.add(e.getKey());
        }
        return out;
    }

    static void mkEntity(BasicModelEditor me, ClassDiagramEditor cde, IPackage pkg, String name,
                         GenCls.Concept concept, Map<String, IClass> classes,
                         Map<String, INodePresentation> nodes, double x, double y) throws Exception {
        IClass c = me.createClass(pkg, name);
        for (GenCls.AttrDef a : concept.attrs.getOrDefault(name, Collections.emptyList())) {
            IAttribute at = me.createAttribute(c, a.name, a.type);
            at.setVisibility(INamedElement.PRIVATE_VISIBILITY);
        }
        for (String crud : CRUD) op(me, c, name + "情報の" + crud + "要求");
        classes.put(name, c);
        nodes.put(name, cde.createNodePresentation(c, new Point2D.Double(x, y)));
    }

    static void op(BasicModelEditor me, IClass c, String name) throws Exception {
        IOperation o = me.createOperation(c, name, "void");
        o.setVisibility(INamedElement.PUBLIC_VISIBILITY);
    }

    static void link(BasicModelEditor me, ClassDiagramEditor cde, IClass a, IClass b,
                     Map<String, INodePresentation> nodes) throws Exception {
        IAssociation as = me.createAssociation(a, b, "", "", "");
        for (IAttribute ae : as.getMemberEnds()) ae.setMultiplicityString("1");
        cde.createLinkPresentation(as, nodes.get(a.getName()), nodes.get(b.getName()));
    }
}
