package townkit.core.farm;

import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.economy.away.AwayFarm;
import com.alex.test.village.villagers.farmer.FarmerBalance;
import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import townkit.api.farm.AwayYield;
import townkit.api.farm.HarvestAction;
import townkit.api.farm.HarvestRule;
import townkit.api.hooks.HookCall;
import townkit.core.TownKit;
import townkit.core.work.WorkLog;

/**
 * Away catch-up: Towntopia's away farmer harvests a ripe plot in {@code FarmerAway$Shift#harvest(AwayFarm.Plot)}.
 * Before it, for a plot whose block a harvest rule holds:
 * <ul>
 *   <li>a block of the same crop directly above the plot skips it entirely (no harvest, no yield; it stays ripe until the
 *   village is loaded): the away model keeps an upper half only for the pitcher plant, so another two-block crop's halves
 *   can't be kept in step. Any rule, {@code Use} or {@code Break};</li>
 *   <li>a {@code Use} rule never breaks the crop: with an {@code away} yield the shift rolls {@code min..max} of the item
 *   into the away pack and the plot goes to the yield's {@code pickedState} (the plant stays); without one the plot stays
 *   ripe. The shift's own steps are used (its time budget, "no room", the pack, the away result, the farmer's work
 *   experience), through its private methods found once by name.</li>
 * </ul>
 * A {@code Break} rule's plot is left to Towntopia's own away harvest. When any of the shift's private parts can't be found
 * the away rules are off for the session (one log line) and Towntopia's own away harvest runs.
 *
 * @since 0.8.0
 */
final class AwayHarvest {
    /** The shift's private parts, found once; null when one is missing (then {@link #failed} is set). */
    private record Parts(Method blocked, Method depositShare, Method hoeSpeed, Method skill, Method spend, Method give, Method job,
                         Field ctx, Field settings) {
    }

    private static volatile Parts parts;
    private static volatile boolean failed;

    private AwayHarvest() {
    }

    /** Whether the shift's private parts were found (looks once, logs once when something is missing). */
    static boolean available() {
        if (failed) return false;
        if (parts != null) return true;
        return find() != null;
    }

    @Nullable
    private static synchronized Parts find() {
        if (parts != null) return parts;
        if (failed) return null;
        try {
            Class<?> shift = Class.forName("com.alex.test.village.economy.away.jobs.FarmerAway$Shift", false, AwayContext.class.getClassLoader());
            Parts p = new Parts(
                open(shift, "blocked", Map.class), open(shift, "depositShare", Map.class), open(shift, "hoeSpeed"), open(shift, "level"),
                open(shift, "spend", double.class), open(shift, "give", Map.class, boolean.class), open(shift, "job"), field(shift, "ctx"),
                field(shift, "settings"));
            parts = p;
            return p;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            WorkLog.GLOBAL.once("away-harvest|parts", "[farm_harvest] can't find the away farmer's harvest steps (" + e
                + "); harvest rules are not applied to the away catch-up, Towntopia's own away harvest runs");
            return null;
        }
    }

    private static Method open(Class<?> c, String name, Class<?>... types) throws ReflectiveOperationException {
        Method m = c.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m;
    }

    private static Field field(Class<?> c, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Switches the away rules off until the next {@code /reload}. */
    static void fail() {
        failed = true;
        parts = null;
    }

    static boolean failed() {
        return failed;
    }

    /** A {@code /reload} or the server's first data load: the away rules are on again (the steps are looked up afresh). */
    static void reloaded() {
        failed = false;
        parts = null;
    }

    /** Before {@code FarmerAway$Shift#harvest(AwayFarm.Plot)}. */
    static void beforeHarvest(HookCall call) {
        if (!(call.args()[0] instanceof AwayFarm.Plot plot)) return;
        BlockState state = plot.state();
        HarvestRule rule = HarvestTable.GLOBAL.ruleFor(state.getBlock());
        if (rule == null) return;
        Parts p = parts != null ? parts : find();
        if (p == null) return;
        try {
            CropDefinition crop = plot.crop();
            if (crop == null) return;
            Object shift = call.self();
            ServerLevel level = ((AwayContext) p.ctx.get(shift)).level;
            if (crop.matchesCropBlock(level.getBlockState(plot.pos.above()).getBlock())) {
                call.cancel();
                return;
            }
            if (!(rule.action() instanceof HarvestAction.Use) || UseHarvest.switchedOff(rule.id())) return;
            call.cancel();
            // Towntopia's own gate: a plot is worked only for a planting item the farmer's settings allow
            Item seed = crop.representativePlantingItem().orElse(null);
            if (seed == null || !((FarmerSettings) p.settings.get(shift)).isPlantEnabled(seed)) return;
            AwayYield yield = rule.away().orElse(null);
            if (yield == null) return;
            BlockState picked = pickedState(rule, yield, state);
            if (picked == null) return;
            int count = yield.min() == yield.max() ? yield.min() : yield.min() + level.getRandom().nextInt(yield.max() - yield.min() + 1);
            Map<Item, Double> drops = count > 0 ? Map.of(yield.item(), (double) count) : Map.of();
            if ((boolean) p.blocked.invoke(shift, drops)) return;
            double ticks = FarmerBalance.plantHarvestTicks((double) p.hoeSpeed.invoke(shift), (int) p.skill.invoke(shift))
                + (double) p.depositShare.invoke(shift, drops);
            if (!(boolean) p.spend.invoke(shift, ticks)) return;
            // the plot first: a later throw loses at most this yield, it can't be paid twice on the next day
            plot.plant(picked, crop);
            p.give.invoke(shift, drops, true);
            p.job.invoke(shift);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Towntopia's own methods threw or changed: switch the away rules off, its own harvest runs next time
            fail();
            WorkLog.GLOBAL.once("away-harvest|run", "[farm_harvest] the away harvest step failed (" + e
                + "); harvest rules are not applied to the away catch-up any more, Towntopia's own away harvest runs");
            TownKit.LOG.debug("[TownKit] away harvest failure", e);
        }
    }

    /** The block's state with the yield's picked properties set, or null (logged once) when one doesn't fit the block. */
    @Nullable
    private static BlockState pickedState(HarvestRule rule, AwayYield yield, BlockState state) {
        BlockState out = state;
        for (Map.Entry<String, String> e : yield.pickedState().entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(e.getKey());
            if (property == null) return badPicked(rule, state, "has no property '" + e.getKey() + "'");
            java.util.Optional<? extends Comparable<?>> value = property.getValue(e.getValue());
            if (value.isEmpty()) {
                return badPicked(rule, state, "has '" + e.getKey() + "', but '" + e.getValue() + "' is not one of its values "
                    + property.getPossibleValues());
            }
            out = with(out, property, value.get());
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState with(BlockState state, Property property, Comparable value) {
        return (BlockState) state.setValue(property, value);
    }

    @Nullable
    private static BlockState badPicked(HarvestRule rule, BlockState state, String why) {
        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        WorkLog.GLOBAL.once("away-picked|" + rule.id() + "|" + block, "[" + HarvestTable.KIND + "] rule " + rule.id()
            + ": its away picked_state doesn't fit " + block + " (" + block + " " + why + "); that plot gets no away yield and stays as it is");
        return null;
    }
}
