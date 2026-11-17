package townkit.core.brain;

import com.alex.test.village.brain.Activity;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import townkit.api.brain.ChoiceContext;
import townkit.api.brain.ChoicePhase;
import townkit.api.brain.EveningStep;
import townkit.api.village.Village;
import townkit.api.village.Villager;
import townkit.core.village.VillageHandle;
import townkit.core.village.VillagerHandle;

/** One decision's context for choice and interrupt providers; handles are made only if a provider asks for them. */
final class CoreChoiceContext implements ChoiceContext {
    private final Activity.Context raw;
    private final ChoicePhase phase;
    private final float eat;
    private final float fetch;
    private final float work;
    private final float leisure;
    @Nullable
    private final EveningStep step;
    @Nullable
    private Villager villager;
    @Nullable
    private Village village;

    CoreChoiceContext(Activity.Context raw, ChoicePhase phase, float eat, float fetch, float work, float leisure, @Nullable EveningStep step) {
        this.raw = raw;
        this.phase = phase;
        this.eat = eat;
        this.fetch = fetch;
        this.work = work;
        this.leisure = leisure;
        this.step = step;
    }

    @Override
    public Villager villager() {
        if (this.villager == null) this.villager = VillagerHandle.of(this.raw.model());
        return this.villager;
    }

    @Override
    public Village village() {
        if (this.village == null) this.village = VillageHandle.of(this.raw.mgr());
        return this.village;
    }

    @Override
    public ServerLevel level() {
        return this.raw.level();
    }

    @Override
    public ChoicePhase phase() {
        return this.phase;
    }

    @Override
    public float eatScore() {
        return this.eat;
    }

    @Override
    public float fetchScore() {
        return this.fetch;
    }

    @Override
    public float workScore() {
        return this.work;
    }

    @Override
    public float leisureScore() {
        return this.leisure;
    }

    @Override
    public Optional<EveningStep> eveningStep() {
        return Optional.ofNullable(this.step);
    }

    @Override
    public String currentActivity() {
        Activity current = this.raw.brain().currentActivity();
        return current == null ? "none" : current.name();
    }
}
