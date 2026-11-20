package townkit.core.farm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import townkit.api.farm.HarvestAction;
import townkit.api.farm.HarvestRule;
import townkit.api.farm.Holding;
import townkit.api.farm.Ripe;
import townkit.core.work.WorkLog;
import townkit.core.work.WorkTable;

/** {@link HarvestTable} without a game. */
class HarvestTableTest {
    private final HarvestFixture fx = new HarvestFixture();
    private final List<String> lines = new ArrayList<>();
    private Block bush;
    private Block other;
    private Block plain;
    private HarvestTable table;

    private static ResourceLocation id(String s) {
        return ResourceLocation.parse(s);
    }

    private static HarvestRule rule(String id, Ripe ripe, Block... blocks) {
        HarvestRule.Builder b = HarvestRule.builder(id(id)).ripe(ripe);
        for (Block block : blocks) b.block(block);
        return b.build();
    }

    @BeforeEach
    void setUp() {
        this.bush = this.fx.bush("mymod:bush");
        this.other = this.fx.bush("mymod:other_bush");
        this.plain = this.fx.block("mymod:plain");
        this.table = new HarvestTable(new WorkTable<>(HarvestTable.KIND, HarvestRule::id), this.fx.blockAccess(),
            new WorkLog(this.lines::add));
    }

    @Test
    void ruleForUnknownBlockNull() {
        assertNull(this.table.ruleFor(this.bush), "an empty table knows no block");
        assertFalse(this.table.hasRipeOverrides());
        this.table.table().add("a", rule("a:one", new Ripe.AtLeast("age", 3), this.bush));
        assertNull(this.table.ruleFor(this.other));
        assertNotNull(this.table.ruleFor(this.bush));
        assertTrue(this.table.hasRipeOverrides());
    }

    @Test
    void removalWins() {
        this.table.table().add("a", rule("a:one", new Ripe.AtLeast("age", 3), this.bush));
        assertNotNull(this.table.ruleFor(this.bush));
        WorkTable.Handle remove = this.table.table().remove("b", id("a:one"));
        assertNull(this.table.ruleFor(this.bush), "a removal wins over the add");
        assertTrue(this.table.entries().isEmpty());
        remove.remove();
        assertNotNull(this.table.ruleFor(this.bush));
    }

    @Test
    void defaultRipeRuleIsFoundButOverridesNothing() {
        HarvestRule use = HarvestRule.builder(id("a:use")).block(this.bush).action(new HarvestAction.Use(Holding.SHEARS)).build();
        this.table.table().add("a", use);
        assertSame(use, this.table.ruleFor(this.bush));
        assertFalse(this.table.hasRipeOverrides(), "Towntopia's ripe rule stays, so no ripe hook is needed");
    }

    @Test
    void addChangeAndMergeOrder() {
        this.table.table().add("a", rule("a:one", new Ripe.AtLeast("age", 3), this.bush));
        this.table.table().add("a", rule("a:two", new Ripe.AtLeast("age", 2), this.other));
        this.table.table().change("b", id("a:one"), r -> r.toBuilder().ripe(new Ripe.AtLeast("age", 2)).build());
        assertEquals(List.of(id("a:one"), id("a:two")), this.table.entries().stream().map(HarvestRule::id).toList());
        assertEquals(new Ripe.AtLeast("age", 2), this.table.ruleFor(this.bush).ripe());
    }

    @Test
    void tagMembersAreMatchedAndRebuiltOnTagsChanged() {
        TagKey<Block> tag = TagKey.create(Registries.BLOCK, id("mymod:bushes"));
        this.table.table().add("a", HarvestRule.builder(id("a:tagged")).blockTag(tag).ripe(new Ripe.AtLeast("age", 3)).build());
        assertNull(this.table.ruleFor(this.bush), "the tag is empty (not loaded yet)");
        this.fx.tag("mymod:bushes", this.bush, this.other);
        this.table.tagsChanged();
        assertNotNull(this.table.ruleFor(this.bush));
        assertNotNull(this.table.ruleFor(this.other));
        assertNull(this.table.ruleFor(this.plain));
    }

    @Test
    void ripePropertyMissingEverywhereDropsTheRule() {
        this.table.table().add("a", rule("a:bad", new Ripe.AtLeast("ripeness", 1), this.bush, this.other));
        this.table.table().add("a", rule("a:badvalue", new Ripe.AtLeast("age", 9), this.plain));
        this.table.table().add("a", rule("a:bad2", new Ripe.Is("age", "banana"), this.bush));
        assertNull(this.table.ruleFor(this.bush));
        assertNull(this.table.ruleFor(this.other));
        assertTrue(this.lines.isEmpty(), "nothing is logged while the index is built: " + this.lines);
        this.table.report();
        assertEquals(3, this.lines.size(), this.lines.toString());
        assertTrue(this.lines.stream().anyMatch(l -> l.contains("a:bad") && l.contains("ripeness")), this.lines.toString());
        this.table.report();
        assertEquals(3, this.lines.size(), "each is logged once");
    }

    @Test
    void partlyValidTagRuleKeepsTheBlocksThatHaveTheProperty() {
        TagKey<Block> tag = TagKey.create(Registries.BLOCK, id("mymod:mixed"));
        this.fx.tag("mymod:mixed", this.bush, this.plain);
        this.table.table().add("a", HarvestRule.builder(id("a:mixed")).blockTag(tag).ripe(new Ripe.AtLeast("age", 3)).build());
        assertNotNull(this.table.ruleFor(this.bush));
        assertNull(this.table.ruleFor(this.plain), "the block without the property is left out");
        this.table.report();
        assertEquals(1, this.lines.size(), this.lines.toString());
    }

    @Test
    void firstRuleWinsAClash() {
        HarvestRule first = rule("a:one", new Ripe.AtLeast("age", 3), this.bush);
        this.table.table().add("a", first);
        this.table.table().add("b", rule("b:two", new Ripe.AtLeast("age", 2), this.bush));
        assertSame(first, this.table.ruleFor(this.bush));
        this.table.report();
        assertEquals(1, this.lines.size(), this.lines.toString());
        assertTrue(this.lines.get(0).contains("a:one") && this.lines.get(0).contains("b:two"), this.lines.get(0));
    }
}
