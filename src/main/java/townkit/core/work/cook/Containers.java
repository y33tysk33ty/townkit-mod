package townkit.core.work.cook;

import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.cook.CookPlanner;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import townkit.core.FieldReader;
import townkit.core.TownKit;
import townkit.core.work.WorkLog;

/**
 * Keep-containers dishes: a batch that consumed milk buckets or honey bottles gives the empty bucket or bottle back.
 * Towntopia's cook uses the full container up and leaves nothing; a dish with {@code keepContainers} returns each
 * ingredient's crafting remainder ({@link Item#getCraftingRemainingItem}; a bowl, which food leaves nothing for, gives
 * none), one per consumed item.
 *
 * <p>{@link CookHooks} attaches the two after-hooks only while some merged dish keeps containers: the cook's
 * {@code CookActivity#consumeInputs} (the remainders go to her pack, a full pack pops them at her feet and logs the loss
 * like Towntopia's own pack-full cases) and the away cook's {@code CookAway$Shift#takeAll} (to the away pack, else the
 * village's storage). What was consumed is read the way Towntopia read it: through {@link CookVariants#onInputs} in the
 * same context, so a tag dish gives back for the variant it actually used. Server thread.
 */
public final class Containers {
    /** Set while {@link CookHooks} has the container hooks attached: the away batch is noted. */
    private static volatile boolean on;
    /** The recipe of the away batch being cooked on this thread (noted at its first read, cleared with the away day). */
    private static final ThreadLocal<CookActivity.Recipe> AWAY_BATCH = new ThreadLocal<>();
    @Nullable
    private static FieldReader<AwayContext> shiftCtx;
    @Nullable
    private static FieldReader<InventoryManager> shiftPack;
    @Nullable
    @SuppressWarnings("rawtypes")
    private static FieldReader<Map> shiftMade;

    private Containers() {
    }

    static void setOn(boolean value) {
        on = value;
        if (!value) settleStaleAway();
    }

    // --- what is left behind -------------------------------------------------------------------------------------------

    /** The containers {@code consumed} leaves, as item counts (an item's crafting remainder, once per consumed item). */
    public static Map<Item, Integer> remainderCounts(@Nullable Map<Item, Integer> consumed) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        if (consumed == null) return out;
        for (Map.Entry<Item, Integer> e : consumed.entrySet()) {
            Item left = e.getKey() == null ? null : e.getKey().getCraftingRemainingItem();
            if (left != null && e.getValue() != null && e.getValue() > 0) out.merge(left, e.getValue(), Integer::sum);
        }
        return out;
    }

    /** The containers {@code consumed} leaves, as stacks (one stack per container item). */
    public static List<ItemStack> remainders(@Nullable Map<Item, Integer> consumed) {
        List<ItemStack> out = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : remainderCounts(consumed).entrySet()) out.add(new ItemStack(e.getKey(), e.getValue()));
        return out;
    }

    // --- the cook ------------------------------------------------------------------------------------------------------

    /**
     * What a cook's tick holds until its end: the containers of her batches and the context they belong to. Nothing is
     * ever deleted: {@link #drain} gives every item to its owner, or to {@code pop} when giving fails.
     */
    static final class Held<C, T> {
        private C owner;
        private final List<T> items = new ArrayList<>();

        /** Holds {@code more} for {@code ctx}; what an earlier owner still held is settled first (never dropped). */
        void hold(C ctx, List<T> more, java.util.function.BiConsumer<C, T> give, java.util.function.BiConsumer<C, T> pop) {
            if (this.owner != null && this.owner != ctx) drain(give, pop);
            this.owner = ctx;
            this.items.addAll(more);
        }

        /** Gives each item to its owner; an item whose hand-over throws goes to {@code pop}, the rest are still given. */
        void drain(java.util.function.BiConsumer<C, T> give, java.util.function.BiConsumer<C, T> pop) {
            C c = this.owner;
            List<T> all = new ArrayList<>(this.items);
            this.owner = null;
            this.items.clear();
            for (T t : all) {
                try {
                    give.accept(c, t);
                } catch (RuntimeException e) {
                    failed("hand-over", e);
                    try {
                        pop.accept(c, t);
                    } catch (RuntimeException e2) {
                        failed("hand-over fallback", e2);
                    }
                }
            }
        }

        boolean isEmpty() {
            return this.items.isEmpty();
        }
    }

    private static final ThreadLocal<Held<Activity.Context, ItemStack>> HELD = ThreadLocal.withInitial(Held::new);

    /** Into her pack through {@link VillageItemOwnership#produce}; a pack that can't hold it pops it at her feet. */
    private static void giveToCook(Activity.Context ctx, ItemStack left) {
        ItemStack owned = VillageItemOwnership.produce(left, ctx.mgr());
        if (owned.isEmpty()) return;
        if (!ctx.npc().getInventory().addItem(owned)) {
            ItemLossLog.pop(ctx, ctx.npc().blockPosition(), owned, "cook container, pack full");
        }
    }

    private static void popForCook(Activity.Context ctx, ItemStack left) {
        ItemLossLog.pop(ctx, ctx.npc().blockPosition(), left, "cook container, hand-over failed");
    }

    /**
     * After {@code CookActivity#consumeInputs(ctx, recipe)}: a batch of a keep-containers dish took its inputs. The
     * containers are held until the end of her tick ({@link #flushCook}): Towntopia has checked that the dish fits in her pack
     * after the batch and adds it right after this method, so a container taking the room first would lose the dish.
     */
    static void afterConsume(Object[] args, @Nullable Object returned) {
        if (!Boolean.TRUE.equals(returned) || !(args[0] instanceof Activity.Context ctx) || !(args[1] instanceof CookActivity.Recipe recipe)) return;
        if (!CookOverlay.GLOBAL.keepsContainers(recipe.result())) return;
        Map<Item, Integer> consumed = CookVariants.onInputs(recipe, recipe.inputsPerBatch());
        List<ItemStack> left = remainders(consumed);
        if (left.isEmpty()) return;
        HELD.get().hold(ctx, left, Containers::giveToCook, Containers::popForCook);
    }

    /** Before a cook's tick: what a tick that threw left is still given to its cook (never deleted). */
    static void clearPending() {
        Held<Activity.Context, ItemStack> h = HELD.get();
        if (!h.isEmpty()) h.drain(Containers::giveToCook, Containers::popForCook);
    }

    /** After a cook's tick: her batch's containers go to her (see {@link #giveToCook}), whichever tick held them. */
    static void flushCook(Activity.Context ctx) {
        Held<Activity.Context, ItemStack> h = HELD.get();
        if (!h.isEmpty()) h.drain(Containers::giveToCook, Containers::popForCook);
    }

    // --- the away cook -------------------------------------------------------------------------------------------------

    /** Where an away cook's held container goes: her pack, else the village's storage. */
    record AwayHand(AwayContext ctx, @Nullable InventoryManager pack) {
    }

    private static final ThreadLocal<Held<AwayHand, ItemStack>> AWAY_HELD = ThreadLocal.withInitial(Held::new);

    /** The away cook reads a batch's inputs for the first time ({@code CookAway$Shift#shortOf}): this is its dish now. */
    static void noteBatch(Object recipe) {
        flushAway(); // the previous batch's dish was given long ago: its containers may take room now
        if (on && recipe instanceof CookActivity.Recipe r) AWAY_BATCH.set(r);
    }

    /** After the away day: forget the batch; containers still held are placed (her pack, else the village's storage). */
    static void endAwayDay() {
        AWAY_BATCH.remove();
        flushAway();
    }

    /**
     * Before an away day, when hooks go off and when the server stops: anything still held is left over from a day that
     * threw (its after-hook never ran), so her pack and book may be gone. It goes to the village's storage, else it is
     * logged as lost, never dropped silently.
     */
    static void settleStaleAway() {
        AWAY_BATCH.remove();
        settleStaleAway(AWAY_HELD.get());
    }

    static void settleStaleAway(Held<AwayHand, ItemStack> h) {
        settleStaleAway(h, Containers::placeStale, Containers::popAway);
    }

    static <C, T> void settleStaleAway(Held<C, T> h, java.util.function.BiConsumer<C, T> give, java.util.function.BiConsumer<C, T> pop) {
        if (!h.isEmpty()) h.drain(give, pop);
    }

    /** Package-private for tests: this thread's held away containers. */
    static Held<AwayHand, ItemStack> awayHeld() {
        return AWAY_HELD.get();
    }

    private static void placeStale(AwayHand hand, ItemStack owned) {
        int count = owned.getCount();
        int stored = hand.ctx().store(owned);
        if (stored < count) {
            WorkLog.GLOBAL.once("container-lost|" + BuiltInRegistries.ITEM.getKey(owned.getItem()), "[" + CookOverlay.KIND + "] "
                + (count - stored) + " " + BuiltInRegistries.ITEM.getKey(owned.getItem())
                + " an away cook's dish gave back found no room after a day that failed, lost");
        }
    }

    /** Places the held away containers: the away pack, else the village's storage (never deleted without the log line). */
    static void flushAway() {
        Held<AwayHand, ItemStack> h = AWAY_HELD.get();
        if (!h.isEmpty()) h.drain(Containers::placeAway, Containers::popAway);
    }

    private static void placeAway(AwayHand hand, ItemStack owned) {
        int count = owned.getCount();
        Item item = owned.getItem();
        int stored;
        // InventoryManager.addItem is all or nothing (false = nothing was added), so the full count goes to storage then
        if (hand.pack() != null && hand.pack().addItem(owned.copy())) {
            stored = count;
        } else {
            stored = hand.ctx().store(owned);
        }
        if (stored < count) {
            WorkLog.GLOBAL.once("container-lost|" + BuiltInRegistries.ITEM.getKey(item), "[" + CookOverlay.KIND + "] "
                + (count - stored) + " " + BuiltInRegistries.ITEM.getKey(item) + " an away cook's dish gave back found no room, lost");
        }
    }

    private static void popAway(AwayHand hand, ItemStack owned) {
        WorkLog.GLOBAL.once("container-lost|" + BuiltInRegistries.ITEM.getKey(owned.getItem()), "[" + CookOverlay.KIND + "] "
            + owned.getCount() + " " + BuiltInRegistries.ITEM.getKey(owned.getItem()) + " an away cook's dish gave back could not be placed, lost");
    }

    /**
     * After {@code CookAway$Shift#takeAll(wanted, pantry)}: when it took the inputs of the batch noted at its start (not its
     * fuel) and that dish keeps containers, the remainders are added to the pantry the shift plans with and held. They are
     * placed (the away pack, else the village's storage) when the next batch starts or the day ends, after the shift has
     * given the dish: Towntopia checked the dish fits before taking, and a container taking the room first would lose it.
     */
    static void afterAwayTake(Object shift, Object[] args, @Nullable Object returned) {
        if (!Boolean.TRUE.equals(returned) || !(args[0] instanceof Map<?, ?> wanted) || !(args[1] instanceof CookPlanner.Pantry pantry)) return;
        CookActivity.Recipe recipe = AWAY_BATCH.get();
        if (recipe == null || !CookOverlay.GLOBAL.keepsContainers(recipe.result())) return;
        if (!wanted.equals(CookVariants.onInputs(recipe, recipe.inputsPerBatch()))) return;
        AwayContext ctx = read(shiftCtx(shift), shift);
        InventoryManager pack = read(shiftPack(shift), shift);
        Map<?, ?> made = read(shiftMade(shift), shift);
        if (ctx == null) return;
        @SuppressWarnings("unchecked")
        Map<Item, Integer> consumed = (Map<Item, Integer>) wanted;
        List<ItemStack> held = new ArrayList<>();
        for (ItemStack left : remainders(consumed)) {
            ItemStack owned = VillageItemOwnership.produce(left, ctx.mgr);
            if (owned.isEmpty()) continue;
            pantry.add(owned.getItem(), owned.getCount());
            // the away result: what the shift made (its dishes) counts the containers it gave back too. Counted now, because
            // the shift reads its result before the held containers are placed; one that finds no room is logged as lost.
            if (made != null) {
                @SuppressWarnings("unchecked")
                Map<Item, Integer> m = (Map<Item, Integer>) made;
                m.merge(owned.getItem(), owned.getCount(), Integer::sum);
            }
            held.add(owned);
        }
        if (!held.isEmpty()) AWAY_HELD.get().hold(new AwayHand(ctx, pack), held, Containers::placeAway, Containers::popAway);
    }

    @Nullable
    private static <T> T read(FieldReader<T> f, Object owner) {
        if (!f.available()) return null;
        try {
            return f.read(owner);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static FieldReader<AwayContext> shiftCtx(Object shift) {
        FieldReader<AwayContext> f = shiftCtx;
        if (f == null) {
            f = FieldReader.of(CookActivity.class, "com.alex.test.village.economy.away.jobs.CookAway$Shift", "ctx", AwayContext.class);
            shiftCtx = f;
        }
        return f;
    }

    @SuppressWarnings("rawtypes")
    private static FieldReader<Map> shiftMade(Object shift) {
        FieldReader<Map> f = shiftMade;
        if (f == null) {
            f = FieldReader.of(CookActivity.class, "com.alex.test.village.economy.away.jobs.CookAway$Shift", "made", Map.class);
            shiftMade = f;
        }
        return f;
    }

    private static FieldReader<InventoryManager> shiftPack(Object shift) {
        FieldReader<InventoryManager> f = shiftPack;
        if (f == null) {
            f = FieldReader.of(CookActivity.class, "com.alex.test.village.economy.away.jobs.CookAway$Shift", "pack", InventoryManager.class);
            shiftPack = f;
        }
        return f;
    }

    /** Logs once that a hook of this feature couldn't run (the dish still cooks; only the container is not returned). */
    static void failed(String where, RuntimeException e) {
        WorkLog.GLOBAL.once("container|" + where + "|" + e.getClass().getName(), "[" + CookOverlay.KIND + "] giving a keep-containers "
            + "dish's containers back (" + where + ") failed (" + e + "); that batch keeps none");
        TownKit.LOG.debug("[TownKit] container hook failure", e);
    }
}
