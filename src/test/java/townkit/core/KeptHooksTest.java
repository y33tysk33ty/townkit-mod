package townkit.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.hooks.TownKitHooks;
import townkit.api.spi.TownKitBackend;

/** Hooks the 0.6.1 trim cut and 0.6.2 brings back through {@code keep} rules in hookgen/hook-rules.txt. */
class KeptHooksTest {
    private static final List<String> WAR_ADDON = List.of(
        "village.villagers.cleric.behavior.ClericCare#candidates(Activity.Context,ProfessionSettings)",
        "client.VillagerGeoModel#rigSkin(PathfinderMob)",
        "entity.mayor.MayorSpawnManager#ensureMayor(ServerLevel,TownHallBuilding)",
        "village.villagers.guard.behavior.GuardThreatEvents#resolveManager(NpcEntity,ServerLevel)",
        "village.villagers.guard.behavior.GuardThreatEvents#resolveManager(ServerPlayer,ServerLevel)",
        "village.VillageCrowding#isInside(NpcEntity,Set)");

    @BeforeEach
    void install() {
        TownKitBackend.resetForTests();
        TownKitBackend.install(new CoreBackend());
    }

    @AfterEach
    void reset() {
        TownKitBackend.resetForTests();
    }

    @Test
    void warAddonHooksExist() {
        for (String key : WAR_ADDON) assertTrue(TownKitHooks.find(key).isPresent(), key);
        assertTrue(TownKitHooks.find("client.VillagerGeoModel#rigSkin(PathfinderMob)").get().client());
    }
}
