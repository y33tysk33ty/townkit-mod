package townkit.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import townkit.api.village.RaidStage;
import townkit.api.village.VillageState;
import townkit.core.event.VillageWatch.Change;
import townkit.core.event.VillageWatch.Reading;

class VillageWatchTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final VillageWatch watch = new VillageWatch();

    private static Reading r(UUID id, VillageState s, RaidStage st) {
        return new Reading(id, s, st);
    }

    private List<Change> up(Reading... rs) {
        return this.watch.update(List.of(rs));
    }

    @Test
    void firstReadingIsSilent() {
        assertTrue(up(r(A, VillageState.ACTIVE, RaidStage.IDLE)).isEmpty());
    }

    @Test
    void newVillageLaterIsSilent() {
        up(r(A, VillageState.ACTIVE, RaidStage.IDLE));
        assertTrue(up(r(A, VillageState.ACTIVE, RaidStage.IDLE), r(B, VillageState.DORMANT, RaidStage.WARNING)).isEmpty());
    }

    @Test
    void stateChangeFires() {
        up(r(A, VillageState.ACTIVE, RaidStage.IDLE));
        assertEquals(List.of(new VillageWatch.StateChanged(A, VillageState.ACTIVE, VillageState.DORMANT)),
            up(r(A, VillageState.DORMANT, RaidStage.IDLE)));
    }

    @Test
    void raidStartStageEnd() {
        up(r(A, VillageState.ACTIVE, RaidStage.IDLE));
        assertEquals(List.of(new VillageWatch.StageChanged(A, RaidStage.IDLE, RaidStage.WARNING),
            new VillageWatch.Started(A, RaidStage.WARNING)), up(r(A, VillageState.ACTIVE, RaidStage.WARNING)));
        assertEquals(List.of(new VillageWatch.StageChanged(A, RaidStage.WARNING, RaidStage.ASSAULT)),
            up(r(A, VillageState.ACTIVE, RaidStage.ASSAULT)));
        assertEquals(List.of(new VillageWatch.StageChanged(A, RaidStage.ASSAULT, RaidStage.IDLE), new VillageWatch.Ended(A)),
            up(r(A, VillageState.ACTIVE, RaidStage.IDLE)));
    }

    @Test
    void skippedStagesCollapse() {
        up(r(A, VillageState.ACTIVE, RaidStage.IDLE));
        assertEquals(List.of(new VillageWatch.StageChanged(A, RaidStage.IDLE, RaidStage.ASSAULT),
            new VillageWatch.Started(A, RaidStage.ASSAULT)), up(r(A, VillageState.ACTIVE, RaidStage.ASSAULT)));
    }

    @Test
    void unloadedRaidThenLoadedIsBaseline() {
        up(r(A, VillageState.ACTIVE, null));
        assertTrue(up(r(A, VillageState.ACTIVE, RaidStage.ASSAULT)).isEmpty());
        assertEquals(List.of(new VillageWatch.StageChanged(A, RaidStage.ASSAULT, RaidStage.IDLE), new VillageWatch.Ended(A)),
            up(r(A, VillageState.ACTIVE, RaidStage.IDLE)));
    }

    @Test
    void unknownStageKeepsKnown() {
        up(r(A, VillageState.ACTIVE, RaidStage.WARNING));
        assertTrue(up(r(A, VillageState.ACTIVE, null)).isEmpty());
        assertTrue(up(r(A, VillageState.ACTIVE, RaidStage.WARNING)).isEmpty());
    }

    @Test
    void attachMidStateIsSilent() {
        up(r(A, VillageState.ACTIVE, RaidStage.IDLE));
        this.watch.clear();
        assertTrue(up(r(A, VillageState.DORMANT, RaidStage.ASSAULT)).isEmpty());
        assertEquals(List.of(new VillageWatch.StateChanged(A, VillageState.DORMANT, VillageState.RECONCILING),
            new VillageWatch.StateChanged(A, VillageState.RECONCILING, VillageState.ACTIVE)),
            up(r(A, VillageState.ACTIVE, RaidStage.ASSAULT)));
    }

    @Test
    void dormantToActiveReportsReconciling() {
        up(r(A, VillageState.DORMANT, RaidStage.IDLE));
        assertEquals(List.of(new VillageWatch.StateChanged(A, VillageState.DORMANT, VillageState.RECONCILING),
            new VillageWatch.StateChanged(A, VillageState.RECONCILING, VillageState.ACTIVE)),
            up(r(A, VillageState.ACTIVE, RaidStage.IDLE)));
        // only that pair expands
        assertEquals(List.of(new VillageWatch.StateChanged(A, VillageState.ACTIVE, VillageState.INACTIVE)),
            up(r(A, VillageState.INACTIVE, RaidStage.IDLE)));
    }

    @Test
    void stateBeforeStage() {
        up(r(A, VillageState.DORMANT, RaidStage.IDLE));
        List<Change> out = up(r(A, VillageState.ACTIVE, RaidStage.WARNING));
        assertEquals(4, out.size());
        assertEquals(new VillageWatch.StateChanged(A, VillageState.DORMANT, VillageState.RECONCILING), out.get(0));
        assertEquals(new VillageWatch.StateChanged(A, VillageState.RECONCILING, VillageState.ACTIVE), out.get(1));
        assertEquals(new VillageWatch.StageChanged(A, RaidStage.IDLE, RaidStage.WARNING), out.get(2));
    }

    @Test
    void missingVillagesAreForgotten() {
        up(r(A, VillageState.ACTIVE, RaidStage.IDLE));
        up(r(B, VillageState.ACTIVE, RaidStage.IDLE));
        assertTrue(up(r(A, VillageState.DORMANT, RaidStage.WARNING)).isEmpty());
    }
}
