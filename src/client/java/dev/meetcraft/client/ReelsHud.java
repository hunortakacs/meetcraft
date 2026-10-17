package dev.meetcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.meetcraft.MeetCraft;
import java.util.List;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Instagram reels in the HUD: a tall view at the side of the screen, fed by the MeetCraft Reels extension from your
 * Instagram tab. Its keys scroll to the next or previous reel, mute them, and switch auto-scroll (the next reel as soon
 * as one finishes). Client side only; the reels never go near the Minecraft server or the Screen blocks.
 */
final class ReelsHud implements HudElement {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(MeetCraft.id("reels"));
	private static final int MARGIN = 6;
	private static final int STRIP = 13;
	private static final int BACKDROP = 0xC0000000;
	private static final int HINT = 0xFFAAAAAA;
	private static final int ICON = 0xFFE0E0E0;
	private static final int STRIKE = 0xFFE53935;
	private static final int AUTO = 0xFF34A853;
	private static final int TRACK = 0x60FFFFFF;
	private static final int PROGRESS = 0xFFFFFFFF;
	/** Reels are 9:16; the view keeps that shape until the first frame says otherwise. */
	private static final float REEL_ASPECT = 9 / 16f;

	private static final String[] SPEAKER = {
		"...#",
		"..##",
		"####",
		"####",
		"####",
		"####",
		"..##",
		"...#",
	};
	private static final String[] WAVES = {
		"..#.",
		"#..#",
		".#.#",
		".#.#",
		".#.#",
		".#.#",
		"#..#",
		"..#.",
	};
	private static final String[] CROSS = {
		"#..#",
		".##.",
		".##.",
		"#..#",
	};

	// I for Instagram; the arrows scroll like they would in the browser. M and N sit next to each other under the
	// right hand. All free in vanilla, and rebindable under Controls → Instagram Reels.
	final KeyMapping viewKey = key("view", InputConstants.KEY_I);
	final KeyMapping nextKey = key("next", InputConstants.KEY_DOWN);
	final KeyMapping previousKey = key("previous", InputConstants.KEY_UP);
	final KeyMapping muteKey = key("mute", InputConstants.KEY_M);
	final KeyMapping autoKey = key("auto", InputConstants.KEY_N);

	private final VideoFeed video;
	private final ReelsBridge bridge;
	private final MeetViewHud meetView;
	private final ClientConfig config;
	private boolean open;

	ReelsHud(VideoFeed video, ReelsBridge bridge, MeetViewHud meetView, ClientConfig config) {
		this.video = video;
		this.bridge = bridge;
		this.meetView = meetView;
		this.config = config;
	}

	private static KeyMapping key(String name, int defaultKey) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.meetcraft.reels." + name, defaultKey, CATEGORY));
	}

	void tick(Minecraft mc) {
		while (viewKey.consumeClick()) {
			open = !open;
			if (open) {
				warnIfDisconnected(mc);
			}
		}
		for (int by : new int[] {1, -1}) {
			KeyMapping key = by > 0 ? nextKey : previousKey;
			while (key.consumeClick()) {
				// With the view closed, the first press just brings it back, on the reel you left.
				if (!open) {
					open = true;
					warnIfDisconnected(mc);
				} else if (!bridge.step(by)) {
					warnIfDisconnected(mc);
				}
			}
		}
		while (muteKey.consumeClick()) {
			config.reelsMuted = !config.reelsMuted;
			config.save();
			overlay(mc, config.reelsMuted ? "Reels muted" : "Reels sound on");
		}
		while (autoKey.consumeClick()) {
			config.reelsAuto = !config.reelsAuto;
			config.save();
			overlay(mc, config.reelsAuto ? "Reels auto-scroll on: the next one plays when a reel ends" : "Reels auto-scroll off");
		}
		bridge.setState(viewing(mc), config.videoFps, config.reelsMuted, config.reelsAuto);
	}

	/** While this is true the reel plays and its frames come in; otherwise the extension pauses it. */
	private boolean viewing(Minecraft mc) {
		return open && mc.player != null;
	}

	private void warnIfDisconnected(Minecraft mc) {
		if (!bridge.isConnected()) {
			overlay(mc, "Instagram isn't connected; open Firefox with the MeetCraft Reels extension.");
		}
	}

	private static void overlay(Minecraft mc, String text) {
		if (mc.player != null) {
			mc.player.sendOverlayMessage(Component.literal(text));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		// The enlarged Meet view covers most of the screen anyway; the reel carries on behind it.
		if (!open || mc.getConnection() == null || mc.getDebugOverlay().showDebugScreen() || meetView.isEnlarged()) {
			return;
		}
		video.upload(mc);
		int[] size = video.liveSize();
		int guiWidth = graphics.guiWidth();
		int guiHeight = graphics.guiHeight();
		boolean left = config.reelsSide == ClientConfig.ReelsSide.LEFT;

		// Stay clear of what else lives up there: the minimap and the Meet mic on the left, the minimap or status
		// effects on the right.
		int top = MARGIN;
		if (left) {
			top = meetView.topLeftY(guiWidth) + MicIconHud.SIZE + 4;
		} else {
			int[] map = meetView.minimapBounds(guiWidth);
			if (map != null && map[0] > guiWidth / 2) {
				top = map[1] + map[3] + 6;
			} else if (mc.player != null && !mc.player.getActiveEffects().isEmpty()) {
				top = 56;
			}
		}
		int availH = guiHeight - top - MARGIN - STRIP;
		float aspect = size != null ? size[0] / (float) size[1] : REEL_ASPECT;
		int h = Math.min(availH, Math.round(guiHeight * config.reelsSize / 100f));
		int w = Math.round(h * aspect);
		if (w > guiWidth / 2) {
			w = guiWidth / 2;
			h = Math.round(w / aspect);
		}
		int x = left ? MARGIN : guiWidth - w - MARGIN;
		// Centred on the screen when there's room, else as high as allowed.
		int y = Math.max(top, (guiHeight - h - STRIP) / 2);
		int pad = 2;
		int r = config.cornerRadius;
		Rounded.fill(graphics, x - pad, y - pad, w + 2 * pad, h + STRIP + pad, r == 0 ? 0 : r + pad, BACKDROP);

		ReelsBridge.Reel reel = bridge.reel();
		if (size != null) {
			Rounded.blit(graphics, video.id(), x, y, w, h, r);
			if (reel.soundBlocked()) {
				List<FormattedCharSequence> lines = mc.font.split(Component.literal("No sound until you click the Instagram tab once"), Math.max(20, w - 8));
				graphics.fill(x, y + r, x + w, y + r + 4 + lines.size() * 10, BACKDROP);
				drawLines(graphics, mc, lines, x + w / 2, y + r + 3, HINT);
			}
		} else {
			String message = !bridge.isConnected() ? "Instagram isn't connected: open Firefox with the MeetCraft Reels extension"
				: !reel.tabOpen() ? "Opening Instagram reels in Firefox…"
				: "Waiting for a reel. Logged in to Instagram?";
			List<FormattedCharSequence> lines = mc.font.split(Component.literal(message), Math.max(20, w - 8));
			drawLines(graphics, mc, lines, x + w / 2, y + (h - lines.size() * 10) / 2, HINT);
		}

		// The strip under the reel: how far through it is, the sound, auto-scroll, and the keys while there's room.
		int barY = y + h + 2;
		graphics.fill(x, barY, x + w, barY + 1, TRACK);
		if (reel.duration() > 0) {
			int done = (int) Math.round(w * Math.clamp(reel.time() / reel.duration(), 0, 1));
			graphics.fill(x, barY, x + done, barY + 1, PROGRESS);
		}
		int rowY = barY + 3;
		boolean muted = config.reelsMuted || reel.muted() || reel.soundBlocked();
		MicIconHud.draw(graphics, SPEAKER, x, rowY, ICON);
		if (muted) {
			MicIconHud.draw(graphics, CROSS, x + 5, rowY + 2, STRIKE);
		} else {
			MicIconHud.draw(graphics, WAVES, x + 5, rowY, ICON);
		}
		int textX = x + 12;
		if (config.reelsAuto) {
			graphics.text(mc.font, "auto", textX, rowY, AUTO);
			textX += mc.font.width("auto") + 6;
		}
		String next = nextKey.getTranslatedKeyMessage().getString();
		String previous = previousKey.getTranslatedKeyMessage().getString();
		for (String hint : new String[] {next + " next · " + previous + " back", next + " next"}) {
			if (textX + mc.font.width(hint) <= x + w) {
				graphics.text(mc.font, hint, x + w - mc.font.width(hint), rowY, HINT);
				break;
			}
		}
	}

	private static void drawLines(GuiGraphicsExtractor graphics, Minecraft mc, List<FormattedCharSequence> lines, int centreX, int y, int color) {
		for (FormattedCharSequence line : lines) {
			graphics.centeredText(mc.font, line, centreX, y, color);
			y += 10;
		}
	}
}
