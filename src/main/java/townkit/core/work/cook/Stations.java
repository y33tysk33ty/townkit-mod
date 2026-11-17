package townkit.core.work.cook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import townkit.api.work.CookStation;
import townkit.api.work.StationContext;
import townkit.api.work.StationHandler;
import townkit.core.TownKit;
import townkit.core.work.WorkTable;

/**
 * Addon cook stations ({@link WorkTable} of kind {@value #KIND}) and the station blocks found in each kitchen.
 *
 * <p>A kitchen's blocks come from {@link #scan}: TownKit scans the kitchen room whenever Towntopia does (its
 * {@code scanFunctionalBlocks}, run on every block change in the room and by the rolling revalidation), and once more
 * on first use after the station list changed. A scan keeps the positions whose block matched some station then;
 * answers check them against the stations as they are now. Kitchens are any objects (Towntopia's
 * {@code KitchenBuilding} in the game), held weakly.
 *
 * <p>Handlers run through {@link #started}/{@link #stopped}: an exception is logged once per owner, the cook goes on,
 * and ten failures in a row switch that station's handler off.
 */
public final class Stations {
    public static final String KIND = "cook_stations";
    static final int MAX_FAILURES = 10;

    /** Block ids and tags (the registries in the game; a table in unit tests). */
    public interface Blocks {
        ResourceLocation id(Block block);

        @Nullable
        Block byId(ResourceLocation id);

        boolean is(Block block, TagKey<Block> tag);
    }

    public static final Blocks REGISTRY = new Blocks() {
        @Override
        public ResourceLocation id(Block block) {
            return BuiltInRegistries.BLOCK.getKey(block);
        }

        @Nullable
        @Override
        public Block byId(ResourceLocation id) {
            return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        }

        @Override
        public boolean is(Block block, TagKey<Block> tag) {
            return block.builtInRegistryHolder().is(tag);
        }
    };

    /** A registered station: its id, its blocks and handler, and the handler's run of failures. */
    public static final class Def {
        private final String owner;
        private final ResourceLocation id;
        private final CookStation station;
        private int failures;
        private volatile boolean off;

        public Def(String owner, ResourceLocation id, CookStation station) {
            this.owner = Objects.requireNonNull(owner, "owner");
            this.id = Objects.requireNonNull(id, "id");
            this.station = Objects.requireNonNull(station, "station");
        }

        public String owner() {
            return this.owner;
        }

        public ResourceLocation id() {
            return this.id;
        }

        public CookStation station() {
            return this.station;
        }

        /** True once the handler was switched off after {@value Stations#MAX_FAILURES} failures in a row. */
        public boolean off() {
            return this.off;
        }

        @Override
        public String toString() {
            return "station " + this.id + " (" + this.owner + ")";
        }
    }

    /** A registration: the table handle and the def (its {@link Def#off()} is the subscription's disabled state). */
    public record Registration(WorkTable.Handle handle, Def def) {
    }

    /** A kitchen's scan: the station-table version it was made for, and the matching blocks found then. */
    private record Scan(long version, Map<BlockPos, Block> found) {
    }

    /** The running game's stations. */
    public static final Stations GLOBAL = new Stations(new WorkTable<>(KIND, Def::id), REGISTRY,
        (message, t) -> TownKit.LOG.error("[TownKit] {}", message, t));

    private final WorkTable<ResourceLocation, Def> table;
    private final Blocks blocks;
    private final BiConsumer<String, Throwable> errors;
    private final Map<Object, Scan> scans = new WeakHashMap<>();
    private final Set<String> loggedOwners = ConcurrentHashMap.newKeySet();

    /** {@code errors} gets a log line and the exception for a handler's first failure per owner and every switch-off. */
    public Stations(WorkTable<ResourceLocation, Def> table, Blocks blocks, BiConsumer<String, Throwable> errors) {
        this.table = table;
        this.blocks = blocks;
        this.errors = errors;
    }

    public WorkTable<ResourceLocation, Def> table() {
        return this.table;
    }

    public Registration add(String owner, ResourceLocation id, CookStation station) {
        Def def = new Def(owner, id, station);
        return new Registration(this.table.add(owner, def), def);
    }

    /** Removes the station {@code id} (a removal wins over every add), like a {@code "remove"} data file. */
    public WorkTable.Handle remove(String owner, ResourceLocation id) {
        return this.table.remove(owner, id);
    }

    /** The station with {@code id} as it is now, or null. */
    @Nullable
    public Def get(ResourceLocation id) {
        return this.table.get(id);
    }

    public boolean isEmpty() {
        return this.table.merged().isEmpty();
    }

    public boolean matches(Def def, Block block) {
        CookStation s = def.station;
        return s.block() != null ? s.block() == block : this.blocks.is(block, Objects.requireNonNull(s.blocks()));
    }

    /** Whether {@code block} is some station's block now. */
    public boolean isStationBlock(Block block) {
        for (Def d : this.table.merged()) {
            if (matches(d, block)) return true;
        }
        return false;
    }

    // --- kitchens --------------------------------------------------------------------------------------------------

    /** Records {@code kitchen}'s station blocks: of {@code room}, those whose block ({@code blockAt}) is a station's. */
    public void scan(Object kitchen, Iterable<BlockPos> room, Function<BlockPos, Block> blockAt) {
        long version = this.table.version();
        Map<BlockPos, Block> found = new LinkedHashMap<>();
        if (!isEmpty()) {
            for (BlockPos pos : room) {
                Block b = blockAt.apply(pos);
                if (b != null && isStationBlock(b)) found.put(pos.immutable(), b);
            }
        }
        synchronized (this.scans) {
            this.scans.put(kitchen, new Scan(version, Collections.unmodifiableMap(found)));
        }
    }

    /** True when {@code kitchen} was scanned since the station list last changed. */
    public boolean scanned(Object kitchen) {
        Scan s = scan(kitchen);
        return s != null && s.version == this.table.version();
    }

    /** The blocks of station {@code id} in {@code kitchens} (by their last scans), in kitchen order. */
    public List<BlockPos> positions(Iterable<?> kitchens, ResourceLocation id) {
        Def def = get(id);
        if (def == null) return List.of();
        List<BlockPos> out = new ArrayList<>();
        for (Object k : kitchens) {
            Scan s = scan(k);
            if (s == null) continue;
            s.found.forEach((pos, block) -> {
                if (matches(def, block)) out.add(pos);
            });
        }
        return out;
    }

    /** The kitchen of {@code kitchens} that holds a station block at {@code pos}, or null. */
    @Nullable
    public Object kitchenOf(Iterable<?> kitchens, BlockPos pos) {
        for (Object k : kitchens) {
            Scan s = scan(k);
            Block b = s == null ? null : s.found.get(pos);
            if (b != null && isStationBlock(b)) return k;
        }
        return null;
    }

    /**
     * Whether a dish with {@code station} may be made in {@code kitchens}: always for none, never for a station nobody
     * registered, else when some kitchen holds one of its blocks.
     */
    public boolean candidate(@Nullable ResourceLocation station, Iterable<?> kitchens) {
        return station == null || !positions(kitchens, station).isEmpty();
    }

    @Nullable
    private Scan scan(Object kitchen) {
        synchronized (this.scans) {
            return this.scans.get(kitchen);
        }
    }

    // --- open batches ----------------------------------------------------------------------------------------------

    /** A batch whose handler heard {@code started} and will hear {@code stopped} exactly once. */
    public record Batch(Def def, StationContext ctx) {
    }

    /**
     * Open batches by key (the cook's activity in the game), weak so a dropped key goes. Every way out of a batch goes
     * through one atomic remove, so {@code stopped} fires once even if two ways happen (the cook stopped and unloaded).
     */
    private final Map<Object, Batch> open = new WeakHashMap<>();

    /** Opens a batch for {@code key} and calls {@code started} (a batch still open for the key is stopped first). */
    public void start(Object key, Def def, StationContext ctx) {
        stop(key);
        Batch b = new Batch(def, ctx);
        synchronized (this.open) {
            this.open.put(key, b);
        }
        started(def, ctx);
    }

    /** The open batch of {@code key}, or null. */
    @Nullable
    public Batch openBatch(Object key) {
        synchronized (this.open) {
            return this.open.isEmpty() ? null : this.open.get(key);
        }
    }

    /** Closes {@code key}'s batch, calling {@code stopped}; false when none was open. */
    public boolean stop(Object key) {
        Batch b;
        synchronized (this.open) {
            if (this.open.isEmpty()) return false;
            b = this.open.remove(key);
        }
        if (b == null) return false;
        stopped(b.def, b.ctx);
        return true;
    }

    /** Closes every open batch {@code which} picks (e.g. a cook that left its level); returns how many. */
    public int stopWhere(java.util.function.Predicate<StationContext> which) {
        List<Batch> out = new ArrayList<>();
        synchronized (this.open) {
            if (this.open.isEmpty()) return 0;
            for (var it = this.open.values().iterator(); it.hasNext(); ) {
                Batch b = it.next();
                if (which.test(b.ctx)) {
                    out.add(b);
                    it.remove();
                }
            }
        }
        for (Batch b : out) stopped(b.def, b.ctx);
        return out.size();
    }

    /** Closes every open batch (the server stops): no entry keeps a level alive after its world closes. */
    public int stopAll() {
        return stopWhere(ctx -> true);
    }

    public int openCount() {
        synchronized (this.open) {
            return this.open.size();
        }
    }

    // --- handlers --------------------------------------------------------------------------------------------------

    public void started(Def def, StationContext ctx) {
        call(def, h -> h.started(ctx));
    }

    public void stopped(Def def, StationContext ctx) {
        call(def, h -> h.stopped(ctx));
    }

    private void call(Def def, Consumer<StationHandler> call) {
        StationHandler h = def.station.handler();
        if (h == null || def.off) return;
        try {
            call.accept(h);
            def.failures = 0;
        } catch (Throwable t) { // like the hook listeners: an addon's Error must not reach the cook's tick
            def.failures++;
            if (this.loggedOwners.add(def.owner)) {
                this.errors.accept("cook station handler from '" + def.owner + "' failed (logged once per owner; "
                    + MAX_FAILURES + " failures in a row switch it off)", t);
            }
            if (def.failures >= MAX_FAILURES) {
                def.off = true;
                this.errors.accept("cook station handler of " + def + " switched off after " + MAX_FAILURES
                    + " failures in a row", t);
            }
        }
    }
}
