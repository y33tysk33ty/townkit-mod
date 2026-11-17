package townkit.core.work;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Finds every {@link WorkSite} in Towntopia's classes as shipped: one per instruction, so a read that occurs twice in a
 * method is listed twice (the {@code occurrences} column of {@code work-sites.tsv}). Scoped kinds (constants, returns)
 * are not listed here: they are everywhere; {@link #scopedCounts} counts them in one method.
 */
public final class WorkSiteScanner {
    private static final String PREFIX = "com/alex/test/";

    private WorkSiteScanner() {
    }

    public static List<WorkSite> scan(ClassNode cls) {
        String owner = cls.name.startsWith(PREFIX) ? cls.name.substring(PREFIX.length()) : cls.name;
        List<WorkSite> out = new ArrayList<>();
        for (MethodNode m : cls.methods) {
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                WorkSite.Kind k = WorkSite.Kind.of(in);
                if (k != null && !k.scoped) out.add(new WorkSite(owner, m.name + m.desc, k));
            }
        }
        return out;
    }

    /** How often each scoped kind occurs in {@code m}. */
    public static Map<WorkSite.Kind, Integer> scopedCounts(MethodNode m) {
        Map<WorkSite.Kind, Integer> out = new TreeMap<>();
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            WorkSite.Kind k = WorkSite.Kind.of(in);
            if (k != null && k.scoped) out.merge(k, 1, Integer::sum);
        }
        return out;
    }

    public static List<WorkSite> scanJar(Path jar) throws IOException {
        List<WorkSite> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (!n.startsWith(PREFIX) || !n.endsWith(".class")) continue;
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
