package townkit.core.village;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alex.test.village.raid.RaidManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import townkit.api.village.RaidStage;
import townkit.api.village.VillageState;

class VillageReadsTest {
    @Test
    void stateOrder() {
        assertEquals(VillageState.INACTIVE, VillageReads.state(true, true, true));
        assertEquals(VillageState.RECONCILING, VillageReads.state(false, true, true));
        assertEquals(VillageState.DORMANT, VillageReads.state(false, false, true));
        assertEquals(VillageState.ACTIVE, VillageReads.state(false, false, false));
        assertEquals(RaidManager.Stage.values().length, RaidStage.values().length);
        assertEquals(
            List.of(RaidManager.Stage.values()).stream().map(Enum::name).toList(),
            List.of(RaidStage.values()).stream().map(Enum::name).toList());
    }

    /** An adopted vanilla raid sets challengeActive and leaves Towntopia's stage at IDLE: the fight still reads as ASSAULT. */
    @Test
    void anAdoptedVanillaRaidReadsAsAssault() {
        assertEquals(RaidStage.ASSAULT, VillageReads.raidStage("IDLE", true));
    }

    @Test
    void noRaidReadsAsIdle() {
        assertEquals(RaidStage.IDLE, VillageReads.raidStage("IDLE", false));
    }

    @Test
    void aRunningStageWinsOverTheChallengeFlag() {
        assertEquals(RaidStage.CAMP, VillageReads.raidStage("CAMP", false));
        assertEquals(RaidStage.RETREAT, VillageReads.raidStage("RETREAT", true));
    }

    @Test
    void anUnknownStageReadsAsIdle() {
        assertEquals(RaidStage.IDLE, VillageReads.raidStage("SOMETHING_NEW", false));
    }
}
