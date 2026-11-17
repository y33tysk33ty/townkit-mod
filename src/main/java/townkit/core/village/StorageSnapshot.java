package townkit.core.village;

import com.alex.test.town.MayorShop;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageInventory;
import com.alex.test.village.tokens.VillageTokenBinding;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import townkit.api.village.StorageCategory;
import townkit.api.village.VillageStorage;
import townkit.core.TownKit;

/** A {@link VillageStorage} built from a village's storage buildings, reading only (peek, never take) and only loaded chunks. */
public final class StorageSnapshot implements VillageStorage {
    private static final StorageSnapshot EMPTY = new StorageSnapshot(List.of(), Map.of(), Map.of(), 0, 0, Map.of(), 0, 0);
    private static final AtomicBoolean warnedCategory = new AtomicBoolean();

    private final List<ItemStack> items;
    private final Map<StorageCategory, List<ItemStack>> byCategory;
    private final Map<Item, Integer> counts;
    private final int ownEmeralds;
    private final int plainEmeralds;
    private final Map<UUID, Integer> otherEmeralds;
    private final int otherEmeraldTotal;
    private final int storageBuildings;
    private final int unreadBuildings;

    /** Takes ownership of the given stacks (they must already be merged, non-empty and not shared). */
    private StorageSnapshot(List<ItemStack> mergedItems, Map<StorageCategory, List<ItemStack>> byCategory, Map<Item, Integer> counts,
                            int ownEmeralds, int plainEmeralds, Map<UUID, Integer> otherEmeralds, int storageBuildings, int unreadBuildings) {
        this.items = List.copyOf(mergedItems);
        Map<StorageCategory, List<ItemStack>> cat = new HashMap<>();
        for (Map.Entry<StorageCategory, List<ItemStack>> e : byCategory.entrySet()) cat.put(e.getKey(), List.copyOf(e.getValue()));
        this.byCategory = cat;
        this.counts = Map.copyOf(counts);
        this.ownEmeralds = ownEmeralds;
        this.plainEmeralds = plainEmeralds;
        this.otherEmeralds = Map.copyOf(otherEmeralds);
        int total = 0;
        for (int n : otherEmeralds.values()) total += n;
        this.otherEmeraldTotal = total;
        this.storageBuildings = storageBuildings;
        this.unreadBuildings = unreadBuildings;
    }

    /** The snapshot of a village that is gone: nothing in it. */
    public static VillageStorage empty() {
        return EMPTY;
    }

    public static VillageStorage of(VillageManager mgr) {
        ServerLevel level = mgr.getLevel();
        if (level == null) return empty();
        UUID villageId = mgr.getVillageId();
        List<ItemStack> merged = new ArrayList<>();
        Map<StorageCategory, List<ItemStack>> byCategory = new EnumMap<>(StorageCategory.class);
        Map<Item, Integer> counts = new HashMap<>();
        Map<UUID, Integer> others = new HashMap<>();
        int own = 0;
        int plain = 0;
        int buildings = 0;
        int unread = 0;
        Set<Object> seen = new HashSet<>();
        for (Building b : mgr.getBuildings()) {
            if (!(b instanceof StorageBuilding storage)) continue;
            buildings++;
            List<BlockPos> loaded = new ArrayList<>();
            boolean skipped = false;
            for (BlockPos pos : storage.getFunctionalBlocks()) {
                if (readable(level, pos)) loaded.add(pos);
                else skipped = true;
            }
            if (skipped) unread++;
            for (StorageInventory inv : unseen(StorageBuilding.inventories(level, loaded), seen)) {
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack peeked = inv.peek(i);
                    if (peeked.isEmpty()) continue;
                    ItemStack stack = peeked.copy();
                    counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    if (stack.is(Items.EMERALD)) {
                        VillageTokenBinding binding = VillageTokenBinding.read(stack);
                        if (binding == null) plain += stack.getCount();
                        else if (MayorShop.isVillageEmerald(stack, villageId)) own += stack.getCount();
                        else others.merge(binding.townHallId(), stack.getCount(), Integer::sum);
                    }
                    add(byCategory.computeIfAbsent(category(stack), k -> new ArrayList<>()), stack.copy());
                    add(merged, stack);
                }
            }
        }
        return new StorageSnapshot(merged, byCategory, counts, own, plain, others, buildings, unread);
    }

    @Override
    public List<ItemStack> items() {
        return copies(this.items);
    }

    @Override
    public List<ItemStack> items(StorageCategory category) {
        return copies(this.byCategory.getOrDefault(category, List.of()));
    }

    @Override
    public int count(Item item) {
        return this.counts.getOrDefault(item, 0);
    }

    @Override
    public int ownEmeralds() {
        return this.ownEmeralds;
    }

    @Override
    public int otherVillageEmeralds() {
        return this.otherEmeraldTotal;
    }

    @Override
    public Map<UUID, Integer> otherVillageEmeraldsByVillage() {
        return this.otherEmeralds;
    }

    @Override
    public int plainEmeralds() {
        return this.plainEmeralds;
    }

    @Override
    public int storageBuildings() {
        return this.storageBuildings;
    }

    @Override
    public int unreadBuildings() {
        return this.unreadBuildings;
    }

    private static List<ItemStack> copies(List<ItemStack> in) {
        List<ItemStack> out = new ArrayList<>(in.size());
        for (ItemStack s : in) out.add(s.copy());
        return out;
    }

    /** Loaded, and so are its four horizontal neighbours (a double chest's other half is looked up too). */
    private static boolean readable(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!level.isLoaded(pos.relative(d))) return false;
        }
        return true;
    }

    /** The inventories whose container no earlier building already gave: two buildings can hold the same one. */
    static List<StorageInventory> unseen(List<StorageInventory> inventories, Set<Object> seen) {
        List<StorageInventory> out = new ArrayList<>(inventories.size());
        for (StorageInventory inv : inventories) {
            if (seen.add(inv.key())) out.add(inv);
        }
        return out;
    }

    private static StorageCategory category(ItemStack stack) {
        return category(StorageBuilding.categoryOf(stack).name());
    }

    /** A category a Towntopia update added reads as MISC: storage() never throws for it. */
    static StorageCategory category(String name) {
        try {
            return StorageCategory.valueOf(name);
        } catch (IllegalArgumentException e) {
            if (warnedCategory.compareAndSet(false, true)) {
                TownKit.LOG.warn("[TownKit] unknown Towntopia storage category {}, reading it as MISC", name);
            }
            return StorageCategory.MISC;
        }
    }

    /** Merges the stack into the list (same item and components), or appends it. Takes ownership of the stack. */
    private static void add(List<ItemStack> list, ItemStack stack) {
        for (ItemStack have : list) {
            if (ItemStack.isSameItemSameComponents(have, stack)) {
                have.grow(stack.getCount());
                return;
            }
        }
        list.add(stack);
    }
}
