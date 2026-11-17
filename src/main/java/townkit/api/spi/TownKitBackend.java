package townkit.api.spi;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import townkit.api.brain.ActivityContext;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;
import javax.annotation.Nullable;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.VisibleForTesting;
import townkit.api.Subscription;
import townkit.api.event.TypedEvent;
import townkit.api.hooks.HookListener;
import townkit.api.hooks.HookPoint;
import townkit.api.hooks.HookRegistration;
import townkit.api.village.Village;
import townkit.api.village.Villager;
import townkit.api.villager.VillagerDataKey;

/**
 * What the public API needs from TownKit's internals. TownKit installs the one implementation when it starts, so
 * {@code townkit.api} never imports {@code townkit.core} or {@code townkit.mixin}. Not for addons.
 */
@ApiStatus.Internal
public interface TownKitBackend {
    HookRegistration addHook(String owner, String key, HookListener listener, boolean after);

    Optional<HookPoint> findHook(String key);

    List<HookPoint> allHooks();

    boolean isHookLive(String key);

    int hookCount();

    <T> Subscription listen(TypedEvent<T> event, String owner, Consumer<T> handler);

    boolean available(TypedEvent<?> event);

    TownKitVillagesView villages();

    /** Towntopia's object behind a handle; null once it's gone. Throws IllegalArgumentException for handles TownKit didn't make. */
    @Nullable
    VillageManager unwrap(Village village);

    @Nullable
    VillagerModel unwrap(Villager villager);

    Optional<NpcEntity> npc(Villager villager);

    <T> VillagerDataKey<T> registerDataKey(ResourceLocation id, Codec<T> codec);

    TownKitBrainView brain();

    TownKitBuildingsView buildings();

    TownKitPathsView paths();

    TownKitTransportView transport();

    /** @since 0.7.0 */
    TownKitShopView shop();

    /** @since 0.7.0 */
    TownKitBubblesView bubbles();

    /** @since 0.7.0 */
    TownKitSkinsView skins();

    /** @since 0.7.0 */
    TownKitAnimationsView animations();

    /** @since 0.7.1 */
    TownKitWorkView work();

    /** Towntopia's context behind an addon activity's context, during a call; null outside one. */
    @Nullable
    Activity.Context unwrap(ActivityContext ctx);

    /** Called once, by TownKit, before anything else. */
    static void install(TownKitBackend backend) {
        synchronized (Installed.class) {
            if (Installed.backend != null) throw new IllegalStateException("TownKit's backend is already installed");
            Installed.backend = backend;
        }
    }

    static TownKitBackend get() {
        TownKitBackend b = Installed.backend;
        if (b == null) {
            throw new IllegalStateException("TownKit is not initialised yet: call TownKit from FMLCommonSetupEvent or later");
        }
        return b;
    }

    @VisibleForTesting
    static void resetForTests() {
        Installed.backend = null;
    }
}

/** Holds the installed backend (interfaces can't have mutable fields). */
final class Installed {
    static volatile TownKitBackend backend;

    private Installed() {
    }
}
