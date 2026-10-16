package dev.meetcraft.client;

import dev.meetcraft.client.chat.GoogleChat;
import dev.meetcraft.client.mixin.ChatComponentAccessor;
import dev.meetcraft.client.mixin.HudAccessor;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.util.Mth;

/**
 * Text status: the Google chat you're typing into, right above Minecraft's chat lines (rising as lines appear), and
 * mic warnings in the top-left when something needs your attention. The mic itself is an icon (MicIconHud).
 */
final class StatusHud implements HudElement {
	private static final int AQUA = 0xFF55FFFF;
	private static final int GOLD = 0xFFFFAA00;

	private final BrowserBridge bridge;
	private final GoogleChat chat;
	private final MeetViewHud meetView;

	StatusHud(BrowserBridge bridge, GoogleChat chat, MeetViewHud meetView) {
		this.bridge = bridge;
		this.chat = chat;
		this.meetView = meetView;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null || mc.getDebugOverlay().showDebugScreen()) {
			return;
		}
		drawWarning(graphics, mc);
		if (chat.running() && chat.current() != null) {
			drawChatLabel(graphics, mc, "» " + chat.currentLabel());
		}
	}

	private void drawWarning(GuiGraphicsExtractor graphics, Minecraft mc) {
		String warning = bridge.micStalled() ? "Meet mic audio stuck: reconnect your headset"
			: bridge.micViaButton() ? "Meet mic is muting slowly: refresh the Meet tab"
			: null;
		if (warning == null) {
			return;
		}
		// Under the minimap and the Meet mic icon, which stack in the top-left.
		int y = meetView.topLeftY(graphics.guiWidth()) + (bridge.micOn() != null && !meetView.isEnlarged() ? MicIconHud.SIZE + 4 : 0);
		graphics.text(mc.font, warning, 6, y, GOLD);
	}

	/** Where vanilla would draw one more line on top of the ones currently showing (see ChatComponent's layout). */
	private static void drawChatLabel(GuiGraphicsExtractor graphics, Minecraft mc, String label) {
		ChatComponent component = mc.gui.hud.getChat();
		ChatComponentAccessor access = (ChatComponentAccessor) component;
		List<GuiMessage.Line> lines = access.meetcraft$lines();
		boolean focused = component.isChatFocused();
		int ticks = ((HudAccessor) mc.gui.hud).meetcraft$tickCount();
		int shown = 0;
		int perPage = component.getLinesPerPage();
		for (int i = Math.min(lines.size() - access.meetcraft$scroll(), perPage) - 1; i >= 0; i--) {
			int age = ticks - lines.get(i + access.meetcraft$scroll()).addedTime();
			if (focused || age < 200) {
				shown = Math.max(shown, i + 1);
			}
		}
		double scale = access.meetcraft$scale();
		int entryHeight = (int) (9 * (mc.options.chatLineSpacing().get() + 1.0));
		int chatBottom = Mth.floor((graphics.guiHeight() - 40) / scale);
		int y = (int) Math.round((chatBottom - (shown + 1) * entryHeight + 1) * scale);
		graphics.text(mc.font, label, 4 + (int) Math.round(2 * scale), y, AQUA);
	}
}
