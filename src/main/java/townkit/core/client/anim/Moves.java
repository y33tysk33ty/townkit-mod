package townkit.core.client.anim;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import townkit.api.anim.TownKitAnimations.ClientMoveListener;
import townkit.core.TownKit;
import townkit.core.anim.MoveFiles;

/**
 * The client's addon moves (read with {@link MoveFiles}) and how a move id turns into a GeckoLib clip to play.
 * {@link #CLIENT} is filled at each client resource reload ({@link MoveHooks}).
 */
public final class Moves {
    /** The client's moves, from its resources. */
    public static final Moves CLIENT = new Moves();

    /** A clip to play: an animation in an animation file. */
    public record Clip(ResourceLocation file, String animation) {
    }

    private volatile Map<ResourceLocation, MoveFiles.Def> defs = Map.of();
    private final Set<ResourceLocation> warned = ConcurrentHashMap.newKeySet();
    private final Set<ResourceLocation> warnedFiles = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedListeners = ConcurrentHashMap.newKeySet();

    /** A client move listener ({@code TownKitAnimations.onClientMove}). */
    public record Listener(long serial, String owner, ClientMoveListener listener) {
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private long nextSerial = 1;

    /** Takes the moves read at a resource reload; a move that failed before is warned about again. */
    public void replace(Map<ResourceLocation, MoveFiles.Def> read) {
        defs = Map.copyOf(read);
        warned.clear();
        warnedFiles.clear();
    }

    @Nullable
    public MoveFiles.Def get(ResourceLocation id) {
        return defs.get(id);
    }

    /** Whether some move file names props. */
    public boolean anyProps() {
        for (MoveFiles.Def d : defs.values()) {
            if (!d.props().isEmpty()) return true;
        }
        return false;
    }

    /** The props of an addon move's file (empty for none, and for Towntopia's moves). */
    public List<ResourceLocation> fileProps(ResourceLocation move) {
        MoveFiles.Def d = defs.get(move);
        return d == null ? List.of() : d.props();
    }

    /** Adds a client move listener; returns a serial for {@link #removeListener}. */
    public synchronized long addListener(String owner, ClientMoveListener listener) {
        long serial = nextSerial++;
        listeners.add(new Listener(serial, owner, listener));
        return serial;
    }

    public boolean removeListener(long serial) {
        return listeners.removeIf(l -> l.serial() == serial);
    }

    public boolean hasListeners() {
        return !listeners.isEmpty();
    }

    /** The listeners, in the order registered. */
    public List<Listener> listeners() {
        return listeners;
    }

    /** How many owners' client move listeners threw (for tests). */
    public int listenerWarnings() {
        return warnedListeners.size();
    }

    /** Logs once per owner a client move listener that threw; true the first time. */
    public boolean warnListener(String owner, Throwable t) {
        if (!warnedListeners.add(owner)) return false;
        TownKit.LOG.warn("[TownKit] a client move listener from '{}' threw: skipped (logged once per owner)", owner, t);
        return true;
    }

    /** Every addon animation file a move uses. */
    public Set<ResourceLocation> animationFiles() {
        Set<ResourceLocation> out = new HashSet<>();
        for (MoveFiles.Def d : defs.values()) out.add(d.animationFile());
        return out;
    }

    /**
     * The clip {@code move} plays, or null when this client can't play it (unknown move, its animation file isn't
     * loaded or lacks the animation): Towntopia's animation plays instead, logged once per move.
     *
     * @param towntopiaClip the clip Towntopia uses for a state name ({@code "SALUTE"} -> {@code "salute"}), or null
     * @param clipExists    whether an animation file holds a clip of that name
     */
    @Nullable
    public Clip resolve(ResourceLocation move, Function<String, String> towntopiaClip, BiPredicate<ResourceLocation, String> clipExists) {
        Clip clip;
        String why;
        if ("towntopia".equals(move.getNamespace())) {
            String name = towntopiaClip.apply(move.getPath().toUpperCase(java.util.Locale.ROOT));
            clip = name == null ? null : new Clip(MoveFiles.TOWNTOPIA_FILE, name);
            why = "Towntopia has no clip for it";
        } else {
            MoveFiles.Def d = defs.get(move);
            clip = d == null ? null : new Clip(d.animationFile(), d.animation());
            why = "no move file for it";
        }
        if (clip != null && !clipExists.test(clip.file(), clip.animation())) {
            why = "animation '" + clip.animation() + "' isn't in " + clip.file() + " (missing file, or no such animation)";
            clip = null;
        }
        if (clip == null && warned.add(move)) {
            TownKit.LOG.warn("[TownKit] move {} can't be played on this client ({}): Towntopia's animation plays instead", move, why);
        }
        return clip;
    }

    /** How many moves and animation files this client warned about (for tests). */
    public int warnings() {
        return warned.size() + warnedFiles.size();
    }
    /**
     * GeckoLib's fallback animation files for a villager: Towntopia's ({@code from}, kept first) plus each of
     * {@code files} GeckoLib has loaded. A file that isn't loaded (missing, or GeckoLib couldn't read it) is left out,
     * logged once per file: GeckoLib throws instead of logging when the last fallback file isn't loaded and a clip name
     * is in no file, so a missing addon file must never be listed.
     */
    public ResourceLocation[] fallbacks(ResourceLocation[] from, Collection<ResourceLocation> files, Predicate<ResourceLocation> loaded) {
        Set<ResourceLocation> all = new LinkedHashSet<>(Arrays.asList(from));
        for (ResourceLocation f : files) {
            if (all.contains(f)) continue;
            if (loaded.test(f)) {
                all.add(f);
            } else if (warnedFiles.add(f)) {
                TownKit.LOG.warn("[TownKit] animation file {} (named in a move file) isn't loaded: the moves using it play Towntopia's "
                    + "animation instead", f);
            }
        }
        return all.size() == from.length ? from : all.toArray(new ResourceLocation[0]);
    }
}
