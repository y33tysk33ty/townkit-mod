package townkit.core.nav;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Finds every nav site (see {@link NavSite}) in Towntopia's classes, as shipped. Returns one {@link NavSite} per
 * instruction, so a key that occurs twice in a method is listed twice (the {@code occurrences} column of
 * {@code nav-sites.tsv}).
 */
public final class NavSiteScanner {
    private static final String PREFIX = "com/alex/test/";
    private static final String NAV_NODE = PREFIX + "village/NavNode";
    private static final String ASTAR = PREFIX + "village/AStarPathfinder";
    private static final String MOVE_CONTROL = "net/minecraft/world/entity/ai/control/MoveControl";
    static final String FOLLOW_WALK = "village/FollowWalk";
    static final String NAV_GRAPH = "village/NavGraph";
    static final String FLOOD_FILL = "floodFillInner";
    static final String ASTAR_OWNER = "village/AStarPathfinder";
    static final String SMOOTH = "smooth";
    /** The reachability {@code TileAdapter} (anonymous) and its {@code kind} descriptor. */
    static final String CELLS_ADAPTER = "village/NavCells$1";
    static final String CELL_DESC = "(L" + NAV_NODE + ";L" + NAV_NODE + ";J)I";

    private NavSiteScanner() {
    }

    public static List<NavSite> scan(ClassNode cls) {
        String owner = cls.name.startsWith(PREFIX) ? cls.name.substring(PREFIX.length()) : cls.name;
        List<NavSite> out = new ArrayList<>();
        for (MethodNode m : cls.methods) {
            String method = m.name + m.desc;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                NavSite.Kind k = kindOf(owner, m.name, m.desc, in);
                if (k != null) {
                    out.add(new NavSite(owner, method, k));
                }
            }
        }
        return out;
    }

    /**
     * The kind of instruction {@code in} inside {@code owner#methodName methodDesc}, or null when it is not a nav
     * site: a call (STEP, COST, PACE, POLL, LOS) or a return of the reachability adapter (CELL).
     */
    static NavSite.Kind kindOf(String owner, String methodName, String methodDesc, AbstractInsnNode in) {
        if (in instanceof MethodInsnNode mi) {
            return kindOf(owner, methodName, mi);
        }
        if (in.getOpcode() == Opcodes.IRETURN && owner.equals(CELLS_ADAPTER) && methodName.equals("kind")
                && methodDesc.equals(CELL_DESC)) {
            return NavSite.Kind.CELL;
        }
        return null;
    }

    /** The kind of call {@code mi} inside {@code owner#methodName}, or null when it is not a nav site. */
    static NavSite.Kind kindOf(String owner, String methodName, MethodInsnNode mi) {
        int op = mi.getOpcode();
        if (op == Opcodes.INVOKEVIRTUAL && mi.owner.equals(NAV_NODE) && mi.name.equals("canStepTo")
                && mi.desc.equals("(L" + NAV_NODE + ";)Z")) {
            return NavSite.Kind.STEP;
        }
        if (op == Opcodes.INVOKESTATIC && mi.owner.equals(ASTAR) && mi.name.equals("edgeCost")
                && mi.desc.endsWith(")I")) {
            return NavSite.Kind.COST;
        }
        if (op == Opcodes.INVOKEVIRTUAL && owner.equals(FOLLOW_WALK) && mi.owner.equals(MOVE_CONTROL)
                && mi.name.equals("setWantedPosition") && mi.desc.equals("(DDDD)V")) {
            return NavSite.Kind.PACE;
        }
        if (op == Opcodes.INVOKEINTERFACE && owner.equals(NAV_GRAPH) && methodName.equals(FLOOD_FILL)
                && mi.owner.equals("java/util/Queue") && mi.name.equals("poll")
                && mi.desc.equals("()Ljava/lang/Object;")) {
            return NavSite.Kind.POLL;
        }
        if (op == Opcodes.INVOKESTATIC && owner.equals(ASTAR_OWNER) && methodName.equals(SMOOTH)
                && mi.owner.equals(ASTAR) && mi.name.equals("lineOfSight") && mi.desc.endsWith(")Z")) {
            return NavSite.Kind.LOS;
        }
        return null;
    }

    public static List<NavSite> scanJar(Path jar) throws IOException {
        List<NavSite> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (!n.startsWith(PREFIX) || !n.endsWith(".class")) {
                    continue;
                }
                ClassNode cn = new ClassNode();
                try (InputStream in = zip.getInputStream(e)) {
                    new ClassReader(in).accept(cn, 0);
                }
                out.addAll(scan(cn));
            }
        }
        return out;
    }
}
