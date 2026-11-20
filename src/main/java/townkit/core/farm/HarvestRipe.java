package townkit.core.farm;

import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import net.minecraft.world.level.block.state.BlockState;
import townkit.api.hooks.HookCall;

/**
 * A harvest rule with a ripe rule of its own answers "is this block ripe" in place of Towntopia. The hooks are
 * attached by {@link FarmHooks} only while such a rule holds a block; each looks the block up once in {@link HarvestTable}
 * and does nothing for a block no rule holds; the answer for a block a rule does hold is a cached boolean (the hook call itself
 * is built by {@link townkit.core.hook.Hooks}).
 *
 * @since 0.8.0
 */
final class HarvestRipe {
    private HarvestRipe() {
    }

    /** After {@code CropDefinitionRegistry.isMature(BlockState, CropDefinition)}: the rule's answer replaces Towntopia's. */
    static void afterIsMature(HookCall call) {
        BlockState state = (BlockState) call.args()[0];
        HarvestTable.Resolved r = HarvestTable.GLOBAL.resolved(state.getBlock());
        if (r != null && r.overridesRipe()) call.setReturnValue(r.ripe(state));
    }

    /**
     * After {@code FarmerPerennials.pickable(Kind, BlockState, CropDefinition)}: the rule's answer replaces the hard-coded
     * sweet-berry age, for a block the crop definition matches.
     */
    static void afterPickable(HookCall call) {
        BlockState state = (BlockState) call.args()[1];
        HarvestTable.Resolved r = HarvestTable.GLOBAL.resolved(state.getBlock());
        if (r == null || !r.overridesRipe()) return;
        CropDefinition definition = (CropDefinition) call.args()[2];
        if (definition.matchesCropBlock(state.getBlock())) call.setReturnValue(r.ripe(state));
    }

    /** After {@code FarmerPerennials.fullBush(BlockState)}: a rule's block is full when its ripe property is at its highest. */
    static void afterFullBush(HookCall call) {
        BlockState state = (BlockState) call.args()[0];
        HarvestTable.Resolved r = HarvestTable.GLOBAL.resolved(state.getBlock());
        if (r != null && r.overridesRipe()) call.setReturnValue(r.full(state));
    }
}
