package townkit.core.client.anim;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.junit.jupiter.api.Test;

/**
 * MoveHooks keeps per-villager state in static WeakHashMaps keyed by the villager. A value that holds its key (or any
 * entity or level) keeps the key alive forever, and with it the whole client level: the values must hold neither, and
 * the maps are emptied at logout.
 */
class MoveHooksLeakTest {
    private static Class<?> nested(String name) throws ClassNotFoundException {
        return Class.forName(MoveHooks.class.getName() + "$" + name, false, MoveHooksLeakTest.class.getClassLoader());
    }

    private static List<String> entityFields(Class<?> type) {
        List<String> out = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> t = f.getType();
            if (Entity.class.isAssignableFrom(t) || Level.class.isAssignableFrom(t) || t == Object.class) out.add(f.getName() + ": " + t.getName());
        }
        return out;
    }

    @Test
    void mapValuesHoldNoEntity() throws ClassNotFoundException {
        for (String name : List.of("Player", "Walk")) {
            List<String> bad = entityFields(nested(name));
            assertTrue(bad.isEmpty(), "MoveHooks." + name + " holds " + bad + ": its WeakHashMap entry would never go");
        }
    }

    /** With nothing wanting the hooks, refresh lets go of GeckoLib's baked-animation map along with the fallback arrays. */
    @Test
    void fallbacksForgottenWhenHooksTurnOff() throws ReflectiveOperationException {
        Field baked = MoveHooks.class.getDeclaredField("fallbacksBaked");
        Field fallbacks = MoveHooks.class.getDeclaredField("fallbacks");
        baked.setAccessible(true);
        fallbacks.setAccessible(true);
        baked.set(null, new java.util.HashMap<>());
        fallbacks.set(null, new net.minecraft.resources.ResourceLocation[0]);
        MoveHooks.refresh();
        assertNull(fallbacks.get(null), "fallbacks");
        assertNull(baked.get(null), "fallbacksBaked");
    }

    @Test
    void mapsClearedAtLogout() throws ClassNotFoundException {
        Class<?> hooks = Class.forName(MoveHooks.class.getName(), false, MoveHooksLeakTest.class.getClassLoader());
        boolean found = false;
        for (Method m : hooks.getDeclaredMethods()) {
            if (m.isAnnotationPresent(SubscribeEvent.class) && m.getParameterCount() == 1
                && m.getParameterTypes()[0] == ClientPlayerNetworkEvent.LoggingOut.class) {
                found = true;
            }
        }
        assertTrue(found, "MoveHooks has a LoggingOut listener");
    }
}
