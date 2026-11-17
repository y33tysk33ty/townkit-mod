package townkit.core.client.skin;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import townkit.api.skin.SkinRef;

/**
 * What a villager with a per-villager skin ({@link SkinRef}) is drawn with on this client. It comes before the
 * viewer's look (an addon skin wins over every look), but a Towntopia skin ({@code towntopia:<key>}) follows the
 * viewer's look the way Towntopia's own skins do:
 * <ul>
 *   <li>with an addon look chosen: that look's file for the skin (a swap line first), else Towntopia's Modern
 *   version, as for any villager ({@link Looks#pick});</li>
 *   <li>with one of Towntopia's looks in use: a swap line to an addon look's file, else Towntopia's own pick
 *   (its look and its own swaps).</li>
 * </ul>
 * A Towntopia skin drawn from Towntopia's files also gets its rig key, so a look with its own body for that skin
 * (Rustic's merchant) draws it. An unknown addon skin, or a key Towntopia has no skin for, gives null: the villager is
 * drawn as without the override, logged once per id until the next resource reload ({@link #forget}).
 */
public final class SkinResolve {
    /**
     * How to draw the villager.
     *
     * @param texture the texture
     * @param file    the addon file drawn (texture and optional body model), or null when it is one of Towntopia's
     * @param rig     Towntopia's skin key for its look's own rig, or null
     */
    public record Drawn(ResourceLocation texture, @Nullable Looks.File file, @Nullable String rig) {
    }

    /** Towntopia's skins, as the client has them. */
    public interface Towntopia {
        /** Whether Towntopia has a skin with this key ({@code merchant}, {@code guard_2}...). */
        boolean knows(String key);

        /** Towntopia's file for the skin as its look {@code look} draws it, or, for null, as the viewer's look and swaps do. */
        @Nullable
        ResourceLocation texture(String key, @Nullable String look);

        /** {@code towntopia:<look>} for Towntopia's look in use. */
        ResourceLocation inUse();
    }

    /** The viewer's swap lines: the look a skin is swapped to while {@code inUse} is in use, or null. */
    public interface Swaps {
        @Nullable
        ResourceLocation from(ResourceLocation inUse, String skin);
    }

    private static final String TOWNTOPIA = "towntopia";

    private final Looks looks;
    private final Skins skins;
    private final Consumer<String> warn;
    private final Set<ResourceLocation> warned = ConcurrentHashMap.newKeySet();

    public SkinResolve(Looks looks, Skins skins, Consumer<String> warn) {
        this.looks = Objects.requireNonNull(looks, "looks");
        this.skins = Objects.requireNonNull(skins, "skins");
        this.warn = Objects.requireNonNull(warn, "warn");
    }

    /** How to draw a villager whose skin is {@code ref} while {@code look} (null: one of Towntopia's) is in use; null to draw it as usual. */
    @Nullable
    public Drawn resolve(SkinRef ref, @Nullable Looks.Def look, Swaps swaps, Towntopia towntopia) {
        ResourceLocation id = ref.id();
        if (!TOWNTOPIA.equals(id.getNamespace())) {
            Looks.File f = this.skins.get(id);
            if (f == null) {
                warnOnce(id, "[TownKit] no skin " + id + " on this client (assets/" + id.getNamespace() + "/" + Skins.FOLDER + "/" + id.getPath()
                    + ".json missing or refused): the villager is drawn as usual");
                return null;
            }
            return new Drawn(f.texture(), f, null);
        }
        String key = id.getPath();
        if (!towntopia.knows(key)) {
            warnOnce(id, "[TownKit] Towntopia has no skin '" + key + "': the villager is drawn as usual");
            return null;
        }
        Looks.SkinName skin = new Looks.SkinName(key, false);
        if (look != null) {
            Looks.Pick pick = this.looks.pick(look, skin, swaps.from(look.id(), key));
            if (pick instanceof Looks.File f) return new Drawn(f.texture(), f, null);
            return towntopia(key, ((Looks.Towntopia) pick).look(), towntopia);
        }
        Looks.File f = this.looks.pickWhileTowntopia(skin, swaps.from(towntopia.inUse(), key));
        if (f != null) return new Drawn(f.texture(), f, null);
        return towntopia(key, null, towntopia);
    }

    @Nullable
    private Drawn towntopia(String key, @Nullable String look, Towntopia towntopia) {
        ResourceLocation texture = towntopia.texture(key, look);
        return texture == null ? null : new Drawn(texture, null, key);
    }

    private void warnOnce(ResourceLocation id, String message) {
        if (this.warned.add(id)) this.warn.accept(message);
    }

    /** Warns again about ids already warned about (after a resource reload). */
    public void forget() {
        this.warned.clear();
    }
}
