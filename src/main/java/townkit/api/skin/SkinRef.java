package townkit.api.skin;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Names a villager skin: one of Towntopia's own skins by its key ({@link #towntopia(String)}, e.g. {@code "merchant"},
 * which follows each viewer's chosen look the way Towntopia's own skins do), or an addon skin by its id
 * ({@link #of(ResourceLocation)}).
 *
 * <p>Throws NullPointerException on a null id or key; {@link #towntopia(String)} throws
 * {@link net.minecraft.ResourceLocationException} on a key that isn't a valid resource path.
 *
 * @since 0.7.0
 */
public record SkinRef(ResourceLocation id) {
    /** The namespace of Towntopia's own skins. */
    private static final String TOWNTOPIA = "towntopia";

    public SkinRef {
        Objects.requireNonNull(id, "id");
    }

    /** Towntopia's own skin with this key (id {@code towntopia:<key>}). */
    public static SkinRef towntopia(String key) {
        Objects.requireNonNull(key, "key");
        return new SkinRef(ResourceLocation.fromNamespaceAndPath(TOWNTOPIA, key));
    }

    /** The skin with this id. */
    public static SkinRef of(ResourceLocation id) {
        return new SkinRef(id);
    }
}
