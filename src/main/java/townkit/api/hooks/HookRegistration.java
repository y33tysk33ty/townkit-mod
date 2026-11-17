package townkit.api.hooks;

import org.jetbrains.annotations.ApiStatus;
import townkit.api.Subscription;

/**
 * Handle returned when a listener is added; {@link #remove()} detaches it (and the hook goes back to costing nothing).
 * Experimental: tied to Towntopia's own names; may break on any Towntopia update. Prefer the stable API in townkit.api.
 */
@ApiStatus.Experimental
public interface HookRegistration extends Subscription {
    long calls();

    long failures();
}
