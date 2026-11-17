package townkit.core.work;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;

/**
 * Towntopia's built-in entries of one kind plus the changes addons and data files make to them, merged into one cached
 * view. Order: built-ins, then Java changes in registration order, then file changes in the order given. A CHANGE edits
 * the current entry with its key (one registered before the ADD of that key still applies); a REMOVE wins over any
 * ADD or CHANGE of the same key; a second ADD of a key replaces the first. Each clash between owners logs one line
 * naming both (once per clash, not per rebuild): an ADD over an existing key, a REMOVE of a key another owner added
 * or changed, and a second owner's CHANGE of a key. Towntopia's own entries are no owner here: removing or changing
 * one is no clash.
 *
 * <p>Writers are synchronized and rebuild the view at once; readers take the published immutable state from a volatile
 * field without locking.
 */
public final class WorkTable<K, E> {
    /** Removes a registered change again. */
    public interface Handle {
        void remove();
    }

    private record State<K, E>(List<E> list, Map<K, E> byKey, boolean touched, long version, List<String[]> unknown,
            Map<K, String> owners) {
    }

    private static final String BUILT_IN = "Towntopia";

    private final String kind;
    private final Function<E, K> key;
    private final WorkLog log;
    private final List<WorkChange<K, E>> java = new ArrayList<>();
    private List<WorkChange<K, E>> files = List.of();
    private List<E> builtIns = List.of();
    private volatile State<K, E> state;

    public WorkTable(String kind, Function<E, K> key) {
        this(kind, key, WorkLog.GLOBAL);
    }

    WorkTable(String kind, Function<E, K> key, WorkLog log) {
        this.kind = kind;
        this.key = key;
        this.log = log;
        this.state = new State<>(this.builtIns, Map.of(), false, 0, List.of(), Map.of());
    }

    public String kind() {
        return this.kind;
    }

    /** Sets Towntopia's own entries (the very list is returned by {@link #merged()} while nothing is registered). */
    public synchronized void builtIns(List<E> towntopia) {
        this.builtIns = towntopia;
        rebuild();
    }

    public synchronized Handle add(String owner, E entry) {
        return register(WorkChange.add(owner, this.key.apply(entry), entry));
    }

    public synchronized Handle change(String owner, K key, UnaryOperator<E> change) {
        return register(WorkChange.change(owner, key, change));
    }

    public synchronized Handle remove(String owner, K key) {
        return register(WorkChange.remove(owner, key));
    }

    /** Replaces the changes read from data files (a reload). */
    public synchronized void setFileChanges(List<WorkChange<K, E>> fromFiles) {
        this.files = List.copyOf(fromFiles);
        rebuild();
        report();
    }

    /**
     * Logs (once each) the changes that name an entry nobody added. Not done on every registration, because an addon
     * may register its change before the addon that adds the entry; call it once everything is registered.
     */
    public void report() {
        for (String[] u : this.state.unknown) this.log.once(u[0], u[1]);
    }

    private Handle register(WorkChange<K, E> change) {
        this.java.add(change);
        rebuild();
        return () -> {
            synchronized (WorkTable.this) {
                // by identity: the same change record may be registered twice
                for (int i = 0; i < this.java.size(); i++) {
                    if (this.java.get(i) == change) {
                        this.java.remove(i);
                        rebuild();
                        return;
                    }
                }
            }
        };
    }

    /** The merged entries; immutable, the same instance until something changes. */
    public List<E> merged() {
        return this.state.list;
    }

    @Nullable
    public E get(K key) {
        return this.state.byKey.get(key);
    }

    /** Who added the merged entry with {@code key} (an addon id, a data file, or "Towntopia"), or null. */
    @Nullable
    public String owner(K key) {
        String o = this.state.owners.get(key);
        return o != null ? o : this.state.byKey.containsKey(key) ? BUILT_IN : null;
    }

    /** False while nothing is registered and no file changes exist: callers keep Towntopia's own list. */
    public boolean touched() {
        return this.state.touched;
    }

    /** Bumped on every rebuild. */
    public long version() {
        return this.state.version;
    }

    private void rebuild() {
        long version = this.state.version + 1;
        if (this.java.isEmpty() && this.files.isEmpty()) {
            Map<K, E> map = new HashMap<>();
            for (E e : this.builtIns) map.put(this.key.apply(e), e);
            this.state = new State<>(this.builtIns, Collections.unmodifiableMap(map), false, version, List.of(), Map.of());
            return;
        }
        Map<K, E> map = new LinkedHashMap<>();
        Map<K, String> owners = new HashMap<>();
        for (E e : this.builtIns) {
            K k = this.key.apply(e);
            map.put(k, e);
            owners.put(k, BUILT_IN);
        }
        List<String[]> unknown = new ArrayList<>();
        Map<K, List<WorkChange<K, E>>> pending = new LinkedHashMap<>();
        Set<K> removed = new HashSet<>();
        Map<K, String> removers = new HashMap<>();
        // addon owners (not Towntopia) that changed each key, in order: a second one is a clash, and so is a removal
        Map<K, List<String>> changers = new HashMap<>();
        for (List<WorkChange<K, E>> source : List.of(this.java, this.files)) {
            for (WorkChange<K, E> c : source) {
                switch (c.kind()) {
                    case ADD -> {
                        String before = owners.put(c.key(), c.owner());
                        if (before != null) {
                            this.log.once("clash|" + this.kind + "|" + c.key() + "|" + before + "|" + c.owner(),
                                "[" + this.kind + "] " + c.owner() + " replaces the entry '" + c.key() + "' from " + before);
                        }
                        map.put(c.key(), c.entry());
                        List<WorkChange<K, E>> waiting = pending.remove(c.key());
                        if (waiting != null) for (WorkChange<K, E> w : waiting) applyChange(map, w);
                    }
                    case CHANGE -> {
                        List<String> by = changers.computeIfAbsent(c.key(), x -> new ArrayList<>());
                        if (!by.contains(c.owner())) {
                            if (!by.isEmpty()) {
                                String before = by.get(by.size() - 1);
                                this.log.once("clash-change|" + this.kind + "|" + c.key() + "|" + before + "|" + c.owner(),
                                    "[" + this.kind + "] " + before + " and " + c.owner() + " both change '" + c.key() + "'; "
                                        + c.owner() + "'s change applies after " + before + "'s");
                            }
                            by.add(c.owner());
                        }
                        if (map.containsKey(c.key())) applyChange(map, c);
                        else pending.computeIfAbsent(c.key(), x -> new ArrayList<>()).add(c);
                    }
                    case REMOVE -> {
                        removed.add(c.key());
                        removers.putIfAbsent(c.key(), c.owner());
                    }
                }
            }
        }
        for (Map.Entry<K, List<WorkChange<K, E>>> p : pending.entrySet()) {
            if (removed.contains(p.getKey())) continue;
            for (WorkChange<K, E> c : p.getValue()) {
                unknown.add(new String[] {"unknown-change|" + this.kind + "|" + c.key() + "|" + c.owner(),
                    "[" + this.kind + "] " + c.owner() + " changes '" + c.key() + "' but there is no such entry"});
            }
        }
        for (K k : removed) {
            String remover = removers.get(k);
            // a removal hides what other owners added or changed: one line each, naming both
            String adder = owners.get(k);
            if (adder != null && !adder.equals(BUILT_IN) && !adder.equals(remover) && map.containsKey(k)) {
                this.log.once("clash-remove|" + this.kind + "|" + k + "|" + adder + "|" + remover, "[" + this.kind + "] " + remover
                    + " removes '" + k + "', which " + adder + " adds; the removal wins");
            }
            for (String changer : changers.getOrDefault(k, List.of())) {
                if (changer.equals(remover) || changer.equals(adder)) continue;
                this.log.once("clash-remove-change|" + this.kind + "|" + k + "|" + changer + "|" + remover, "[" + this.kind + "] "
                    + remover + " removes '" + k + "', which " + changer + " changes; the removal wins");
            }
            if (map.remove(k) == null) {
                unknown.add(new String[] {"unknown-remove|" + this.kind + "|" + k + "|" + removers.get(k),
                    "[" + this.kind + "] " + removers.get(k) + " removes '" + k + "' but there is no such entry"});
            }
        }
        owners.keySet().retainAll(map.keySet());
        this.state = new State<>(List.copyOf(map.values()), Collections.unmodifiableMap(map), true, version, unknown,
            Collections.unmodifiableMap(owners));
    }

    private void applyChange(Map<K, E> map, WorkChange<K, E> c) {
        E current = map.get(c.key());
        try {
            E next = c.change().apply(current);
            if (next == null) {
                this.log.once("null-change|" + this.kind + "|" + c.key() + "|" + c.owner(),
                    "[" + this.kind + "] " + c.owner() + " changed '" + c.key() + "' to nothing; change skipped");
                return;
            }
            map.put(c.key(), next);
        } catch (RuntimeException ex) {
            this.log.once("bad-change|" + this.kind + "|" + c.key() + "|" + c.owner(),
                "[" + this.kind + "] " + c.owner() + " failed to change '" + c.key() + "': " + ex + "; change skipped");
        }
    }
}
