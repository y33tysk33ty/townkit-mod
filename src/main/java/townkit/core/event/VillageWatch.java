package townkit.core.event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import townkit.api.village.RaidStage;
import townkit.api.village.VillageState;

/**
 * Change detection for village state and raid stage, with no Minecraft or Towntopia types. Feed it the current
 * readings; it returns what changed since the last call. A village's first reading is silent, so is the first
 * known raid stage after the stage was unknown, and a {@link #clear()} starts over from a silent baseline. A jump
 * from DORMANT straight to ACTIVE is reported as DORMANT to RECONCILING, then RECONCILING to ACTIVE.
 */
public final class VillageWatch {
    /** One village's current reading; {@code stage} is null while Towntopia hasn't loaded its raid state. */
    public record Reading(UUID id, VillageState state, @Nullable RaidStage stage) {
    }

    public sealed interface Change {
        UUID id();
    }

    public record StateChanged(UUID id, VillageState from, VillageState to) implements Change {
    }

    public record StageChanged(UUID id, RaidStage from, RaidStage to) implements Change {
    }

    public record Started(UUID id, RaidStage stage) implements Change {
    }

    public record Ended(UUID id) implements Change {
    }

    private static final class Seen {
        VillageState state;
        @Nullable
        RaidStage stage;

        Seen(VillageState state, @Nullable RaidStage stage) {
            this.state = state;
            this.stage = stage;
        }
    }

    private final Map<UUID, Seen> seen = new HashMap<>();

    /** Compares the readings with the last ones; state changes come before stage changes for each village. */
    public List<Change> update(Collection<Reading> now) {
        List<Change> out = new ArrayList<>();
        Set<UUID> present = new HashSet<>();
        for (Reading r : now) {
            present.add(r.id());
            Seen s = this.seen.get(r.id());
            if (s == null) {
                this.seen.put(r.id(), new Seen(r.state(), r.stage()));
                continue;
            }
            if (s.state != r.state()) {
                // every wake from DORMANT goes through RECONCILING in Towntopia, but a once-a-second check can miss it
                if (s.state == VillageState.DORMANT && r.state() == VillageState.ACTIVE) {
                    out.add(new StateChanged(r.id(), VillageState.DORMANT, VillageState.RECONCILING));
                    out.add(new StateChanged(r.id(), VillageState.RECONCILING, VillageState.ACTIVE));
                } else {
                    out.add(new StateChanged(r.id(), s.state, r.state()));
                }
                s.state = r.state();
            }
            RaidStage to = r.stage();
            if (to == null) continue;
            RaidStage from = s.stage;
            s.stage = to;
            if (from == null || from == to) continue;
            out.add(new StageChanged(r.id(), from, to));
            if (from == RaidStage.IDLE) out.add(new Started(r.id(), to));
            if (to == RaidStage.IDLE) out.add(new Ended(r.id()));
        }
        this.seen.keySet().retainAll(present);
        return out;
    }

    /** Forgets everything: the next reading of every village is a silent baseline. */
    public void clear() {
        this.seen.clear();
    }
}
