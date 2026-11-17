package townkit.core.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.api.skin.SkinRef;

class SkinOverridesTest {
    private static final SkinRef MERCHANT = SkinRef.towntopia("merchant");
    private static final SkinRef ENVOY = SkinRef.of(ResourceLocation.parse("war:envoy"));
    private static final SkinRef SOLDIER = SkinRef.of(ResourceLocation.parse("war:soldier"));

    private final SkinOverrides<String> overrides = new SkinOverrides<>();

    @Test
    void lastWins() {
        overrides.put("a", "war", true, ENVOY);
        overrides.put("a", "other", false, MERCHANT);
        assertEquals(Optional.of(MERCHANT), overrides.top("a"));
        overrides.put("a", "war", true, SOLDIER);
        assertEquals(Optional.of(SOLDIER), overrides.top("a"), "the same owner setting again goes on top");
        assertEquals(2, overrides.entries("a").size(), "and replaces its earlier one");
        assertEquals(Optional.empty(), overrides.top("b"), "other keys untouched");
    }

    @Test
    void stackedOverridesUnwind() {
        long envoy = overrides.put("a", "one", true, ENVOY);
        long merchant = overrides.put("a", "two", false, MERCHANT);
        long soldier = overrides.put("a", "three", true, SOLDIER);
        assertTrue(overrides.remove("a", soldier));
        assertEquals(Optional.of(MERCHANT), overrides.top("a"), "removing the top restores the one under it");
        assertTrue(overrides.remove("a", envoy));
        assertEquals(Optional.of(MERCHANT), overrides.top("a"), "removing one below changes nothing on top");
        assertFalse(overrides.remove("a", envoy), "a second remove does nothing");
        assertTrue(overrides.removeOwner("a", "two", false));
        assertEquals(Optional.empty(), overrides.top("a"));
        assertFalse(overrides.remove("a", merchant));
    }

    @Test
    void removeClears() {
        long s = overrides.put("a", "war", true, ENVOY);
        assertFalse(overrides.isEmpty());
        assertTrue(overrides.remove("a", s));
        assertEquals(Optional.empty(), overrides.top("a"));
        assertTrue(overrides.isEmpty(), "nothing kept for a key without overrides");
        overrides.put("a", "war", false, ENVOY);
        overrides.drop("a");
        assertTrue(overrides.isEmpty(), "drop forgets the key");
    }

    @Test
    void savedLoadsUnderWhatIsThere() {
        overrides.put("a", "temp", false, MERCHANT);
        overrides.put("a", "war", true, SOLDIER);
        overrides.loadSaved("a", List.of(new SkinOverrides.Saved("old", ENVOY), new SkinOverrides.Saved("war", ENVOY)));
        List<SkinOverrides.Entry> e = overrides.entries("a");
        assertEquals(List.of("old", "temp", "war"), e.stream().map(SkinOverrides.Entry::owner).toList(),
            "saved ones go underneath; an owner already holding a saved one keeps it");
        assertEquals(Optional.of(SOLDIER), overrides.top("a"));
    }

    @Test
    void savedCodecRoundTrips() {
        List<SkinOverrides.Saved> saved = List.of(new SkinOverrides.Saved("war", ENVOY), new SkinOverrides.Saved("x", MERCHANT));
        Tag tag = SkinOverrides.Saved.LIST_CODEC.encodeStart(NbtOps.INSTANCE, saved).getOrThrow();
        assertEquals(saved, SkinOverrides.Saved.LIST_CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow());
    }
}
