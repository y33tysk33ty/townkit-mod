package townkit.core.brain;

import com.alex.test.village.brain.Activity;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import townkit.api.brain.MoveStatus;
import net.minecraft.server.level.ServerLevel;
import townkit.api.brain.ActivityContext;
import townkit.api.village.Village;
import townkit.api.village.Villager;
import townkit.core.village.VillageHandle;
import townkit.core.village.VillagerHandle;

/** The context of one addon activity run, re-bound to Towntopia's context for each call (no allocation per tick). */
public final class CoreActivityContext implements ActivityContext {
    private final VillagerHandle villager;
    final AddonRun run;
    @Nullable
    private Activity.Context raw;
    @Nullable
    private VillageHandle village;
    private final MoveTracker move = new MoveTracker();

    CoreActivityContext(VillagerHandle villager, AddonRun run) {
        this.villager = villager;
        this.run = run;
    }

    void bind(Activity.Context raw) {
        this.raw = raw;
        if (this.village == null && raw.mgr() != null) this.village = VillageHandle.of(raw.mgr());
    }

    void unbind() {
        this.raw = null;
    }

    /** Towntopia's context while a call is running; null outside one. */
    @Nullable
    public Activity.Context raw() {
        return this.raw;
    }

    private Activity.Context require() {
        if (this.raw == null) throw new IllegalStateException("ActivityContext used outside its start/tick/stop call");
        return this.raw;
    }

    @Override
    public Villager villager() {
        return this.villager;
    }

    @Override
    public Village village() {
        require();
        return this.village;
    }

    @Override
    public ServerLevel level() {
        return require().level();
    }

    @Override
    public long gameTime() {
        return require().level().getGameTime();
    }

    @Override
    public MoveStatus moveTo(BlockPos goal, float speed) {
        return this.move.toPos(require(), goal, speed);
    }

    @Override
    public MoveStatus moveTo(Entity target, float speed) {
        return this.move.toEntity(require(), target, speed);
    }

    void stopMove(Activity.Context raw) {
        this.move.stop(raw);
    }

    @Override
    public Optional<String> lastMoveProblem() {
        return this.move.problem();
    }
}
