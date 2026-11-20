package townkit.core.work.cook;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.cook.settings.CookSettings;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Ranchers milk for addon dishes. Towntopia's ranchers milk a cow only for cakes (and for orders): a cook who
 * wants cakes makes {@code BucketNeeds#wantsCake} answer true, which sets the milk target. {@link CookHooks} adds
 * {@link #cookWantsMilk}: the same answer when one of the cook's merged dishes (other than the cake) takes a milk bucket,
 * is allowed and has a stock row above 0 that the village's storage doesn't reach yet. Towntopia's 100-tick cache and its
 * cap of 6 buckets stay as they are (the answer is read inside that cache).
 */
public final class MilkDemand {
    private MilkDemand() {
    }

    /** Whether this cook's settings and the village's storage ask for milk for an addon dish. Server thread. */
    public static boolean cookWantsMilk(NpcEntity cook) {
        List<Item> dishes = CookOverlay.GLOBAL.dishesWithInput(Items.MILK_BUCKET, Items.CAKE);
        if (dishes.isEmpty() || !(cook.level() instanceof ServerLevel level)) return false;
        CookSettings settings = CookSettings.of(cook);
        if (!settings.works()) return false;
        VillageManager village = village(cook, level);
        if (village == null) return false;
        return wants(dishes, settings::allowsRecipe, settings::stockTarget, item -> stored(village, level, item));
    }

    /** {@code wants} for the overlay's current dishes (tests). */
    static boolean wants(CookOverlay overlay, Item milk, Item cake, Predicate<Item> allows, ToIntFunction<Item> stockOf,
            ToIntFunction<Item> storedOf) {
        return wants(overlay.dishesWithInput(milk, cake), allows, stockOf, storedOf);
    }

    private static boolean wants(List<Item> dishes, Predicate<Item> allows, ToIntFunction<Item> stockOf, ToIntFunction<Item> storedOf) {
        for (int i = 0; i < dishes.size(); i++) {
            Item dish = dishes.get(i);
            if (!allows.test(dish)) continue;
            int stock = stockOf.applyAsInt(dish);
            if (stock > 0 && storedOf.applyAsInt(dish) < stock) return true;
        }
        return false;
    }

    private static VillageManager village(NpcEntity cook, ServerLevel level) {
        var link = cook.getVillageLink();
        return link == null ? null : VillageManagerRegistry.get(level.getServer()).get(link.villageId());
    }

    private static int stored(VillageManager village, ServerLevel level, Item item) {
        int n = 0;
        for (Building b : village.getBuildings()) {
            if (b instanceof StorageBuilding storage && storage.isValid()) n += storage.countItem(level, item);
        }
        return n;
    }
}
