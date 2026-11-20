package townkit.core.farm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.core.work.WorkLog;

/** W1: the per-rule failure counter that switches a use-harvest rule off after ten bad answers in a row. */
class UseHarvestTest {
    private static final ResourceLocation A = ResourceLocation.fromNamespaceAndPath("test", "a");
    private static final ResourceLocation B = ResourceLocation.fromNamespaceAndPath("test", "b");

    private final List<String> lines = new ArrayList<>();
    private final UseHarvest.Failures failures = new UseHarvest.Failures(new WorkLog(this.lines::add));

    @Test
    void failureCounterSwitchesOffAtTen() {
        for (int i = 0; i < 9; i++) this.failures.record(A, i % 2 == 0 ? UseHarvest.Result.BLOCK_REFUSED : UseHarvest.Result.BLOCK_GONE);
        assertFalse(this.failures.off(A), "nine in a row: still on");
        this.failures.record(A, UseHarvest.Result.BLOCK_REFUSED);
        assertTrue(this.failures.off(A), "ten in a row: off");
        assertFalse(this.failures.off(B), "another rule is not touched");
        assertEquals(1, this.lines.size(), "one log line: " + this.lines);
        assertTrue(this.lines.get(0).contains(A.toString()), this.lines.get(0));
        for (int i = 0; i < 20; i++) this.failures.record(A, UseHarvest.Result.BLOCK_REFUSED);
        assertEquals(1, this.lines.size(), "still one log line");
    }

    @Test
    void aPickResetsTheRow() {
        for (int i = 0; i < 9; i++) this.failures.record(A, UseHarvest.Result.BLOCK_REFUSED);
        this.failures.record(A, UseHarvest.Result.PICKED);
        for (int i = 0; i < 9; i++) this.failures.record(A, UseHarvest.Result.BLOCK_REFUSED);
        assertFalse(this.failures.off(A), "the pick started a new row");
        this.failures.record(A, UseHarvest.Result.BLOCK_REFUSED);
        assertTrue(this.failures.off(A));
    }

    @Test
    void noToolDoesNotCount() {
        for (int i = 0; i < 9; i++) this.failures.record(A, UseHarvest.Result.BLOCK_REFUSED);
        for (int i = 0; i < 30; i++) this.failures.record(A, UseHarvest.Result.NO_TOOL);
        assertFalse(this.failures.off(A), "missing tools neither count nor break the row");
        this.failures.record(A, UseHarvest.Result.BLOCK_GONE);
        assertTrue(this.failures.off(A), "the row went on across the missing tools");
        assertFalse(this.failures.anyOff() && this.failures.off(B));
    }

    @Test
    void reloadResetsSwitchOff() {
        for (int i = 0; i < 10; i++) this.failures.record(A, UseHarvest.Result.BLOCK_REFUSED);
        assertTrue(this.failures.off(A));
        this.failures.reset();
        assertFalse(this.failures.off(A), "on again after a reload");
        for (int i = 0; i < 9; i++) this.failures.record(A, UseHarvest.Result.BLOCK_REFUSED);
        assertFalse(this.failures.off(A), "the count started over");
        this.failures.record(A, UseHarvest.Result.BLOCK_REFUSED);
        assertTrue(this.failures.off(A));
        assertEquals(2, this.lines.size(), "switched off again: logged again " + this.lines);
    }
}
