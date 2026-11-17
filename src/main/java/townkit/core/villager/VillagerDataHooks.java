package townkit.core.villager;

import com.mojang.serialization.Codec;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import townkit.api.villager.VillagerDataKey;
import townkit.core.TownKit;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.Hooks;

/**
 * Drops a villager's data when Towntopia forgets the villager. The hook is attached only once there is data to look
 * after: the first registered key, or a non-empty store when the server starts.
 */
public final class VillagerDataHooks {
    public static final String REMOVE_VILLAGER = "village.VillageManager#removeVillager(UUID)";
    private static volatile boolean attached;

    private VillagerDataHooks() {
    }

    public static <T> VillagerDataKey<T> register(ResourceLocation id, Codec<T> codec) {
        VillagerDataKey<T> key = DataKeys.register(id, codec);
        ensureAttached();
        return key;
    }

    /** At server start: look after data saved by an earlier session even if no addon registered a key yet. */
    public static void onServerStarted(MinecraftServer server) {
        if (!VillagerDataStore.of(server).isEmpty()) ensureAttached();
    }

    static synchronized void ensureAttached() {
        if (attached) return;
        HookInfo h = HookManifest.get().byKey.get(REMOVE_VILLAGER);
        if (h == null) {
            TownKit.LOG.warn("[TownKit] can't find {}: villager data won't be dropped when a villager is removed", REMOVE_VILLAGER);
            return;
        }
        // after-hook: VILLAGER_REMOVED listeners (before-hooks) have already seen the data
        Hooks.add(h, "townkit", call -> {
            if (Boolean.TRUE.equals(call.returnValue()) && call.args()[0] instanceof UUID npcId
                && call.self() instanceof com.alex.test.village.VillageManager mgr && mgr.getLevel() != null) {
                VillagerDataStore.of(mgr.getLevel().getServer()).drop(npcId);
            }
        }, true);
        attached = true;
    }
}
