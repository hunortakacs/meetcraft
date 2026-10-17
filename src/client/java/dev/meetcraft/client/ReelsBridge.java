package dev.meetcraft.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.meetcraft.MeetCraft;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Where the MeetCraft Reels browser extension connects: the game says whether the reels view is open, whether reels
 * are muted and whether they move on by themselves, and the extension sends back JPEG frames of the reel on screen
 * plus where it's at. All local; nothing about reels goes through the Minecraft server.
 */
final class ReelsBridge extends ExtensionServer {
	/**
	 * What Instagram is doing, as last reported; {@link #NONE} until it says. {@code soundBlocked}: Firefox won't play
	 * sound until the Instagram tab has been clicked once, so the reel plays silently.
	 */
	record Reel(boolean tabOpen, boolean playing, boolean muted, boolean soundBlocked, double time, double duration) {
	}

	static final Reel NONE = new Reel(false, false, false, false, 0, 0);

	private final VideoFeed video;
	private volatile Reel reel = NONE;
	private volatile boolean framesArriving;
	/** The last state sent, resent as is when the extension (re)connects. */
	private volatile JsonObject state = new JsonObject();

	ReelsBridge(int port, VideoFeed video) {
		super("Instagram", port);
		this.video = video;
	}

	Reel reel() {
		return isConnected() ? reel : NONE;
	}

	/** Sends the game's side of things when any of it changed; the extension acts on each change. */
	void setState(boolean view, int fps, boolean muted, boolean auto) {
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "state");
		msg.addProperty("view", view);
		msg.addProperty("fps", fps);
		msg.addProperty("muted", muted);
		msg.addProperty("auto", auto);
		if (!Objects.equals(msg, state)) {
			boolean wasViewing = state.has("view") && state.get("view").getAsBoolean();
			if (view && !wasViewing) {
				framesArriving = false; // log the next first frame again
			}
			state = msg;
			send(msg);
		}
	}

	/** Scrolls to the next reel ({@code by} = 1) or back to the previous one (-1). */
	boolean step(int by) {
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "step");
		msg.addProperty("by", by);
		return send(msg);
	}

	@Override
	protected void onConnected() {
		framesArriving = false;
		if (state.has("type")) {
			send(state);
		}
	}

	@Override
	protected void onDisconnected() {
		reel = NONE;
	}

	@Override
	protected void onJson(JsonObject msg) {
		if (msg.get("type").getAsString().equals("reel")) {
			reel = new Reel(bool(msg, "tab"), bool(msg, "playing"), bool(msg, "muted"), bool(msg, "soundBlocked"),
				number(msg, "time"), number(msg, "duration"));
		}
	}

	@Override
	protected void onFrame(ByteBuffer frame) {
		if (!framesArriving) {
			framesArriving = true;
			MeetCraft.LOGGER.info("Instagram video is arriving ({} KB frames)", frame.remaining() / 1024);
		}
		video.acceptJpeg(frame);
	}

	private static boolean bool(JsonObject msg, String key) {
		JsonElement value = msg.get(key);
		return value != null && !value.isJsonNull() && value.getAsBoolean();
	}

	private static double number(JsonObject msg, String key) {
		JsonElement value = msg.get(key);
		return value == null || value.isJsonNull() ? 0 : value.getAsDouble();
	}
}
