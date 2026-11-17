package townkit.api.hooks;

import javax.annotation.Nullable;
import org.jetbrains.annotations.ApiStatus;

/**
 * One generated hook into a Towntopia method, as addons see it.
 * Experimental: tied to Towntopia's own names; may break on any Towntopia update. Prefer the stable API in townkit.api.
 *
 * @param key      {@code <class relative to com.alex.test>#<method>(<simple param types>)}
 * @param returns  return type name: "void", "int", "com.alex.test....", "java.lang.String[]"
 * @param isStatic a static method ({@code self} is null)
 * @param ctor     a constructor: only has an after side
 * @param client   client-only code: never loaded on a dedicated server
 * @param thread   null for the server thread; otherwise e.g. "worker" (listeners must be thread-safe)
 */
@ApiStatus.Experimental
public record HookPoint(String key, String returns, boolean isStatic, boolean ctor, boolean client, @Nullable String thread) {
}
