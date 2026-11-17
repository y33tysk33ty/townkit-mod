package townkit.core.village;

import java.lang.ref.WeakReference;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The server a handle was made on, held weakly: a handle kept after leaving a single-player world must neither keep
 * that world in memory nor read its leftover data. Once another server (or none) is running, the handle reads as gone.
 */
final class ServerRef {
    private final WeakReference<MinecraftServer> server;

    ServerRef(MinecraftServer server) {
        this.server = new WeakReference<>(server);
    }

    /** The handle's server while it's still the running one; null otherwise. */
    @Nullable
    MinecraftServer live() {
        MinecraftServer s = this.server.get();
        return s != null && s == ServerLifecycleHooks.getCurrentServer() && s.isRunning() ? s : null;
    }
}
