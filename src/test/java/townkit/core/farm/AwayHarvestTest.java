package townkit.core.farm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The away rules' switch-off (a failed step) is undone by a reload, as the use-harvest switch-off is. */
class AwayHarvestTest {
    @Test
    void reloadSwitchesAwayRulesBackOn() {
        AwayHarvest.reloaded();
        AwayHarvest.fail();
        assertTrue(AwayHarvest.failed(), "a failed step switches the away rules off");
        assertFalse(AwayHarvest.available(), "while off the steps are not looked up");
        FarmHooks.reloaded();
        assertFalse(AwayHarvest.failed(), "a reload switches them on again");
    }
}
