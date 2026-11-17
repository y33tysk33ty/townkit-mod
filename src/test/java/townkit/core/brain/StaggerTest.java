package townkit.core.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StaggerTest {
    @Test
    void dueOncePerInterval() {
        UUID id = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        int due = 0;
        for (long t = 1000; t < 1100; t++) if (Stagger.due(id, t, 20)) due++;
        assertEquals(5, due);
    }

    @Test
    void spreadsVillagers() {
        Random r = new Random(42);
        int due = 0;
        for (int i = 0; i < 100; i++) if (Stagger.due(new UUID(r.nextLong(), r.nextLong()), 5000L, 20)) due++;
        assertTrue(due >= 1 && due <= 20, "due " + due);
    }
}
