package townkit.core.hook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/** Checks a replacement return value fits the hooked method, so a listener mistake fails loudly at the source. */
final class ReturnTypes {
    private static final Map<String, Class<?>> BOXES = Map.of(
        "boolean", Boolean.class, "byte", Byte.class, "char", Character.class, "short", Short.class,
        "int", Integer.class, "long", Long.class, "float", Float.class, "double", Double.class);
    private static final Map<String, Class<?>> RESOLVED = new ConcurrentHashMap<>();

    private ReturnTypes() {
    }

    static void check(HookInfo info, @Nullable Object value) {
        Class<?> box = BOXES.get(info.returns());
        if (box != null) {
            if (value == null || !box.isInstance(value)) {
                throw new IllegalArgumentException(info.key() + " returns " + info.returns() + "; got " + describe(value));
            }
            return;
        }
        if (value == null) {
            return;
        }
        Class<?> type = RESOLVED.computeIfAbsent(info.returns(), ReturnTypes::resolve);
        if (type != null && !type.isInstance(value)) {
            throw new IllegalArgumentException(info.key() + " returns " + info.returns() + "; got " + describe(value));
        }
    }

    @Nullable
    private static Class<?> resolve(String name) {
        try {
            return Class.forName(name.endsWith("[]") ? toArrayName(name) : name, false, ReturnTypes.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return Object.class;
        }
    }

    private static String toArrayName(String name) {
        // "java.lang.String[]" -> "[Ljava.lang.String;" (only used for the type check)
        String base = name.substring(0, name.length() - 2);
        return "[L" + base + ";";
    }

    private static String describe(@Nullable Object v) {
        return v == null ? "null" : v.getClass().getName();
    }
}
