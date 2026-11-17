package townkit.test;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import townkit.core.TownKit;

/** Named checks for a GameTest: each logs PASS/FAIL, and any FAIL fails the test with every failure listed. */
final class Checks {
    final List<String> failures = new ArrayList<>();
    final String test;

    Checks(String test) {
        this.test = test;
    }

    void check(String what, boolean ok, Object detail) {
        TownKit.LOG.info("[TownKit test] {} {}: {} ({})", this.test, ok ? "PASS" : "FAIL", what, detail);
        if (!ok) this.failures.add(what + " (" + detail + ")");
    }

    /**
     * A check of behaviour that exists only with Towntopia's hooks on: asserted then; with hooks off it is logged as
     * skipped (the hooks-off truth is asserted by the test itself or by {@link HooksOff}).
     */
    void checkHooksOn(String what, boolean ok, Object detail) {
        if (!HooksOff.off()) {
            check(what, ok, detail);
            return;
        }
        TownKit.LOG.info("[TownKit test] {} SKIP (hooks off): {} ({})", this.test, what, detail);
    }

    void finish(GameTestHelper helper) {
        if (this.failures.isEmpty()) helper.succeed();
        else helper.fail(this.test + ": " + String.join("; ", this.failures));
    }
}
