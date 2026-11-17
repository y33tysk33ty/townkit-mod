package townkit.core;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import java.util.AbstractList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import townkit.api.Subscription;
import townkit.api.event.TypedEvent;
import townkit.api.hooks.HookListener;
import townkit.api.hooks.HookPoint;
import townkit.api.hooks.HookRegistration;
import townkit.api.spi.TownKitBackend;
import townkit.api.spi.TownKitVillagesView;
import townkit.api.village.Village;
import townkit.api.village.Villager;
import townkit.core.event.EventBindings;
import townkit.core.brain.CoreBrain;
import townkit.core.brain.CoreActivityContext;
import townkit.api.spi.TownKitBrainView;
import townkit.api.brain.ActivityContext;
import com.alex.test.village.brain.Activity;
import townkit.core.villager.VillagerDataHooks;
import townkit.api.villager.VillagerDataKey;
import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;
import townkit.core.hook.HookInfo;
import townkit.core.hook.HookManifest;
import townkit.core.hook.HookStatus;
import townkit.core.hook.Hooks;
import townkit.core.village.CoreVillages;
import townkit.core.village.VillageHandle;
import townkit.core.village.VillagerHandle;

/** The one {@link TownKitBackend}: the public API's way into the hook engine. Installed by {@link TownKit}. */
public final class CoreBackend implements TownKitBackend {
    private final CoreVillages villages = new CoreVillages();
    private final CoreBrain brain = new CoreBrain();
    private final townkit.core.building.CoreBuildings buildings = new townkit.core.building.CoreBuildings();
    private final townkit.core.nav.CorePaths paths = new townkit.core.nav.CorePaths();
    private final townkit.core.nav.TransportKinds transport = townkit.core.nav.TransportKinds.GLOBAL;
    private final townkit.core.shop.CoreShop shop = new townkit.core.shop.CoreShop();
    private final townkit.core.bubble.CoreBubbles bubbles = new townkit.core.bubble.CoreBubbles();
    private final townkit.core.skin.CoreSkins skins = new townkit.core.skin.CoreSkins();
    private final townkit.core.anim.CoreAnimations animations = new townkit.core.anim.CoreAnimations();
    private final townkit.core.work.CoreWork work = new townkit.core.work.CoreWork();

    @Override
    public HookRegistration addHook(String owner, String key, HookListener listener, boolean after) {
        HookInfo h = HookManifest.get().byKey.get(key);
        if (h == null) throw new IllegalArgumentException("No TownKit hook '" + key + "'");
        return Hooks.add(h, owner, listener, after);
    }

    @Override
    public Optional<HookPoint> findHook(String key) {
        HookInfo h = HookManifest.get().byKey.get(key);
        return h == null ? Optional.empty() : Optional.of(Hooks.point(h));
    }

    @Override
    public List<HookPoint> allHooks() {
        HookInfo[] all = HookManifest.get().hooks;
        // a view: points are only made for the hooks a caller actually looks at
        return new AbstractList<>() {
            @Override
            public HookPoint get(int i) {
                return Hooks.point(all[i]);
            }

            @Override
            public int size() {
                return all.length;
            }
        };
    }

    @Override
    public boolean isHookLive(String key) {
        HookInfo h = HookManifest.get().byKey.get(key);
        return h != null && HookStatus.isApplied(h);
    }

    @Override
    public int hookCount() {
        return Hooks.hookCount();
    }

    @Override
    public <T> Subscription listen(TypedEvent<T> event, String owner, Consumer<T> handler) {
        return EventBindings.listen(event, owner, handler);
    }

    @Override
    public boolean available(TypedEvent<?> event) {
        return EventBindings.available(event);
    }

    @Override
    public TownKitVillagesView villages() {
        return this.villages;
    }

    @Override
    public VillageManager unwrap(Village village) {
        if (!(village instanceof VillageHandle h)) throw new IllegalArgumentException("Not a TownKit village handle: " + village);
        return h.manager();
    }

    @Override
    public VillagerModel unwrap(Villager villager) {
        if (!(villager instanceof VillagerHandle h)) throw new IllegalArgumentException("Not a TownKit villager handle: " + villager);
        return h.model();
    }

    @Override
    public TownKitBrainView brain() {
        return this.brain;
    }

    @Override
    public townkit.api.spi.TownKitBuildingsView buildings() {
        return this.buildings;
    }

    @Override
    public townkit.api.spi.TownKitPathsView paths() {
        return this.paths;
    }

    @Override
    public townkit.api.spi.TownKitTransportView transport() {
        return this.transport;
    }

    @Override
    public townkit.api.spi.TownKitShopView shop() {
        return this.shop;
    }

    @Override
    public townkit.api.spi.TownKitBubblesView bubbles() {
        return this.bubbles;
    }

    @Override
    public townkit.api.spi.TownKitSkinsView skins() {
        return this.skins;
    }

    @Override
    public townkit.api.spi.TownKitAnimationsView animations() {
        return this.animations;
    }

    /** @since 0.7.1 */
    @Override
    public townkit.api.spi.TownKitWorkView work() {
        return this.work;
    }

    @Override
    public Activity.Context unwrap(ActivityContext ctx) {
        if (!(ctx instanceof CoreActivityContext c)) throw new IllegalArgumentException("Not a TownKit activity context: " + ctx);
        return c.raw();
    }

    @Override
    public <T> VillagerDataKey<T> registerDataKey(ResourceLocation id, Codec<T> codec) {
        return VillagerDataHooks.register(id, codec);
    }

    @Override
    public Optional<NpcEntity> npc(Villager villager) {
        if (!(villager instanceof VillagerHandle h)) throw new IllegalArgumentException("Not a TownKit villager handle: " + villager);
        return Optional.ofNullable(h.npc());
    }
}
