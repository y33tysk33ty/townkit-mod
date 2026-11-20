package townkit.core.farm;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.farmer.FarmerSeedCrafting;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import townkit.api.hooks.HookCall;
import townkit.core.TownKit;
import townkit.core.work.WorkLog;

/**
 * The farmer makes seeds from produce for addon crops, with the same rules as Towntopia's {@code FarmerSeedCrafting}
 * has for melon and pumpkin seeds. The hooks ({@link FarmHooks}) call {@link #fruitFor}, {@link #plan} and {@link #craft}
 * after (or before) Towntopia's own.
 *
 * <p>Towntopia's private helpers {@code count}, {@code storageTotal} and {@code nearestStorageWith} are called through
 * method handles found with a private lookup, failing soft: if a Towntopia update renames or retypes one, {@link #available()}
 * is false (one log line) and the seed hooks are not attached, so Towntopia's farmer runs as it does without TownKit.
 *
 * @since 0.8.0
 */
final class SeedCrafting {
    /** How many seeds the farmer carries at most (Towntopia's own limit). */
    static final int CARRY = 16;

    private static final MethodHandle COUNT;
    private static final MethodHandle STORAGE_TOTAL;
    private static final MethodHandle NEAREST;
    private static volatile boolean failed;

    static {
        MethodHandle count = null;
        MethodHandle total = null;
        MethodHandle nearest = null;
        try {
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(FarmerSeedCrafting.class, MethodHandles.lookup());
            count = l.findStatic(FarmerSeedCrafting.class, "count", MethodType.methodType(int.class, InventoryManager.class, Item.class));
            total = l.findStatic(FarmerSeedCrafting.class, "storageTotal",
                MethodType.methodType(int.class, ServerLevel.class, VillageManager.class, Item.class));
            nearest = l.findStatic(FarmerSeedCrafting.class, "nearestStorageWith",
                MethodType.methodType(StorageBuilding.class, ServerLevel.class, VillageManager.class, NpcEntity.class, Item.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            WorkLog.GLOBAL.once("seed-lookup", "[" + SeedTable.KIND + "] can't reach Towntopia's seed-crafting helpers (" + e
                + "); farmers make no seeds for addon crops");
            count = null;
        }
        COUNT = count;
        STORAGE_TOTAL = total;
        NEAREST = nearest;
    }

    private SeedCrafting() {
    }

    /** Whether the helpers were found (and have not failed since). */
    static boolean available() {
        return COUNT != null && STORAGE_TOTAL != null && NEAREST != null && !failed;
    }

    /** Logs (once each) the table's seeds that no {@code farmer_crops} definition plants. Call after the server started. */
    static void reportUnplantable() {
        for (SeedTable.Entry e : SeedTable.GLOBAL.entries()) {
            boolean planted;
            try {
                planted = CropDefinitionRegistry.byPlantingItem(e.seed()) != null;
            } catch (RuntimeException | LinkageError ex) {
                return;
            }
            if (!planted) {
                WorkLog.GLOBAL.once("seed-unplanted|" + e.seed(), "[" + SeedTable.KIND + "] " + e.seed()
                    + " is no planting item of any farmer_crops definition, so the farmer makes seeds nobody plants (kept)");
            }
        }
    }

    // --- the hooks -------------------------------------------------------------------------------------------------------

    /** After {@code fruitFor(Item)}: Towntopia has no produce for this seed, so the table's. */
    static void afterFruitFor(HookCall call) {
        if (call.returnValue() != null) return;
        SeedTable.Entry e = SeedTable.GLOBAL.get(call.arg(0, Item.class));
        if (e != null) call.setReturnValue(e.produce());
    }

    /** After {@code plan(...)}: Towntopia found nothing to make, so run the same checks for the table's seeds. */
    static void afterPlan(HookCall call) {
        if (call.returnValue() != null || !available()) return;
        try {
            ServerLevel level = call.arg(0, ServerLevel.class);
            VillageManager village = call.arg(1, VillageManager.class);
            NpcEntity farmer = call.arg(2, NpcEntity.class);
            FarmerSettings settings = FarmerSettings.of(farmer);
            if (settings.turnOffPlanting()) return;
            InventoryManager inventory = farmer.getInventory();
            for (SeedTable.Entry e : SeedTable.GLOBAL.entries()) {
                if (!settings.shouldPlant(e.seed()) || count(inventory, e.seed()) > 0) continue;
                boolean carriesProduce = count(inventory, e.produce()) > 0;
                StorageBuilding storage = carriesProduce ? null : nearestStorageWith(level, village, farmer, e.produce());
                if ((carriesProduce || storage != null)
                    && storageTotal(level, village, e.seed()) <= 0
                    && village.farmers().plotsNeeding(level, village, settings, e.seed()) > 0) {
                    call.setReturnValue(new FarmerSeedCrafting.Plan(e.seed(), e.produce(), produceNeeded(e, 0), storage));
                    return;
                }
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Before {@code craft(...)}, for a table seed only: make up to 16 seeds from the carried produce, as Towntopia does. */
    static void beforeCraft(HookCall call) {
        if (!available()) return;
        SeedTable.Entry e = SeedTable.GLOBAL.get(call.arg(2, Item.class));
        if (e == null) return;
        try {
            NpcEntity farmer = call.arg(0, NpcEntity.class);
            VillageManager village = call.arg(1, VillageManager.class);
            call.cancelWith(craft(farmer, village, e));
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static int craft(NpcEntity farmer, VillageManager village, SeedTable.Entry e) throws Throwable {
        InventoryManager inventory = farmer.getInventory();
        int carriedSeeds = count(inventory, e.seed());
        int produceUsed = Math.min(count(inventory, e.produce()), produceNeeded(e, carriedSeeds));
        if (produceUsed <= 0) return 0;
        int seeds = Math.min(CARRY - carriedSeeds, produceUsed * e.seedsPerProduce());
        ItemStack made = VillageItemOwnership.produce(new ItemStack(e.seed(), seeds), village);
        if (!made.isEmpty() && inventory.capacityFor(made) >= seeds) {
            remove(inventory, e.produce(), produceUsed);
            inventory.addItem(made);
            return seeds;
        }
        return 0;
    }

    static int produceNeeded(SeedTable.Entry e, int carriedSeeds) {
        int missing = Math.max(0, CARRY - carriedSeeds);
        return (missing + e.seedsPerProduce() - 1) / e.seedsPerProduce();
    }

    private static void remove(InventoryManager inventory, Item item, int amount) {
        for (int i = 0; i < 27 && amount > 0; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (inventory.mayUse(stack) && stack.is(item)) {
                int taken = Math.min(amount, stack.getCount());
                inventory.reduceSlot(i, taken);
                amount -= taken;
            }
        }
    }

    // --- Towntopia's private helpers ---------------------------------------------------------------------------------------

    private static int count(InventoryManager inventory, Item item) throws Throwable {
        return (int) COUNT.invokeExact(inventory, item);
    }

    private static int storageTotal(ServerLevel level, VillageManager village, Item item) throws Throwable {
        return (int) STORAGE_TOTAL.invokeExact(level, village, item);
    }

    @Nullable
    private static StorageBuilding nearestStorageWith(ServerLevel level, VillageManager village, NpcEntity farmer, Item item) throws Throwable {
        return (StorageBuilding) NEAREST.invokeExact(level, village, farmer, item);
    }

    /** A {@code /reload} or a server start: the helpers get another try. */
    static void reloaded() {
        failed = false;
    }

    private static void fail(Throwable t) {
        failed = true;
        WorkLog.GLOBAL.once("seed-failed", "[" + SeedTable.KIND + "] Towntopia's seed-crafting helper failed (" + t
            + "); farmers make no seeds for addon crops until the next /reload or restart");
        TownKit.LOG.debug("[TownKit] seed crafting failure", t);
        FarmHooks.refresh();
    }
}
