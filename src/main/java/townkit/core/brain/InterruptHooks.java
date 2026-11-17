package townkit.core.brain;

import com.alex.test.village.brain.Activity;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import townkit.api.Subscription;
import townkit.api.brain.ChoicePhase;
import townkit.api.brain.InterruptProvider;
import townkit.core.TownKit;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;

/**
 * Asks addon interrupt providers after Towntopia's own {@code checkOverrides} found nothing, on each villager's
 * staggered slot. Attached with the first provider, removed with the last. Server thread only.
 */
public final class InterruptHooks {
    static final String OVERRIDES = "village.brain.VillagerBrain#checkOverrides(Activity.Context)";
    public static final int MIN_INTERVAL = 10;

    private static final List<ProviderEntry<InterruptProvider>> PROVIDERS = new CopyOnWriteArrayList<>();
    @Nullable
    private static Hooks.Entry attached;
    /** Towntopia's LeisureActivity, found fail-soft; when missing, interrupts only replace an idle villager. */
    @Nullable
    private static Class<?> leisure;
    private static boolean leisureLooked;

    private InterruptHooks() {
    }

    public static synchronized Subscription add(String owner, InterruptProvider provider, int intervalTicks) {
        if (intervalTicks < MIN_INTERVAL) throw new IllegalArgumentException("Interrupt interval must be at least " + MIN_INTERVAL + " ticks, was " + intervalTicks);
        ProviderEntry<InterruptProvider>[] self = new ProviderEntry[1];
        self[0] = new ProviderEntry<>(owner, provider, intervalTicks, () -> remove(self[0]));
        PROVIDERS.add(self[0]);
        attach();
        return self[0];
    }

    private static synchronized void remove(ProviderEntry<InterruptProvider> entry) {
        PROVIDERS.remove(entry);
        if (PROVIDERS.isEmpty() && attached != null) {
            Hooks.remove(attached);
            attached = null;
        }
    }

    /** What the villager is doing, as far as interrupts (and TownKit's hold-still for moves) care. */
    public static InterruptGate.Kind kindOf(@Nullable Activity current) {
        if (current == null) return InterruptGate.Kind.NONE;
        if (ActivityRegistry.addonId(current) != null) return InterruptGate.Kind.ADDON;
        Class<?> l = leisure();
        return l != null && l.isInstance(current) ? InterruptGate.Kind.LEISURE : InterruptGate.Kind.OTHER;
    }

    @Nullable
    private static Class<?> leisure() {
        if (!leisureLooked) {
            leisureLooked = true;
            try {
                leisure = Class.forName("com.alex.test.village.brain.LeisureActivity", false, Activity.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                TownKit.LOG.warn("[TownKit] can't find Towntopia's LeisureActivity: addon interrupts (and holding still for moves) only replace "
                    + "idle villagers");
            }
        }
        return leisure;
    }

    private static void attach() {
        if (attached != null) return;
        leisure();
        HookInfo h = HookManifest.get().byKey.get(OVERRIDES);
        if (h == null) {
            TownKit.LOG.warn("[TownKit] can't find {}: addon interrupts are unavailable", OVERRIDES);
            return;
        }
        attached = Hooks.add(h, "townkit", call -> {
            if (call.returnValue() != null) return; // Towntopia's own interrupt wins
            Activity.Context ctx = (Activity.Context) call.args()[0];
            long now = ctx.level().getGameTime();
            Activity current = ctx.brain().currentActivity();
            ResourceLocation currentAddon = ActivityRegistry.addonId(current);
            InterruptGate.Kind kind = kindOf(current);
            if (kind == InterruptGate.Kind.OTHER || ctx.model().isEscapingWater()) return;
            CoreChoiceContext cc = null;
            for (ProviderEntry<InterruptProvider> e : PROVIDERS) {
                if (e.off || !Stagger.due(ctx.model().npcId, now, e.interval)) continue;
                ResourceLocation id = e.get(InterruptProvider::activity); // isolated: a bad provider fails alone
                if (id == null || !InterruptGate.allows(kind, ActivityRegistry.allowsRoutineInterruption(current), id.equals(currentAddon), false)) continue;
                if (cc == null) cc = new CoreChoiceContext(ctx, ChoicePhase.INTERRUPT, 0F, 0F, 0F, 0F, null);
                CoreChoiceContext asked = cc;
                if (e.ask(p -> p.wants(asked) ? 1D : 0D, 0D) > 0.5D) {
                    Activity a = ActivityRegistry.create(id, ctx.model());
                    if (a != null) {
                        call.setReturnValue(a);
                        return;
                    }
                }
            }
        }, true);
    }
}
