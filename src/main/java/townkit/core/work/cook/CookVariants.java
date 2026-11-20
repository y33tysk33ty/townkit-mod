package townkit.core.work.cook;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageInventory;
import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import townkit.api.work.Dish;
import townkit.core.TownKit;
import townkit.core.work.WorkLog;

/**
 * Tag ingredients ("any of"): a dish's input variants, and the swap of a tag dish's inputs to the one its village
 * uses.
 *
 * <p>{@link CookOverlay} hands Towntopia one recipe per dish; a tag dish's inputs are its first variant. Every
 * {@code CookActivity$Recipe.inputsPerBatch()} call in Towntopia goes through {@link #onInputs} ({@code work-sites.tsv},
 * kind {@code INPUTS}). Inside a village's context (a cook's tick, Towntopia's check whether the cook has work, an away
 * cook's day: {@link CookHooks} sets it around them) a tag dish's inputs are the village's chosen variant; outside one, the first variant.
 *
 * <p>The choice, at each swap: among the variants whose inputs are all there (the village's storage plus the cook's
 * pack), the one whose items are most stocked (ties: the earlier variant); with none all there, the most stocked. It is
 * made at most once per cook tick, and once per batch of an away cook's day (at the batch's first read). A cook's running
 * batch remembers its variant: from Towntopia setting her current recipe to that dish ({@code CookActivity.recipe}, set
 * when the batch is started) until it clears it (the batch ended or was given up), her reads of that dish get it, so the
 * inputs she fetched are the ones counted and consumed. That is hers alone: other cooks of the village choose from what
 * they have. One batch is always one variant: the cook never mixes members inside a batch.
 *
 * <p>A context is only good for the game tick it was entered in: if a hooked method throws (its after-hook doesn't run),
 * the leftover context is ignored from the next tick on and replaced by the next enter. Server thread (a thread-local).
 */
public final class CookVariants {
    /** At most this many variants per dish (the product of its tags' members, in tag order); the rest are cut, logged once. */
    public static final int MAX_VARIANTS = 8;
    /** The cook's pack: the slots Towntopia's cook counts as carried. */
    private static final int PACK_SLOTS = 27;

    /** While some merged dish has tag ingredients ({@link CookHooks#refresh}): the bridge's one static read. */
    private static volatile boolean active;

    /** The running game's variants (a holder: the bridge runs from Towntopia's class init, before anything is set up). */
    private static final class Global {
        static final CookVariants INSTANCE = new CookVariants(CookOverlay.GLOBAL);
    }

    /** One entered context. */
    private static final class Ctx {
        final Object village;
        final Object owner;
        /** The cook whose tick this is, or null (the away day, the work check). */
        @Nullable
        final Object cook;
        final LongSupplier clock;
        final long time;
        final Supplier<Map<Item, Integer>> countsSource;
        @Nullable
        Map<Item, Integer> counts;
        /** The variant each tag dish got in this context (the away day: in this batch). */
        final Map<Item, Map<Item, Integer>> decided = new HashMap<>();

        Ctx(Object village, Object owner, LongSupplier clock, @Nullable Object cook, Supplier<Map<Item, Integer>> counts) {
            this.village = village;
            this.owner = owner;
            this.cook = cook;
            this.clock = clock;
            this.time = clock.getAsLong();
            this.countsSource = counts;
        }

        boolean valid() {
            return this.clock.getAsLong() == this.time;
        }

        Map<Item, Integer> counts() {
            Map<Item, Integer> c = this.counts;
            if (c == null) {
                try {
                    c = this.countsSource.get();
                } catch (RuntimeException e) {
                    WorkLog.GLOBAL.once("tag-counts|" + e.getClass().getName(), "[" + CookOverlay.KIND + "] counting a village's "
                        + "items for a tag dish failed (" + e + "); its first variant is used");
                    c = Map.of();
                }
                this.counts = c;
            }
            return c;
        }
    }

    /** A cook's running batch of a tag dish: the dish, and the variant it uses (null until her first read of it). */
    private static final class Running {
        final Item dish;
        @Nullable
        Map<Item, Integer> variant;

        Running(Item dish, @Nullable Map<Item, Integer> variant) {
            this.dish = dish;
            this.variant = variant;
        }
    }

    private final CookOverlay overlay;
    private final ThreadLocal<Ctx> current = new ThreadLocal<>();
    /** Cooks (their activity objects, weakly) with a running batch of a tag dish. Guarded by {@code this}. */
    private final Map<Object, Running> running = new WeakHashMap<>();

    CookVariants(CookOverlay overlay) {
        this.overlay = overlay;
    }

    static CookVariants global() {
        return Global.INSTANCE;
    }

    /** Switches the bridge on while a tag dish exists (else it answers Towntopia's value after one read). */
    static void setActive(boolean on) {
        active = on;
    }

    // --- variants ------------------------------------------------------------------------------------------------------

    /**
     * The input variants of {@code d}: its item ingredients, plus one member of each tag (the product of the tags'
     * members, in tag order, the first tag varying slowest), at most {@link #MAX_VARIANTS} (cut logged once). A member that
     * is also another ingredient adds to its count. Empty when a tag has no members (the dish can't be made). A dish
     * without tag ingredients has one variant, its ingredients.
     *
     * @param members a tag's items, in tag order (each once; items that exist)
     */
    public static List<Map<Item, Integer>> variants(Dish d, Function<TagKey<Item>, List<Item>> members) {
        return variants(d, members, WorkLog.GLOBAL);
    }

    static List<Map<Item, Integer>> variants(Dish d, Function<TagKey<Item>, List<Item>> members, WorkLog log) {
        Map<TagKey<Item>, Integer> tags = d.tagIngredients();
        if (tags.isEmpty()) return List.of(d.ingredients());
        List<TagKey<Item>> keys = new ArrayList<>(tags.keySet());
        List<List<Item>> lists = new ArrayList<>(keys.size());
        long total = 1;
        for (TagKey<Item> t : keys) {
            List<Item> m = members.apply(t);
            LinkedHashSet<Item> distinct = new LinkedHashSet<>();
            if (m != null) {
                for (Item i : m) {
                    if (i != null) distinct.add(i);
                }
            }
            if (distinct.isEmpty()) return List.of();
            lists.add(List.copyOf(distinct));
            total = total > Integer.MAX_VALUE ? total : total * distinct.size();
        }
        int n = (int) Math.min(total, MAX_VARIANTS);
        int[] at = new int[lists.size()];
        List<Map<Item, Integer>> out = new ArrayList<>(n);
        for (int v = 0; v < n; v++) {
            Map<Item, Integer> m = new LinkedHashMap<>(d.ingredients());
            for (int k = 0; k < keys.size(); k++) m.merge(lists.get(k).get(at[k]), tags.get(keys.get(k)), Integer::sum);
            out.add(Collections.unmodifiableMap(m));
            for (int k = at.length - 1; k >= 0; k--) {
                if (++at[k] < lists.get(k).size()) break;
                at[k] = 0;
            }
        }
        if (total > MAX_VARIANTS) {
            log.once("tag-variants|" + d.result(), "[" + CookOverlay.KIND + "] " + d.result() + ": its tag ingredients make "
                + (total > Integer.MAX_VALUE ? "too many" : String.valueOf(total)) + " variants; the cook uses the first "
                + MAX_VARIANTS + " (in tag order)");
        }
        return List.copyOf(out);
    }

    // --- the context ---------------------------------------------------------------------------------------------------

    /**
     * Enters {@code village}'s context on this thread (replacing any left over). {@code cook}: the cook whose tick this is
     * (null for the away day and the work check), {@code running}: the tag dish of her running batch, if any (her batch
     * keeps the variant it started with).
     *
     * @param owner  what {@link #exit} names (the cook, the away context)
     * @param clock  the game time (a context is good for the tick it was entered in)
     * @param counts the village's storage plus the cook's pack, asked for when a choice is made (once per tick; once per
     *               batch of the away day)
     */
    void enter(Object village, Object owner, LongSupplier clock, @Nullable Object cook, @Nullable Item running,
            Supplier<Map<Item, Integer>> counts) {
        Ctx c = new Ctx(village, owner, clock, cook, counts);
        if (cook != null) markRunning(cook, running, null);
        this.current.set(c);
    }

    /**
     * Enters {@code village}'s context for a work check outside a cook's tick (Towntopia's {@code CookActivity.hasWork},
     * which decides whether the cook starts work): no cook, no running batch. Inside a valid context of the same village
     * (the check made from within a cook's tick) nothing changes, and the matching {@link #exit} does nothing.
     */
    void enterCheck(Object village, Object owner, LongSupplier clock, Supplier<Map<Item, Integer>> counts) {
        Ctx c = this.current.get();
        if (c != null && c.village == village && c.valid()) return;
        enter(village, owner, clock, null, null, counts);
    }

    /** Leaves the context {@code owner} entered (nothing when another is current). */
    void exit(Object owner) {
        Ctx c = this.current.get();
        if (c != null && c.owner == owner) this.current.remove();
    }

    /**
     * Leaves the cook's tick context; {@code running}: the tag dish her batch is on after the tick. A batch started in this
     * tick remembers the variant the tick chose for it, so her fetch and consume agree.
     */
    void exitCook(Object cook, @Nullable Item running) {
        Ctx c = this.current.get();
        if (c == null || c.owner != cook) return;
        this.current.remove();
        if (c.valid()) markRunning(cook, running, running == null ? null : c.decided.get(running));
    }

    /** Forgets this thread's context (one left by a hooked method that threw). */
    void clear() {
        this.current.remove();
    }

    /**
     * Records {@code cook}'s running batch: kept as it is while it stays on the same dish (its variant stays), else
     * started on {@code running} with {@code variant} (null: chosen at her next read), or forgotten when none runs.
     */
    private synchronized void markRunning(Object cook, @Nullable Item running, @Nullable Map<Item, Integer> variant) {
        if (running == null) {
            this.running.remove(cook);
            return;
        }
        Running r = this.running.get(cook);
        if (r == null || r.dish != running) {
            this.running.put(cook, new Running(running, variant));
        } else if (r.variant == null) {
            r.variant = variant;
        }
    }

    // --- the swap ------------------------------------------------------------------------------------------------------

    /** The inputs of {@code recipe} as Towntopia should read them here ({@code towntopia}: the recipe's own). */
    Map<Item, Integer> inputs(Object recipe, Map<Item, Integer> towntopia) {
        return inputs(recipe, towntopia, false);
    }

    /**
     * Like {@link #inputs}, at the first read of an away batch ({@code CookAway$Shift#shortOf}, called once at the top of
     * each batch): the dish is chosen afresh from fresh counts; the batch's later reads ({@code takeAll}, {@code goOn})
     * get the same variant. In a cook's tick it is a plain read.
     */
    Map<Item, Integer> batchInputs(Object recipe, Map<Item, Integer> towntopia) {
        return inputs(recipe, towntopia, true);
    }

    private Map<Item, Integer> inputs(Object recipe, Map<Item, Integer> towntopia, boolean newBatch) {
        Ctx c = this.current.get();
        if (c == null) return towntopia;
        if (!c.valid()) {
            this.current.remove();
            return towntopia;
        }
        List<Map<Item, Integer>> vs = this.overlay.variantsOf(recipe);
        if (vs == null || vs.size() < 2) return towntopia;
        Item dish = ((CookActivity.Recipe) recipe).result();
        if (newBatch && c.cook == null) {
            c.decided.remove(dish);
            c.counts = null;
        }
        Map<Item, Integer> d = c.decided.get(dish);
        if (d == null || !vs.contains(d)) {
            d = choose(c, dish, vs);
            c.decided.put(dish, d);
        }
        return d;
    }

    /** The variant of the cook's running batch of {@code dish} (chosen now if it has none yet), else the best one now. */
    private synchronized Map<Item, Integer> choose(Ctx c, Item dish, List<Map<Item, Integer>> vs) {
        Running r = c.cook == null ? null : this.running.get(c.cook);
        if (r != null && r.dish == dish) {
            int kept = r.variant == null ? -1 : vs.indexOf(r.variant);
            if (kept >= 0) return vs.get(kept);
            Map<Item, Integer> best = best(vs, c.counts());
            r.variant = best;
            return best;
        }
        return best(vs, c.counts());
    }

    /** The most stocked variant among those all there, else the most stocked; ties: the earlier. */
    private static Map<Item, Integer> best(List<Map<Item, Integer>> vs, Map<Item, Integer> counts) {
        int best = 0;
        boolean bestThere = false;
        long bestScore = -1;
        for (int i = 0; i < vs.size(); i++) {
            boolean there = true;
            long score = 0;
            for (Map.Entry<Item, Integer> e : vs.get(i).entrySet()) {
                int have = counts.getOrDefault(e.getKey(), 0);
                if (have < e.getValue()) there = false;
                score += have;
            }
            if ((there && !bestThere) || (there == bestThere && score > bestScore)) {
                best = i;
                bestThere = there;
                bestScore = score;
            }
        }
        return vs.get(best);
    }

    /**
     * Rewritten {@code inputsPerBatch()} call in {@code CookAway$Shift#shortOf}, the first read of each away batch: the
     * away day chooses afresh for every batch.
     */
    public static Map<Item, Integer> onBatchInputs(Object recipe, Map<Item, Integer> towntopia) {
        Containers.noteBatch(recipe);
        if (!active) return towntopia;
        try {
            return Global.INSTANCE.batchInputs(recipe, towntopia);
        } catch (RuntimeException e) {
            WorkLog.GLOBAL.once("tag-inputs|" + e.getClass().getName(), "[" + CookOverlay.KIND + "] choosing a tag dish's "
                + "variant failed (" + e + "); its first variant is used");
            return towntopia;
        }
    }

    /** Rewritten {@code CookActivity$Recipe.inputsPerBatch()} calls (every one in the jar but the made dish's bowl check). */
    public static Map<Item, Integer> onInputs(Object recipe, Map<Item, Integer> towntopia) {
        if (!active) return towntopia;
        try {
            return Global.INSTANCE.inputs(recipe, towntopia);
        } catch (RuntimeException e) {
            WorkLog.GLOBAL.once("tag-inputs|" + e.getClass().getName(), "[" + CookOverlay.KIND + "] choosing a tag dish's "
                + "variant failed (" + e + "); its first variant is used");
            return towntopia;
        }
    }

    // --- the game's contexts (CookHooks) -------------------------------------------------------------------------------

    /** The tag dish {@code r} is a recipe of, or null. */
    @Nullable
    private Item tagDish(@Nullable CookActivity.Recipe r) {
        return r != null && this.overlay.variantsOf(r) != null ? r.result() : null;
    }

    /** Before a cook's tick: its village's context ({@code recipe}: the cook's current recipe). */
    void enterCook(CookActivity cook, Activity.Context ctx, @Nullable CookActivity.Recipe recipe) {
        ServerLevel level = ctx.level();
        enter(ctx.mgr(), cook, level::getGameTime, cook, tagDish(recipe), () -> cookCounts(ctx));
    }

    /** After a cook's tick ({@code recipe}: its current recipe now). */
    void exitCook(CookActivity cook, @Nullable CookActivity.Recipe recipe) {
        Ctx c = this.current.get();
        if (c == null || c.owner != cook) return;
        exitCook((Object) cook, tagDish(recipe));
    }

    /** Before Towntopia's "does the cook have work" check ({@code CookActivity.hasWork}): its village's context. */
    void enterCheck(Activity.Context ctx) {
        ServerLevel level = ctx.level();
        enterCheck(ctx.mgr(), ctx, level::getGameTime, () -> cookCounts(ctx));
    }

    /** Before an away cook's day: its village's context (no running batch: the away day cooks whole batches). */
    void enterAway(AwayContext ctx) {
        ServerLevel level = ctx.level;
        enter(ctx.mgr, ctx, level::getGameTime, null, null, () -> awayCounts(ctx));
    }

    @Nullable
    private static volatile MethodHandle storageCounts;
    private static volatile boolean storageCountsTried;

    /** The village's storage (Towntopia's own per-tick count when it can be read) plus the cook's pack. */
    @SuppressWarnings("unchecked")
    private static Map<Item, Integer> cookCounts(Activity.Context ctx) {
        Map<Item, Integer> storage = null;
        MethodHandle h = storageCountsHandle();
        if (h != null) {
            try {
                storage = (Map<Item, Integer>) h.invoke(ctx);
            } catch (Throwable t) {
                if (t instanceof Error err) throw err;
                storage = null;
            }
        }
        Map<Item, Integer> out = new HashMap<>(storage != null ? storage : storage(ctx.mgr(), ctx.level()));
        addPack(out, ctx.npc());
        return out;
    }

    @Nullable
    private static MethodHandle storageCountsHandle() {
        if (!storageCountsTried) {
            try {
                storageCounts = MethodHandles.privateLookupIn(CookActivity.class, MethodHandles.lookup()).findStatic(CookActivity.class,
                    "storageCounts", MethodType.methodType(Map.class, Activity.Context.class));
            } catch (ReflectiveOperationException | RuntimeException e) {
                TownKit.LOG.warn("[TownKit] can't use Towntopia's cook storage count ({}); tag dishes count storage themselves",
                    e.toString());
            }
            storageCountsTried = true;
        }
        return storageCounts;
    }

    /** What Towntopia's cook counts as the village's storage (valid storage rooms, items it may use as material). */
    private static Map<Item, Integer> storage(VillageManager mgr, ServerLevel level) {
        Map<Item, Integer> out = new HashMap<>();
        for (Building b : mgr.getBuildings()) {
            if (!(b instanceof StorageBuilding s) || !s.isValid()) continue;
            for (StorageInventory chest : s.inventories(level)) {
                for (int i = 0; i < chest.size(); i++) {
                    ItemStack st = chest.peek(i);
                    if (!st.isEmpty() && s.mayUseAsMaterial(level, st)) out.merge(st.getItem(), st.getCount(), Integer::sum);
                }
            }
        }
        return out;
    }

    private static Map<Item, Integer> awayCounts(AwayContext ctx) {
        Map<Item, Integer> out = new HashMap<>(ctx.materialCounts());
        addPack(out, ctx.npc());
        return out;
    }

    private static void addPack(Map<Item, Integer> out, @Nullable NpcEntity npc) {
        InventoryManager inv = npc == null ? null : npc.getInventory();
        if (inv == null) return;
        for (int i = 0; i < Math.min(PACK_SLOTS, InventoryManager.SIZE); i++) {
            ItemStack st = inv.getSlot(i);
            if (!st.isEmpty() && inv.mayUseAsMaterial(st)) out.merge(st.getItem(), st.getCount(), Integer::sum);
        }
    }
}
