package townkit.core.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkTableTest {
    record Item(String id, int n) {
    }

    private final List<String> lines = new ArrayList<>();
    private WorkTable<String, Item> table;
    private final List<Item> builtIns = List.of(new Item("a", 1), new Item("b", 2));

    @BeforeEach
    void setUp() {
        this.table = new WorkTable<>("test", Item::id, new WorkLog(this.lines::add));
        this.table.builtIns(this.builtIns);
    }

    @Test
    void nothingRegisteredUntouched() {
        assertFalse(this.table.touched());
        assertSame(this.builtIns, this.table.merged());
        assertEquals(new Item("a", 1), this.table.get("a"));
    }

    @Test
    void addAppends() {
        this.table.add("addon", new Item("c", 3));
        assertTrue(this.table.touched());
        assertEquals(List.of(new Item("a", 1), new Item("b", 2), new Item("c", 3)), this.table.merged());
        assertEquals(new Item("c", 3), this.table.get("c"));
    }

    @Test
    void changeEditsBuiltIn() {
        this.table.change("addon", "b", i -> new Item("b", i.n() * 10));
        assertEquals(List.of(new Item("a", 1), new Item("b", 20)), this.table.merged());
        assertEquals(new Item("b", 20), this.table.get("b"));
    }

    @Test
    void removeHidesBuiltIn() {
        this.table.remove("addon", "a");
        assertEquals(List.of(new Item("b", 2)), this.table.merged());
        assertNull(this.table.get("a"));
    }

    @Test
    void removalWinsOverLaterChange() {
        this.table.remove("one", "a");
        this.table.change("two", "a", i -> new Item("a", 99));
        this.table.add("three", new Item("a", 5));
        assertEquals(List.of(new Item("b", 2)), this.table.merged());
        this.table.report();
        assertFalse(this.lines.stream().anyMatch(l -> l.contains("no such entry")), this.lines.toString());
    }

    @Test
    void secondAddReplacesAndLogsClash() {
        this.table.add("first", new Item("c", 3));
        this.table.add("second", new Item("c", 4));
        this.table.add("unrelated", new Item("d", 1)); // rebuilds again: no repeat
        assertEquals(new Item("c", 4), this.table.get("c"));
        assertEquals(1, this.lines.size(), this.lines.toString());
        assertTrue(this.lines.get(0).contains("first") && this.lines.get(0).contains("second"), this.lines.get(0));
    }

    @Test
    void removeOverOtherAddLogsClash() {
        this.table.add("adder", new Item("c", 3));
        this.table.remove("remover", "c");
        this.table.add("unrelated", new Item("d", 1)); // rebuilds again: no repeat
        assertNull(this.table.get("c"));
        assertEquals(1, this.lines.size(), this.lines.toString());
        assertTrue(this.lines.get(0).contains("adder") && this.lines.get(0).contains("remover"), this.lines.get(0));
    }

    @Test
    void removeOverOtherChangeLogsClash() {
        this.table.change("changer", "a", i -> new Item("a", 9));
        this.table.remove("remover", "a");
        this.table.add("unrelated", new Item("d", 1));
        assertNull(this.table.get("a"));
        assertEquals(1, this.lines.size(), this.lines.toString());
        assertTrue(this.lines.get(0).contains("changer") && this.lines.get(0).contains("remover"), this.lines.get(0));
    }

    @Test
    void removeOfBuiltInOrOwnEntryIsNoClash() {
        this.table.remove("remover", "a"); // Towntopia's own entry: not a clash between owners
        this.table.add("self", new Item("c", 3));
        this.table.change("self", "c", i -> new Item("c", 4));
        this.table.remove("self", "c"); // the owner removing its own entry
        assertNull(this.table.get("a"));
        assertNull(this.table.get("c"));
        assertTrue(this.lines.isEmpty(), this.lines.toString());
    }

    @Test
    void twoOwnersChangingOneKeyLogClash() {
        this.table.change("first", "b", i -> new Item("b", i.n() + 1));
        this.table.change("second", "b", i -> new Item("b", i.n() * 10));
        this.table.change("second", "b", i -> new Item("b", i.n() + 1)); // the same owner again: no new clash
        this.table.add("unrelated", new Item("d", 1));
        assertEquals(new Item("b", 31), this.table.get("b"), "both still apply, in order");
        assertEquals(1, this.lines.size(), this.lines.toString());
        assertTrue(this.lines.get(0).contains("first") && this.lines.get(0).contains("second"), this.lines.get(0));
    }

    @Test
    void oneOwnerChangingItsOwnAddIsNoClash() {
        this.table.add("addon", new Item("c", 3));
        this.table.change("addon", "c", i -> new Item("c", 4));
        this.table.change("other", "a", i -> new Item("a", 5));
        assertTrue(this.lines.isEmpty(), this.lines.toString());
    }

    @Test
    void changeBeforeAddApplies() {
        this.table.change("changer", "c", i -> new Item("c", i.n() + 100));
        this.table.add("adder", new Item("c", 3));
        assertEquals(new Item("c", 103), this.table.get("c"));
        this.table.report();
        assertTrue(this.lines.isEmpty(), this.lines.toString());
    }

    @Test
    void removeUnknownLogsOnce() {
        this.table.remove("addon", "zzz");
        this.table.add("other", new Item("c", 3));
        this.table.report();
        this.table.report();
        assertEquals(1, this.lines.size(), this.lines.toString());
        assertTrue(this.lines.get(0).contains("addon") && this.lines.get(0).contains("zzz"));
        assertEquals(3, this.table.merged().size());
    }

    @Test
    void handleRemoveRestores() {
        WorkTable.Handle h = this.table.remove("addon", "a");
        WorkTable.Handle c = this.table.change("addon", "b", i -> new Item("b", 7));
        h.remove();
        h.remove(); // twice is harmless
        assertEquals(List.of(new Item("a", 1), new Item("b", 7)), this.table.merged());
        c.remove();
        assertFalse(this.table.touched());
        assertSame(this.builtIns, this.table.merged());
    }

    @Test
    void filesAfterJava() {
        this.table.change("java", "a", i -> new Item("a", 10));
        this.table.setFileChanges(List.of(WorkChange.change("file", "a", i -> new Item("a", i.n() + 5))));
        assertEquals(new Item("a", 15), this.table.get("a"));
        this.table.setFileChanges(List.of(WorkChange.remove("file", "a")));
        assertNull(this.table.get("a"));
    }

    @Test
    void mergedIsCachedUntilChange() {
        this.table.add("addon", new Item("c", 3));
        List<Item> first = this.table.merged();
        long v = this.table.version();
        assertSame(first, this.table.merged());
        assertEquals(v, this.table.version());
        this.table.add("addon", new Item("d", 4));
        assertTrue(this.table.version() > v);
    }
}
