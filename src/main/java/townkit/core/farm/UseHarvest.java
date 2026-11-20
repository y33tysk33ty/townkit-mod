package townkit.core.farm;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.farmer.FarmerAxeUtils;
import com.alex.test.village.villagers.farmer.FarmerBalance;
import com.alex.test.village.villagers.farmer.FarmerManager;
import com.alex.test.village.villagers.farmer.FarmerShearsUtils;
import com.alex.test.village.villagers.farmer.behavior.FarmerActivity;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import townkit.api.farm.HarvestAction;
import townkit.api.farm.HarvestRule;
import townkit.api.farm.Holding;
import townkit.api.hooks.HookCall;
import townkit.core.TownKit;
import townkit.core.work.WorkLog;

/**
 * Use-harvest: the farmer picks a crop the way a player right-clicks it, and the plant stays.
 *
 * <p>A NeoForge {@link FakePlayer} per level stands at the farmer's place with a copy of her tool for the rule's
 * {@link Holding} in its main hand (or nothing) and clicks the top face of the block: {@code BlockState.useItemOn}, then,
 * when that passes to the default interaction, {@code useWithoutItem}, as a player's click goes; both are called directly
 * (no {@code PlayerInteractEvent}, so claim guards and other mods' click handlers are not involved). Items the click
 * drops within one block of the crop are caught ({@link DropCapture}) and, with anything the click handed the stand-in
 * player, go through {@code VillageItemOwnership.produce} into the farmer's pack; what doesn't fit is popped as Towntopia
 * does ({@code ItemLossLog.pop}). Wear on the copy is written back to her tool. A pick that worked rolls Towntopia's skill
 * bonus: the first caught item Towntopia's bonus list (or the bonus-produce tag) accepts, at
 * {@code FarmerBalance.extraFoodChance(skill)}. (A broken crop is Towntopia's own roll, made on the drops of the main block.)
 *
 * <p>A pick worked when the click consumed the action and the block is still there. A click that passed, or a block gone
 * afterwards, counts against the rule; ten in a row switch the rule off worldwide until the next {@code /reload} or server
 * start (logged once). A missing tool never counts: the job fails the way Towntopia's own tool jobs fail.
 *
 * <p>The hooks (attached by {@link FarmHooks} only while some rule has a {@code Use} action) replace Towntopia's
 * {@code harvestCrop} (skipping its farmland-to-dirt roll), {@code pickBerries}, {@code pickGlowBerries} and
 * {@code harvestPod} for a rule's block; the pick steps keep Towntopia's shears check and shears wear. A {@code Break}
 * rule's block is broken by Towntopia's own {@code harvestBlock}, run inside a capture, so what neighbouring parts of the
 * crop drop when it goes ends up in the pack, not on the ground.
 *
 * @since 0.8.0
 */
public final class UseHarvest {
    /** How a use-harvest went. */
    public enum Result {
        /** Picked: the click consumed the action and the plant is still there. */
        PICKED,
        /** The farmer has no tool for the rule's {@link Holding}; not counted against the rule. */
        NO_TOOL,
        /** The block ignored the click (it passed). */
        BLOCK_REFUSED,
        /** The block is not the rule's any more (before or after the click). */
        BLOCK_GONE
    }

    /** Bad answers in a row that switch a rule off. */
    public static final int SWITCH_OFF_AFTER = 10;

    static final Failures FAILURES = new Failures(WorkLog.GLOBAL);
    private static final GameProfile PROFILE = new GameProfile(UUID.fromString("6f1a3c5e-7477-4b8d-9a10-746f776e6b69"), "[TownKit farmer]");

    /** Break captures opened by the {@code harvestBlock} before-hook and not yet closed by its after-hook (server thread). */
    private static final java.util.ArrayDeque<BreakCapture> BREAKS = new java.util.ArrayDeque<>();
    @Nullable
    private static volatile Method bonusMethod;
    private static volatile boolean reflectionTried;

    private UseHarvest() {
    }

    /** What one pick did, for the hooks. */
    private record Outcome(Result result, int picked, boolean toolWorn) {
    }

    /**
     * Picks the crop at {@code pos} by {@code rule} (which must have a {@link HarvestAction.Use} action) for {@code farmer}
     * and counts the answer toward the rule's switch-off. Server thread only.
     */
    public static Result run(ServerLevel level, NpcEntity farmer, VillageManager mgr, BlockPos pos, HarvestRule rule) {
        Result r = pick(level, farmer, mgr, pos, rule).result;
        FAILURES.record(rule.id(), r);
        return r;
    }

    /** Whether the rule was switched off after ten bad answers in a row (until the next reload or server start). */
    public static boolean switchedOff(ResourceLocation rule) {
        return FAILURES.off(rule);
    }

    /** A {@code /reload} or a server start: every rule is on again and every count starts over. */
    static void reloaded() {
        FAILURES.reset();
    }

    /** The server is stopping: a break capture still open would keep its level into the next world. */
    static void serverStopping() {
        releaseStale();
    }

    private static Outcome pick(ServerLevel level, NpcEntity farmer, VillageManager mgr, BlockPos pos, HarvestRule rule) {
        if (!(rule.action() instanceof HarvestAction.Use use)) throw new IllegalArgumentException("rule " + rule.id() + " is not a use rule");
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        HarvestRule holder = HarvestTable.GLOBAL.ruleFor(block);
        if (holder == null || !holder.id().equals(rule.id())) return new Outcome(Result.BLOCK_GONE, 0, false);
        InventoryManager inventory = farmer.getInventory();
        int slot = toolSlot(farmer, use.holding());
        if (use.holding() != Holding.EMPTY_HAND && slot < 0) return new Outcome(Result.NO_TOOL, 0, false);
        ItemStack tool = slot < 0 ? ItemStack.EMPTY : inventory.getSlot(slot);
        ItemStack held = tool.copy();

        FakePlayer fake = FakePlayerFactory.get(level, PROFILE);
        fake.getInventory().clearContent();
        Vec3 face = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        fake.moveTo(farmer.getX(), farmer.getY(), farmer.getZ(), farmer.getYRot(), farmer.getXRot());
        fake.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, face);
        fake.setItemInHand(InteractionHand.MAIN_HAND, held);
        BlockHitResult hit = new BlockHitResult(face, Direction.UP, pos, false);
        boolean[] consumed = new boolean[1];
        List<ItemStack> picked = new ArrayList<>();
        ItemStack handAfter;
        boolean clicked = false;
        try {
            picked.addAll(DropCapture.during(level, new AABB(pos).inflate(1), () -> {
                ItemInteractionResult r = state.useItemOn(fake.getMainHandItem(), level, fake, InteractionHand.MAIN_HAND, hit);
                if (r.consumesAction()) {
                    consumed[0] = true;
                } else if (r == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                    consumed[0] = state.useWithoutItem(level, fake, hit).consumesAction();
                }
            }));
            clicked = true;
        } finally {
            handAfter = fake.getMainHandItem();
            fake.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            // anything the click handed the stand-in player (some blocks give produce straight to the player)
            for (int i = 0; i < fake.getInventory().getContainerSize(); i++) {
                ItemStack s = fake.getInventory().getItem(i);
                if (!s.isEmpty()) picked.add(s.copy());
            }
            fake.getInventory().clearContent();
            // the click threw: what it handed the stand-in player is still in the world's items, never deleted
            if (!clicked) popStray(level, pos, picked);
        }

        boolean worn = false;
        Result result;
        int count;
        Activity.Context ctx;
        boolean handedOver = false;
        try {
            if (slot >= 0) {
                if (handAfter.isEmpty()) {
                    inventory.reduceSlot(slot, tool.getCount()); // the tool broke on the click
                    worn = true;
                } else if (handAfter.is(tool.getItem())) {
                    if (!ItemStack.matches(tool, handAfter)) {
                        worn = handAfter.getDamageValue() != tool.getDamageValue();
                        inventory.setSlot(slot, handAfter.copy());
                    }
                } else {
                    picked.add(handAfter.copy()); // the click turned the tool into something else: that is what came back
                    inventory.reduceSlot(slot, tool.getCount());
                    worn = true;
                }
            } else if (!handAfter.isEmpty()) {
                picked.add(handAfter.copy());
            }

            result = !level.getBlockState(pos).is(block) ? Result.BLOCK_GONE : consumed[0] ? Result.PICKED : Result.BLOCK_REFUSED;
            VillagerModel model = mgr.getModel(farmer.getNpcId());
            ctx = model == null ? null : new Activity.Context(model.brain(), model, mgr, level, farmer);
            if (result == Result.PICKED && !picked.isEmpty()) {
                Item bonus = bonusItem(picked);
                int skill = model == null ? 0 : model.brain().farmerSkillLevel();
                if (bonus != null && level.random.nextDouble() < FarmerBalance.extraFoodChance(skill)) picked.add(new ItemStack(bonus));
            }
            count = 0;
            for (ItemStack s : picked) count += s.getCount();
            handedOver = true;
            toPack(level, farmer, mgr, ctx, pos, picked, "use-harvest, pack full");
            if (ctx != null && result == Result.PICKED) {
                ctx.brain().diary().log(VillagerDiary.ts(level) + " FARM picked " + count + " from "
                    + block.getName().getString() + " at " + pos.toShortString());
            }
        } catch (RuntimeException e) {
            // a step between the click and the pack threw: what was picked is still dropped, never deleted
            if (!handedOver) popStray(level, pos, picked);
            throw e;
        }
        return new Outcome(result, count, worn);
    }

    /** The farmer's tool slot for {@code holding} (-1: none, or nothing needed), found the way Towntopia's farmer finds it. */
    private static int toolSlot(NpcEntity farmer, Holding holding) {
        return switch (holding) {
            case EMPTY_HAND -> -1;
            case SHEARS -> shearsSlot(farmer);
            case AXE -> FarmerAxeUtils.findBestUsableAxeSlot(farmer.getInventory(), FarmerSettings.of(farmer));
        };
    }

    /** Towntopia's {@code usableShearsSlot}: shears only when the farmer's settings allow them. */
    private static int shearsSlot(NpcEntity farmer) {
        return FarmerSettings.of(farmer).allowShears() ? FarmerShearsUtils.findBestUsableShearsSlot(farmer.getInventory()) : -1;
    }

    /** Puts each stack (owned by the village) into the farmer's pack; what doesn't fit is popped at {@code pos}. */
    private static void toPack(ServerLevel level, NpcEntity farmer, VillageManager mgr, @Nullable Activity.Context ctx, BlockPos pos,
            List<ItemStack> stacks, String reason) {
        for (ItemStack s : stacks) {
            try {
                ItemStack owned = VillageItemOwnership.produce(s, mgr);
                if (owned.isEmpty() || farmer.getInventory().addItem(owned)) continue;
                if (ctx != null) {
                    ItemLossLog.pop(ctx, pos, owned, reason);
                } else {
                    TownKit.LOG.warn("[TownKit] [farm] {}: {} popped at {}", reason, owned, pos.toShortString());
                    Block.popResource(level, pos, owned);
                }
            } catch (RuntimeException e) {
                TownKit.LOG.warn("[TownKit] [farm] handing {} over failed ({}); popped at {}", s, e.toString(), pos.toShortString());
                popStray(level, pos, List.of(s));
            }
        }
    }

    /** Drops {@code stacks} at {@code pos} (an error path: nothing is deleted); a failure of a single drop is logged. */
    private static void popStray(ServerLevel level, BlockPos pos, List<ItemStack> stacks) {
        for (ItemStack s : stacks) {
            if (s.isEmpty()) continue;
            try {
                Block.popResource(level, pos, s.copy());
            } catch (RuntimeException e) {
                TownKit.LOG.warn("[TownKit] [farm] could not drop {} at {}: {}", s, pos.toShortString(), e.toString());
            }
        }
    }

    /** The bonus item: Towntopia's own choice (its list, and the bonus-produce tag through its hook) for the caught items. */
    @Nullable
    private static Item bonusItem(List<ItemStack> drops) {
        reflect();
        Method m = bonusMethod;
        if (m != null) {
            try {
                return (Item) m.invoke(null, List.copyOf(drops));
            } catch (ReflectiveOperationException | RuntimeException e) {
                WorkLog.GLOBAL.once("use-harvest-bonus", "[farm] can't ask Towntopia for the bonus item (" + e + "); picked crops use the "
                    + FarmHooks.FARMER_BONUS_PRODUCE.location() + " tag only");
            }
        }
        for (ItemStack s : drops) if (s.is(FarmHooks.FARMER_BONUS_PRODUCE)) return s.getItem();
        return null;
    }

    private static void reflect() {
        if (reflectionTried) return;
        synchronized (UseHarvest.class) {
            if (reflectionTried) return;
            try {
                Method bonus = FarmerActivity.class.getDeclaredMethod("eligibleBonusProduct", List.class);
                bonus.setAccessible(true);
                bonusMethod = bonus;
            } catch (ReflectiveOperationException | RuntimeException e) {
                bonusMethod = null;
            }
            reflectionTried = true;
        }
    }

    // --- hooks (attached by FarmHooks) ---------------------------------------------------------------------------------

    /** The rule with a {@code Use} action holding {@code block} and not switched off, or null. */
    @Nullable
    private static HarvestRule useRule(Block block) {
        HarvestRule rule = HarvestTable.GLOBAL.ruleFor(block);
        return rule != null && rule.action() instanceof HarvestAction.Use && !FAILURES.off(rule.id()) ? rule : null;
    }

    /**
     * Before {@code FarmerActivity.harvestCrop(Context, Job)}: for a use rule's block that passes Towntopia's own checks
     * (ripe, a planting item the farmer may plant), pick it instead of breaking it; no farmland-to-dirt roll.
     */
    static void beforeHarvestCrop(HookCall call) {
        Activity.Context ctx = (Activity.Context) call.args()[0];
        FarmerManager.Job job = (FarmerManager.Job) call.args()[1];
        BlockState before = ctx.level().getBlockState(job.target());
        HarvestRule rule = useRule(before.getBlock());
        if (rule == null) return;
        // Towntopia's own first checks; when they fail it answers false itself
        if (!FarmerManager.isMatureCrop(before) || job.plantingItem() == null
            || !FarmerSettings.of(ctx.npc()).isPlantEnabled(job.plantingItem())) {
            return;
        }
        call.cancelWith(run(ctx.level(), ctx.npc(), ctx.mgr(), job.target(), rule) == Result.PICKED);
    }

    /**
     * Before {@code pickBerries}, {@code pickGlowBerries} and {@code harvestPod} (Context, BlockPos): a use rule's block is
     * picked by use. Towntopia's shears check for these jobs stays (no usable shears: the job fails, not counted), and so
     * does its shears wear (once per pick; not again when the click already wore the shears in the hand).
     */
    static void beforePick(HookCall call) {
        Activity.Context ctx = (Activity.Context) call.args()[0];
        BlockPos pos = (BlockPos) call.args()[1];
        ServerLevel level = ctx.level();
        HarvestRule rule = useRule(level.getBlockState(pos).getBlock());
        if (rule == null) return;
        int shears = shearsSlot(ctx.npc());
        if (shears < 0) {
            call.cancelWith(false);
            return;
        }
        // ripeness needs no check here: FarmerManager.validateJob -> FarmerPerennials.validate -> pickable re-checks it this tick
        Outcome o = pick(level, ctx.npc(), ctx.mgr(), pos, rule);
        FAILURES.record(rule.id(), o.result);
        if (o.result == Result.PICKED) {
            boolean shearsWorn = o.toolWorn && ((HarvestAction.Use) rule.action()).holding() == Holding.SHEARS;
            if (!shearsWorn) FarmerShearsUtils.damageAfterUse(level, ctx.npc().getInventory(), shears);
            if (call.key().equals(FarmHooks.PICK_BERRIES)) ctx.mgr().farmers().onBerriesPicked(ctx.model().npcId, o.picked);
        }
        call.cancelWith(o.result == Result.PICKED);
    }

    /** A break capture with the call it belongs to. */
    private record BreakCapture(Object self, BlockPos pos, DropCapture.Handle handle) {
    }

    /**
     * Before {@code FarmerActivity.harvestBlock(Context, BlockPos, ItemStack)} for a {@code Break} rule's block: opens a
     * capture of the block's box grown by one, so what other parts of the crop drop as the block goes is caught; the
     * after-hook closes it and puts the items in the pack. Nothing is cancelled or run again: Towntopia's method and every
     * other listener run once, as without the rule. A capture left open by an earlier call whose after-hook never came
     * (it threw, or a later before-listener cancelled it) is released first (its items back into the world), and
     * {@link DropCapture} releases any capture still open at the end of the tick.
     */
    static void beforeHarvestBlock(HookCall call) {
        releaseStale();
        Activity.Context ctx = (Activity.Context) call.args()[0];
        BlockPos pos = (BlockPos) call.args()[1];
        ServerLevel level = ctx.level();
        HarvestRule rule = HarvestTable.GLOBAL.ruleFor(level.getBlockState(pos).getBlock());
        if (rule == null || !(rule.action() instanceof HarvestAction.Break)) return;
        BREAKS.addLast(new BreakCapture(call.self(), pos, DropCapture.open(level, new AABB(pos).inflate(1), pos)));
    }

    /** After {@code harvestBlock}: closes this call's capture and puts what it caught into the farmer's pack. */
    static void afterHarvestBlock(HookCall call) {
        if (BREAKS.isEmpty()) return;
        BreakCapture b = BREAKS.pollLast();
        if (b.self != call.self() || !b.pos.equals(call.args()[1])) {
            DropCapture.release(b.handle); // not this call's: a stale one
            releaseStale();
            return;
        }
        List<ItemStack> caught = DropCapture.close(b.handle);
        if (caught.isEmpty()) return;
        Activity.Context ctx = (Activity.Context) call.args()[0];
        toPack(ctx.level(), ctx.npc(), ctx.mgr(), ctx, b.pos, caught, "harvest, pack full");
    }

    /** Releases every break capture still open (Towntopia's {@code harvestBlock} never runs inside another one). */
    private static void releaseStale() {
        while (!BREAKS.isEmpty()) DropCapture.release(BREAKS.pollLast().handle);
    }

    /**
     * Before {@code ItemLossLog.pop}: Towntopia pops what doesn't fit in the pack at the harvested block. That item belongs
     * on the ground (Towntopia has logged it as dropped), so the capture is paused for it rather than catching it and
     * popping (and logging) it a second time.
     */
    static void beforePop(HookCall call) {
        DropCapture.pause();
    }

    /** After {@code ItemLossLog.pop}: the capture catches again. */
    static void afterPop(HookCall call) {
        DropCapture.resume();
    }

    /**
     * Bad answers in a row per rule, worldwide; {@link #SWITCH_OFF_AFTER} switch the rule off until {@link #reset()}.
     * A pick starts a new row; a missing tool neither counts nor breaks the row.
     */
    static final class Failures {
        private final WorkLog log;
        private final Map<ResourceLocation, Integer> inARow = new HashMap<>();
        private final Set<ResourceLocation> off = ConcurrentHashMap.newKeySet();
        private int generation;

        Failures(WorkLog log) {
            this.log = log;
        }

        synchronized void record(ResourceLocation rule, Result result) {
            switch (result) {
                case PICKED -> this.inARow.remove(rule);
                case NO_TOOL -> {
                }
                case BLOCK_REFUSED, BLOCK_GONE -> {
                    int n = this.inARow.merge(rule, 1, Integer::sum);
                    if (n >= SWITCH_OFF_AFTER && this.off.add(rule)) {
                        this.log.once("use-harvest-off|" + rule + "|" + this.generation, "[" + HarvestTable.KIND + "] rule " + rule
                            + ": the block gave no harvest " + SWITCH_OFF_AFTER + " times in a row (the click passed, or the "
                            + "block was gone); the rule is off until the next /reload or server start");
                    }
                }
            }
        }

        /** Whether {@code rule} is switched off. */
        boolean off(ResourceLocation rule) {
            return !this.off.isEmpty() && this.off.contains(rule);
        }

        boolean anyOff() {
            return !this.off.isEmpty();
        }

        synchronized void reset() {
            this.inARow.clear();
            this.off.clear();
            this.generation++;
        }
    }
}
