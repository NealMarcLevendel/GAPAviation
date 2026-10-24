package ivautopilot;

import ivautopilot.nav.AirportMarkerBlock;
import ivautopilot.nav.AirportMarkerBlockEntity;
import ivautopilot.nav.RunwayMarkerBlock;
import ivautopilot.nav.RunwayMarkerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ApRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, IvAutopilot.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, IvAutopilot.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, IvAutopilot.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, IvAutopilot.MODID);

    public static final RegistryObject<Block> RUNWAY_MARKER = BLOCKS.register("runway_marker",
            () -> new RunwayMarkerBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW)
                    .strength(1.5F).sound(SoundType.METAL).lightLevel(state -> 10)));
    public static final RegistryObject<Item> RUNWAY_MARKER_ITEM = ITEMS.register("runway_marker",
            () -> new BlockItem(RUNWAY_MARKER.get(), new Item.Properties()));
    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<RunwayMarkerBlockEntity>> RUNWAY_MARKER_BE = BLOCK_ENTITIES.register("runway_marker",
            () -> BlockEntityType.Builder.of(RunwayMarkerBlockEntity::new, RUNWAY_MARKER.get()).build(null));
    public static final RegistryObject<Block> AIRPORT_MARKER = BLOCKS.register("airport_marker",
            () -> new AirportMarkerBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(1.5F).sound(SoundType.METAL).lightLevel(state -> 10)));
    public static final RegistryObject<Item> AIRPORT_MARKER_ITEM = ITEMS.register("airport_marker",
            () -> new BlockItem(AIRPORT_MARKER.get(), new Item.Properties()));
    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<AirportMarkerBlockEntity>> AIRPORT_MARKER_BE = BLOCK_ENTITIES.register("airport_marker",
            () -> BlockEntityType.Builder.of(AirportMarkerBlockEntity::new, AIRPORT_MARKER.get()).build(null));
    public static final RegistryObject<Block> TAXI_WAYPOINT = BLOCKS.register("taxi_waypoint",
            () -> new ivautopilot.taxi.TaxiWaypointBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW)
                    .strength(0.5F).sound(SoundType.METAL).noCollission().noOcclusion().lightLevel(state -> 6)));
    public static final RegistryObject<Block> TERMINAL = BLOCKS.register("terminal_gate",
            () -> new ivautopilot.taxi.TerminalBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE)
                    .strength(0.5F).sound(SoundType.METAL).noCollission().noOcclusion().lightLevel(state -> 6)));
    public static final RegistryObject<Item> TERMINAL_ITEM = ITEMS.register("terminal_gate",
            () -> new BlockItem(TERMINAL.get(), new Item.Properties()));
    public static final RegistryObject<Item> TAXI_WAYPOINT_ITEM = ITEMS.register("taxi_waypoint",
            () -> new BlockItem(TAXI_WAYPOINT.get(), new Item.Properties()));
    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<ivautopilot.taxi.TaxiWaypointBlockEntity>> TAXI_WAYPOINT_BE = BLOCK_ENTITIES.register("taxi_waypoint",
            () -> BlockEntityType.Builder.of(ivautopilot.taxi.TaxiWaypointBlockEntity::new, TAXI_WAYPOINT.get(), ApRegistry.TERMINAL.get()).build(null));
    public static final RegistryObject<Item> TAXI_WAND = ITEMS.register("taxiway_wand",
            () -> new ivautopilot.taxi.TaxiWandItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.ivautopilot"))
            .icon(() -> new ItemStack(RUNWAY_MARKER_ITEM.get()))
            .displayItems((params, output) -> {
                output.accept(RUNWAY_MARKER_ITEM.get());
                output.accept(AIRPORT_MARKER_ITEM.get());
                output.accept(TAXI_WAYPOINT_ITEM.get());
                output.accept(TERMINAL_ITEM.get());
                output.accept(TAXI_WAND.get());
            })
            .build());

    private ApRegistry() {
    }
}
