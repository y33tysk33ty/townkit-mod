package townkit.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import javax.annotation.Nullable;

/**
 * Reads one private Towntopia field, failing soft: if a Towntopia update renames or retypes the field, the reader is
 * unavailable (and so is the event that needs it) instead of crashing the game the way a required mixin accessor would.
 */
public final class FieldReader<T> {
    @Nullable
    private final VarHandle handle;

    private FieldReader(@Nullable VarHandle handle) {
        this.handle = handle;
    }

    public static <T> FieldReader<T> of(Class<?> owner, String name, Class<T> type) {
        try {
            return new FieldReader<>(MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findVarHandle(owner, name, type));
        } catch (ReflectiveOperationException | RuntimeException e) {
            TownKit.LOG.warn("[TownKit] can't read {}.{} ({}): events that need it are unavailable", owner.getName(), name, e.toString());
            return new FieldReader<>(null);
        }
    }

    /** For a class addons can't name (package-private in Towntopia), loaded through {@code neighbour}'s class loader. */
    public static <T> FieldReader<T> of(Class<?> neighbour, String ownerName, String name, Class<T> type) {
        try {
            return of(Class.forName(ownerName, false, neighbour.getClassLoader()), name, type);
        } catch (ClassNotFoundException | LinkageError e) {
            TownKit.LOG.warn("[TownKit] can't find {} ({}): events that need it are unavailable", ownerName, e.toString());
            return new FieldReader<>(null);
        }
    }

    public boolean available() {
        return this.handle != null;
    }

    @SuppressWarnings("unchecked")
    public T read(Object owner) {
        if (this.handle == null) throw new IllegalStateException("field reader unavailable");
        return (T) this.handle.get(owner);
    }
}
