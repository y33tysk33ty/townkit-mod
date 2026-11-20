package townkit.test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import townkit.core.TownKit;

/**
 * Plain blocks the building GameTests use as "modded" blocks: no block entity, no vanilla superclass; and the item the
 * transport GameTests use as a stop marker ({@link #TEST_STOP_MARKER}) and the shop GameTests' coin ({@link #TEST_COIN}). Registered only
 * when the system property {@code townkit.testblocks=true} is set (the gameTestServer run sets it), so they never exist
 * in a normal game.
 */
public final class TestBlocks {
    public static final String PROPERTY = "townkit.testblocks";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(TownKit.MODID);

    public static final DeferredBlock<Block> TEST_TABLE = BLOCKS.registerSimpleBlock("test_table", props());
    public static final DeferredBlock<Block> TEST_OVEN = BLOCKS.registerSimpleBlock("test_oven", props());
    public static final DeferredBlock<Block> TEST_HAMMOCK = BLOCKS.registerSimpleBlock("test_hammock", props());
    public static final DeferredBlock<Block> TEST_CRATE = BLOCKS.registerSimpleBlock("test_crate", props());
    /** The mining GameTests' "modded" ores: they drop what copper ore drops (the loot is the block's own, nothing of TownKit's). */
    public static final DeferredBlock<Block> TEST_TIN_ORE = BLOCKS.registerSimpleBlock("test_tin_ore", ore());
    public static final DeferredBlock<Block> TEST_DEEPSLATE_TIN_ORE = BLOCKS.registerSimpleBlock("test_deepslate_tin_ore", ore());
    public static final DeferredBlock<Block> TEST_ZINC_ORE = BLOCKS.registerSimpleBlock("test_zinc_ore", ore());
    /** W3: the test corn crop block (a plain crop with an age; the farmer_crops test data names it). */
    public static final DeferredBlock<Block> TEST_CORN_CROP = BLOCKS.register("test_corn_crop",
            () -> new net.minecraft.world.level.block.CropBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.WHEAT)));
    /** W1: a berry bush like the sweet berry bush (age 0-3); the farmer_crops test data names it (on soil, picked, not broken). */
    public static final DeferredBlock<Block> TEST_BUSH = BLOCKS.register("test_bush",
            () -> new net.minecraft.world.level.block.SweetBerryBushBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.SWEET_BERRY_BUSH)));
    /**
     * W1: a field crop picked by right-click (like a tomato): at its highest age a use pops two {@link #TEST_PRODUCE} and
     * sets the age to {@value PickCropBlock#PICKED_AGE}; the plant stays. Broken, it drops one. Its farmer_crops test data
     * says break_and_replant on farmland, planted from {@link #TEST_PICK_SEED}.
     */
    public static final DeferredBlock<Block> TEST_PICK_CROP = BLOCKS.register("test_pick_crop",
            () -> new PickCropBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.WHEAT)));
    /** W1: a crop that ignores a right-click (every use passes); broken, it drops one {@link #TEST_PRODUCE}. */
    public static final DeferredBlock<Block> TEST_PASS_CROP = BLOCKS.register("test_pass_crop",
            () -> new DropsProduceCropBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.WHEAT)));
    /**
     * W1: a two-block crop like Diversity Delight's: the upper half needs the lower one below it and breaks (with its
     * drops) when the lower half goes; each half drops one {@link #TEST_PRODUCE}.
     */
    public static final DeferredBlock<Block> TEST_TALL_CROP = BLOCKS.register("test_tall_crop",
            () -> new TallCropBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.WHEAT)));
    /** Holds items only through NeoForge's item-handler capability: its block entity is not a {@code Container}. */
    public static final DeferredBlock<Block> TEST_HANDLER_CRATE = BLOCKS.register("test_handler_crate",
            () -> new HandlerCrateBlock(props()));

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(TownKit.MODID);
    /** The transport GameTests' marker item (kind {@code townkit_test:hop}). */
    public static final DeferredItem<Item> TEST_STOP_MARKER = ITEMS.registerSimpleItem("test_stop_marker");
    /** The marker of {@code townkit:test_setup_kind}, enabled at mod setup by {@link TownKitGameTests}. */
    public static final DeferredItem<Item> TEST_SETUP_MARKER = ITEMS.registerSimpleItem("test_setup_marker");
    /** The shop GameTests' placeholder coin (an addon's own currency item). */
    public static final DeferredItem<Item> TEST_COIN = ITEMS.registerSimpleItem("test_coin");
    /** The cooking GameTests' tag ingredients: {@code townkit:test_veg} holds a and b (test data); c joins it in a test. */
    public static final DeferredItem<Item> TEST_VEG_A = ITEMS.registerSimpleItem("test_veg_a");
    public static final DeferredItem<Item> TEST_VEG_B = ITEMS.registerSimpleItem("test_veg_b");
    public static final DeferredItem<Item> TEST_VEG_C = ITEMS.registerSimpleItem("test_veg_c");
    /** The cooking GameTests' "modded" food: its own food values are nutrition 4 and a regeneration effect. */
    public static final DeferredItem<Item> TEST_MEAL = ITEMS.registerSimpleItem("test_meal", new Item.Properties().food(
            new net.minecraft.world.food.FoodProperties.Builder().nutrition(4).saturationModifier(0.3F)
                .effect(() -> new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 100), 1.0F)
                .build()));
    /** W13: a meal eaten from a bowl; in {@code #townkit:meal_returns_remainder} (test data), so the bowl comes back. */
    public static final DeferredItem<Item> TEST_BOWL_STEW = ITEMS.registerSimpleItem("test_bowl_stew", new Item.Properties().craftRemainder(
            net.minecraft.world.item.Items.BOWL).food(new net.minecraft.world.food.FoodProperties.Builder().nutrition(5).build()));
    /** The same meal, not in the tag: nothing comes back. */
    public static final DeferredItem<Item> TEST_BOWL_PLAIN = ITEMS.registerSimpleItem("test_bowl_plain", new Item.Properties().craftRemainder(
            net.minecraft.world.item.Items.BOWL).food(new net.minecraft.world.food.FoodProperties.Builder().nutrition(5).build()));
    /** W5: a farm crop's drop; in {@code #townkit:farmer_bonus_produce} (test data), so the farmer's skill bonus applies to it. */
    public static final DeferredItem<Item> TEST_PRODUCE = ITEMS.registerSimpleItem("test_produce");
    /** The same kind of drop, not in the tag: no bonus. */
    public static final DeferredItem<Item> TEST_PRODUCE_PLAIN = ITEMS.registerSimpleItem("test_produce_plain");
    /** W3: an addon crop whose harvest gives no seeds: planted from {@link #TEST_KERNELS} (farmer_crops test data), harvested as {@link #TEST_CORN}. */
    public static final DeferredItem<Item> TEST_KERNELS = ITEMS.registerSimpleItem("test_kernels");
    public static final DeferredItem<Item> TEST_CORN = ITEMS.registerSimpleItem("test_corn");
    /** W1: the test bush's planting item (farmer_crops test data). */
    public static final DeferredItem<Item> TEST_BERRY = ITEMS.registerSimpleItem("test_berry");
    /** W1: the pick crop's planting item (farmer_crops test data). */
    public static final DeferredItem<Item> TEST_PICK_SEED = ITEMS.registerSimpleItem("test_pick_seed");
    /** W1: the pass crop's planting item (farmer_crops test data). */
    public static final DeferredItem<Item> TEST_PASS_SEED = ITEMS.registerSimpleItem("test_pass_seed");
    /** The forge GameTests' "modded" pickaxe (an addon tier's gear; in {@code #minecraft:pickaxes} through test data). */
    public static final DeferredItem<Item> TEST_STEEL_PICKAXE = ITEMS.register("test_steel_pickaxe", () -> new PickaxeItem(Tiers.IRON,
            new Item.Properties().attributes(PickaxeItem.createAttributes(Tiers.IRON, 1.0F, -2.8F))));
    /** The forge GameTests' "modded" sword (in {@code #minecraft:swords} through test data). */
    public static final DeferredItem<Item> TEST_STEEL_SWORD = ITEMS.register("test_steel_sword", () -> new SwordItem(Tiers.IRON,
            new Item.Properties().attributes(SwordItem.createAttributes(Tiers.IRON, 3, -2.4F))));
    /** The forge GameTests' "modded" axe (in {@code #minecraft:axes} through test data). */
    public static final DeferredItem<Item> TEST_STEEL_AXE = ITEMS.register("test_steel_axe", () -> new AxeItem(Tiers.IRON,
            new Item.Properties().attributes(AxeItem.createAttributes(Tiers.IRON, 6.0F, -3.1F))));
    /** The forge GameTests' "modded" chestplate (in {@code #minecraft:chest_armor} through test data). */
    public static final DeferredItem<Item> TEST_STEEL_CHESTPLATE = ITEMS.register("test_steel_chestplate", () -> new ArmorItem(
            ArmorMaterials.IRON, ArmorItem.Type.CHESTPLATE, new Item.Properties().durability(ArmorItem.Type.CHESTPLATE.getDurability(15))));

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TownKit.MODID);
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HandlerCrateEntity>> HANDLER_CRATE_ENTITY =
            BLOCK_ENTITIES.register("test_handler_crate",
                    () -> BlockEntityType.Builder.of(HandlerCrateEntity::new, TEST_HANDLER_CRATE.get()).build(null));

    /** A crop whose break drops one {@link #TEST_PRODUCE} (test blocks have no loot tables). */
    public static class DropsProduceCropBlock extends net.minecraft.world.level.block.CropBlock {
        DropsProduceCropBlock(BlockBehaviour.Properties p) {
            super(p);
        }

        @Override
        protected java.util.List<net.minecraft.world.item.ItemStack> getDrops(BlockState state,
                net.minecraft.world.level.storage.loot.LootParams.Builder params) {
            return new java.util.ArrayList<>(java.util.List.of(new net.minecraft.world.item.ItemStack(TEST_PRODUCE.get())));
        }
    }

    public static final class PickCropBlock extends DropsProduceCropBlock {
        public static final int PICKED_AGE = 4;

        PickCropBlock(BlockBehaviour.Properties p) {
            super(p);
        }

        @Override
        protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level, BlockPos pos,
                net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
            if (!this.isMaxAge(state)) return net.minecraft.world.InteractionResult.PASS;
            if (!level.isClientSide) {
                popResource(level, pos, new net.minecraft.world.item.ItemStack(TEST_PRODUCE.get(), 2));
                level.setBlock(pos, this.getStateForAge(PICKED_AGE), 2);
            }
            return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    public static final class TallCropBlock extends Block {
        public static final net.minecraft.world.level.block.state.properties.BooleanProperty UPPER =
                net.minecraft.world.level.block.state.properties.BooleanProperty.create("upper");

        TallCropBlock(BlockBehaviour.Properties p) {
            super(p);
            this.registerDefaultState(this.stateDefinition.any().setValue(UPPER, false));
        }

        @Override
        protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> b) {
            b.add(UPPER);
        }

        @Override
        protected boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
            if (!state.getValue(UPPER)) return true;
            BlockState below = level.getBlockState(pos.below());
            return below.is(this) && !below.getValue(UPPER);
        }

        @Override
        protected BlockState updateShape(BlockState state, net.minecraft.core.Direction dir, BlockState neighbour,
                net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
            if (state.getValue(UPPER) && dir == net.minecraft.core.Direction.DOWN && !state.canSurvive(level, pos)) {
                return Blocks.AIR.defaultBlockState();
            }
            return super.updateShape(state, dir, neighbour, level, pos, neighbourPos);
        }

        @Override
        protected java.util.List<net.minecraft.world.item.ItemStack> getDrops(BlockState state,
                net.minecraft.world.level.storage.loot.LootParams.Builder params) {
            return new java.util.ArrayList<>(java.util.List.of(new net.minecraft.world.item.ItemStack(TEST_PRODUCE.get())));
        }
    }

    public static final class HandlerCrateBlock extends Block implements EntityBlock {
        HandlerCrateBlock(BlockBehaviour.Properties p) {
            super(p);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new HandlerCrateEntity(pos, state);
        }
    }

    public static final class HandlerCrateEntity extends BlockEntity {
        final ItemStackHandler items = new ItemStackHandler(9);

        public HandlerCrateEntity(BlockPos pos, BlockState state) {
            super(HANDLER_CRATE_ENTITY.get(), pos, state);
        }
    }

    private TestBlocks() {
    }

    private static BlockBehaviour.Properties ore() {
        return props().requiresCorrectToolForDrops().dropsLike(Blocks.COPPER_ORE);
    }

    private static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of().strength(1.0F).sound(SoundType.WOOD);
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Called from {@link TownKitGameTests} during mod construction; registers nothing unless {@link #enabled()}. */
    public static void registerIfEnabled(IEventBus modBus) {
        if (enabled()) {
            BLOCKS.register(modBus);
            ITEMS.register(modBus);
            BLOCK_ENTITIES.register(modBus);
            modBus.addListener((RegisterCapabilitiesEvent e) -> e.registerBlockEntity(Capabilities.ItemHandler.BLOCK,
                    HANDLER_CRATE_ENTITY.get(), (be, side) -> be.items));
            TownKit.LOG.info("[TownKit] test blocks and items registered ({}=true)", PROPERTY);
        }
    }
}
