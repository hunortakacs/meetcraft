package dev.meetcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** The Meet mic key: hold-to-talk or toggle, whichever the player picked in MeetCraft's settings. */
final class MicKey {
	// R: free in vanilla and under your left hand while it's on WASD; Plasmo Voice's own talk key is Left Alt.
	// Rebindable under Controls → Multiplayer.
	final KeyMapping mapping = KeyMappingHelper.registerKeyMapping(
		new KeyMapping("key.meetcraft.mic", InputConstants.KEY_R, KeyMapping.Category.MULTIPLAYER));
	/** The game's intent is resent this often, so the extension can always put Meet back where we want it. */
	private static final long RESEND_MILLIS = 500;
	/** What the player wants, whatever Meet currently shows. Always starts muted. */
	private volatile boolean wantLive;
	private long lastSentAt;
	private boolean wasConnected;

	boolean wantLive() {
		return wantLive;
	}

	void tick(Minecraft client, BrowserBridge bridge, ClientConfig.MicKeyMode mode) {
		boolean before = wantLive;
		switch (mode) {
			case PUSH_TO_TALK -> {
				// Presses queue up as clicks even here; drop them so switching to toggle doesn't replay them.
				while (mapping.consumeClick()) {
				}
				wantLive = mapping.isDown();
			}
			case TOGGLE -> {
				while (mapping.consumeClick()) {
					wantLive = !wantLive;
					overlay(client, !bridge.isConnected()
						? "Meet isn't connected; open your Meet tab with the MeetCraft extension."
						: wantLive ? "Mic live" : "Mic muted");
				}
			}
		}
		if (mode == ClientConfig.MicKeyMode.PUSH_TO_TALK && wantLive && !before && !bridge.isConnected()) {
			overlay(client, "Meet isn't connected; open your Meet tab with the MeetCraft extension.");
		}
		boolean connected = bridge.isConnected();
		long now = System.currentTimeMillis();
		if (connected && (wantLive != before || !wasConnected || now - lastSentAt >= RESEND_MILLIS)) {
			bridge.setMic(wantLive);
			lastSentAt = now;
		}
		wasConnected = connected;
	}

	private static void overlay(Minecraft client, String text) {
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.literal(text));
		}
	}
}
