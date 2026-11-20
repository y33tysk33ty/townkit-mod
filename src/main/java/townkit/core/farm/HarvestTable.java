package townkit.core.farm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import townkit.api.farm.BlockRef;
import townkit.api.farm.HarvestRule;
import townkit.api.farm.Ripe;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkTable;

/**
 * The harvest rules: a {@link WorkTable} of kind {@value #KIND} keyed by the rule's id, and a lookup from a block to
 * the rule that holds it. The lookup is built lazily from the merged rules (and again when the table or the block tags
 * change), so {@link #ruleFor} is one map read, and a static read of an empty map while no rule exists.
 *
 * <p>A rule's ripe property is checked per block when the lookup is built: a block without the property, or a value outside
 * its range, is left out; a rule with no block left is dropped. Problems are noted while building (tags may not be loaded
 * yet) and logged once by {@link #report()}, which is called when tags are bound (server started, after a reload).
 * When two rules hold the same block the first one in merge order keeps it.
 *
 * @since 0.8.0
 */
public final class HarvestTable {
    /** The data-file kind (folder {@code townkit/farm_harvest}). */
    public static final String KIND = "farm_harvest";

    /** The running game's table. */
    public static final HarvestTable GLOBAL = new HarvestTable(new WorkTable<>(KIND, HarvestRule::id), HarvestBlocks.REGISTRY, WorkLog.GLOBAL);

    /** A rule as one block sees it: with the ripe property found on that block, ready to answer without allocating. */
    public static final class Resolved {
        private enum Mode { TOWNTOPIA, AT_LEAST, IS }

        private final HarvestRule rule;
        private final Mode mode;
        @Nullable
        private final Property<?> property;
        @Nullable
        private final IntegerProperty integer;
        private final int atLeast;
        private final int highest;
        @Nullable
        private final Object is;

        private Resolved(HarvestRule rule, Mode mode, @Nullable Property<?> property, int atLeast, int highest, @Nullable Object is) {
            this.rule = rule;
            this.mode = mode;
            this.property = property;
            this.integer = property instanceof IntegerProperty ip ? ip : null;
            this.atLeast = atLeast;
            this.highest = highest;
            this.is = is;
        }

        public HarvestRule rule() {
            return this.rule;
        }

        /** Whether the rule replaces Towntopia's own ripe rule (it names a property). */
        public boolean overridesRipe() {
            return this.mode != Mode.TOWNTOPIA;
        }

        /** The rule's ripe answer for a state of this block; only meaningful when {@link #overridesRipe()}. */
        public boolean ripe(BlockState state) {
            if (this.mode == Mode.AT_LEAST) return state.getValue(this.integer) >= this.atLeast;
            return same(state, this.property, this.is);
        }

        /** Towntopia's "full bush" for this rule's block: the ripe property is at its highest (or, not a number, ripe). */
        public boolean full(BlockState state) {
            if (this.integer != null) return state.getValue(this.integer) >= this.highest;
            return ripe(state);
        }

        private static <T extends Comparable<T>> boolean same(BlockState state, Property<T> property, @Nullable Object value) {
            return state.getValue(property).equals(value);
        }
    }

    /** A rule with the blocks it holds after checks (for the after-start report). */
    public record Held(HarvestRule rule, List<Block> blocks) {
    }

    private record Index(long version, Map<Block, Resolved> byBlock, boolean ripeOverrides, List<Held> held, List<String[]> notes) {
    }

    private final WorkTable<net.minecraft.resources.ResourceLocation, HarvestRule> table;
    private final HarvestBlocks blocks;
    private final WorkLog log;
    private volatile Index index = new Index(-1, Map.of(), false, List.of(), List.of());

    HarvestTable(WorkTable<net.minecraft.resources.ResourceLocation, HarvestRule> table, HarvestBlocks blocks, WorkLog log) {
        this.table = table;
        this.blocks = blocks;
        this.log = log;
    }

    public WorkTable<net.minecraft.resources.ResourceLocation, HarvestRule> table() {
        return this.table;
    }

    /** The merged rules in merge order; immutable. */
    public List<HarvestRule> entries() {
        return this.table.merged();
    }

    /** The rule holding {@code block}, or null. One map read; a static read while no rule holds any block. */
    @Nullable
    public HarvestRule ruleFor(Block block) {
        Resolved r = resolved(block);
        return r == null ? null : r.rule;
    }

    /** The rule holding {@code block} with its checked ripe property, or null. */
    @Nullable
    public Resolved resolved(Block block) {
        Map<Block, Resolved> map = index().byBlock;
        return map.isEmpty() ? null : map.get(block);
    }

    /** Whether any rule replaces Towntopia's ripe rule (the ripe hooks are needed). */
    public boolean hasRipeOverrides() {
        return index().ripeOverrides;
    }

    /** Whether any rule holds a block. */
    public boolean holdsAnything() {
        return !index().byBlock.isEmpty();
    }

    /** The rules with the blocks they hold, in merge order. */
    public List<Held> held() {
        return index().held;
    }

    /** The server's block tags were (re)loaded: rules naming a tag are matched again. */
    public void tagsChanged() {
        for (HarvestRule r : this.table.merged()) {
            for (BlockRef ref : r.blocks()) {
                if (ref.tag() != null) {
                    this.table.bump();
                    return;
                }
            }
        }
    }

    /** Logs (once each) what the checks found. Call when the server's tags are loaded. */
    public void report() {
        this.table.report();
        for (String[] n : index().notes) this.log.once(n[0], n[1]);
    }

    /**
     * Why {@code rule} can't hold any of {@code blocks} (its ripe property missing or out of range on every one of them),
     * or null when it can (or nothing is known yet).
     */
    @Nullable
    public static String problem(HarvestRule rule, Collection<Block> blocks, HarvestBlocks access) {
        if (blocks.isEmpty() || rule.ripe() instanceof Ripe.Towntopia) return null;
        List<String> whys = new ArrayList<>();
        for (Block b : blocks) {
            String why = check(rule, b, access, null);
            if (why == null) return null;
            whys.add(access.id(b) + " " + why);
        }
        return String.join("; ", whys);
    }

    private Index index() {
        Index i = this.index;
        return i.version == this.table.version() ? i : rebuild();
    }

    private synchronized Index rebuild() {
        long version = this.table.version();
        Index current = this.index;
        if (current.version == version) return current;
        Map<Block, Resolved> by = new java.util.HashMap<>();
        Map<Block, HarvestRule> owner = new java.util.HashMap<>();
        List<Held> held = new ArrayList<>();
        List<String[]> notes = new ArrayList<>();
        boolean overrides = false;
        for (HarvestRule rule : this.table.merged()) {
            Set<Block> matched = new LinkedHashSet<>();
            for (BlockRef ref : rule.blocks()) {
                if (ref.block() != null) matched.add(ref.block());
                else this.blocks.forEachInTag(ref.tag(), matched::add);
            }
            String name = rule.id().toString();
            if (matched.isEmpty()) {
                notes.add(new String[] {"harvest-nomatch|" + name, "[" + KIND + "] rule " + name
                    + " matches no block (an empty or unknown block tag?); it does nothing"});
                continue;
            }
            List<Block> kept = new ArrayList<>();
            List<String> left = new ArrayList<>();
            for (Block b : matched) {
                Resolved[] out = new Resolved[1];
                String why = check(rule, b, this.blocks, out);
                if (why != null) {
                    left.add(this.blocks.id(b) + " " + why);
                    continue;
                }
                HarvestRule first = owner.putIfAbsent(b, rule);
                if (first != null) {
                    notes.add(new String[] {"harvest-clash|" + first.id() + "|" + name + "|" + this.blocks.id(b), "[" + KIND
                        + "] rules " + first.id() + " and " + name + " both hold " + this.blocks.id(b) + "; " + first.id() + " keeps it"});
                    continue;
                }
                by.put(b, out[0]);
                kept.add(b);
                if (out[0].overridesRipe()) overrides = true;
            }
            if (!left.isEmpty()) {
                notes.add(new String[] {"harvest-left|" + name, "[" + KIND + "] rule " + name + (kept.isEmpty() ? " skipped: " : " leaves out: ")
                    + String.join("; ", left)});
            }
            if (!kept.isEmpty()) held.add(new Held(rule, Collections.unmodifiableList(kept)));
        }
        Index next = new Index(version, Collections.unmodifiableMap(by), overrides, List.copyOf(held), List.copyOf(notes));
        this.index = next;
        return next;
    }

    /** Null when the rule's ripe rule is fine on {@code block} (and {@code out[0]} is set if given), else why not. */
    @Nullable
    private static String check(HarvestRule rule, Block block, HarvestBlocks access, @Nullable Resolved[] out) {
        Ripe ripe = rule.ripe();
        if (ripe instanceof Ripe.Towntopia) {
            if (out != null) out[0] = new Resolved(rule, Resolved.Mode.TOWNTOPIA, null, 0, 0, null);
            return null;
        }
        String name = ripe instanceof Ripe.AtLeast a ? a.property() : ((Ripe.Is) ripe).property();
        Property<?> p = access.property(block, name);
        if (p == null) return "has no property '" + name + "'";
        if (ripe instanceof Ripe.AtLeast a) {
            if (!(p instanceof IntegerProperty ip)) return "has '" + name + "', but it is not a whole-number property";
            int min = Collections.min(ip.getPossibleValues());
            int max = Collections.max(ip.getPossibleValues());
            if (a.value() < min || a.value() > max) return "has '" + name + "' only from " + min + " to " + max + ", not " + a.value();
            if (out != null) out[0] = new Resolved(rule, Resolved.Mode.AT_LEAST, p, a.value(), max, null);
            return null;
        }
        String value = ((Ripe.Is) ripe).value();
        Optional<?> parsed = p.getValue(value);
        if (parsed.isEmpty()) return "has '" + name + "', but '" + value + "' is not one of its values " + p.getPossibleValues();
        int max = p instanceof IntegerProperty ip ? Collections.max(ip.getPossibleValues()) : 0;
        if (out != null) out[0] = new Resolved(rule, Resolved.Mode.IS, p, 0, max, parsed.get());
        return null;
    }
}
