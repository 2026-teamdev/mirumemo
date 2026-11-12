import com.change_vision.jude.api.inf.AstahAPI;
import com.change_vision.jude.api.inf.model.*;
import com.change_vision.jude.api.inf.presentation.*;
import com.change_vision.jude.api.inf.project.ProjectAccessor;

import java.awt.geom.Point2D;
import java.util.*;

/**
 * .asta の中のシーケンス図を、メッセージ1本1行の表にして吐く。
 * Yose の出力が UC0001（手で直した正）と同じ作法になっているか突き合わせるための道具。
 *
 *   usage: java Dump <対象.asta> [図のID ...]
 */
public class Dump {

    public static void main(String[] args) throws Exception {
        ProjectAccessor pa = AstahAPI.getAstahAPI().getProjectAccessor();
        pa.open(args[0], true, false, true);          // ロックを取らずに読むだけ
        Set<String> only = new LinkedHashSet<>(Arrays.asList(args).subList(1, args.length));

        List<ISequenceDiagram> dgms = new ArrayList<>();
        collect(pa.getProject(), dgms);
        dgms.sort(Comparator.comparing(INamedElement::getName));

        for (ISequenceDiagram d : dgms) {
            String uc = d.getName().split(" ")[0];
            if (!only.isEmpty() && !only.contains(uc)) continue;

            List<ILinkPresentation> msgs = new ArrayList<>();
            for (IPresentation p : d.getPresentations()) {
                if (p instanceof ILinkPresentation && p.getModel() instanceof IMessage) {
                    msgs.add((ILinkPresentation) p);
                }
            }
            msgs.sort(Comparator.comparingDouble(Dump::y));

            System.out.println("# " + d.getName() + "  (" + msgs.size() + "本)");
            int n = 0;
            for (ILinkPresentation lp : msgs) {
                IMessage m = (IMessage) lp.getModel();
                System.out.printf("%3d\t%4.0f\t%-6s\t%-5s\t%-22s\t%-22s\t%s%n",
                        ++n, y(lp), kind(m), m.getIndex(),
                        name(m.getSource()), name(m.getTarget()), m.getName());
            }
            System.out.println();
        }
        pa.close();
    }

    static double y(ILinkPresentation lp) {
        Point2D[] pts = lp.getAllPoints();
        return pts.length == 0 ? 0 : pts[0].getY();
    }

    static String kind(IMessage m) {
        if (m.isCreateMessage())  return "CREATE";
        if (m.isReturnMessage())  return "RET";
        if (m.isAsynchronous())   return "ASYNC";
        return "SYNC";
    }

    static String name(INamedElement e) {
        if (e == null) return "-";
        String n = e.getName();
        if ((n == null || n.isEmpty()) && e instanceof ILifeline) {
            IClass b = ((ILifeline) e).getBase();
            if (b != null) n = b.getName();
        }
        return n == null ? "-" : n;
    }

    static void collect(IPackage p, List<ISequenceDiagram> out) throws Exception {
        for (IDiagram d : p.getDiagrams()) if (d instanceof ISequenceDiagram) out.add((ISequenceDiagram) d);
        for (INamedElement e : p.getOwnedElements()) {
            if (e instanceof IPackage) collect((IPackage) e, out);
            else for (IDiagram d : e.getDiagrams()) if (d instanceof ISequenceDiagram) out.add((ISequenceDiagram) d);
        }
    }
}
