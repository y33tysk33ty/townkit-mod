package townkit.api.village;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A read-only snapshot of what a village's storage held when {@link Village#storage()} was called. It never changes
 * afterwards, and every stack it hands out is a copy. Storage in chunks that were not loaded is skipped (those chunks
 * are not loaded for this), see {@link #unreadBuildings()}. A container that two storage buildings share is counted
 * once. Get one from {@link Village#storage()}; TownKit implements this. Server thread only.
 *
 * @since 0.6.2
 */
public interface VillageStorage {
    /** Every stack in the readable storage, merged by item and components, counts summed (may exceed the stack size). Copies. */
    List<ItemStack> items();

    /** {@link #items()} limited to one category. Copies. */
    List<ItemStack> items(StorageCategory category);

    /** How many of this item the readable storage holds (all components together). */
    int count(Item item);

    /** Emeralds bound to this village. */
    int ownEmeralds();

    /** Emeralds bound to any other village. */
    int otherVillageEmeralds();

    /** Emeralds bound to other villages, per village id. Unmodifiable. */
    Map<UUID, Integer> otherVillageEmeraldsByVillage();

    /** Emeralds not bound to any village. */
    int plainEmeralds();

    /** Storage buildings found in the village. */
    int storageBuildings();

    /**
     * Storage buildings with at least one working block that couldn't be read: the block's chunk isn't loaded, or the
     * chunk of one of its four horizontal neighbours isn't (a double chest's other half could be there). Those blocks
     * were skipped.
     */
    int unreadBuildings();
}
