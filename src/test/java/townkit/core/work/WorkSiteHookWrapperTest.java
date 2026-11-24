package townkit.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookInjector;

/**
 * In game the hook injector splits a hooked Towntopia method into a wrapper (the original name, with its own returns)
 * and the body ({@code townkit$<name>}). The work audit must count only the body: counting the wrapper gave a false
 * "doesn't match the audit (Towntopia changed?)" for MinerActivity#isOre and ForgeOrderBook#tierOf in every log.
 */
class WorkSiteHookWrapperTest {
    private static final Path JAR = Path.of("libs", "towntopia-0.1.3.jar");
    private static final String PREFIX = "com/alex/test/";

    /** Collects the WARN messages logged while it's attached to the root logger. */
    private static final class Warnings extends AbstractAppender implements AutoCloseable {
        final List<String> messages = new ArrayList<>();
        private final Logger root = (Logger) LogManager.getRootLogger();

        Warnings() {
            super("work-site-warnings", null, null, true, Property.EMPTY_ARRAY);
            start();
            this.root.addAppender(this);
        }

        @Override
        public void append(LogEvent e) {
            if (e.getLevel().isMoreSpecificThan(Level.WARN)) {
                synchronized (this.messages) {
                    this.messages.add(e.getMessage().getFormattedMessage());
                }
            }
        }

        @Override
        public void close() {
            this.root.removeAppender(this);
            stop();
        }
    }

    private static ClassNode load(String owner) throws Exception {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            ZipEntry e = zip.getEntry(PREFIX + owner + ".class");
            assertNotNull(e, owner + " not in the jar");
            ClassNode cn = new ClassNode();
            try (InputStream in = zip.getInputStream(e)) {
                new ClassReader(in).accept(cn, 0);
            }
            return cn;
        }
    }

    /** Hooks {@code name} of the class as the game does, rewrites its work sites, returns the rewritten site ids. */
    private static BitSet hookAndRewrite(ClassNode cn, String name, List<String> warnings) {
        MethodNode m = cn.methods.stream().filter(x -> x.name.equals(name)).findFirst().orElseThrow();
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        HookInfo h = new HookInfo(1, "test." + name, cn.name, m.name, m.desc, isStatic, "private", null, false, false);
        assertEquals(List.of(1), HookInjector.inject(cn, List.of(h)));
        assertTrue(cn.methods.stream().anyMatch(x -> x.name.equals(HookInjector.BODY_PREFIX + name)), "no body for " + name);
        BitSet ids = new BitSet();
        WorkSiteRewriter.rewrite(cn, WorkSites.load(), ids::set);
        return ids;
    }

    @Test
    void hookedIsOreAndTierOfRaiseNoFalseAuditMismatch() throws Exception {
        try (Warnings w = new Warnings()) {
            BitSet ore = hookAndRewrite(load("village/villagers/miner/behavior/MinerActivity"), "isOre", w.messages);
            BitSet tier = hookAndRewrite(load("village/villagers/blacksmith/ForgeOrderBook"), "tierOf", w.messages);

            List<String> mismatches = w.messages.stream()
                .filter(s -> s.contains("doesn't match the audit") && (s.contains("#isOre(") || s.contains("#tierOf(")))
                .toList();
            assertEquals(List.of(), mismatches);
            assertTrue(!ore.isEmpty() && !tier.isEmpty(), "the bodies' returns are still rewritten");
        }
    }
}
