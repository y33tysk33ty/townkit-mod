package townkit.api.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SkinRefTest {
    @Test
    void towntopiaUsesTowntopiaNamespace() {
        assertEquals(ResourceLocation.fromNamespaceAndPath("towntopia", "merchant"), SkinRef.towntopia("merchant").id());
    }

    @Test
    void ofKeepsTheId() {
        ResourceLocation id = ResourceLocation.parse("myaddon:glen");
        assertEquals(new SkinRef(id), SkinRef.of(id));
        assertEquals(id, SkinRef.of(id).id());
    }

    @Test
    void nullAndBadKeysRejected() {
        assertThrows(NullPointerException.class, () -> SkinRef.of(null));
        assertThrows(NullPointerException.class, () -> SkinRef.towntopia(null));
        // ResourceLocation rejects upper case and spaces
        assertThrows(RuntimeException.class, () -> SkinRef.towntopia("Not A Key"));
    }
}
