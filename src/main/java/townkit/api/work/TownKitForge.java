package townkit.api.work;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import townkit.api.Subscription;
import townkit.api.spi.TownKitBackend;
import townkit.api.spi.TownKitWorkView;

/**
 * What Towntopia's blacksmith forges: smith materials, gear tiers placed relative to Towntopia's, and forge recipes
 * (gear and stock items), added, changed or removed.
 *
 * <p><b>Materials.</b> Towntopia's are {@code towntopia:cobble}, {@code towntopia:iron}, {@code towntopia:leather},
 * {@code towntopia:diamond}, {@code towntopia:netherite}, {@code towntopia:template}, {@code towntopia:netherrack} and
 * {@code towntopia:wood}; recipes name them by id, and they can't be replaced or removed. Up to 16 addon materials can
 * be in use at once (more are skipped, logged). The smith counts a material from the village's storage and its own pack
 * and fetches and uses it like Towntopia's own.
 *
 * <p><b>Tiers.</b> Towntopia's gear tiers are {@code towntopia:wood}, {@code towntopia:stone}, {@code towntopia:iron},
 * {@code towntopia:diamond} and {@code towntopia:netherite}. An addon tier sits where its {@link TierPlace} puts it, so
 * the smith upgrades villagers through it ("between iron and diamond": iron, then the addon tier, then diamond). A tier
 * follows the Towntopia tier just above it for the smith's "best material" setting (a tier between iron and diamond is
 * allowed while the setting allows diamond) and needs the smith level its place gives. Towntopia's tiers can't be
 * removed. A tier after a tier nobody registered is skipped (logged).
 *
 * <p><b>Recipes</b> ({@link ForgeRecipe}): keyed by result item. Gear recipes are made for the villagers who use that
 * slot (miners' pickaxes, guards' swords and armour, ...); villagers wear and use registered gear as Towntopia's own of
 * that tier (allowed while their tool settings allow the Towntopia tier just above it), and Towntopia rates it at its
 * tier in its upgrade and worn logic. Stock items (no gear) and gear can be ordered by players (Mayor stock orders).
 * Towntopia's own recipes (its tools, armour, shield, shears, bucket and template copy) can be changed or removed.
 * Recycling is Towntopia's: TownKit doesn't recycle addon gear.
 *
 * <p>Merging follows {@link TownKitCooking}: Java changes in the order they were registered, then the data files in
 * file-id order, re-read on {@code /reload}; a removal wins; adding what exists replaces it whole (logged). Data files
 * {@code data/<namespace>/townkit/forge_materials/*.json}:
 * {@code {"action":"add","id":"mymod:steel","items":"#c:ingots/steel"}} (a tag, or one item id without {@code #});
 * {@code forge_tiers}: {@code {"action":"add","id":"mymod:steel","after":"towntopia:iron","skill":30}} ({@code skill}
 * optional); {@code forge_recipes}: {@code {"action":"add","result":"mymod:steel_pickaxe","count":1,
 * "cost":{"mymod:steel":3,"towntopia:wood":2},"tier":"mymod:steel","slot":"pickaxe","smithing":false}} ({@code count},
 * {@code tier} with {@code slot}, and {@code smithing} optional), {@code {"action":"change","result":...,"cost":{...}}},
 * {@code {"action":"remove","result":"minecraft:bucket"}}; materials and tiers are removed by {@code "id"} (Java:
 * {@link #removeMaterial}, {@link #removeTier}).
 *
 * <p><b>Smelting.</b> {@link #smelt} adds "raw X smelts into Y" to the three raws the smith smelts. Data files
 * {@code data/<namespace>/townkit/smelting/*.json}, keyed by the raw item: {@code {"action":"add","raw":"mymod:raw_tin",
 * "result":"mymod:tin_ingot"}}, {@code {"action":"change","raw":"minecraft:raw_copper","result":"mymod:tin_ingot"}} (also for
 * Towntopia's raw iron and gold), {@code {"action":"remove","raw":"mymod:raw_tin"}}. Raw copper and addon entries can be
 * removed ({@link #removeSmelt}); removing raw iron or raw gold is skipped (logged): the smith's iron gathering shares raw iron, and raw gold
 * keeps its kitchen path. TownKit ships no entries of its own.
 *
 * <p>With nothing registered, Towntopia's smith runs on its own objects, untouched. With TownKit's hooks switched off
 * (or when Towntopia's smith code changed so that TownKit can't reach all of it: one log line), Towntopia's smith keeps
 * its own lists; {@link #tierOf} still answers from what was registered. Register at mod setup or later, from any
 * thread.
 *
 * @since 0.7.1
 */
public final class TownKitForge {
    private TownKitForge() {
    }

    private static TownKitWorkView view() {
        return TownKitBackend.get().work();
    }

    /**
     * Registers the smith material {@code id}, whose items are those in {@code items}, until the subscription is removed.
     * A material with the same id is replaced whole (logged). Throws IllegalArgumentException if {@code owner} is blank.
     */
    public static Subscription material(String owner, ResourceLocation id, TagKey<Item> items) {
        return view().forgeMaterial(owner, id, items);
    }

    /**
     * Registers the gear tier {@code id} at {@code place} until the subscription is removed. Gear of a removed tier is
     * rated as Towntopia rates it again (an unknown tool: tier 0). Throws IllegalArgumentException if {@code owner} is
     * blank.
     */
    public static Subscription tier(String owner, ResourceLocation id, TierPlace place) {
        return view().forgeTier(owner, id, place);
    }

    /**
     * Removes the smith material {@code id} (another addon's or a data file's) until the subscription is removed, like
     * a {@code "remove"} {@code forge_materials} file: a removal wins over every add of that id, and a recipe needing it
     * is skipped (logged). Towntopia's own materials can't be removed (it changes nothing; logged once as a removal of
     * an unknown material). Throws IllegalArgumentException if {@code owner} is blank.
     *
     * @since 0.7.1
     */
    public static Subscription removeMaterial(String owner, ResourceLocation id) {
        return view().removeForgeMaterial(owner, id);
    }

    /**
     * Removes the gear tier {@code id} (another addon's or a data file's) until the subscription is removed, like a
     * {@code "remove"} {@code forge_tiers} file: a removal wins over every add of that id; gear of that tier is rated as
     * Towntopia rates it again, and tiers placed after it are skipped (logged). Towntopia's own tiers can't be removed
     * (it changes nothing; logged once as a removal of an unknown tier). Throws IllegalArgumentException if
     * {@code owner} is blank.
     *
     * @since 0.7.1
     */
    public static Subscription removeTier(String owner, ResourceLocation id) {
        return view().removeForgeTier(owner, id);
    }

    /**
     * Adds {@code recipe} until the subscription is removed; a recipe with the same result is replaced whole (logged).
     * Throws IllegalArgumentException if {@code owner} is blank.
     */
    public static Subscription addRecipe(String owner, ForgeRecipe recipe) {
        return view().addForgeRecipe(owner, recipe);
    }

    /**
     * Changes the recipe making {@code result} (Towntopia's or another addon's) until the subscription is removed. If no
     * such recipe exists the change waits for one (logged once when the server has started without it). Throws
     * IllegalArgumentException if {@code owner} is blank or {@code change} sets nothing.
     */
    public static Subscription changeRecipe(String owner, Item result, ForgeRecipeChange change) {
        return view().changeForgeRecipe(owner, result, change);
    }

    /**
     * Removes the recipe making {@code result} until the subscription is removed (the smith no longer makes it; a
     * Towntopia item keeps its own tier). Throws IllegalArgumentException if {@code owner} is blank.
     */
    public static Subscription removeRecipe(String owner, Item result) {
        return view().removeForgeRecipe(owner, result);
    }

    /**
     * Makes the smith smelt {@code raw} into {@code result} (alongside its spare raw iron, gold and copper) until the
     * subscription is removed. The smith treats an addon raw like its spare raw copper: the same {@code smelt_spare_ore}
     * setting, batch size and fuel; the same away catch-up. Adding a raw that has an entry (Towntopia's own raw iron, gold
     * or copper, or another addon's) replaces it whole (logged), which changes what it smelts into. An entry with air on either side, or
     * a raw that smelts into itself, is skipped (logged). Throws
     * IllegalArgumentException if {@code owner} is blank.
     */
    public static Subscription smelt(String owner, Item raw, Item result) {
        return view().smelt(owner, raw, result);
    }

    /**
     * Removes the smelting entry for {@code raw} until the subscription is removed: Towntopia's raw copper or an addon's raw
     * (same as a {@code "remove"} data file). Removing raw iron or raw gold is skipped (logged once): the smith's iron
     * gathering shares raw iron, and raw gold keeps its kitchen path. Throws IllegalArgumentException if {@code owner} is
     * blank.
     */
    public static Subscription removeSmelt(String owner, Item raw) {
        return view().removeSmelt(owner, raw);
    }

    /**
     * The gear tier {@code item} is rated at: the tier of its registered gear recipe, else Towntopia's own tier for its
     * tools and armour ({@code towntopia:wood} for wooden tools and leather armour); empty for any other item.
     */
    public static Optional<ResourceLocation> tierOf(Item item) {
        return view().forgeTierOf(item);
    }
}
