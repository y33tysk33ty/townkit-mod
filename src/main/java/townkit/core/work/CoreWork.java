package townkit.core.work;

import com.google.gson.JsonElement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import townkit.api.Subscription;
import townkit.api.spi.TownKitWorkView;
import townkit.api.work.CookStation;
import townkit.api.work.Dish;
import townkit.api.work.DishChange;
import townkit.api.work.ForgeRecipe;
import townkit.api.work.ForgeRecipeChange;
import townkit.api.work.OreEntry;
import townkit.api.work.TierPlace;
import townkit.core.farm.FarmHooks;
import townkit.core.work.cook.CookHooks;
import townkit.core.work.cook.CookOverlay;
import townkit.core.work.cook.DishCodec;
import townkit.core.work.cook.StationCodec;
import townkit.core.work.cook.Stations;
import townkit.core.work.forge.ForgeCodecs;
import townkit.core.work.forge.ForgeHooks;
import townkit.core.work.forge.ForgeOverlay;
import townkit.core.work.forge.SmeltCodec;
import townkit.core.work.forge.SmeltOverlay;
import townkit.core.work.mine.MineHooks;
import townkit.core.work.mine.MiningOverlay;
import townkit.core.work.mine.OreCodec;

/**
 * Holds the work tables by kind name ({@code cook_dishes}, {@code cook_stations}, ...) and wires the data-file reload.
 * A module registers its table with its codec once; files under {@code data/<ns>/townkit/<kind>/} then feed it on every (re)load.
 */
public final class CoreWork implements TownKitWorkView {
    /** A registered table with the codec that reads its files. */
    static final class Kind<K, E> {
        final WorkTable<K, E> table;
        final WorkFiles.WorkCodec<K, E> codec;

        Kind(WorkTable<K, E> table, WorkFiles.WorkCodec<K, E> codec) {
            this.table = table;
            this.codec = codec;
        }

        void load(Map<ResourceLocation, JsonElement> files) {
            this.table.setFileChanges(WorkFiles.parse(this.table.kind(), files, this.codec));
        }
    }

    private final Map<String, Kind<?, ?>> kinds = new ConcurrentHashMap<>();

    public CoreWork() {
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent e) -> e.addListener(WorkFiles.listener(this.kinds, this::loadFiles)));
        // addons register in setup; entries nobody added are reported once everything is registered. The cook's hooks
        // are refreshed here too: at startup no datapack sync comes until a player joins
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            this.kinds.values().forEach(k -> k.table.report());
            CookHooks.refresh();
            ForgeHooks.refresh();
            MineHooks.refresh();
            FarmHooks.refresh();
            FarmHooks.started();
        });
        register(CookOverlay.KIND, CookOverlay.GLOBAL.table(), new DishCodec(CookOverlay.REGISTRY));
        register(Stations.KIND, Stations.GLOBAL.table(), new StationCodec(Stations.REGISTRY));
        register(ForgeOverlay.MATERIALS, ForgeOverlay.GLOBAL.materialTable(), new ForgeCodecs.Materials(CookOverlay.REGISTRY));
        register(ForgeOverlay.TIERS, ForgeOverlay.GLOBAL.tierTable(), new ForgeCodecs.Tiers());
        register(ForgeOverlay.RECIPES, ForgeOverlay.GLOBAL.recipeTable(), new ForgeCodecs.Recipes(CookOverlay.REGISTRY));
        register(SmeltOverlay.KIND, SmeltOverlay.GLOBAL.table(), new SmeltCodec(CookOverlay.REGISTRY));
        register(MiningOverlay.KIND, MiningOverlay.GLOBAL.table(), new OreCodec(MiningOverlay.REGISTRY));
        // after a (re)load: the merged views and their hooks follow the new files before villagers read them
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent e) -> {
            CookHooks.refresh();
            ForgeHooks.refresh();
            MineHooks.refresh();
            FarmHooks.refresh();
            // a /reload ends here, with Towntopia's crop definitions and the tags loaded: report again what only shows now
            FarmHooks.started();
        });
        // a material's tag, or a tag dish's tag, may list other items after a reload
        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent e) -> {
            if (e.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) return;
            ForgeHooks.refresh();
            CookOverlay.GLOBAL.tagsChanged();
            townkit.core.farm.HarvestTable.GLOBAL.tagsChanged();
            FarmHooks.refresh();
            CookHooks.refresh();
        });
        // a cook station batch whose cook unloads or dies, or whose server stops, still hears stopped (once)
        NeoForge.EVENT_BUS.addListener((EntityLeaveLevelEvent e) -> {
            if (!e.getLevel().isClientSide()) CookHooks.onEntityLeave(e.getEntity());
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            CookHooks.onServerStopping();
            CookOverlay.serverStopping();
            FarmHooks.serverStopping();
        });
    }

    /**
     * Hands every kind its data files (a {@code /reload} or the server's first load; missing kinds get none) and then
     * refreshes the cook's hooks, so a change made only by files works without a player joining.
     */
    public void loadFiles(Map<String, Map<ResourceLocation, JsonElement>> all) {
        WorkLog.GLOBAL.reset();
        FarmHooks.reloaded();
        for (Map.Entry<String, Kind<?, ?>> k : this.kinds.entrySet()) {
            k.getValue().load(all.getOrDefault(k.getKey(), Map.of()));
        }
        CookHooks.refresh();
        ForgeHooks.refresh();
        MineHooks.refresh();
        FarmHooks.refresh();
    }

    /** Registers a kind's table and the codec for its data files. A kind is registered once. */
    public <K, E> void register(String kind, WorkTable<K, E> table, WorkFiles.WorkCodec<K, E> codec) {
        if (this.kinds.putIfAbsent(kind, new Kind<>(table, codec)) != null) {
            throw new IllegalStateException("Work kind already registered: " + kind);
        }
    }

    @Override
    public Set<String> kinds() {
        return Collections.unmodifiableSet(this.kinds.keySet());
    }

    // --- cook ----------------------------------------------------------------------------------------------------------

    @Override
    public Subscription addDish(String owner, Dish dish) {
        owner(owner);
        Objects.requireNonNull(dish, "dish");
        return subscription(CookOverlay.GLOBAL.add(owner, dish));
    }

    @Override
    public Subscription changeDish(String owner, Item result, DishChange change) {
        owner(owner);
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(change, "change");
        if (change.isEmpty()) throw new IllegalArgumentException("the change sets nothing");
        return subscription(CookOverlay.GLOBAL.change(owner, result, change));
    }

    @Override
    public Subscription removeDish(String owner, Item result) {
        owner(owner);
        Objects.requireNonNull(result, "result");
        return subscription(CookOverlay.GLOBAL.remove(owner, result));
    }

    @Override
    public List<Dish> dishes() {
        return CookOverlay.GLOBAL.dishes();
    }

    @Override
    public Subscription station(String owner, ResourceLocation id, CookStation station) {
        owner(owner);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(station, "station");
        Stations.Registration r = Stations.GLOBAL.add(owner, id, station);
        return subscription(r.handle(), r.def()::off);
    }

    @Override
    public Subscription removeStation(String owner, ResourceLocation id) {
        owner(owner);
        Objects.requireNonNull(id, "id");
        return subscription(Stations.GLOBAL.remove(owner, id));
    }

    // --- blacksmith ------------------------------------------------------------------------------------------------------

    @Override
    public Subscription forgeMaterial(String owner, ResourceLocation id, TagKey<Item> items) {
        owner(owner);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(items, "items");
        return subscription(ForgeOverlay.GLOBAL.material(owner, new ForgeOverlay.MaterialDef(id, items, null)));
    }

    @Override
    public Subscription forgeTier(String owner, ResourceLocation id, TierPlace place) {
        owner(owner);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(place, "place");
        return subscription(ForgeOverlay.GLOBAL.tier(owner, new ForgeOverlay.TierDef(id, place)));
    }

    @Override
    public Subscription removeForgeMaterial(String owner, ResourceLocation id) {
        owner(owner);
        Objects.requireNonNull(id, "id");
        return subscription(ForgeOverlay.GLOBAL.removeMaterial(owner, id));
    }

    @Override
    public Subscription removeForgeTier(String owner, ResourceLocation id) {
        owner(owner);
        Objects.requireNonNull(id, "id");
        return subscription(ForgeOverlay.GLOBAL.removeTier(owner, id));
    }

    @Override
    public Subscription addForgeRecipe(String owner, ForgeRecipe recipe) {
        owner(owner);
        Objects.requireNonNull(recipe, "recipe");
        return subscription(ForgeOverlay.GLOBAL.add(owner, recipe));
    }

    @Override
    public Subscription changeForgeRecipe(String owner, Item result, ForgeRecipeChange change) {
        owner(owner);
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(change, "change");
        if (change.isEmpty()) throw new IllegalArgumentException("the change sets nothing");
        return subscription(ForgeOverlay.GLOBAL.change(owner, result, change));
    }

    @Override
    public Subscription removeForgeRecipe(String owner, Item result) {
        owner(owner);
        Objects.requireNonNull(result, "result");
        return subscription(ForgeOverlay.GLOBAL.remove(owner, result));
    }

    @Override
    public Subscription smelt(String owner, Item raw, Item result) {
        owner(owner);
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(result, "result");
        String problem = SmeltOverlay.problem(raw, result);
        if (problem != null) {
            WorkLog.GLOBAL.once("smelt-skipped|" + owner + "|" + raw + "|" + result, "[" + SmeltOverlay.KIND + "] " + owner
                + "'s entry is skipped: " + problem);
            return subscription(() -> { });
        }
        return subscription(SmeltOverlay.GLOBAL.add(owner, new SmeltOverlay.Smelt(raw, result)));
    }

    @Override
    public Subscription removeSmelt(String owner, Item raw) {
        owner(owner);
        Objects.requireNonNull(raw, "raw");
        return subscription(SmeltOverlay.GLOBAL.remove(owner, raw));
    }

    @Override
    public Optional<ResourceLocation> forgeTierOf(Item item) {
        Objects.requireNonNull(item, "item");
        return ForgeOverlay.GLOBAL.tierOf(item);
    }

    // --- miner ---------------------------------------------------------------------------------------------------------

    @Override
    public Subscription ore(String owner, OreEntry ore) {
        owner(owner);
        Objects.requireNonNull(ore, "ore");
        return subscription(MiningOverlay.GLOBAL.add(owner, ore));
    }

    @Override
    public Subscription changeOre(String owner, ResourceLocation id, UnaryOperator<OreEntry> change) {
        owner(owner);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(change, "change");
        return subscription(MiningOverlay.GLOBAL.change(owner, id, change));
    }

    @Override
    public Subscription removeOre(String owner, ResourceLocation id) {
        owner(owner);
        Objects.requireNonNull(id, "id");
        return subscription(MiningOverlay.GLOBAL.remove(owner, id));
    }

    @Override
    public List<OreEntry> ores() {
        return MiningOverlay.GLOBAL.ores();
    }

    private static void owner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) throw new IllegalArgumentException("owner is blank");
    }

    /** A registration as a {@link Subscription}; every change refreshes the cook's and smith's hooks and players' screens. */
    public static Subscription subscription(WorkTable.Handle handle) {
        return subscription(handle, () -> false);
    }

    private static Subscription subscription(WorkTable.Handle handle, BooleanSupplier disabled) {
        CookHooks.refresh();
        ForgeHooks.refresh();
        MineHooks.refresh();
        FarmHooks.refresh();
        WorkScreens.resend();
        AtomicBoolean removed = new AtomicBoolean();
        return new Subscription() {
            @Override
            public void remove() {
                if (removed.compareAndSet(false, true)) {
                    handle.remove();
                    CookHooks.refresh();
                    ForgeHooks.refresh();
                    MineHooks.refresh();
                    FarmHooks.refresh();
                    WorkScreens.resend();
                }
            }

            @Override
            public boolean isDisabled() {
                return disabled.getAsBoolean();
            }
        };
    }
}
