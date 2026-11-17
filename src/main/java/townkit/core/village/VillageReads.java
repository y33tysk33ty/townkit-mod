package townkit.core.village;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.raid.RaidManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import townkit.api.village.RaidStage;
import townkit.api.village.VillageState;
import townkit.core.TownKit;

/** The Towntopia-touching part of the extra {@code Village} reads, kept apart so it can be unit tested. */
public final class VillageReads {
    private VillageReads() {
    }

    /** Inactive wins over reconciling, which wins over dormant. */
    public static VillageState state(boolean inactive, boolean reconciling, boolean dormant) {
        if (inactive) return VillageState.INACTIVE;
        if (reconciling) return VillageState.RECONCILING;
        if (dormant) return VillageState.DORMANT;
        return VillageState.ACTIVE;
    }

    public static VillageState state(VillageManagerRegistry reg, UUID id) {
        return state(reg.isVillageInactive(id), reg.isVillageReconciling(id), reg.isVillageDormant(id));
    }

    /**
     * The raid stage, or null while the village's raid state isn't loaded yet. Only isLoaded(), stage() and
     * isChallengeActive() are used: most other RaidManager reads run ensureLoaded(), which would load (and so change)
     * Towntopia's state.
     */
    @Nullable
    public static RaidStage raidStage(VillageManager mgr) {
        RaidManager raids = mgr.raids();
        if (!raids.isLoaded()) return null;
        return raidStage(raids.stage().name(), raids.isChallengeActive());
    }

    /**
     * Towntopia's stage name as the stable stage. A vanilla raid Towntopia adopted is fought with the stage left at IDLE
     * and only the challenge flag set (RaidManager.startChallenge), so that reads as ASSAULT.
     */
    static RaidStage raidStage(String name, boolean challengeActive) {
        RaidStage stage;
        try {
            stage = RaidStage.valueOf(name);
        } catch (IllegalArgumentException e) {
            if (warned.compareAndSet(false, true)) {
                TownKit.LOG.warn("[TownKit] unknown Towntopia raid stage {}, reading it as IDLE", name);
            }
            stage = RaidStage.IDLE;
        }
        return stage == RaidStage.IDLE && challengeActive ? RaidStage.ASSAULT : stage;
    }

    private static final AtomicBoolean warned = new AtomicBoolean();

    public static boolean raidInProgress(VillageManager mgr) {
        RaidManager raids = mgr.raids();
        return raids.isLoaded() && raids.inProgress();
    }
}
