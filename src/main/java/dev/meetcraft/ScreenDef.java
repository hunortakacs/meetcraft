package dev.meetcraft;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A theatre screen: the {@code facing} side of the block box spanned by {@code min}..{@code max}.
 * Only horizontal facings are allowed, so screens are always upright walls.
 */
public record ScreenDef(String name, String dimension, BlockPos min, BlockPos max, Direction facing) {
	public static final StreamCodec<ByteBuf, ScreenDef> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, ScreenDef::name,
		ByteBufCodecs.STRING_UTF8, ScreenDef::dimension,
		BlockPos.STREAM_CODEC, ScreenDef::min,
		BlockPos.STREAM_CODEC, ScreenDef::max,
		Direction.STREAM_CODEC, ScreenDef::facing,
		ScreenDef::new
	);

	public static ScreenDef of(String name, String dimension, BlockPos a, BlockPos b, Direction facing) {
		return new ScreenDef(name, dimension, BlockPos.min(a, b), BlockPos.max(a, b), facing);
	}

	/** Width in blocks, measured along the screen face. */
	public int width() {
		return facing.getAxis() == Direction.Axis.X ? max.getZ() - min.getZ() + 1 : max.getX() - min.getX() + 1;
	}

	public int height() {
		return max.getY() - min.getY() + 1;
	}
}
