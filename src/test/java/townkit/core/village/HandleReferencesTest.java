package townkit.core.village;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;

/** Handles are "safe to keep": they must not keep a stopped (single-player) server and its worlds in memory. */
class HandleReferencesTest {
    @Test
    void handlesDoNotHoldTheServerStrongly() {
        for (Class<?> c : new Class<?>[] {VillageHandle.class, VillagerHandle.class}) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                assertFalse(MinecraftServer.class.isAssignableFrom(f.getType()), c.getSimpleName() + "." + f.getName() + " holds the server");
            }
        }
    }
}
