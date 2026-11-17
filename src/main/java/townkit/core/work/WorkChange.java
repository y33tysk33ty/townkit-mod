package townkit.core.work;

import java.util.function.UnaryOperator;
import javax.annotation.Nullable;

/**
 * One addon or data-file edit of a work table: add an entry, change the entry with a key, or remove it.
 *
 * @param owner  who made it (an addon id, or a data file), named in clash logs
 * @param entry  the new entry, for {@link Kind#ADD}
 * @param change the edit, for {@link Kind#CHANGE}
 */
public record WorkChange<K, E>(String owner, Kind kind, K key, @Nullable E entry, @Nullable UnaryOperator<E> change) {
    public enum Kind { ADD, CHANGE, REMOVE }

    public static <K, E> WorkChange<K, E> add(String owner, K key, E entry) {
        return new WorkChange<>(owner, Kind.ADD, key, entry, null);
    }

    public static <K, E> WorkChange<K, E> change(String owner, K key, UnaryOperator<E> change) {
        return new WorkChange<>(owner, Kind.CHANGE, key, null, change);
    }

    public static <K, E> WorkChange<K, E> remove(String owner, K key) {
        return new WorkChange<>(owner, Kind.REMOVE, key, null, null);
    }
}
