package townkit.core.client.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** The {@code addonLookSwaps} line reader: what it takes and what it skips (with a warning). */
class LookChoiceTest {
    private static final ResourceLocation MEDIEVAL = ResourceLocation.parse("myaddon:medieval");
    private static final ResourceLocation RUSTIC = ResourceLocation.parse("towntopia:rustic");

    @Test
    void readsAWellFormedLine() {
        LookChoice.Swap s = LookChoice.Swap.parse("myaddon:medieval: cook = towntopia:rustic");
        assertNotNull(s);
        assertEquals(new LookChoice.Swap(MEDIEVAL, "cook", RUSTIC), s);
        assertEquals(s, LookChoice.Swap.parse(s.line()), "a line written by line() reads back the same");
    }

    @Test
    void trimsSpaceAndLowercasesTheSkin() {
        assertEquals(new LookChoice.Swap(MEDIEVAL, "guard_2", RUSTIC), LookChoice.Swap.parse("  myaddon:medieval :  Guard_2=  towntopia:rustic "));
    }

    @Test
    void refusesLinesThatAreNotSwaps() {
        assertNull(LookChoice.Swap.parse(""), "empty");
        assertNull(LookChoice.Swap.parse("myaddon:medieval: cook"), "no '='");
        assertNull(LookChoice.Swap.parse("medieval cook = towntopia:rustic"), "no ':' before the skin");
        assertNull(LookChoice.Swap.parse("medieval: cook = towntopia:rustic"), "the look in use is not an id (no namespace)");
        assertNull(LookChoice.Swap.parse("myaddon:medieval: = towntopia:rustic"), "no skin");
        assertNull(LookChoice.Swap.parse("myaddon:medieval: cook = "), "no look to take the picture from");
        assertNull(LookChoice.Swap.parse("myaddon:medieval: cook = Bad Id"), "the look to take it from isn't an id");
        assertNull(LookChoice.Swap.parse("My Addon:medieval: cook = towntopia:rustic"), "the look in use isn't an id");
    }

    @Test
    void indexSkipsBadLinesWithAWarningAndDropsSelfSwaps() {
        List<String> warnings = new ArrayList<>();
        var index = LookChoice.index(List.of(
            "myaddon:medieval: cook = towntopia:rustic",
            "not a swap",
            "myaddon:medieval: guard = myaddon:medieval",
            "myaddon:medieval: cook = towntopia:london"), warnings::add);
        assertEquals(1, warnings.size(), "one bad line, one warning");
        assertTrue(warnings.get(0).contains("not a swap"), warnings.get(0));
        assertEquals(RUSTIC.withPath("london"), index.get(MEDIEVAL).get("cook"), "a later line for the same skin wins");
        assertNull(index.get(MEDIEVAL).get("guard"), "a look swapped to itself is no swap");
    }
}
