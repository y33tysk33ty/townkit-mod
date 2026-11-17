package townkit.core.hook;

import javax.annotation.Nullable;
import townkit.api.hooks.HookCall;
import townkit.api.hooks.HookPoint;

/** One live call of a hooked method, handed to listeners. Only created when a listener exists. */
final class HookCallImpl implements HookCall {
    private final HookInfo info;
    @Nullable
    private final Object self;
    private final Object[] args;
    private final boolean after;
    private boolean cancelled;
    /** Before: the value to return instead (when cancelled). After: the value the method returns. */
    @Nullable
    private Object value;

    HookCallImpl(HookInfo info, @Nullable Object self, Object[] args, boolean after, @Nullable Object returning) {
        this.info = info;
        this.self = self;
        this.args = args;
        this.after = after;
        this.value = returning;
    }

    @Override
    public HookPoint hook() {
        return Hooks.point(this.info);
    }

    @Override
    public String key() {
        return this.info.key();
    }

    @Override
    @Nullable
    public Object self() {
        return this.self;
    }

    @Override
    public Object[] args() {
        return this.args;
    }

    @Override
    public boolean isAfter() {
        return this.after;
    }

    @Override
    public void cancel() {
        if (this.after) {
            throw new IllegalStateException("cancel() only works in a before-listener (" + this.info.key() + ")");
        }
        if (!this.info.returnsVoid()) {
            throw new IllegalStateException(this.info.key() + " returns " + this.info.returns() + ": use cancelWith(value)");
        }
        this.cancelled = true;
        this.value = null;
    }

    @Override
    public void cancelWith(@Nullable Object returnValue) {
        if (this.after) {
            throw new IllegalStateException("cancelWith() only works in a before-listener; use setReturnValue in an after-listener");
        }
        if (this.info.returnsVoid()) {
            throw new IllegalStateException(this.info.key() + " returns void: use cancel()");
        }
        ReturnTypes.check(this.info, returnValue);
        this.cancelled = true;
        this.value = returnValue;
    }

    @Override
    @Nullable
    public Object returnValue() {
        if (!this.after) {
            throw new IllegalStateException("the return value only exists in an after-listener");
        }
        return this.value;
    }

    @Override
    public void setReturnValue(@Nullable Object value) {
        if (!this.after) {
            throw new IllegalStateException("setReturnValue() only works in an after-listener; use cancelWith in a before-listener");
        }
        if (this.info.returnsVoid()) {
            throw new IllegalStateException(this.info.key() + " returns void");
        }
        ReturnTypes.check(this.info, value);
        this.value = value;
    }

    boolean cancelled() {
        return this.cancelled;
    }

    @Nullable
    Object value() {
        return this.value;
    }
}
