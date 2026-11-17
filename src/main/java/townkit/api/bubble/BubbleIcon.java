package townkit.api.bubble;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import townkit.api.spi.TownKitBackend;

/**
 * An icon a villager's bubble can show: one of Towntopia's own ({@link #towntopia(String)}, id
 * {@code towntopia:<id>}) or an addon icon registered with {@link TownKitBubbles#icon}.
 *
 * <p>An addon icon's picture comes from a file in the addon's (or a resource pack's) assets,
 * {@code assets/<namespace>/townkit/bubbles/<name>.json} for the id {@code <namespace>:<name>}:
 * <pre>{@code {"texture": "<namespace>:textures/townkit/bubbles/<name>.png", "valence": "positive|neutral|negative"}}</pre>
 * A client without the file (or its picture) never crashes: it leaves the bubble to Towntopia, which shows none for an
 * addon icon, and logs the id once.
 *
 * @param id      the icon's id
 * @param valence how it feels (sets the pitch of the villager's "huh" when it pops up)
 * @since 0.7.0
 */
public record BubbleIcon(ResourceLocation id, Valence valence) {
    public BubbleIcon {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(valence, "valence");
    }

    /**
     * Towntopia's own icon with this id, such as {@code "status_hungry"} or {@code "emote_love"}, with Towntopia's
     * valence for it. Throws IllegalArgumentException if Towntopia has no such icon.
     */
    public static BubbleIcon towntopia(String id) {
        Objects.requireNonNull(id, "id");
        return TownKitBackend.get().bubbles().towntopia(id);
    }
}
