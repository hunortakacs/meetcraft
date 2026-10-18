package dev.meetcraft.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Rounded rectangles for the HUD: boxes and video, drawn as rows pulled in at the corners, in the game's own pixel steps. */
final class Rounded {
	private Rounded() {
	}

	/**
	 * How far row {@code row} of a {@code height}-tall box is pulled in on each side to round its corners with radius
	 * {@code r}. Drawing rows with these insets gives a rounded rectangle without masks or shaders.
	 */
	private static int inset(int row, int height, int r) {
		double dy;
		if (row < r) {
			dy = r - row - 0.5;
		} else if (row >= height - r) {
			dy = row - (height - r) + 0.5;
		} else {
			return 0;
		}
		return (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
	}

	static void fill(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int r, int color) {
		r = Math.min(r, Math.min(width, height) / 2);
		if (r == 0) {
			graphics.fill(x, y, x + width, y + height, color);
			return;
		}
		graphics.fill(x, y + r, x + width, y + height - r, color);
		for (int row = 0; row < height; row++) {
			if (row >= r && row < height - r) {
				continue;
			}
			int in = inset(row, height, r);
			graphics.fill(x + in, y + row, x + width - in, y + row + 1, color);
		}
	}

	static void blit(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int width, int height, int r) {
		r = Math.min(r, Math.min(width, height) / 2);
		if (r == 0) {
			graphics.blit(texture, x, y, x + width, y + height, 0, 1, 0, 1);
			return;
		}
		graphics.blit(texture, x, y + r, x + width, y + height - r, 0, 1, r / (float) height, (height - r) / (float) height);
		for (int row = 0; row < height; row++) {
			if (row >= r && row < height - r) {
				continue;
			}
			int in = inset(row, height, r);
			graphics.blit(texture, x + in, y + row, x + width - in, y + row + 1,
				in / (float) width, 1 - in / (float) width, row / (float) height, (row + 1) / (float) height);
		}
	}
}
