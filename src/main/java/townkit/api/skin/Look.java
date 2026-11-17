package townkit.api.skin;

import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * An addon look: a whole set of villager skins that a player can pick on the Theme page of the Mayor screen, next to
 * Towntopia's own looks. A look comes from a file, {@code assets/<ns>/townkit/looks/<name>.json} for the id
 * {@code <ns>:<name>}, in an addon's resources or a plain resource pack (see {@link TownKitSkins}).
 *
 * <p>Throws NullPointerException on a null id or name.
 *
 * @param id   the look's id, {@code <ns>:<name>} from its file's place
 * @param name the name shown on the Theme page (the file's {@code "name"}, a translation key or plain text)
 * @since 0.7.0
 */
public record Look(ResourceLocation id, Component name) {
    public Look {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
