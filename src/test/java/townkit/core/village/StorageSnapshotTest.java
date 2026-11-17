package townkit.core.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageInventory;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import townkit.api.village.StorageCategory;
import townkit.api.village.VillageStorage;

class StorageSnapshotTest {
    /** The stable tier holds only the read methods: nothing a later field would force us to keep forever. */
    @Test
    void theStableTypeIsAnInterfaceWithNoStaticFactory() {
        assertTrue(VillageStorage.class.isInterface());
        for (var m : VillageStorage.class.getDeclaredMethods()) {
            assertTrue(!Modifier.isStatic(m.getModifiers()), m.toString());
        }
    }

    /** Only key() matters here: two buildings' lists may name the same container, by object or by position id. */
    private static StorageInventory inventory(Object key) {
        return new StorageInventory() {
            @Override
            public int size() {
                return 0;
            }

            @Override
            public ItemStack peek(int slot) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ItemStack take(int slot, int count) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ItemStack insert(ItemStack stack, boolean simulate) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Object key() {
                return key;
            }
        };
    }

    @Test
    void aContainerInASecondBuildingIsSkipped() {
        StorageInventory a = inventory("shared");
        StorageInventory sameContainerOtherBuilding = inventory("shared");
        StorageInventory b = inventory("own");
        Set<Object> seen = new HashSet<>();
        assertEquals(List.of(a), StorageSnapshot.unseen(List.of(a), seen));
        assertEquals(List.of(b), StorageSnapshot.unseen(List.of(sameContainerOtherBuilding, b), seen));
    }

    /** storage() promises no exception: a category added by a Towntopia update reads as MISC. */
    @Test
    void anUnknownCategoryReadsAsMisc() {
        assertEquals(StorageCategory.FOOD, StorageSnapshot.category("FOOD"));
        assertEquals(StorageCategory.MISC, StorageSnapshot.category("SOMETHING_NEW"));
    }

    /** Fails when a Towntopia update changes its categories: re-check TownKit's storage categories against the new Towntopia. */
    @Test
    void categoriesMirrorTowntopia() {
        assertEquals(
            List.of(StorageBuilding.AutoCategory.values()).stream().map(Enum::name).toList(),
            List.of(StorageCategory.values()).stream().map(Enum::name).toList());
    }

    @Test
    void theEmptySnapshotHoldsNothing() {
        VillageStorage none = StorageSnapshot.empty();
        assertTrue(none.items().isEmpty());
        assertTrue(none.items(StorageCategory.FOOD).isEmpty());
        assertEquals(0, none.ownEmeralds());
        assertEquals(0, none.plainEmeralds());
        assertEquals(0, none.otherVillageEmeralds());
        assertTrue(none.otherVillageEmeraldsByVillage().isEmpty());
        assertEquals(0, none.storageBuildings());
        assertEquals(0, none.unreadBuildings());
    }
}
