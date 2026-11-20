package townkit.core.work.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** W13 containers: what a batch's consumed ingredients leave behind (bare items, no registries). */
class ContainersTest {
    private CookOverlayTest.TestItems items;
    private Item milk;
    private Item bucket;
    private Item honey;
    private Item bottle;
    private Item bowl;
    private Item stew;
    private Item wheat;

    @BeforeEach
    void setUp() throws Exception {
        this.items = new CookOverlayTest.TestItems();
        this.bucket = this.items.item("minecraft:bucket");
        this.bottle = this.items.item("minecraft:glass_bottle");
        this.bowl = this.items.item("minecraft:bowl");
        this.milk = this.items.item("minecraft:milk_bucket");
        this.honey = this.items.item("minecraft:honey_bottle");
        this.stew = this.items.item("minecraft:mushroom_stew");
        this.wheat = this.items.item("minecraft:wheat");
        remainder(this.milk, this.bucket);
        remainder(this.honey, this.bottle);
    }

    /** Sets the item's {@code craftRemainder} the way Towntopia's registry does ({@code Item.getCraftingRemainingItem()}). */
    private static void remainder(Item item, Item left) throws Exception {
        Field f = Item.class.getDeclaredField("craftingRemainingItem");
        f.setAccessible(true);
        f.set(item, left);
    }

    private static Map<Item, Integer> inputs(Object... pairs) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((Item) pairs[i], (Integer) pairs[i + 1]);
        return m;
    }

    @Test
    void milkBucketGivesBucket() {
        assertEquals(Map.of(this.bucket, 2), Containers.remainderCounts(inputs(this.milk, 2)));
    }

    @Test
    void honeyBottleGivesBottle() {
        assertEquals(Map.of(this.bottle, 3), Containers.remainderCounts(inputs(this.honey, 3)));
    }

    @Test
    void bowlGivesNothing() {
        assertTrue(Containers.remainderCounts(inputs(this.bowl, 1, this.stew, 1, this.wheat, 4)).isEmpty());
    }

    @Test
    void sameContainerFromTwoIngredientsAdds() {
        Item honey2 = this.items.item("test:other_bucket_thing");
        try {
            remainder(honey2, this.bucket);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertEquals(Map.of(this.bucket, 3), Containers.remainderCounts(inputs(this.milk, 1, honey2, 2)));
    }

    @Test
    void emptyAndNullInputsGiveNothing() {
        assertTrue(Containers.remainderCounts(Map.of()).isEmpty());
        assertTrue(Containers.remainderCounts(null).isEmpty());
    }

    /** Delivered and popped items, in order, for the {@link Containers.Held} tests. */
    private final java.util.List<String> log = new java.util.ArrayList<>();

    private void give(String ctx, String item) {
        this.log.add("give " + ctx + " " + item);
    }

    private void pop(String ctx, String item) {
        this.log.add("pop " + ctx + " " + item);
    }

    /** A stale hold from a tick that threw reaches its cook when drained (never deleted). */
    @Test
    void staleHoldIsDeliveredToItsCook() {
        Containers.Held<String, String> h = new Containers.Held<>();
        h.hold("cook-a", java.util.List.of("bucket"), this::give, this::pop);
        h.drain(this::give, this::pop);
        assertEquals(java.util.List.of("give cook-a bucket"), this.log);
        assertTrue(h.isEmpty());
    }

    /** A hold for another context is settled to its own cook before the new one is held; nothing is dropped. */
    @Test
    void mismatchedContextDoesNotDrop() {
        Containers.Held<String, String> h = new Containers.Held<>();
        h.hold("cook-a", java.util.List.of("bucket"), this::give, this::pop);
        h.hold("cook-b", java.util.List.of("bottle"), this::give, this::pop);
        assertEquals(java.util.List.of("give cook-a bucket"), this.log);
        h.drain(this::give, this::pop);
        assertEquals(java.util.List.of("give cook-a bucket", "give cook-b bottle"), this.log);
    }

    /** One stack whose hand-over throws goes to the fallback; the next one is still delivered. */
    @Test
    void throwingHandOverStillDeliversTheRest() {
        Containers.Held<String, String> h = new Containers.Held<>();
        h.hold("cook-a", java.util.List.of("one", "two"), this::give, this::pop);
        h.drain((c, i) -> {
            if (i.equals("one")) throw new IllegalStateException("boom");
            give(c, i);
        }, this::pop);
        assertEquals(java.util.List.of("pop cook-a one", "give cook-a two"), this.log);
    }

    /** A pack of one free slot and a full storage: places "glass bottle" or "dish" if a slot is free, else reports a loss. */
    private static final class Room {
        int freeSlots = 1;
        final java.util.List<String> lost = new java.util.ArrayList<>();
        final java.util.List<String> placed = new java.util.ArrayList<>();

        void put(String what) {
            if (this.freeSlots > 0) {
                this.freeSlots--;
                this.placed.add(what);
            } else {
                this.lost.add(what);
            }
        }
    }

    /**
     * The away cook's batch: Towntopia checks the dish fits, takes the inputs (honey bottles from a stack: no slot frees up),
     * then gives the dish. The glass bottle left over is held until after that give, so the dish gets the last slot and
     * the full storage takes (here: loses) only the bottle, never the dish.
     */
    @Test
    void awayContainerWaitsForTheDishesHandOver() {
        Room room = new Room();
        Containers.Held<Room, String> held = new Containers.Held<>();
        // takeAll: the hook holds the remainder
        held.hold(room, java.util.List.of("glass_bottle"), Room::put, (r, i) -> r.lost.add(i));
        // give: the dish takes the free slot
        room.put("dish");
        // the next batch (or the end of the day) places the held container
        held.drain(Room::put, (r, i) -> r.lost.add(i));
        assertEquals(java.util.List.of("dish"), room.placed);
        assertEquals(java.util.List.of("glass_bottle"), room.lost);
    }
    /**
     * A day that threw leaves its containers held; the server stopping (or the next day) settles them: nothing stays held,
     * each goes to the stale hand-over, and one that fails goes to the logged-loss path instead of vanishing.
     */
    @Test
    void staleAwayHoldIsSettledNotKept() {
        Containers.Held<String, String> h = new Containers.Held<>();
        h.hold("village", java.util.List.of("bucket", "bottle"), (c, i) -> {
            if (i.equals("bottle")) throw new IllegalStateException("book closed");
            this.give(c, i);
        }, this::pop);
        Containers.settleStaleAway(h, (c, i) -> {
            if (i.equals("bottle")) throw new IllegalStateException("book closed");
            this.give(c, i);
        }, this::pop);
        assertTrue(h.isEmpty());
        assertEquals(java.util.List.of("give village bucket", "pop village bottle"), this.log);
        Containers.settleStaleAway(); // the thread's own hold: empty, must not throw
        assertTrue(Containers.awayHeld().isEmpty());
    }
}
