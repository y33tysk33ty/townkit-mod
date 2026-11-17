package townkit.core.work.mine;

import com.alex.test.village.villagers.miner.OreHeights;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import com.alex.test.village.villagers.settings.SettingsSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import townkit.api.work.OreEntry;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkTable;

/**
 * The addon ores ({@link WorkTable} of kind {@value #KIND}, keyed by the ore's id) merged into one cached {@link View}.
 * Towntopia's own seven ores are not in it and are never changed: {@code OreHeights}, {@code OreBlocks} and its settings
 * enum stay as shipped. What the view answers is read by {@link MineHooks} (the miner's lucky rolls, the druid's
 * regrown rock, the mine choice) and {@link MineBridges} (the ore check), and the settings row by the settings
 * registry's hook ({@link #schema()}).
 *
 * <p>Nothing here needs a game (the unit tests run it with bare blocks), and the view doesn't load Towntopia's classes:
 * only the settings page ({@link #schema()}) does, when it is asked for. With no addon ore every question answers "no"
 * after one volatile read, and the schema is Towntopia's own object.
 *
 * <p>Chances are on the scale of Towntopia's placements: an addon ore's density at {@code y} is its weight spread over
 * its range ({@link OreEntry.Spread#at}); of a roll that produced an ore it takes {@code S / (T + S)}, where
 * {@code T} is the sum of Towntopia's ore densities at {@code y} and {@code S} the sum of the addon densities of the
 * roll's kind (the miner's lucky rolls, or the druid's regrown rock).
 */
public final class MiningOverlay {
    /** The data-file kind (folder {@code townkit/ores}). */
    public static final String KIND = "ores";
    /** The namespace Towntopia's seven ores' ids ({@code towntopia:coal} ...) live in. */
    public static final String TOWNTOPIA = "towntopia";
    /** Towntopia's seven ores' ids' paths ({@code OreHeights.Ore#id}). */
    public static final Set<String> TOWNTOPIA_PATHS = Set.of("coal", "copper", "iron", "lapis", "redstone", "gold", "diamond");
    private static final String OFF_LINE = "\nOFF: no wish for it.";

    /** Block ids and display names (the registry in game; the unit tests hand their own). */
    public interface BlockIds {
        ResourceLocation id(Block block);

        /** The block with this id, or null when there is none (air counts as none). */
        @Nullable
        Block byId(ResourceLocation id);

        /** The block's name for settings rows. */
        String name(Block block);
    }

    /** The game's blocks. */
    public static final BlockIds REGISTRY = new BlockIds() {
        @Override
        public ResourceLocation id(Block block) {
            return BuiltInRegistries.BLOCK.getKey(block);
        }

        @Nullable
        @Override
        public Block byId(ResourceLocation id) {
            Block b = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            return b == null || b instanceof AirBlock ? null : b;
        }

        @Override
        public String name(Block block) {
            return block.getName().getString();
        }
    };

    /** The running game's overlay. */
    public static final MiningOverlay GLOBAL = new MiningOverlay(new WorkTable<>(KIND, OreEntry::id), () -> MinerSettingsSchema.SCHEMA,
        REGISTRY, WorkLog.GLOBAL);

    /**
     * The merged state for one table version; immutable. {@code lucky}, {@code druid} and {@code steer} are the ores
     * with that opt-in, {@code blocks} every stone and deepslate block of every ore.
     */
    public record View(List<OreEntry> ores, OreEntry[] lucky, OreEntry[] druid, OreEntry[] steer, Block[] blocks) {
        /** Whether there is an addon ore. */
        public boolean touched() {
            return !this.ores.isEmpty();
        }

        /** Whether {@code block} is an addon ore's stone or deepslate block. */
        public boolean isAddonOre(Block block) {
            Block[] all = this.blocks;
            for (int i = 0; i < all.length; i++) {
                if (all[i] == block) return true;
            }
            return false;
        }

        /** The share of a roll that produced an ore, at {@code y}, that is an addon ore of the miner's lucky rolls. */
        public double luckyShare(double towntopia, int y) {
            return share(this.lucky, true, towntopia, y);
        }

        /** The share of the druid's regrown ore, at {@code y}, that is an addon ore. */
        public double druidShare(double towntopia, int y) {
            return share(this.druid, false, towntopia, y);
        }

        /**
         * The lucky-roll ore {@code r} (a draw in 0..1) picks at {@code y}, or null for Towntopia's own ore. Addon ores
         * share {@link #luckyShare} by their weights at {@code y}.
         */
        @Nullable
        public OreEntry pickLucky(double towntopia, int y, double r) {
            return pick(this.lucky, true, towntopia, y, r);
        }

        /** The druid ore {@code r} picks at {@code y}, or null for Towntopia's own ore. */
        @Nullable
        public OreEntry pickDruid(double towntopia, int y, double r) {
            return pick(this.druid, false, towntopia, y, r);
        }

        /** The block {@code ore} is placed or rolled as at {@code y}: deepslate below Y 0 when it has one. */
        public Block blockAt(OreEntry ore, int y) {
            return y < 0 && ore.deepslate() != null ? ore.deepslate() : ore.stone();
        }

        /**
         * What the villager's wanted addon ores add to Towntopia's fit of a mine at {@code y} (Towntopia adds up, per wanted
         * ore, how close the mine's depth is to the ore's best one: 0 to 1): 1 for each ore whose depths hold {@code y}.
         */
        public static double fit(OreEntry[] wanted, int y) {
            double f = 0;
            for (int i = 0; i < wanted.length; i++) {
                OreEntry.Depths d = wanted[i].depths();
                if (d != null && d.contains(y)) f += 1.0;
            }
            return f;
        }

        private static double density(OreEntry e, boolean lucky, int y) {
            OreEntry.Spread s = lucky ? e.luckyRolls() : e.druid();
            return s == null ? 0 : s.at(y);
        }

        private static double share(OreEntry[] list, boolean lucky, double towntopia, int y) {
            double s = 0;
            for (int i = 0; i < list.length; i++) s += density(list[i], lucky, y);
            return s <= 0 ? 0 : s / (Math.max(0, towntopia) + s);
        }

        @Nullable
        private static OreEntry pick(OreEntry[] list, boolean lucky, double towntopia, int y, double r) {
            double s = 0;
            for (int i = 0; i < list.length; i++) s += density(list[i], lucky, y);
            if (s <= 0) return null;
            double share = s / (Math.max(0, towntopia) + s);
            if (r >= share) return null;
            double target = r / share * s;
            double acc = 0;
            OreEntry last = null;
            for (int i = 0; i < list.length; i++) {
                double d = density(list[i], lucky, y);
                if (d <= 0) continue;
                last = list[i];
                acc += d;
                if (target < acc) return last;
            }
            return last;
        }
    }

    private final WorkTable<ResourceLocation, OreEntry> table;
    private final Supplier<SettingsSchema> schemaSource;
    private final BlockIds blocks;
    private final WorkLog log;
    @Nullable
    private volatile View view;
    private long viewVersion = -1;
    @Nullable
    private volatile SettingsSchema schema;
    private long schemaVersion = -1;
    private static final View NONE = new View(List.of(), new OreEntry[0], new OreEntry[0], new OreEntry[0], new Block[0]);

    public MiningOverlay(WorkTable<ResourceLocation, OreEntry> table, Supplier<SettingsSchema> schema, BlockIds blocks, WorkLog log) {
        this.table = table;
        this.schemaSource = schema;
        this.blocks = blocks;
        this.log = log;
    }

    public WorkTable<ResourceLocation, OreEntry> table() {
        return this.table;
    }

    /** Whether {@code id} names one of Towntopia's seven ores ({@code towntopia:coal} ...). */
    public static boolean isTowntopias(ResourceLocation id) {
        return TOWNTOPIA.equals(id.getNamespace()) && TOWNTOPIA_PATHS.contains(id.getPath());
    }

    /** Why {@code ore} can't be an addon ore (a Towntopia ore's id, or air as a block), or null. */
    @Nullable
    public static String problem(OreEntry ore) {
        if (isTowntopias(ore.id())) return ore.id() + " is one of Towntopia's own ores; they stay Towntopia's";
        if (ore.stone() instanceof AirBlock || ore.deepslate() instanceof AirBlock) return "minecraft:air can't be an ore";
        return null;
    }

    /**
     * Adds {@code ore}; one that can't be an ore ({@link #problem}) is logged once and registers nothing (a no-op handle).
     */
    public WorkTable.Handle add(String owner, OreEntry ore) {
        String problem = problem(ore);
        if (problem != null) {
            this.log.once("ore-skipped|" + owner + "|" + ore.id(), "[" + KIND + "] " + owner + "'s ore " + ore.id() + " is skipped: " + problem);
            return () -> { };
        }
        return this.table.add(owner, ore);
    }

    /**
     * Removes the ore {@code id} (another addon's, a data file's, or one added later: a removal wins), like a
     * {@code "remove"} data file. One of Towntopia's seven is skipped (logged once), as such a data file is.
     */
    public WorkTable.Handle remove(String owner, ResourceLocation id) {
        if (isTowntopias(id)) return skipped(owner, id, "remove");
        return this.table.remove(owner, id);
    }

    /**
     * Changes the ore {@code id} as it stands in merge order (waiting for it if nobody added it yet), like a
     * {@code "change"} data file. A change that answers another id, one of Towntopia's ids or an air block is skipped
     * when applied (logged once). One of Towntopia's seven is skipped (logged once), as such a data file is.
     */
    public WorkTable.Handle change(String owner, ResourceLocation id, UnaryOperator<OreEntry> change) {
        if (isTowntopias(id)) return skipped(owner, id, "change");
        return this.table.change(owner, id, ore -> {
            OreEntry next = change.apply(ore);
            if (next == null) return null;
            if (!next.id().equals(id)) throw new IllegalArgumentException("the change gives the ore the id " + next.id());
            String problem = problem(next);
            if (problem != null) throw new IllegalArgumentException(problem);
            return next;
        });
    }

    private WorkTable.Handle skipped(String owner, ResourceLocation id, String what) {
        this.log.once("ore-skipped-" + what + "|" + owner + "|" + id, "[" + KIND + "] " + owner + "'s " + what + " of " + id
            + " is skipped: it is one of Towntopia's own ores; they stay Towntopia's");
        return () -> { };
    }

    /** Whether any addon ore is registered. */
    public boolean touched() {
        return this.table.touched() && !view().ores.isEmpty();
    }

    /** The addon ores in merge order. */
    public List<OreEntry> ores() {
        return view().ores;
    }

    /** The merged view (cached per table version). */
    public View view() {
        View v = this.view;
        if (v != null && this.viewVersion == this.table.version()) return v;
        return rebuild();
    }

    private synchronized View rebuild() {
        long version = this.table.version();
        View cached = this.view;
        if (cached != null && this.viewVersion == version) return cached;
        List<OreEntry> ores = this.table.merged();
        View built;
        if (ores.isEmpty()) {
            built = NONE;
        } else {
            List<OreEntry> lucky = new ArrayList<>();
            List<OreEntry> druid = new ArrayList<>();
            List<OreEntry> steer = new ArrayList<>();
            List<Block> blocks = new ArrayList<>();
            for (OreEntry e : ores) {
                if (e.luckyRolls() != null) lucky.add(e);
                if (e.druid() != null) druid.add(e);
                if (e.depths() != null) steer.add(e);
                blocks.add(e.stone());
                if (e.deepslate() != null) blocks.add(e.deepslate());
            }
            built = new View(List.copyOf(ores), lucky.toArray(new OreEntry[0]), druid.toArray(new OreEntry[0]), steer.toArray(new OreEntry[0]),
                blocks.toArray(new Block[0]));
        }
        this.view = built;
        this.viewVersion = version;
        return built;
    }

    // --- the miner's settings page ------------------------------------------------------------------------------------

    /** The miner's settings schema with a "wanted ores" row per addon ore; Towntopia's own object when there is none. */
    public SettingsSchema schema() {
        View v = view();
        SettingsSchema base = this.schemaSource.get();
        if (v.ores.isEmpty()) return base;
        SettingsSchema s = this.schema;
        if (s != null && this.schemaVersion == this.viewVersion) return s;
        return mergeSchema(base, v);
    }

    /** The registry's answer for the miner: the merged page for {@code towntopia} (Towntopia's own object when no ore). */
    public SettingsSchema schemaFor(SettingsSchema towntopia) {
        return towntopia == null || view().ores.isEmpty() ? towntopia : schema();
    }

    private synchronized SettingsSchema mergeSchema(SettingsSchema base, View v) {
        if (this.schema != null && this.schemaVersion == this.viewVersion) return this.schema;
        List<SettingsSchema.Section> sections = new ArrayList<>();
        for (SettingsSchema.Section s : base.sections()) {
            List<SettingsSchema.Row> rows = new ArrayList<>();
            for (SettingsSchema.Row r : s.rows()) {
                rows.add(r.kind() == SettingsSchema.RowKind.ALLOW_LIST && r.settingId().equals(MinerSettingsSchema.WANTED_ORES) ? withOres(r, v) : r);
            }
            sections.add(new SettingsSchema.Section(s.title(), List.copyOf(rows)));
        }
        SettingsSchema merged = new SettingsSchema(base.profession(), base.tagName(), List.copyOf(sections));
        this.schema = merged;
        this.schemaVersion = this.viewVersion;
        return merged;
    }

    private SettingsSchema.Row withOres(SettingsSchema.Row r, View v) {
        List<SettingsSchema.AllowListEntry> entries = new ArrayList<>(r.allowListEntries());
        for (OreEntry e : v.ores) {
            String label = e.label() != null ? e.label().getString() : this.blocks.name(e.stone());
            OreEntry.Depths d = e.depths();
            String text = d == null ? "ON: nothing changes (this ore has no best depth)." + OFF_LINE
                : "ON: prefers your mine at the best depth for it.\nBest mine depth: Y " + d.low() + " to " + d.high() + "." + OFF_LINE;
            entries.add(new SettingsSchema.AllowListEntry(e.id().toString(), label, false, text));
        }
        return new SettingsSchema.Row(r.kind(), r.settingId(), r.label(), r.defaultBool(), r.defaultInt(), r.minInt(), r.maxInt(),
            r.stepInt(), r.tierNames(), List.copyOf(entries), r.description(), r.ticksAsSeconds(), r.keepsOne());
    }

    /** What Towntopia's ores add up to at {@code y} (its density arrays summed). */
    public static double towntopiaTotal(int y) {
        double t = 0;
        double[] d = OreHeights.densities(y);
        for (int i = 0; i < d.length; i++) t += d[i];
        return t;
    }
}
