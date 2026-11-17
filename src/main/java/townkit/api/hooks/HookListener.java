package townkit.api.hooks;

import org.jetbrains.annotations.ApiStatus;

/**
 * Called when a hooked Towntopia method runs. Keep it fast: it runs inside Towntopia's own tick.
 * Exceptions are caught and logged; ten failures in a row switch the listener off.
 * Hooks marked with a thread (see {@link HookPoint#thread()}) run off the server thread.
 * Experimental: tied to Towntopia's own names; may break on any Towntopia update. Prefer the stable API in townkit.api.
 */
@ApiStatus.Experimental
@FunctionalInterface
public interface HookListener {
    void handle(HookCall call);
}
