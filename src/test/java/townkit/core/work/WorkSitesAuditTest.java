package townkit.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The audit of Towntopia's instructions the work overlay replaces or follows ({@code townkit/work-sites.tsv}) against
 * the jar TownKit is built for: every audited read and call in the jar is listed (REWRITE or KEEP) with its count, every
 * listed one is in the jar, every constant and return of a scoped method is listed with the method's count, the
 * audited cook sites and the smith's tier, material and recipe sites are rewritten, and the rewrite of each listed
 * class verifies.
 */
class WorkSitesAuditTest {
    private static final Path JAR = Path.of("libs", "towntopia-0.1.3.jar");
    private static final String P = "com/alex/test/";
    private static final char TAB = (char) 9;
    private static final char NL = (char) 10;

    /** The audited cook sites, all rewritten. */
    private static final Set<String> COOK_REWRITES = Set.of(
        "village/villagers/cook/behavior/CookActivity#recipes()Ljava/util/List;#RECIPES",
        "village/villagers/cook/behavior/CookActivity#fetchCompanion(Lcom/alex/test/village/brain/Activity$Context;Lcom/alex/test/village/villagers/cook/VillageKitchens;Lcom/alex/test/village/villagers/cook/CookPlanner$Pantry;Lcom/alex/test/village/villagers/cook/settings/CookSettings;Lcom/alex/test/village/villagers/cook/CookPlanner$Plan;)Lcom/alex/test/village/brain/Activity$Status;#RECIPES",
        "village/villagers/cook/behavior/CookActivity#visitHeatBatches(Lcom/alex/test/village/brain/Activity$Context;Lcom/alex/test/village/villagers/cook/VillageKitchens;Lcom/alex/test/village/villagers/cook/CookPlanner$Pantry;Lcom/alex/test/village/villagers/cook/settings/CookSettings;Lcom/alex/test/village/villagers/cook/CookPlanner$Plan;)I#RECIPES",
        "village/villagers/cook/behavior/CookActivity#applyProducedFoodProperties(Lnet/minecraft/world/item/ItemStack;)V#RECIPES",
        "village/villagers/cook/behavior/CookActivity#firstCraftableFromCarried(Lcom/alex/test/village/brain/Activity$Context;Lcom/alex/test/village/villagers/cook/VillageKitchens;Lcom/alex/test/village/villagers/cook/CookPlanner$Pantry;Lcom/alex/test/village/villagers/cook/settings/CookSettings;Lcom/alex/test/village/building/KitchenBuilding$HeatSource;)Lcom/alex/test/village/villagers/cook/CookPlanner$Plan;#RECIPES",
        "village/villagers/cook/behavior/CookActivity#depositKeepRule(Lcom/alex/test/village/brain/Activity$Context;)Ljava/util/function/Predicate;#RECIPES",
        "village/villagers/cook/behavior/CookActivity#isDish(Lnet/minecraft/world/item/ItemStack;)Z#DISHES",
        "village/villagers/cook/behavior/CookActivity#held(Lcom/alex/test/village/brain/Activity$Context;)Ljava/util/Map;#DISHES",
        "village/villagers/builder/craft/RecipeBook#never(Lnet/minecraft/world/item/Item;)Z#INGREDIENTS",
        "village/villagers/farmer/FarmerCompost#mayCompost(Lnet/minecraft/world/item/Item;)Z#INGREDIENTS",
        "village/villagers/supplies/ProfessionSupplyRules#shouldKeepFromGenericDeposit(Lcom/alex/test/entity/NpcEntity;Lnet/minecraft/world/item/ItemStack;)Z#INGREDIENTS",
        "village/villagers/cook/settings/CookSettings#<init>(Lcom/alex/test/entity/NpcEntity;)V#COOK_SCHEMA");

    private static final String SMITH = "village/villagers/blacksmith/behavior/BlacksmithActivity";
    private static final String PLANNER = SMITH + "$Planner";
    private static final String STOCK = "village/villagers/blacksmith/ForgeOrderBook$Stock";
    private static final String PLANNER_INIT = PLANNER + "#<init>(Lcom/alex/test/village/brain/Activity$Context;FLcom/alex/test/"
        + "village/building/ForgeBuilding;ZLcom/alex/test/village/villagers/blacksmith/behavior/BlacksmithActivity$AwayOrderBook;)V#";
    private static final String KIND = "Lcom/alex/test/village/villagers/blacksmith/ForgeOrderBook$ToolKind;";

    /**
     * The smith's tier sites the ladder needs rewritten: every array sized by
     * the tier count, every loop bound at netherite, every tier gate and cap in the planner, the skill gate, the tier an
     * item is rated at, and the tier read for craft points. If any is missing, addon tiers stay off.
     */
    private static final Set<String> TIER_REWRITES = Set.of(
        STOCK + "#<init>()V#INT_5@1", STOCK + "#<init>()V#INT_5@2",
        STOCK + "#ownedAtLeast(" + KIND + "I)I#INT_4@1", STOCK + "#armorAtLeast(II)I#INT_4@1",
        PLANNER_INIT + "INT_5@1", PLANNER_INIT + "INT_5@2", PLANNER_INIT + "INT_1@2", PLANNER_INIT + "INT_2@1",
        PLANNER_INIT + "INT_3@1", PLANNER_INIT + "INT_3@2", PLANNER_INIT + "INT_3@3",
        PLANNER_INIT + "INT_4@1", PLANNER_INIT + "INT_4@2", PLANNER_INIT + "INT_4@3", PLANNER_INIT + "MAX_TIER",
        PLANNER + "#toolsAtLeast(" + KIND + "I)I#INT_4@1", PLANNER + "#armorAtLeast(II)I#INT_4@1",
        PLANNER + "#urgentTools([" + KIND + ")V#INT_1@1", PLANNER + "#shears()V#INT_2@1",
        PLANNER + "#buckets(ILjava/lang/String;)V#INT_2@1", PLANNER + "#defenderBasics()V#INT_2@1",
        PLANNER + "#upgrades([" + KIND + ")V#INT_1@1", PLANNER + "#armorUpgrades()V#INT_2@1",
        PLANNER + "#spares()V#INT_1@2", PLANNER + "#spares()V#INT_2@1", PLANNER + "#spares()V#INT_1@3",
        PLANNER + "#spares()V#INT_1@4", PLANNER + "#spares()V#INT_1@5",
        "village/villagers/blacksmith/ForgeOrderBook#tierOf(Lnet/minecraft/world/item/Item;)I#IRETURN",
        SMITH + "#finishCraft(Lcom/alex/test/village/brain/Activity$Context;Lcom/alex/test/village/villagers/blacksmith/behavior/"
            + "BlacksmithActivity$Recipe;)V#RECIPE_TIER",
        SMITH + "$Offline#forgeOne(Lcom/alex/test/village/villagers/blacksmith/behavior/BlacksmithActivity$Job;J)Z#RECIPE_TIER");

    /** The smith's classes whose constants and returns {@link #dumpAllSites} lists. */
    private static final List<String> SMITH_CLASSES = List.of(
        "village/villagers/blacksmith/ForgeOrderBook",
        "village/villagers/blacksmith/ForgeOrderBook$Stock",
        "village/villagers/blacksmith/BlacksmithBalance",
        "village/villagers/blacksmith/behavior/BlacksmithActivity",
        "village/villagers/blacksmith/behavior/BlacksmithActivity$Planner",
        "village/villagers/blacksmith/behavior/BlacksmithActivity$Offline",
        "village/villagers/blacksmith/behavior/BlacksmithActivity$Offline$Payment");

    private static Map<String, Integer> jarCounts() throws Exception {
        Map<String, Integer> out = new TreeMap<>();
        for (WorkSite s : WorkSiteScanner.scanJar(JAR)) out.merge(s.key(), 1, Integer::sum);
        return out;
    }

    private static WorkSites sites() {
        return WorkSites.load();
    }

    private static ClassNode classFromJar(String owner) throws Exception {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            ZipEntry e = zip.getEntry(P + owner + ".class");
            assertNotNull(e, owner + " not in the jar");
            ClassNode cn = new ClassNode();
            try (InputStream in = zip.getInputStream(e)) {
                new ClassReader(in).accept(cn, 0);
            }
            return cn;
        }
    }

    /** Every audited read and call in the jar is a row, with the right count; every such row is in the jar. */
    @Test
    void tsvMatchesJar() throws Exception {
        Map<String, Integer> jar = jarCounts();
        WorkSites s = sites();
        Map<String, Integer> tsv = new TreeMap<>();
        for (int i = 0; i < s.size(); i++) {
            if (!s.kind(i).scoped) tsv.put(s.key(i), s.count(i));
        }
        List<String> problems = new ArrayList<>();
        jar.forEach((k, n) -> {
            Integer t = tsv.get(k);
            if (t == null) problems.add("unlisted read: " + k + " (" + n + ")");
            else if (!t.equals(n)) problems.add("count " + k + ": tsv " + t + ", jar " + n);
        });
        tsv.keySet().stream().filter(k -> !jar.containsKey(k)).forEach(k -> problems.add("listed but not in the jar: " + k));
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * Constants and returns of a method rows name: the method's count in the jar is the row's, and every occurrence of a
     * constant kind the method's rows name has its own row (REWRITE or KEEP), so a tier constant can't hide.
     */
    @Test
    void scopedRowsMatchJar() throws Exception {
        WorkSites s = sites();
        Map<String, ClassNode> classes = new HashMap<>();
        Map<String, List<Integer>> occurrences = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < s.size(); i++) {
            if (!s.kind(i).scoped) continue;
            String base = s.baseKey(i);
            String owner = s.owner(i);
            String method = base.substring(owner.length() + 1, base.lastIndexOf('#'));
            ClassNode cn = classes.computeIfAbsent(owner, o -> {
                try {
                    return classFromJar(o);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            MethodNode m = cn.methods.stream().filter(x -> (x.name + x.desc).equals(method)).findFirst().orElse(null);
            if (m == null) {
                problems.add("method not in the jar: " + base);
                continue;
            }
            int jar = WorkSiteScanner.scopedCounts(m).getOrDefault(s.kind(i), 0);
            if (jar != s.count(i)) problems.add("count " + s.key(i) + ": tsv " + s.count(i) + ", jar " + jar);
            if (s.kind(i).perOccurrence() && s.occurrence(i) > 0) occurrences.computeIfAbsent(base, x -> new ArrayList<>()).add(s.occurrence(i));
        }
        occurrences.forEach((base, listed) -> {
            if (s.siteId(base) >= 0) problems.add("both a whole-method row and occurrence rows: " + base);
            int n = s.baseCount(base);
            for (int k = 1; k <= n; k++) {
                if (!listed.contains(k)) problems.add("unlisted occurrence: " + base + "@" + k);
            }
        });
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * Every iconst_1..5 in every method of the smith's classes is listed (a REWRITE or KEEP row per
     * occurrence, or one whole-method KEEP row), so a tier literal can't sit unaudited in a method no other row names.
     */
    @Test
    void everySmithTierConstantIsListed() throws Exception {
        WorkSites s = sites();
        List<String> problems = new ArrayList<>();
        for (String owner : SMITH_CLASSES) {
            for (MethodNode m : classFromJar(owner).methods) {
                for (Map.Entry<WorkSite.Kind, Integer> e : WorkSiteScanner.scopedCounts(m).entrySet()) {
                    // tier constants only: the smelting reads (Items.*, countItem) are audited in the methods rows name
                    if (!e.getKey().perOccurrence() || e.getKey().insnOwner != null) continue;
                    String base = owner + "#" + m.name + m.desc + "#" + e.getKey().name();
                    if (s.baseCount(base) != e.getValue()) {
                        problems.add("unlisted or miscounted: " + base + " (jar " + e.getValue() + ", tsv " + s.baseCount(base) + ")");
                        continue;
                    }
                    if (s.siteId(base) >= 0) continue; // whole-method KEEP
                    for (int k = 1; k <= e.getValue(); k++) {
                        if (s.siteId(base + "@" + k) < 0) problems.add("unlisted occurrence: " + base + "@" + k);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.size() + " problems:\n" + String.join("\n", problems));
    }

    @Test
    void spikeCookSitesAreRewritten() {
        WorkSites s = sites();
        for (String key : COOK_REWRITES) {
            int id = s.siteId(key);
            assertTrue(id >= 0, "not listed: " + key);
            assertTrue(s.rewrite(id), "not REWRITE: " + key);
        }
        // the class inits that build the lists keep Towntopia's own reads
        int clinit = s.siteId("village/villagers/cook/behavior/CookActivity#<clinit>()V#RECIPES");
        assertTrue(clinit >= 0 && !s.rewrite(clinit));
    }

    /** The smith's tier sites are audited and rewritten; spare materials and their reads too. */
    @Test
    void smithSitesAreRewritten() {
        WorkSites s = sites();
        List<String> missing = new ArrayList<>();
        for (String key : TIER_REWRITES) {
            int id = s.siteId(key);
            if (id < 0 || !s.rewrite(id)) missing.add(key);
        }
        assertTrue(missing.isEmpty(), "tier sites not REWRITE: " + missing);
        int mat = s.siteId(SMITH + "$Mat#<clinit>()V#MAT_VALUES");
        assertTrue(mat >= 0 && s.rewrite(mat), "the material enum is not extended");
        int items = 0;
        int item = 0;
        for (int i = 0; i < s.size(); i++) {
            if (s.rewrite(i) && s.kind(i) == WorkSite.Kind.MAT_ITEMS) items += s.count(i);
            if (s.rewrite(i) && s.kind(i) == WorkSite.Kind.MAT_ITEM) item += s.count(i);
        }
        // Mat.item x3, Mat.items x6
        assertEquals(3, item, "Mat.item reads rewritten");
        assertEquals(6, items, "Mat.items reads rewritten");
    }

    /** Each bridge a row names is a public static method with the descriptor the row's kind calls. */
    @Test
    void bridgesExist() throws Exception {
        WorkSites s = sites();
        ClassLoader cl = getClass().getClassLoader();
        for (int i = 0; i < s.size(); i++) {
            if (!s.rewrite(i) || s.kind(i).style == WorkSite.Style.ENUM_EXTEND) continue;
            String base = s.baseKey(i);
            String methodDesc = base.substring(base.indexOf('('), base.lastIndexOf('#'));
            Type desc = Type.getMethodType(s.kind(i).bridgeDesc(methodDesc));
            Class<?>[] params = new Class<?>[desc.getArgumentTypes().length];
            for (int a = 0; a < params.length; a++) params[a] = type(desc.getArgumentTypes()[a], cl);
            Class<?> owner = Class.forName(s.bridgeOwner(i).replace('/', '.'), false, cl);
            Method m = owner.getMethod(s.bridgeName(i), params);
            assertTrue(Modifier.isStatic(m.getModifiers()), m.toString());
            assertEquals(type(desc.getReturnType(), cl), m.getReturnType(), m.toString());
        }
    }

    private static Class<?> type(Type t, ClassLoader cl) throws ClassNotFoundException {
        return switch (t.getSort()) {
            case Type.INT -> int.class;
            case Type.LONG -> long.class;
            case Type.BOOLEAN -> boolean.class;
            case Type.ARRAY -> Class.forName(t.getDescriptor().replace('/', '.'), false, cl);
            default -> Class.forName(t.getClassName(), false, cl);
        };
    }

    /** Rewriting each listed class hits every REWRITE row of it, verifies, and a second pass changes nothing. */
    @Test
    void everyRewriteRowIsRewritten() throws Exception {
        WorkSites s = sites();
        assertFalse(s.rewriteOwners().isEmpty());
        for (String owner : s.rewriteOwners()) {
            ClassNode cn = classFromJar(owner);
            BitSet ids = new BitSet();
            int n = WorkSiteRewriter.rewrite(cn, s, ids::set);
            BitSet expected = new BitSet();
            int count = 0;
            for (int i = 0; i < s.size(); i++) {
                if (s.owner(i).equals(owner) && s.rewrite(i)) {
                    expected.set(i);
                    count += s.kind(i).perOccurrence() ? 1 : s.count(i);
                }
            }
            assertEquals(expected, ids, owner);
            assertEquals(count, n, owner);
            int bridgeCalls = 0;
            for (MethodNode m : cn.methods) {
                new Analyzer<>(new BasicVerifier()).analyze(cn.name, m);
                for (AbstractInsnNode in : m.instructions) {
                    if (in instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC
                            && (mi.owner.startsWith("townkit/") || mi.name.equals(WorkSiteRewriter.EXTEND))) {
                        bridgeCalls++;
                    }
                }
            }
            assertEquals(count, bridgeCalls, owner + ": one bridge call per rewritten instruction");
            assertEquals(0, WorkSiteRewriter.rewrite(cn, s, x -> { }), owner + ": a second pass rewrote again");
        }
    }

    /** The material enum gets its spare constants through a method of its own. */
    @Test
    void materialEnumGetsSpares() throws Exception {
        ClassNode cn = classFromJar(SMITH + "$Mat");
        assertEquals(1, WorkSiteRewriter.rewrite(cn, sites(), x -> { }));
        MethodNode ext = cn.methods.stream().filter(m -> m.name.equals(WorkSiteRewriter.EXTEND)).findFirst().orElse(null);
        assertNotNull(ext);
        long news = 0;
        for (AbstractInsnNode in : ext.instructions) {
            if (in.getOpcode() == Opcodes.NEW) news++;
        }
        assertEquals(WorkSiteRewriter.SPARE_MATERIALS, news);
        assertEquals(0, WorkSiteRewriter.rewrite(cn, sites(), x -> { }), "a second pass adds nothing");
        assertEquals(1, cn.methods.stream().filter(m -> m.name.equals(WorkSiteRewriter.EXTEND)).count());
    }

    /** A method whose count of a listed read changed (Towntopia changed it) stays as shipped. */
    @Test
    void changedCountLeavesTheMethodAlone() throws Exception {
        String key = "village/villagers/cook/behavior/CookActivity#isDish(Lnet/minecraft/world/item/ItemStack;)Z#DISHES";
        WorkSites s = WorkSites.parse(new StringReader(key + "\t2\tREWRITE\ttownkit/core/work/cook/CookOverlay#onDishes\ttest\n"));
        ClassNode cn = classFromJar("village/villagers/cook/behavior/CookActivity");
        assertEquals(0, WorkSiteRewriter.rewrite(cn, s, x -> { }));
        // a tier constant whose method now has a different number of them: the method stays as shipped
        String tier = PLANNER + "#shears()V#INT_2@1";
        WorkSites t = WorkSites.parse(new StringReader(tier + "\t2\tREWRITE\ttownkit/core/work/forge/ForgeBridges#onTier\ttest\n"));
        assertEquals(0, WorkSiteRewriter.rewrite(classFromJar(PLANNER), t, x -> { }));
    }

    /**
     * Writes every audited instruction in the jar to {@code build/work-sites-all.tsv}, and the constants and returns of
     * every method of the smith's classes to {@code build/work-sites-scoped.tsv} (after a Towntopia update).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "TOWNKIT_DUMP_SITES", matches = ".+")
    void dumpAllSites() throws Exception {
        StringBuilder sb = new StringBuilder();
        jarCounts().forEach((k, n) -> sb.append(k).append(TAB).append(n).append(NL));
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "work-sites-all.tsv"), sb.toString());
        StringBuilder sc = new StringBuilder();
        for (String owner : SMITH_CLASSES) {
            ClassNode cn = classFromJar(owner);
            for (MethodNode m : cn.methods) {
                WorkSiteScanner.scopedCounts(m).forEach((k, n) -> sc.append(owner).append('#').append(m.name).append(m.desc)
                    .append('#').append(k.name()).append(TAB).append(n).append(NL));
            }
        }
        Files.writeString(Path.of("build", "work-sites-scoped.tsv"), sc.toString());
    }
}
