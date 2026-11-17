package townkit.core.hook;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import townkit.core.building.CheckSiteRewriter;
import townkit.core.building.RewrittenSites;
import townkit.core.building.SiteVerdicts;
import townkit.core.nav.NavSiteRewriter;
import townkit.core.nav.NavSites;
import townkit.core.nav.RewrittenNavSites;
import townkit.core.work.RewrittenWorkSites;
import townkit.core.work.WorkSiteRewriter;
import townkit.core.work.WorkSites;

/**
 * Controls the generated hook mixins. Server owners can switch hooks off without a rebuild through
 * config/townkit-hooks.properties (all hooks, or chosen Towntopia classes), e.g. if a Towntopia update breaks one.
 */
public final class HookMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE = "townkit-hooks.properties";
    private boolean enabled = true;
    private final List<Pattern> disabled = new ArrayList<>();

    @Override
    public void onLoad(String mixinPackage) {
        Path dir;
        try {
            dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
        } catch (Throwable t) {
            dir = Path.of("config");
        }
        Path file = dir.resolve(FILE);
        Properties p = new Properties();
        try {
            if (Files.isRegularFile(file)) {
                try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
            } else {
                Files.createDirectories(dir);
                Files.writeString(file, """
                    # TownKit hooks. Changes apply on the next game start.
                    # enabled=false switches every generated Towntopia hook off (typed events built on them report unavailable and warn).
                    enabled=true
                    # Comma-separated Towntopia classes to leave unhooked, relative to com.alex.test; '*' is a wildcard.
                    # Example: disabledClasses=village.MoveToCommand,village.raid.*
                    disabledClasses=
                    """, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOGGER.warn("[TownKit] could not read {}: {}", file, e.toString());
        }
        // a -Dtownkit.hooks.enabled system property wins over the file (used for A/B performance runs)
        this.enabled = !"false".equalsIgnoreCase(System.getProperty("townkit.hooks.enabled", p.getProperty("enabled", "true")).trim());
        for (String s : p.getProperty("disabledClasses", "").split(",")) {
            String c = s.trim();
            if (!c.isEmpty()) {
                this.disabled.add(Pattern.compile("com\\.alex\\.test\\." + c.replace(".", "\\.").replace("$", "\\$").replace("*", ".*")));
            }
        }
        if (!this.enabled) {
            RewrittenSites.GLOBAL.hooksSwitchedOff();
            RewrittenNavSites.GLOBAL.hooksSwitchedOff();
            LOGGER.warn("[TownKit] all Towntopia hooks are switched off in {}", file);
        } else if (!this.disabled.isEmpty()) {
            LOGGER.info("[TownKit] hooks switched off for: {}", p.getProperty("disabledClasses"));
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!this.enabled) {
            HookStatus.classSkipped(targetClassName, "hooks disabled in config");
            return false;
        }
        for (Pattern p : this.disabled) {
            if (p.matcher(targetClassName).matches()) {
                HookStatus.classSkipped(targetClassName, "class disabled in config");
                if (targetClassName.startsWith("com.alex.test.")) {
                    // its building checks are not rewritten either: reports must not count stand-ins there
                    RewrittenSites.GLOBAL.classSkipped(targetClassName.substring("com.alex.test.".length()).replace('.', '/'));
                    RewrittenNavSites.GLOBAL.classSkipped(targetClassName.substring("com.alex.test.".length()).replace('.', '/'));
                }
                return false;
            }
        }
        return true;
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // The carrier mixins are empty; the hooks themselves are written into the class here
        List<HookInfo> hooks = HookManifest.get().byOwner.get(targetClass.name);
        if (hooks != null) {
            long t0 = System.nanoTime();
            try {
                HookInjector.inject(targetClass, hooks);
            } catch (Throwable t) {
                LOGGER.error("[TownKit] could not inject hooks into {}; that class stays unhooked", targetClassName, t);
                if (targetClass.name.startsWith("com/alex/test/")) {
                    RewrittenSites.GLOBAL.classSkipped(targetClass.name.substring("com/alex/test/".length()));
                    RewrittenNavSites.GLOBAL.classSkipped(targetClass.name.substring("com/alex/test/".length()));
                }
                return;
            } finally {
                HookStatus.injectNanos.addAndGet(System.nanoTime() - t0);
            }
        }
        if (hooks != null) {
            // "@carrier" classes (hook-rules.txt) come here without hooks, only for the nav-site rewrite
            HookStatus.classHooked(targetClass.name);
        }
        rewriteCheckSites(targetClass);
        rewriteNavSites(targetClass);
        rewriteWorkSites(targetClass);
    }

    /** Audited work sites; null when the file can't be read (the work overlay can't reach Towntopia then). */
    private WorkSites workSites;
    private boolean workSitesLoaded;

    /**
     * Points Towntopia's audited reads of the cook's lists (work-sites.tsv) at the work overlay's bridges. Like the
     * other rewrites it only runs for classes that reach here, so hooks switched off leave Towntopia's lists in place.
     */
    private void rewriteWorkSites(ClassNode targetClass) {
        String name = targetClass.name;
        if (!name.startsWith("com/alex/test/")) {
            return;
        }
        WorkSites s;
        synchronized (this) {
            if (!this.workSitesLoaded) {
                this.workSitesLoaded = true;
                try {
                    this.workSites = WorkSites.load();
                } catch (Throwable t) {
                    LOGGER.error("[TownKit] could not read the work sites; Towntopia's cook keeps its own lists", t);
                }
            }
            s = this.workSites;
        }
        String owner = name.substring("com/alex/test/".length());
        if (s == null || !s.rewriteOwners().contains(owner)) {
            return;
        }
        int n;
        BitSet ids = new BitSet();
        try {
            n = WorkSiteRewriter.rewrite(targetClass, s, ids::set);
        } catch (Throwable t) {
            LOGGER.error("[TownKit] work: rewrite of {} failed", name, t);
            ids.clear();
            n = 0;
        }
        RewrittenWorkSites.GLOBAL.classRewritten(owner, ids);
        List<String> missing = RewrittenWorkSites.GLOBAL.missing(s, owner);
        if (!missing.isEmpty()) {
            LOGGER.warn("[TownKit] work: {} audited read(s) in {} were not rewritten (Towntopia changed, or the rewrite "
                    + "failed); Towntopia's own lists are read there: {}", missing.size(), name, missing);
        }
        LOGGER.info("[TownKit] work: rewrote {} list reads in {}", n, name);
    }

    /** Audited nav sites; null when the file can't be read (nav rewriting off). */
    private NavSites navSites;
    private boolean navSitesLoaded;
    private final AtomicInteger navSitesRewritten = new AtomicInteger();

    /**
     * Points Towntopia's audited nav sites (nav-sites.tsv) at the path bridge. Like the building rewrite it only runs
     * for classes that reach here, so hooks switched off in the config leave them alone too.
     */
    private void rewriteNavSites(ClassNode targetClass) {
        String name = targetClass.name;
        if (!name.startsWith("com/alex/test/")) {
            return;
        }
        NavSites s;
        synchronized (this) {
            if (!this.navSitesLoaded) {
                this.navSitesLoaded = true;
                try {
                    this.navSites = NavSites.load();
                } catch (Throwable t) {
                    LOGGER.error("[TownKit] could not read the nav sites; Towntopia's route checks stay as shipped", t);
                }
            }
            s = this.navSites;
        }
        String owner = name.substring("com/alex/test/".length());
        if (s == null || !s.rewriteOwners().contains(owner)) {
            return;
        }
        int n;
        BitSet ids = new BitSet();
        try {
            n = NavSiteRewriter.rewrite(targetClass, s, ids::set);
        } catch (Throwable t) {
            LOGGER.error("[TownKit] nav: nav-site rewrite of {} failed", name, t);
            ids.clear();
            n = 0;
        }
        if (n > 0 || !RewrittenNavSites.GLOBAL.loaded(owner)) {
            // a second pass over the class finds nothing left to rewrite; a failed first pass still records it
            RewrittenNavSites.GLOBAL.classRewritten(owner, ids);
        }
        List<String> missing = RewrittenNavSites.missing(s, owner, ids);
        if (!missing.isEmpty()) {
            LOGGER.warn("[TownKit] nav: {} audited route check(s) in {} were not rewritten (Towntopia changed, or the "
                    + "rewrite failed); path rules don't apply there: {}", missing.size(), name, missing);
        }
        int total = this.navSitesRewritten.addAndGet(n);
        LOGGER.info("[TownKit] nav: rewrote {} route checks in {} ({} so far)", n, name, total);
    }

    /** Verdicts for the building check-site rewrite; null when the file can't be read (rewriting off). */
    private SiteVerdicts verdicts;
    private boolean verdictsLoaded;
    private final AtomicInteger sitesRewritten = new AtomicInteger();

    /**
     * Points the audited block checks of Towntopia's scoped building classes at the stand-in bridge. Runs only for
     * classes that reach here, so hooks switched off in the config (all, or for that class) leave the checks alone too.
     */
    private void rewriteCheckSites(ClassNode targetClass) {
        String name = targetClass.name;
        if (!name.startsWith("com/alex/test/")) {
            return;
        }
        SiteVerdicts v;
        synchronized (this) {
            if (!this.verdictsLoaded) {
                this.verdictsLoaded = true;
                try {
                    this.verdicts = SiteVerdicts.load();
                } catch (Throwable t) {
                    LOGGER.error("[TownKit] could not read the building check sites; Towntopia's block checks stay as shipped", t);
                }
            }
            v = this.verdicts;
        }
        String owner = name.substring("com/alex/test/".length());
        if (v == null || !v.scopedOwners().contains(owner)) {
            return;
        }
        int n;
        BitSet ids = new BitSet();
        try {
            n = CheckSiteRewriter.rewrite(targetClass, v, ids::set);
        } catch (Throwable t) {
            LOGGER.error("[TownKit] building: check-site rewrite of {} failed", name, t);
            ids.clear();
            n = 0;
        }
        // reports count a stand-in only where the bridge really runs
        RewrittenSites.GLOBAL.classRewritten(owner, ids);
        List<String> missing = RewrittenSites.missingUsable(v, owner, ids);
        if (!missing.isEmpty()) {
            LOGGER.warn("[TownKit] building: {} audited check(s) in {} were not rewritten (Towntopia changed, or the "
                    + "rewrite failed); no stand-in counts there: {}", missing.size(), name, missing);
        }
        int total = this.sitesRewritten.addAndGet(n);
        LOGGER.info("[TownKit] building: rewrote {} block checks in {} ({} so far)", n, name, total);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
