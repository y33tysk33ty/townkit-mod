package townkit.api.work;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import townkit.api.Subscription;
import townkit.api.spi.TownKitBackend;
import townkit.api.spi.TownKitWorkView;

/**
 * What Towntopia's cook makes: add dishes, change Towntopia's 18 (or another addon's), or remove them.
 *
 * <p>TownKit keeps one merged dish list: Towntopia's dishes, then the Java changes in the order they were registered,
 * then the data files ({@code data/<namespace>/townkit/cook_dishes/*.json}) in file-id order, re-read on
 * {@code /reload}. A change of a dish edits it as it stands at that point; a removal wins over every add and change of
 * that dish; adding a dish that already exists replaces it whole (one log line names both owners). A change or removal
 * of a dish nobody added waits for it, and is logged once if it never comes.
 *
 * <p>Towntopia reads the merged list everywhere it reads its own: the cook's planning and batches, the Mayor's stock
 * orders, the storage menu, the away catch-up, the farmer's compost and the builder's crafting (ingredients they leave
 * alone), and the cook's settings: an added dish gets an "allowed recipes" switch (named after the item, with a "needs"
 * line built from its ingredients) and a stock row (starting at the dish's {@link Dish#stock()}); a removed dish
 * loses both. A Towntopia dish an addon changes or replaces keeps its row, with the new dish's stock as its default.
 * Saved settings are keyed by item id, as Towntopia keys them, and are never rewritten: a removed dish's saved switch
 * and stock count come back with the dish. A batch already started on a dish that a change or removal makes stale ends
 * without making anything (the ingredients stay with the cook).
 *
 * <p>A dish with a {@linkplain Dish#station() station} is made only while one of the village's kitchens holds a block
 * of that {@link CookStation} (see {@link #station}): the cook works at that block instead of a crafting table.
 *
 * <p>With nothing registered, Towntopia's cook runs on its own objects, untouched. With TownKit's hooks switched off
 * ({@code townkit-hooks.properties}), {@link #dishes()} still answers but Towntopia's cook keeps its own 18 dishes.
 * Players whose client has TownKit see the merged rows in the cook's settings screen (the server sends them); players
 * without TownKit see Towntopia's own rows and can't switch off or wipe an addon dish's saved switch. A changed
 * Towntopia dish's row keeps Towntopia's own "needs" text on players' screens (Towntopia's translation wins there).
 *
 * <p>Register at mod setup or later, from any thread.
 *
 * @since 0.7.1
 */
public final class TownKitCooking {
    private TownKitCooking() {
    }

    private static TownKitWorkView view() {
        return TownKitBackend.get().work();
    }

    /**
     * Adds {@code dish} until the subscription is removed; a dish with the same result is replaced whole (logged).
     * {@code owner} names the addon in logs. Throws IllegalArgumentException if {@code owner} is blank.
     */
    public static Subscription addDish(String owner, Dish dish) {
        return view().addDish(owner, dish);
    }

    /**
     * Changes the dish making {@code result} (Towntopia's or another addon's) until the subscription is removed. If no
     * such dish exists the change waits for one (logged once when the server has started without it). Throws
     * IllegalArgumentException if {@code owner} is blank or {@code change} sets nothing.
     */
    public static Subscription changeDish(String owner, Item result, DishChange change) {
        return view().changeDish(owner, result, change);
    }

    /**
     * Removes the dish making {@code result} until the subscription is removed. A removal wins over every add and
     * change of that dish. Throws IllegalArgumentException if {@code owner} is blank.
     */
    public static Subscription removeDish(String owner, Item result) {
        return view().removeDish(owner, result);
    }

    /** The merged dish list, in order (Towntopia's 18 when nothing is registered); immutable. */
    public static List<Dish> dishes() {
        return view().dishes();
    }

    /**
     * Registers the cook station {@code id} until the subscription is removed; a dish names it with
     * {@link Dish.Builder#station}. A station with the same id (from Java or a {@code cook_stations} data file) is
     * replaced whole (logged). The subscription is {@linkplain Subscription#isDisabled() disabled} once the station's
     * handler was switched off after ten failures in a row (the station itself keeps working). Throws
     * IllegalArgumentException if {@code owner} is blank.
     *
     * <p>Data files {@code data/<namespace>/townkit/cook_stations/*.json} do the same without a handler:
     * {@code {"action":"add","id":"mymod:pot","blocks":"#mymod:pots"}} (a block tag, or one block id without
     * {@code #}) and {@code {"action":"remove","id":"mymod:pot"}}.
     */
    public static Subscription station(String owner, ResourceLocation id, CookStation station) {
        return view().station(owner, id, station);
    }

    /**
     * Removes the cook station {@code id} (another addon's or a data file's) until the subscription is removed, like a
     * {@code {"action":"remove"}} {@code cook_stations} file: a removal wins over every add of that id, and dishes that
     * name it aren't made while it is gone. Throws IllegalArgumentException if {@code owner} is blank.
     *
     * @since 0.7.1
     */
    public static Subscription removeStation(String owner, ResourceLocation id) {
        return view().removeStation(owner, id);
    }
}
