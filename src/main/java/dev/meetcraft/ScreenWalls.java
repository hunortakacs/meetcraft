package dev.meetcraft;

import dev.meetcraft.block.MeetBlocks;
import dev.meetcraft.block.ScreenBlock;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Turns screen blocks into {@link ScreenDef}s: every filled rectangle of screen blocks that face the same way and
 * sit in one plane is one screen. Called whenever a screen block appears or disappears.
 */
final class ScreenWalls {
	static final int MAX_SIZE = 64;

	private ScreenWalls() {
	}

	/** Rebuilds the screens around {@code pos} after a screen block facing {@code facing} was placed or removed there. */
	static void update(ServerLevel level, BlockPos pos, Direction facing, List<ScreenDef> screens) {
		String dimension = level.dimension().identifier().toString();
		Direction side = facing.getClockWise();
		List<BlockPos> touched = List.of(pos, pos.above(), pos.below(), pos.relative(side), pos.relative(side.getOpposite()));

		// Anything that included or bordered the changed block may have grown, shrunk or split; rebuild those.
		screens.removeIf(s -> s.dimension().equals(dimension) && s.facing() == facing && touched.stream().anyMatch(p -> contains(s, p)));
		Set<BlockPos> visited = new HashSet<>();
		for (BlockPos start : touched) {
			if (!visited.contains(start) && isScreen(level, start, facing)) {
				ScreenDef screen = rectangle(level, start, facing, side, dimension, visited);
				if (screen != null) {
					screens.add(screen);
				}
			}
		}
	}

	/** Flood-fills the wall containing {@code start}; returns it as a screen only if it is a filled rectangle. */
	private static @Nullable ScreenDef rectangle(ServerLevel level, BlockPos start, Direction facing, Direction side, String dimension,
												 Set<BlockPos> visited) {
		ArrayDeque<BlockPos> queue = new ArrayDeque<>(List.of(start));
		visited.add(start);
		BlockPos min = start;
		BlockPos max = start;
		int count = 0;
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			count++;
			if (count > MAX_SIZE * MAX_SIZE) {
				return null;
			}
			min = BlockPos.min(min, p);
			max = BlockPos.max(max, p);
			for (BlockPos next : List.of(p.above(), p.below(), p.relative(side), p.relative(side.getOpposite()))) {
				if (!visited.contains(next) && isScreen(level, next, facing)) {
					visited.add(next);
					queue.add(next);
				}
			}
		}
		ScreenDef screen = ScreenDef.of(min.toShortString(), dimension, min, max, facing);
		boolean filled = screen.width() * screen.height() == count;
		boolean fits = screen.width() <= MAX_SIZE && screen.height() <= MAX_SIZE;
		return filled && fits ? screen : null;
	}

	private static boolean isScreen(ServerLevel level, BlockPos pos, Direction facing) {
		if (!level.isLoaded(pos)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		return state.is(MeetBlocks.SCREEN) && state.getValue(ScreenBlock.FACING) == facing;
	}

	private static boolean contains(ScreenDef s, BlockPos p) {
		return p.getX() >= s.min().getX() && p.getX() <= s.max().getX()
			&& p.getY() >= s.min().getY() && p.getY() <= s.max().getY()
			&& p.getZ() >= s.min().getZ() && p.getZ() <= s.max().getZ();
	}
}
