package townkit.api.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import townkit.api.spi.TownKitBackend;

class TownKitHooksSearchTest {
    @AfterEach
    void reset() {
        TownKitBackend.resetForTests();
    }

    @Test
    void searchSplitsWordsOnAnyWhitespace() {
        List<HookPoint> hooks = List.of(new HookPoint("village.brain.VillagerBrain#tick(VillageManager,ServerLevel)", "void", false, false, false, null),
            new HookPoint("village.VillageManager#tick(ServerLevel)", "void", false, false, false, null));
        TownKitBackend.resetForTests();
        TownKitBackend.install((TownKitBackend) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {TownKitBackend.class},
            (proxy, method, args) -> {
                if (method.getName().equals("allHooks")) return hooks;
                throw new UnsupportedOperationException(method.getName());
            }));
        assertEquals(1, TownKitHooks.search("brain\ttick", 10).size());
        assertEquals(1, TownKitHooks.search("brain  tick", 10).size());
    }
}
