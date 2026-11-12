import com.change_vision.jude.api.inf.AstahAPI;
import com.change_vision.jude.api.inf.model.*;
import com.change_vision.jude.api.inf.project.ProjectAccessor;

import java.util.*;

/**
 * .asta の中のクラス図を、クラス1つ1ブロックの表にして吐く。
 * GenCls の入力を seq.spec から .asta に替えたとき、出力が変わっていないかを
 * 突き合わせるための道具。06 の Dump.java（シーケンス図版）と同じ役割。
 *
 *   usage: java ClsDump <対象.asta>
 */
public class ClsDump {

    public static void main(String[] args) throws Exception {
        ProjectAccessor pa = AstahAPI.getAstahAPI().getProjectAccessor();
        pa.open(args[0], true, false, true);          // ロックを取らずに読むだけ
        List<IAssociation> assocs = new ArrayList<>();
        walk(pa.getProject(), "", assocs);

        System.out.println();
        System.out.println("=== 関連 ===");
        List<String> lines = new ArrayList<>();
        Set<IAssociation> seen = new LinkedHashSet<>(assocs);
        for (IAssociation as : seen) {
            IAttribute[] e = as.getMemberEnds();
            if (e.length != 2) continue;
            lines.add(String.format("%-22s %-6s -- %-6s %-22s %s",
                    typeName(e[0]), mult(e[0]), mult(e[1]), typeName(e[1]), kind(e[0]) + kind(e[1])));
        }
        Collections.sort(lines);
        for (String s : lines) System.out.println(s);
        System.out.println("関連 " + lines.size() + " 本");
        pa.close();
    }

    static void walk(INamedElement e, String path, List<IAssociation> assocs) {
        if (e instanceof IClass) {
            IClass c = (IClass) e;
            System.out.println("## " + path + c.getName());
            for (IAttribute a : c.getAttributes()) {
                if (a.getAssociation() != null) { assocs.add(a.getAssociation()); continue; }
                System.out.println("   attr  " + a.getName() + " : " + a.getTypeExpression());
            }
            for (IOperation o : c.getOperations()) {
                System.out.println("   op    " + o.getName() + "() : " + o.getReturnTypeExpression());
            }
        } else if (e instanceof IPackage) {
            String p = e.getName().isEmpty() ? path : path + e.getName() + "/";
            for (INamedElement ch : ((IPackage) e).getOwnedElements()) walk(ch, p, assocs);
        }
    }

    static String typeName(IAttribute a) { return a.getType() == null ? "-" : a.getType().getName(); }
    static String mult(IAttribute a) { String m = a.getMultiplicityRangeString(); return m.isEmpty() ? "_" : m; }
    static String kind(IAttribute a) { return a.isComposite() ? "C" : a.isAggregate() ? "A" : ""; }
}
