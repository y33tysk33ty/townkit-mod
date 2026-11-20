package townkit.core.farm;

import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import com.alex.test.village.villagers.farmer.crops.CropHarvestStrategy;
import com.alex.test.village.villagers.farmer.crops.CropPlantingStrategy;
import com.alex.test.village.villagers.farmer.crops.HarvestTool;
import net.minecraft.world.level.block.Block;
import townkit.api.farm.HarvestAction;
import townkit.api.farm.HarvestRule;
import townkit.api.farm.Holding;
import townkit.core.work.WorkLog;

/**
 * What only shows once Towntopia's crop definitions are loaded, logged once each (so after the server started and
 * after a {@code /reload}): a rule no {@code farmer_crops} definition matches, a rule on a {@code pick_keep_plant} crop
 * (Towntopia never makes a harvest job for it), and a {@code Use} rule with shears or an axe whose crop file's
 * {@code harvest_tool} doesn't name that tool (the farmer would not fetch it), and a rule on a modded {@code on_soil} bush
 * without a {@code Use} action (Towntopia's own {@code pickBerries} only handles the sweet berry bush).
 *
 * @since 0.8.0
 */
final class HarvestChecks {
    private HarvestChecks() {
    }

    static void report() {
        try {
            for (HarvestTable.Held h : HarvestTable.GLOBAL.held()) check(h.rule(), h);
        } catch (RuntimeException | LinkageError e) {
            WorkLog.GLOBAL.once("harvest-checks", "[" + HarvestTable.KIND + "] can't read Towntopia's crop definitions (" + e
                + "); the harvest rules are not checked against them");
        }
    }

    private static void check(HarvestRule rule, HarvestTable.Held held) {
        String name = rule.id().toString();
        boolean any = false;
        for (Block block : held.blocks()) {
            CropDefinition def = CropDefinitionRegistry.byCropBlock(block);
            if (def == null) continue;
            any = true;
            String where = HarvestBlocks.REGISTRY.id(block) + " (crop " + def.id() + ")";
            // bushes, vines and pods are picked by Towntopia's perennials code (their crop files say pick_keep_plant); any other
            // pick_keep_plant crop (farmland, near water) never gets a harvest job
            boolean perennial = def.planting() instanceof CropPlantingStrategy.OnSoil || def.planting() instanceof CropPlantingStrategy.Hanging
                || def.planting() instanceof CropPlantingStrategy.OnLog;
            if (def.harvest() instanceof CropHarvestStrategy.PickKeepPlant && !perennial) {
                WorkLog.GLOBAL.once("harvest-pick-keep|" + name + "|" + def.id(), "[" + HarvestTable.KIND + "] rule " + name + ": "
                    + where + " says pick_keep_plant on a field crop, which Towntopia never harvests, so the rule does nothing there; "
                    + "keep break_and_replant in the crop file and put the picking in the rule");
            }
            if (def.planting() instanceof CropPlantingStrategy.OnSoil && block != net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH
                && !(rule.action() instanceof HarvestAction.Use)) {
                WorkLog.GLOBAL.once("harvest-onsoil|" + name + "|" + def.id(), "[" + HarvestTable.KIND + "] rule " + name + ": "
                    + where + " is an on_soil bush, which Towntopia's own pickBerries only handles for the sweet berry bush; "
                    + "give the rule a Use action");
            }
            if (rule.action() instanceof HarvestAction.Use use && use.holding() != Holding.EMPTY_HAND) {
                HarvestTool needed = use.holding() == Holding.SHEARS ? HarvestTool.SHEARS : HarvestTool.AXE;
                if (def.harvestTool() != needed) {
                    WorkLog.GLOBAL.once("harvest-tool|" + name + "|" + def.id(), "[" + HarvestTable.KIND + "] rule " + name + " uses "
                        + use.holding() + " on " + where + ", but its crop file's harvest_tool is " + def.harvestTool()
                        + ", so the farmer never fetches that tool; set \"harvest_tool\" to \"" + needed.name().toLowerCase(java.util.Locale.ROOT)
                        + "\" in the crop file");
                }
            }
        }
        if (!any) {
            WorkLog.GLOBAL.once("harvest-nocrop|" + name, "[" + HarvestTable.KIND + "] rule " + name
                + " matches no farmer_crops definition (no crop file names its blocks as crop_block), so it does nothing");
        }
    }
}
