package townkit.api.shop;

import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;

/**
 * A {@link ShopCondition}'s answer: {@link #ok()} (shown and buyable), {@link #hidden()} (not listed, not buyable) or
 * {@link #greyed(Component)} (listed greyed out with the reason as its tooltip, not buyable).
 *
 * @since 0.7.0
 */
public final class ShopCheck {
    private static final ShopCheck OK = new ShopCheck(true, true, null);
    private static final ShopCheck HIDDEN = new ShopCheck(false, false, null);

    private final boolean shown;
    private final boolean available;
    @Nullable
    private final Component reason;

    private ShopCheck(boolean shown, boolean available, @Nullable Component reason) {
        this.shown = shown;
        this.available = available;
        this.reason = reason;
    }

    public static ShopCheck ok() {
        return OK;
    }

    public static ShopCheck hidden() {
        return HIDDEN;
    }

    /** Shown greyed out, not buyable; {@code reason} is the tooltip and the refusal text. Throws NullPointerException on null. */
    public static ShopCheck greyed(Component reason) {
        return new ShopCheck(true, false, Objects.requireNonNull(reason, "reason"));
    }

    /** False for {@link #hidden()}. */
    public boolean shown() {
        return this.shown;
    }

    /** True only for {@link #ok()}. */
    public boolean available() {
        return this.available;
    }

    /** The reason of a {@link #greyed(Component)} answer; empty otherwise. */
    public Optional<Component> reason() {
        return Optional.ofNullable(this.reason);
    }

    @Override
    public String toString() {
        return this.available ? "ShopCheck[ok]" : this.shown ? "ShopCheck[greyed: " + this.reason.getString() + "]" : "ShopCheck[hidden]";
    }
}
