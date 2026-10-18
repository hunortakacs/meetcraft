package dev.meetcraft.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.meetcraft.MeetCraft;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Where the MeetCraft (Meet) browser extension connects: mic control, and JPEG frames of the Meet video. */
public final class BrowserBridge extends ExtensionServer {
	private final Consumer<ByteBuffer> onFrame;
	private final Consumer<@Nullable String> onMeetingChat;
	private volatile @Nullable Boolean micOn;
	/** "gate" when the extension switches Meet's audio directly, "button" when it has to fall back to Meet's button. */
	private volatile @Nullable String micPath;
	private volatile boolean wantFrames;
	private volatile boolean framesArriving;
	private volatile int videoFps = 30;
	private volatile boolean liveTiles = true;

	public BrowserBridge(int port, Consumer<ByteBuffer> onFrame, Consumer<@Nullable String> onMeetingChat) {
		super("Meet", port);
		this.onFrame = onFrame;
		this.onMeetingChat = onMeetingChat;
	}

	/** Mic state last reported by Meet, or null when not in a call / not connected. */
	public @Nullable Boolean micOn() {
		return isConnected() ? micOn : null;
	}

	/** True when the mic is only as fast and sure as Meet reacting to its button, because the gate isn't holding its audio. */
	public boolean micViaButton() {
		return isConnected() && "button".equals(micPath);
	}

	/** True when the gate's audio engine is paused in the browser: you're silent even while holding the key. */
	public boolean micPaused() {
		return isConnected() && "paused".equals(micPath);
	}

	/** True when the browser's audio for the mic has stopped moving, typically a Bluetooth headset's call link hanging. */
	public boolean micStalled() {
		return isConnected() && "stalled".equals(micPath);
	}

	public boolean setMic(boolean on) {
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "setMic");
		msg.addProperty("on", on);
		return send(msg);
	}

	public void setVideoFps(int fps) {
		if (fps != videoFps) {
			videoFps = fps;
			sendFramesRequest();
		}
	}

	/** Whether the extension draws the call's videos live over a slower picture of the page (see meet.js). */
	public void setLiveTiles(boolean live) {
		if (live != liveTiles) {
			liveTiles = live;
			sendFramesRequest();
		}
	}

	public void setWantFrames(boolean want) {
		if (want != wantFrames) {
			wantFrames = want;
			framesArriving = false;
			MeetCraft.LOGGER.info(want ? "Near a screen; asking the browser for the Meet video" : "No screen nearby; video paused");
			sendFramesRequest();
		}
	}

	private void sendFramesRequest() {
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "frames");
		msg.addProperty("on", wantFrames);
		msg.addProperty("fps", videoFps);
		msg.addProperty("live", liveTiles);
		send(msg);
	}

	@Override
	protected void onConnected() {
		framesArriving = false;
		sendFramesRequest();
	}

	@Override
	protected void onDisconnected() {
		micOn = null;
		micPath = null;
	}

	@Override
	protected void onJson(JsonObject msg) {
		switch (msg.get("type").getAsString()) {
			case "mic" -> {
				JsonElement on = msg.get("on");
				micOn = on == null || on.isJsonNull() ? null : on.getAsBoolean();
				JsonElement path = msg.get("gate");
				micPath = path == null || path.isJsonNull() ? null : path.getAsString();
			}
			// The Google Chat space attached to the Meet call, when the Meet page links to one.
			case "meetChat" -> {
				JsonElement space = msg.get("space");
				onMeetingChat.accept(space == null || space.isJsonNull() ? null : space.getAsString());
			}
			default -> {
			}
		}
	}

	@Override
	protected void onFrame(ByteBuffer frame) {
		if (wantFrames) {
			if (!framesArriving) {
				framesArriving = true;
				MeetCraft.LOGGER.info("Meet video is arriving ({} KB frames)", frame.remaining() / 1024);
			}
			onFrame.accept(frame);
		}
	}
}
