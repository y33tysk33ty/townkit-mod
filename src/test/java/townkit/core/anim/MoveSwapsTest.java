package townkit.core.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class MoveSwapsTest {
    private static final ResourceLocation WALK = ResourceLocation.parse("towntopia:walk");
    private static final ResourceLocation SALUTE = ResourceLocation.parse("towntopia:salute");
    private static final ResourceLocation MARCH = ResourceLocation.parse("war:march");
    private static final ResourceLocation STOMP = ResourceLocation.parse("war:stomp");
    private static final ResourceLocation SHRUG = ResourceLocation.parse("towntopia:shrug");

    @Test
    void perVillagerBeatsGlobal() {
        MoveSwaps<String> s = new MoveSwaps<>();
        s.add(null, "war", WALK, MARCH);
        s.add("anna", "other", WALK, STOMP);
        assertEquals(STOMP, s.lookup("anna", WALK), "the villager's own swap wins");
        assertEquals(MARCH, s.lookup("ben", WALK), "everyone else gets the global one");
        assertEquals(MARCH, s.lookup(null, WALK));
        // a global swap registered later still doesn't beat the villager's own
        s.add(null, "late", WALK, SHRUG);
        assertEquals(STOMP, s.lookup("anna", WALK));
        assertEquals(SHRUG, s.lookup("ben", WALK));
        assertNull(s.lookup("anna", SALUTE), "nothing for a move nobody swapped");
        s.add(null, "war", SALUTE, SHRUG);
        assertEquals(Map.of(WALK, STOMP, SALUTE, SHRUG), s.effective("anna"), "per move: the villager's own, else global");
        assertEquals(Map.of(WALK, SHRUG, SALUTE, SHRUG), s.effective("ben"));
    }

    @Test
    void stackedUnwind() {
        MoveSwaps<String> s = new MoveSwaps<>();
        long first = s.add(null, "a", WALK, MARCH);
        long second = s.add(null, "b", WALK, STOMP);
        long third = s.add(null, "a", WALK, SHRUG);
        assertEquals(SHRUG, s.lookup(null, WALK), "last registered wins");
        assertTrue(s.remove(third));
        assertEquals(STOMP, s.lookup(null, WALK), "removing it restores the one before");
        assertTrue(s.remove(first));
        assertEquals(STOMP, s.lookup(null, WALK), "removing an older one changes nothing on top");
        assertFalse(s.remove(first), "a second remove does nothing");
        long own = s.add("anna", "a", WALK, MARCH);
        long own2 = s.add("anna", "b", WALK, SHRUG);
        assertEquals(SHRUG, s.lookup("anna", WALK));
        assertTrue(s.remove(own2));
        assertEquals(MARCH, s.lookup("anna", WALK));
        assertTrue(s.remove(own));
        assertEquals(STOMP, s.lookup("anna", WALK), "with its own gone, the villager gets the global one again");
        assertTrue(s.remove(second));
        assertNull(s.lookup("anna", WALK));
        assertTrue(s.isEmpty());
    }

    @Test
    void versionChangesOnEveryChange() {
        MoveSwaps<String> s = new MoveSwaps<>();
        long v0 = s.version();
        long serial = s.add(null, "a", WALK, MARCH);
        long v1 = s.version();
        assertTrue(v1 != v0, "add changes the version");
        s.add("anna", "a", SALUTE, SHRUG);
        long v2 = s.version();
        assertTrue(v2 != v1, "a villager's add too");
        assertTrue(s.remove(serial));
        long v3 = s.version();
        assertTrue(v3 != v2, "remove changes it");
        assertFalse(s.remove(serial));
        assertEquals(v3, s.version(), "a remove that removes nothing doesn't");
        s.clearKeyed();
        assertTrue(s.version() != v3, "clearKeyed changes it");
    }

    @Test
    void clearKeyedKeepsGlobal() {
        MoveSwaps<String> s = new MoveSwaps<>();
        s.add(null, "a", WALK, MARCH);
        s.add("anna", "a", WALK, STOMP);
        assertTrue(s.has("anna"));
        s.clearKeyed();
        assertFalse(s.has("anna"));
        assertEquals(MARCH, s.lookup("anna", WALK));
    }
}
