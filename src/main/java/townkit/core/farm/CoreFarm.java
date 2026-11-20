package townkit.core.farm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import townkit.api.Subscription;
import townkit.api.farm.HarvestRule;
import townkit.api.farm.SeedSource;
import townkit.api.spi.TownKitFarmView;
import townkit.core.work.CoreWork;
import townkit.core.work.WorkFiles;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkTable;

/**
 * The farm module: owns the farm tables and registers their kinds with {@link CoreWork}, so their data files
 * ({@code data/<ns>/townkit/<kind>/*.json}) are read on the same (re)load as the cook's. Its hooks are refreshed by
 * {@link CoreWork} at the same moments as the other modules' ({@link FarmHooks#refresh()}).
 *
 * @since 0.8.0
 */
public final class CoreFarm implements TownKitFarmView {
    private final CoreWork work;

    public CoreFarm(CoreWork work) {
        this.work = work;
        register(SeedTable.KIND, SeedTable.GLOBAL.table(), new SeedCodec(SeedCodec.REGISTRY, SeedTable.GLOBAL, WorkLog.GLOBAL));
        register(HarvestTable.KIND, HarvestTable.GLOBAL.table(), new HarvestCodec(HarvestBlocks.REGISTRY, SeedCodec.REGISTRY, WorkLog.GLOBAL));
    }

    /** Registers a farm kind's table and the codec for its data files. */
    <K, E> void register(String kind, WorkTable<K, E> table, WorkFiles.WorkCodec<K, E> codec) {
        this.work.register(kind, table, codec);
    }

    @Override
    public Subscription seedFrom(String owner, Item seed, Item produce, int seedsPerProduce) {
        owner(owner);
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(produce, "produce");
        if (seed == Items.AIR || produce == Items.AIR) {
            WorkLog.GLOBAL.once("seed-air|" + owner, "[" + SeedTable.KIND + "] " + owner + ": a seed entry names air (entry skipped)");
            return CoreWork.subscription(() -> { });
        }
        return CoreWork.subscription(SeedTable.GLOBAL.add(owner, seed, produce, seedsPerProduce));
    }

    @Override
    public Subscription removeSeedFrom(String owner, Item seed) {
        owner(owner);
        Objects.requireNonNull(seed, "seed");
        return CoreWork.subscription(SeedTable.GLOBAL.remove(owner, seed));
    }

    @Override
    public Map<Item, SeedSource> seedSources() {
        return SeedTable.GLOBAL.sources();
    }

    @Override
    public Subscription harvest(String owner, HarvestRule rule) {
        owner(owner);
        Objects.requireNonNull(rule, "rule");
        return CoreWork.subscription(HarvestTable.GLOBAL.table().add(owner, rule));
    }

    @Override
    public Subscription changeHarvest(String owner, ResourceLocation id, UnaryOperator<HarvestRule> change) {
        owner(owner);
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(change, "change");
        return CoreWork.subscription(HarvestTable.GLOBAL.table().change(owner, id, r -> {
            HarvestRule next = change.apply(r);
            if (next != null && !next.id().equals(id)) throw new IllegalArgumentException("a change must keep the id " + id);
            return next;
        }));
    }

    @Override
    public Subscription removeHarvest(String owner, ResourceLocation id) {
        owner(owner);
        Objects.requireNonNull(id, "id");
        return CoreWork.subscription(HarvestTable.GLOBAL.table().remove(owner, id));
    }

    @Override
    public List<HarvestRule> harvests() {
        return HarvestTable.GLOBAL.entries();
    }

    @Override
    public Optional<HarvestRule> harvestFor(BlockState state) {
        return Optional.ofNullable(HarvestTable.GLOBAL.ruleFor(state.getBlock()));
    }

    private static void owner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) throw new IllegalArgumentException("owner is blank");
    }
}
