package townkit.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FieldReaderTest {
    @SuppressWarnings("unused")
    private static final class Owner {
        private final String model = "the model";
    }

    @Test
    void readsAPrivateField() {
        FieldReader<String> r = FieldReader.of(Owner.class, "model", String.class);
        assertTrue(r.available());
        assertEquals("the model", r.read(new Owner()));
    }

    /** A Towntopia update that renames the field must not crash: the reader just reports unavailable. */
    @Test
    void missingFieldIsUnavailableNotACrash() {
        assertFalse(FieldReader.of(Owner.class, "renamed", String.class).available());
        assertFalse(FieldReader.of(Owner.class, "model", Integer.class).available());
    }
}
