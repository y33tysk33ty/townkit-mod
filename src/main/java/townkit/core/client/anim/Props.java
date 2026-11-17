package townkit.core.client.anim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import townkit.core.TownKit;

/**
 * Props: 3D pieces drawn on a villager's bone while a move plays. Prop files
 * ({@code assets/<ns>/townkit/props/<name>.json} -> prop {@code <ns>:<name>}, read at each client resource reload by
 * {@link PropLayer}) and the props addons give moves in code ({@code TownKitAnimations.withProps}). Touches no client
 * class, so the API can register props on either side (a dedicated server keeps them unused). Thread-safe.
 */
public final class Props {
    /** The client's props and the props registered for moves. */
    public static final Props CLIENT = new Props();
    /** Where prop files live, under {@code assets/<ns>/}. */
    public static final String FOLDER = "townkit/props";

    /**
     * One prop file.
     *
     * @param geo     the prop's GeckoLib geo model (every cube of it is drawn)
     * @param texture its texture
     * @param bone    the villager bone it is drawn on (e.g. {@code bone2}, the right forearm of every Towntopia rig)
     */
    public record Def(ResourceLocation id, ResourceLocation geo, ResourceLocation texture, String bone) {
    }

    private record Registered(long serial, String owner, ResourceLocation move, List<ResourceLocation> props) {
    }

    private volatile Map<ResourceLocation, Def> defs = Map.of();
    private final List<Registered> registered = new CopyOnWriteArrayList<>();
    private long nextSerial = 1;
    /** The models each prop was warned about (no key built per frame: this runs for every undrawable prop drawn). */
    private final Map<ResourceLocation, Set<ResourceLocation>> warned = new ConcurrentHashMap<>();

    /** The prop id of a prop file found at {@code file} ({@code <ns>:townkit/props/<name>.json}). */
    public static ResourceLocation idOf(ResourceLocation file) {
        String path = file.getPath();
        return ResourceLocation.fromNamespaceAndPath(file.getNamespace(), path.substring(FOLDER.length() + 1, path.length() - ".json".length()));
    }

    /** Reads a prop file; empty (logged) when it lacks {@code geo}, {@code texture} or {@code bone}, or isn't valid. */
    public static Optional<Def> parse(ResourceLocation id, Reader reader) {
        try {
            JsonObject o = JsonParser.parseReader(reader).getAsJsonObject();
            ResourceLocation geo = o.has("geo") ? ResourceLocation.tryParse(o.get("geo").getAsString()) : null;
            ResourceLocation texture = o.has("texture") ? ResourceLocation.tryParse(o.get("texture").getAsString()) : null;
            String bone = o.has("bone") ? o.get("bone").getAsString() : null;
            if (geo == null || texture == null || bone == null || bone.isBlank()) {
                TownKit.LOG.warn("[TownKit] prop {} ignored: its file needs \"geo\" and \"texture\" (resource ids) and \"bone\"", id);
                return Optional.empty();
            }
            return Optional.of(new Def(id, geo, texture, bone));
        } catch (RuntimeException e) {
            TownKit.LOG.warn("[TownKit] prop {} ignored: can't read its file ({})", id, e.toString());
            return Optional.empty();
        }
    }

    /** Takes the props read at a resource reload; a prop that failed before is warned about again. */
    public void replace(Map<ResourceLocation, Def> read) {
        defs = Map.copyOf(read);
        warned.clear();
    }

    /** The prop file read for {@code id}, or null. */
    @Nullable
    public Def get(ResourceLocation id) {
        return defs.get(id);
    }

    /** Whether some prop file was read. */
    public boolean anyFiles() {
        return !defs.isEmpty();
    }

    /** Gives {@code move} these props (on top of its file's); returns a serial for {@link #remove}. */
    public synchronized long addFor(String owner, ResourceLocation move, List<ResourceLocation> props) {
        long serial = nextSerial++;
        registered.add(new Registered(serial, owner, move, List.copyOf(props)));
        return serial;
    }

    public boolean remove(long serial) {
        return registered.removeIf(r -> r.serial() == serial);
    }

    public boolean anyRegistered() {
        return !registered.isEmpty();
    }

    /** Whether a prop is registered for a move of {@code namespace} (Towntopia's own moves: {@code "towntopia"}). */
    public boolean anyRegisteredIn(String namespace) {
        for (Registered r : registered) {
            if (r.move().getNamespace().equals(namespace)) return true;
        }
        return false;
    }

    /** The props drawn for {@code move}: its file's ({@code fileProps}) first, then the registered ones, each once. */
    public List<ResourceLocation> propsOf(ResourceLocation move, List<ResourceLocation> fileProps) {
        if (registered.isEmpty()) return fileProps;
        Set<ResourceLocation> out = new LinkedHashSet<>(fileProps);
        for (Registered r : registered) {
            if (r.move().equals(move)) out.addAll(r.props());
        }
        return out.size() == fileProps.size() ? fileProps : new ArrayList<>(out);
    }

    /**
     * The prop to draw on a villager drawn with {@code model}, or null when it can't be drawn: no prop file, its geo
     * isn't loaded, its texture is missing, or the model lacks its bone. Each of those is logged once per (prop, model).
     */
    @Nullable
    public Def drawable(ResourceLocation prop, ResourceLocation model, Predicate<String> hasBone, Predicate<ResourceLocation> geoLoaded,
                        Predicate<ResourceLocation> textureExists) {
        Def d = defs.get(prop);
        String why;
        if (d == null) why = "no prop file for it";
        else if (!geoLoaded.test(d.geo())) why = "its model " + d.geo() + " isn't loaded";
        else if (!textureExists.test(d.texture())) why = "its texture " + d.texture() + " is missing";
        else if (!hasBone.test(d.bone())) why = "the villager model has no bone '" + d.bone() + "'";
        else return d;
        if (warned.computeIfAbsent(prop, k -> ConcurrentHashMap.newKeySet()).add(model)) {
            TownKit.LOG.warn("[TownKit] prop {} isn't drawn on villagers with model {}: {}", prop, model, why);
        }
        return null;
    }

    /** How many (prop, model) pairs were warned about (for tests). */
    public int warnings() {
        int n = 0;
        for (Set<ResourceLocation> models : warned.values()) n += models.size();
        return n;
    }
}
