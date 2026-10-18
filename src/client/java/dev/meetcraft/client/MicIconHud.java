package dev.meetcraft.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * The Meet mic in the top-left, drawn in the same pixel style as Plasmo Voice's in-game mic so the two read as a
 * pair: green with a Meet camera badge when you're live in the call, grey and struck through when muted. It shows what
 * is actually going out to Meet, not just what the key asked for. While the Meet view is enlarged, it moves into that
 * view's bottom strip instead, where Meet keeps its own mic button.
 */
final class MicIconHud implements HudElement {
	private static final String[] MIC = {
		"...####...",
		"..######..",
		"..######..",
		"..######..",
		"..######..",
		"#.######.#",
		"#.######.#",
		"#..####..#",
		".#......#.",
		"..######..",
		"....##....",
		"....##....",
		"..######..",
	};
	/** A little Meet camera, so it can't be mistaken for Plasmo's mic. */
	private static final String[] BADGE = {
		"####.#",
		"######",
		"######",
		"####.#",
	};
	private static final int LIVE = 0xFF34A853;
	private static final int MUTED = 0xFFB0B0B0;
	private static final int STRIKE = 0xFFE53935;
	private static final int BADGE_LIVE = 0xFF00832D;
	private static final int BADGE_MUTED = 0xFF7A7A7A;
	private static final int SHADOW = 0x90000000;

	static final int SIZE = 13;
	/** Including the badge and the shadow. */
	static final int WIDTH = 14;

	private final BrowserBridge bridge;
	private final MeetViewHud meetView;

	MicIconHud(BrowserBridge bridge, MeetViewHud meetView) {
		this.bridge = bridge;
		this.meetView = meetView;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		Boolean mic = bridge.micOn();
		if (mc.player == null || mc.getDebugOverlay().showDebugScreen() || mic == null || meetView.isEnlarged()) {
			return;
		}
		// Top-left: under the minimap, or in its place when it's hidden or on the right.
		drawAt(graphics, 6, meetView.topLeftY(graphics.guiWidth()), mic);
	}

	static void drawAt(GuiGraphicsExtractor graphics, int x, int y, boolean mic) {
		draw(graphics, MIC, x + 1, y + 1, SHADOW);
		draw(graphics, MIC, x, y, mic ? LIVE : MUTED);
		draw(graphics, BADGE, x + 7 + 1, y + 8 + 1, SHADOW);
		draw(graphics, BADGE, x + 7, y + 8, mic ? BADGE_LIVE : BADGE_MUTED);
		if (!mic) {
			for (int i = 0; i < MIC.length; i++) {
				int sx = x + MIC[0].length() - 1 - Math.round(i * (MIC[0].length() - 1) / (float) (MIC.length - 1));
				graphics.fill(sx, y + i, sx + 2, y + i + 1, STRIKE);
			}
		}
	}

	/** Pixel art: each {@code #} in {@code art} is one GUI pixel of {@code color}. */
	static void draw(GuiGraphicsExtractor graphics, String[] art, int x, int y, int color) {
		for (int row = 0; row < art.length; row++) {
			String line = art[row];
			int start = -1;
			for (int col = 0; col <= line.length(); col++) {
				boolean on = col < line.length() && line.charAt(col) == '#';
				if (on && start < 0) {
					start = col;
				} else if (!on && start >= 0) {
					graphics.fill(x + start, y + row, x + col, y + row + 1, color);
					start = -1;
				}
			}
		}
	}
}
