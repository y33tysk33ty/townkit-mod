package townkit.api.hooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.ApiStatus;
import townkit.api.spi.TownKitBackend;

/**
 * Entry point for hooking Towntopia. Every non-trivial Towntopia method has a hook, keyed as
 * {@code <class relative to com.alex.test>#<method>(<simple param types>)}, for example
 * {@code village.brain.VillagerBrain#decideNext(Activity.Context,float,float,float,float)}.
 * The full list is in TownKit's dev-docs/hooks.md and in-game via {@code /townkit hooks search}.
 *
 * <pre>{@code
 * TownKitHooks.after("myaddon", "village.brain.VillagerBrain#decideNext(Activity.Context,float,float,float,float)", call -> {
 *     if (call.returnValue() instanceof LeisureActivity) call.setReturnValue(new MyActivity());
 * });
 * }</pre>
 * Unused hooks cost one array read per call. Listeners cost what they do: keep them quick.
 * Experimental: tied to Towntopia's own names; may break on any Towntopia update. Prefer the stable API in townkit.api.
 */
@ApiStatus.Experimental
public final class TownKitHooks {
    private TownKitHooks() {
    }

    /** Run {@code listener} before the method's body. The listener may cancel the call. */
    public static HookRegistration before(String owner, String key, HookListener listener) {
        HookPoint h = require(key);
        if (h.ctor()) {
            throw new IllegalArgumentException(key + " is a constructor: it only has an after side (use TownKitHooks.after)");
        }
        return TownKitBackend.get().addHook(owner, key, listener, false);
    }

    /** Run {@code listener} after the method returns normally. The listener may replace the return value. */
    public static HookRegistration after(String owner, String key, HookListener listener) {
        require(key);
        return TownKitBackend.get().addHook(owner, key, listener, true);
    }

    public static Optional<HookPoint> find(String key) {
        return TownKitBackend.get().findHook(key);
    }

    /** Keys containing all the given words (case-insensitive), at most {@code limit}. */
    public static List<HookPoint> search(String text, int limit) {
        String[] words = text.toLowerCase(Locale.ROOT).split("\\s+");
        List<HookPoint> out = new ArrayList<>();
        for (HookPoint h : TownKitBackend.get().allHooks()) {
            String k = h.key().toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) {
                if (!k.contains(w)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                out.add(h);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    /** True once the hook's class has been loaded with TownKit's injections in place (see /townkit hooks verify). */
    public static boolean isLive(String key) {
        return TownKitBackend.get().isHookLive(key);
    }

    public static int count() {
        return TownKitBackend.get().hookCount();
    }

    private static HookPoint require(String key) {
        return find(key).orElseThrow(() -> new IllegalArgumentException("No TownKit hook '" + key
            + "'. Try /townkit hooks search, or see TownKit's dev-docs/hooks.md. If Towntopia updated, the method may have moved."));
    }
}
