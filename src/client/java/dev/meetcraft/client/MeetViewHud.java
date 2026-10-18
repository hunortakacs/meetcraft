package dev.meetcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * Your Meet tab in the HUD: a small live view in the top-right corner (the "minimap"), and the same view enlarged to
 * most of the screen while the view key (J) is toggled on. No Screen blocks or server mod needed for this.
 */
final class MeetViewHud implements HudElement {
	private static final int BORDER = 0xFF202124;
	private static final int BACKDROP = 0xC0000000;
	private static final int HINT = 0xFFAAAAAA;

	// X and Z: reachable with the left hand on WASD. X is also vanilla's "load hotbar", which only acts together with a
	// number key in creative. Rebindable under Controls → Multiplayer.
	final KeyMapping mapping = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.meetcraft.view", InputConstants.KEY_X, KeyMapping.Category.MULTIPLAYER));
	// Shows or hides the small view; the enlarged view works either way.
	final KeyMapping minimapKey = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.meetcraft.minimap", InputConstants.KEY_Z, KeyMapping.Category.MULTIPLAYER));
	private final VideoFeed video;
	private final BrowserBridge bridge;
	private final ClientConfig config;
	private boolean enlarged;

	MeetViewHud(VideoFeed video, BrowserBridge bridge, ClientConfig config) {
		this.video = video;
		this.bridge = bridge;
		this.config = config;
	}

	void tick() {
		while (minimapKey.consumeClick()) {
			config.minimap = !config.minimap;
			config.save();
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(config.minimap ? "Meet minimap on" : "Meet minimap off"));
			}
		}
		if (config.viewKey == ClientConfig.ViewKeyMode.HOLD) {
			// Presses queue up as clicks even here; drop them so switching to toggle doesn't replay them.
			while (mapping.consumeClick()) {
			}
			enlarged = mapping.isDown();
			return;
		}
		while (mapping.consumeClick()) {
			enlarged = !enlarged;
		}
	}

	boolean isEnlarged() {
		return enlarged;
	}

	/** Whether the HUD needs the Meet video right now, so the browser should be capturing it. */
	boolean wantsVideo() {
		return enlarged || (config.minimap && bridge.micOn() != null);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null || mc.getDebugOverlay().showDebugScreen()) {
			return;
		}
		video.upload(mc);
		int[] size = video.liveSize();
		if (enlarged) {
			drawEnlarged(graphics, mc, size);
		} else if (size != null) {
			drawMinimap(graphics, mc, size);
		}
	}

	private static final int MARGIN = 6;

	/** Where things stacked in the top-left start: under the minimap when it's there, else the corner itself. */
	int topLeftY(int guiWidth) {
		int[] map = minimapBounds(guiWidth);
		return map != null && map[0] < guiWidth / 2 ? map[1] + map[3] + 4 : MARGIN;
	}

	/** Where the small view sits right now (x, y, width, height), or null when it isn't showing. */
	int @Nullable [] minimapBounds(int guiWidth) {
		int[] size = video.liveSize();
		if (enlarged || !config.minimap || size == null) {
			return null;
		}
		int width = Math.clamp(Math.round(guiWidth * 0.26f), 110, 220);
		int height = Math.round(width * size[1] / (float) size[0]);
		Minecraft mc = Minecraft.getInstance();
		if (config.minimapCorner == ClientConfig.MinimapCorner.TOP_LEFT) {
			return new int[] {MARGIN, MARGIN, width, height};
		}
		// Below the status-effect icons, which also live in the top-right corner.
		int y = mc.player != null && !mc.player.getActiveEffects().isEmpty() ? 56 : MARGIN;
		return new int[] {guiWidth - width - MARGIN, y, width, height};
	}

	private void drawMinimap(GuiGraphicsExtractor graphics, Minecraft mc, int[] size) {
		int[] b = minimapBounds(graphics.guiWidth());
		if (b == null) {
			return;
		}
		int r = config.cornerRadius;
		Rounded.fill(graphics, b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, r == 0 ? 0 : r + 1, BORDER);
		Rounded.blit(graphics, video.id(), b[0], b[1], b[2], b[3], r);
	}

	/**
	 * As big as fits with a margin all round, like an in-game map, sized to the call itself so no space is wasted on
	 * bars; only a slim strip under it for the frame rate, the Meet mic and the key hint.
	 */
	private void drawEnlarged(GuiGraphicsExtractor graphics, Minecraft mc, int @Nullable [] size) {
		int marginX = Math.max(24, graphics.guiWidth() / 12);
		int marginY = Math.max(16, graphics.guiHeight() / 14);
		int strip = MicIconHud.SIZE + 4;
		int availW = graphics.guiWidth() - 2 * marginX;
		int availH = graphics.guiHeight() - 2 * marginY - strip;
		int w, h;
		if (size == null) {
			w = Math.min(availW, 320);
			h = Math.min(availH, 90);
		} else {
			float scale = Math.min(availW / (float) size[0], availH / (float) size[1]);
			w = Math.round(size[0] * scale);
			h = Math.round(size[1] * scale);
		}
		int x = (graphics.guiWidth() - w) / 2;
		int y = (graphics.guiHeight() - h - strip) / 2;
		int pad = 3;
		Rounded.fill(graphics, x - pad, y - pad, w + 2 * pad, h + strip + pad, Math.round(config.cornerRadius * 1.5f), BACKDROP);

		int textY = y + h + 5;
		Boolean mic = bridge.micOn();
		if (mic != null) {
			MicIconHud.drawAt(graphics, x + (w - MicIconHud.WIDTH) / 2, y + h + 2, mic);
		}
		String key = mapping.getTranslatedKeyMessage().getString();
		String hint = config.viewKey == ClientConfig.ViewKeyMode.HOLD ? "release " + key + " to shrink" : key + " to shrink";
		graphics.text(mc.font, hint, x + w - mc.font.width(hint), textY, HINT);
		if (size == null) {
			String message = bridge.isConnected() ? "No Meet video yet: join a call in your Meet tab" : "Meet isn't connected: open your Meet tab with the MeetCraft extension";
			graphics.text(mc.font, message, x + (w - mc.font.width(message)) / 2, y + h / 2 - 4, HINT);
			return;
		}
		int[] fps = video.frameRates();
		// "new" is what Meet itself shows: the capture keeps up with every frame, even with Meet in the background.
		graphics.text(mc.font, fps[0] + " fps · " + fps[1] + " new", x, textY, HINT);
		Rounded.blit(graphics, video.id(), x, y, w, h, Math.round(config.cornerRadius * 1.25f));
	}
}
