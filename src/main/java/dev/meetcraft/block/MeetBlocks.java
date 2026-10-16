package dev.meetcraft.block;

import dev.meetcraft.MeetCraft;
import java.util.function.Function;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class MeetBlocks {
	public static final Block SCREEN = register("screen", ScreenBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(1.5F).sound(SoundType.METAL));

	private MeetBlocks() {
	}

	/** Forces the static registrations above and adds the items to the Redstone creative tab. */
	public static void register() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.REDSTONE_BLOCKS).register(output -> {
			output.accept(SCREEN);
		});
	}

	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties properties) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, MeetCraft.id(name));
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, factory.apply(properties.setId(blockKey)));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, MeetCraft.id(name));
		Registry.register(BuiltInRegistries.ITEM, itemKey, new ScreenItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix().stacksTo(1)));
		return block;
	}
}
