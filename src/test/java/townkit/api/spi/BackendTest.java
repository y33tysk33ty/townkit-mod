package townkit.api.spi;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BackendTest {
    @AfterEach
    void reset() {
        TownKitBackend.resetForTests();
    }

    @Test
    void getBeforeInstallThrows() {
        TownKitBackend.resetForTests();
        var e = assertThrows(IllegalStateException.class, TownKitBackend::get);
        assertTrue(e.getMessage().contains("TownKit is not initialised yet"));
    }

    @Test
    void installTwiceThrows() {
        TownKitBackend.resetForTests();
        TownKitBackend.install(stub());
        assertThrows(IllegalStateException.class, () -> TownKitBackend.install(stub()));
    }

    /** A backend whose every method throws: these tests only exercise install/get. */
    private static TownKitBackend stub() {
        return (TownKitBackend) java.lang.reflect.Proxy.newProxyInstance(BackendTest.class.getClassLoader(), new Class<?>[] {TownKitBackend.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });
    }
}
