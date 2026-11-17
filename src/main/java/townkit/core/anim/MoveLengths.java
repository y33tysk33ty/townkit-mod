package townkit.core.anim;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import townkit.core.TownKit;

/**
 * How long a move plays once, as the server sees it: it has no client resources, so it reads the move file and the
 * animation's {@code animation_length} straight from the mod files ({@code assets/...} inside each mod jar;
 * Towntopia's own moves from Towntopia's animation file). A move it can't find (a resource-pack-only move, a file
 * without a length) counts as {@link #DEFAULT_TICKS}, logged once per move. Lengths are read once per file and kept.
 * Any thread (the maps are concurrent).
 */
public final class MoveLengths {
    /** Extra ticks a once play is kept on the server past its clip, so clients finish it before it is cleared. */
    public static final int MARGIN = 10;
    /** A once play whose length the server can't read. */
    public static final int DEFAULT_TICKS = 100;

    /** Animation lengths (seconds) by animation file, then animation name; an unreadable file maps to empty. */
    private static final Map<ResourceLocation, Map<String, Double>> FILES = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Integer> TICKS = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> WARNED = ConcurrentHashMap.newKeySet();

    private MoveLengths() {
    }

    /** How many ticks a once play of {@code move} is kept on the server (its clip's length plus {@link #MARGIN}). */
    public static int onceTicks(ResourceLocation move) {
        return TICKS.computeIfAbsent(move, MoveLengths::find);
    }

    /** Ticks for a clip of {@code seconds}, rounded up, plus {@link #MARGIN}. */
    public static int ticks(double seconds) {
        return (int) Math.ceil(seconds * 20.0 - 1e-6) + MARGIN;
    }

    private static int find(ResourceLocation move) {
        ResourceLocation file;
        String animation;
        if ("towntopia".equals(move.getNamespace())) {
            file = MoveFiles.TOWNTOPIA_FILE;
            animation = TowntopiaMoves.clip(move.getPath().toUpperCase(java.util.Locale.ROOT));
        } else {
            MoveFiles.Def def = readDef(move).orElse(null);
            file = def == null ? null : def.animationFile();
            animation = def == null ? null : def.animation();
        }
        Double seconds = file == null || animation == null ? null : FILES.computeIfAbsent(file, MoveLengths::readFile).get(animation);
        if (seconds == null || seconds <= 0) {
            if (WARNED.add(move)) {
                TownKit.LOG.warn("[TownKit] the server can't find how long move {} plays (no move or animation file in a mod jar, or no "
                    + "animation_length): a once play of it lasts {} ticks there (logged once)", move, DEFAULT_TICKS);
            }
            return DEFAULT_TICKS;
        }
        return ticks(seconds);
    }

    private static Optional<MoveFiles.Def> readDef(ResourceLocation move) {
        Path p = find("assets/" + move.getNamespace() + "/" + MoveFiles.FOLDER + "/" + move.getPath() + ".json");
        if (p == null) return Optional.empty();
        try (Reader r = Files.newBufferedReader(p)) {
            return MoveFiles.parse(move, r);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Map<String, Double> readFile(ResourceLocation file) {
        Path p = find("assets/" + file.getNamespace() + "/" + file.getPath());
        if (p == null) return Map.of();
        try (Reader r = Files.newBufferedReader(p)) {
            return read(r);
        } catch (IOException | RuntimeException e) {
            TownKit.LOG.warn("[TownKit] can't read animation file {} for move lengths ({})", file, e.toString());
            return Map.of();
        }
    }

    /** The first mod file holding {@code path} (slash-separated, from the jar root), or null. */
    @Nullable
    private static Path find(String path) {
        ModList mods = ModList.get();
        if (mods == null) return null;
        String[] parts = path.split("/");
        for (IModFileInfo info : mods.getModFiles()) {
            try {
                Path p = info.getFile().findResource(parts);
                if (p != null && Files.isRegularFile(p)) return p;
            } catch (RuntimeException e) {
                // a mod file that can't look up paths: skip it
            }
        }
        return null;
    }

    /**
     * The {@code animation_length} (seconds) of every animation in a GeckoLib animation file that gives one, by name.
     * Streams the file, skipping the keyframes (Towntopia's is about 11 MB).
     */
    public static Map<String, Double> read(Reader reader) throws IOException {
        Map<String, Double> out = new HashMap<>();
        JsonReader in = new JsonReader(reader);
        in.setLenient(true);
        in.beginObject();
        while (in.hasNext()) {
            if (!in.nextName().equals("animations") || in.peek() != JsonToken.BEGIN_OBJECT) {
                in.skipValue();
                continue;
            }
            in.beginObject();
            while (in.hasNext()) {
                String name = in.nextName();
                if (in.peek() != JsonToken.BEGIN_OBJECT) {
                    in.skipValue();
                    continue;
                }
                in.beginObject();
                while (in.hasNext()) {
                    if (in.nextName().equals("animation_length") && in.peek() == JsonToken.NUMBER) out.put(name, in.nextDouble());
                    else in.skipValue();
                }
                in.endObject();
            }
            in.endObject();
        }
        in.endObject();
        return out;
    }

    /** Forgets what was read (a world switch can bring other mods' files; for tests too). */
    public static void forget() {
        FILES.clear();
        TICKS.clear();
        WARNED.clear();
    }
}
