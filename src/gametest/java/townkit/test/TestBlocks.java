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
