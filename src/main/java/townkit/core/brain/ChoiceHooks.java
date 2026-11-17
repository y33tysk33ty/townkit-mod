package townkit.core.brain;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.EveningPlanner;
import com.alex.test.village.brain.VillagerBrain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import townkit.api.Subscription;
import townkit.api.brain.ChoicePhase;
import townkit.api.brain.ChoiceProvider;
import townkit.api.brain.EveningStep;
import townkit.api.hooks.HookListener;
import townkit.core.TownKit;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;

/**
 * Puts addon choices into Towntopia's decisions: the daytime choice ({@code decideNext} with its four scores) and the
 * evening plan's leisure steps. Attached with the first provider, removed with the last. Server thread only.
 */
public final class ChoiceHooks {
    static final String DAY = "village.brain.VillagerBrain#decideNext(Activity.Context,float,float,float,float)";
    static final String EVENING = "village.brain.VillagerBrain#eveningOverride(Activity.Context)";
    static final String EVENING_DECIDE = "village.brain.EveningPlanner#decide(EveningPlanner.Facts)";

    private static final List<ProviderEntry<ChoiceProvider>> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final List<Hooks.Entry> ATTACHED = new ArrayList<>();
    /** What the evening plan decided inside the eveningOverride call now running (server thread). */
    @Nullable
    private static String eveningDecision;
    @Nullable
    private static String eveningPhase;

    private ChoiceHooks() {
    }

    public static synchronized Subscription add(String owner, ChoiceProvider provider) {
        ProviderEntry<ChoiceProvider>[] self = new ProviderEntry[1];
        self[0] = new ProviderEntry<>(owner, provider, 0, () -> remove(self[0]));
        PROVIDERS.add(self[0]);
        attach();
        return self[0];
    }

    private static synchronized void remove(ProviderEntry<ChoiceProvider> entry) {
        PROVIDERS.remove(entry);
        if (PROVIDERS.isEmpty()) {
            ATTACHED.forEach(Hooks::remove);
            ATTACHED.clear();
        }
    }

    private static void attach() {
        if (!ATTACHED.isEmpty()) return;
        add(DAY, true, call -> {
            Activity.Context ctx = (Activity.Context) call.args()[0];
            float eat = (Float) call.args()[1];
            float fetch = (Float) call.args()[2];
            float work = (Float) call.args()[3];
            float leisure = (Float) call.args()[4];
            // daytime only: Towntopia's own "work hours" (not night, evening phase DAY); never during a water rescue
            if (!VillagerBrain.isWorkHours(ctx) || ctx.model().isEscapingWater()) return;
            CoreChoiceContext cc = new CoreChoiceContext(ctx, ChoicePhase.DAY, eat, fetch, work, leisure, null);
            ProviderEntry<ChoiceProvider>[] asked = active();
            float[] scores = scores(asked, cc);
            int winner = ChoiceArbiter.pickDay(Math.max(Math.max(eat, fetch), Math.max(work, leisure)), scores, false);
            replace(call, asked, winner, ctx);
        });
        add(EVENING, false, call -> {
            eveningDecision = null;
            eveningPhase = null;
        });
        add(EVENING_DECIDE, true, call -> {
            if (call.returnValue() instanceof Enum<?> decision && call.args()[0] instanceof EveningPlanner.Facts facts) {
                eveningDecision = decision.name();
                eveningPhase = facts.phase() == null ? null : facts.phase().name();
            }
        });
        add(EVENING, true, call -> {
            String decision = eveningDecision;
            String phase = eveningPhase;
            eveningDecision = null;
            eveningPhase = null;
            if (decision == null || !ChoiceArbiter.EVENING_LEISURE.contains(decision) || "NIGHT".equals(phase)) return;
            Activity.Context ctx = (Activity.Context) call.args()[0];
            // Towntopia re-plans the evening every tick and sees an addon activity as "other", so it offers its leisure
            // step again: keep the addon run going instead of replacing it (non-leisure steps and bedtime still do)
            if (ActivityRegistry.addonId(ctx.brain().currentActivity()) != null) {
                call.setReturnValue(null);
                return;
            }
            CoreChoiceContext cc = new CoreChoiceContext(ctx, ChoicePhase.EVENING, 0F, 0F, 0F, 0F, EveningStep.valueOf(decision));
            ProviderEntry<ChoiceProvider>[] asked = active();
            replace(call, asked, ChoiceArbiter.pickEvening(decision, phase, scores(asked, cc)), ctx);
        });
    }

    @SuppressWarnings("unchecked")
    private static ProviderEntry<ChoiceProvider>[] active() {
        return PROVIDERS.stream().filter(e -> !e.off).toArray(ProviderEntry[]::new);
    }

    private static float[] scores(ProviderEntry<ChoiceProvider>[] asked, CoreChoiceContext cc) {
        float[] scores = new float[asked.length];
        for (int i = 0; i < asked.length; i++) scores[i] = (float) asked[i].ask(p -> p.score(cc), 0D);
        return scores;
    }

    private static void replace(townkit.api.hooks.HookCall call, ProviderEntry<ChoiceProvider>[] asked, int winner, Activity.Context ctx) {
        if (winner < 0) return;
        ResourceLocation id = asked[winner].get(ChoiceProvider::activity); // isolated: a bad provider fails alone
        Activity a = id == null ? null : ActivityRegistry.create(id, ctx.model());
        if (a != null) call.setReturnValue(a);
    }

    private static void add(String key, boolean after, HookListener listener) {
        HookInfo h = HookManifest.get().byKey.get(key);
        if (h == null) {
            TownKit.LOG.warn("[TownKit] can't find {}: addon choices are unavailable there", key);
            return;
        }
        ATTACHED.add(Hooks.add(h, "townkit", listener, after));
    }
}
