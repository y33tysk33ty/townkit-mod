package townkit.core.brain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import townkit.api.Subscription;
import townkit.api.brain.ActivityStatus;
import townkit.api.brain.VillagerActivity;

class ActivityRegistryTest {
    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("townkit_test", path);
    }

    private static VillagerActivity idle(ResourceLocation id) {
        return new VillagerActivity() {
            @Override
            public ResourceLocation id() {
                return id;
            }

            @Override
            public ActivityStatus tick(townkit.api.brain.ActivityContext ctx) {
                return ActivityStatus.RUNNING;
            }
        };
    }

    @Test
    void duplicateIdRejected() {
        ActivityRegistry.register(id("a"), v -> idle(id("a")));
        var e = assertThrows(IllegalArgumentException.class, () -> ActivityRegistry.register(id("a"), v -> idle(id("a"))));
        assertTrue(e.getMessage().contains("townkit_test:a"));
    }

    @Test
    void removedRegistrationForgetsId() {
        Subscription s = ActivityRegistry.register(id("b"), v -> idle(id("b")));
        assertTrue(ActivityRegistry.isRegistered(id("b")));
        s.remove();
        assertFalse(ActivityRegistry.isRegistered(id("b")));
    }

    @Test
    void tenFailuresSwitchOff() {
        ActivityRegistry.register(id("c"), v -> idle(id("c")));
        for (int i = 0; i < 9; i++) ActivityRegistry.recordFailure(id("c"));
        ActivityRegistry.recordSuccess(id("c")); // a success resets the run of failures
        for (int i = 0; i < 9; i++) ActivityRegistry.recordFailure(id("c"));
        assertFalse(ActivityRegistry.isSwitchedOff(id("c")));
        ActivityRegistry.recordFailure(id("c"));
        assertTrue(ActivityRegistry.isSwitchedOff(id("c")));
    }
}
