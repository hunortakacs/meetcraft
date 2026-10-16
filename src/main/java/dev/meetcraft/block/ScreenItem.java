package dev.meetcraft.block;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/** Never runs out, so anyone can build a screen of any size in survival without the server going creative. */
public class ScreenItem extends BlockItem {
	public ScreenItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public InteractionResult place(BlockPlaceContext context) {
		ItemStack stack = context.getItemInHand();
		int count = stack.getCount();
		InteractionResult result = super.place(context);
		stack.setCount(count);
		return result;
	}
}
