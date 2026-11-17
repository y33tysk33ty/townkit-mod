package townkit.api.hooks;

import org.jetbrains.annotations.ApiStatus;
import javax.annotation.Nullable;

/**
 * One call of a hooked Towntopia method, as seen by a listener.
 * <ul>
 *   <li>{@link #self()} is the Towntopia object the method runs on (null for static methods).</li>
 *   <li>{@link #args()} are the method's arguments, primitives boxed. Changing the array does not change the call.</li>
 *   <li>Before-listeners may {@link #cancel()} (void methods) or {@link #cancelWith(Object)} (skip the method and return a value).</li>
 *   <li>After-listeners may read and {@link #setReturnValue(Object) replace} the return value.</li>
 * </ul>
 * After-listeners don't run when the method was cancelled or threw.
 * Experimental: tied to Towntopia's own names; may break on any Towntopia update. Prefer the stable API in townkit.api.
 */
@ApiStatus.Experimental
public interface HookCall {
    HookPoint hook();

    String key();

    @Nullable
    Object self();

    Object[] args();

    @SuppressWarnings("unchecked")
    default <T> T self(Class<T> type) {
        return type.cast(this.self());
    }

    @SuppressWarnings("unchecked")
    default <T> T arg(int index, Class<T> type) {
        return type.cast(this.args()[index]);
    }

    boolean isAfter();

    /** Before-listener only, void methods only: skip the method. */
    void cancel();

    /** Before-listener only, non-void methods only: skip the method and return this value instead. */
    void cancelWith(@Nullable Object returnValue);

    /** After-listener only: the value the method is returning (boxed for primitives; null for void). */
    @Nullable
    Object returnValue();

    /** After-listener only: replace the value the method returns. Must match the method's return type. */
    void setReturnValue(@Nullable Object value);
}
