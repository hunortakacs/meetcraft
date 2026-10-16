package dev.meetcraft.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.meetcraft.MeetCraft;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jspecify.annotations.Nullable;

/**
 * Localhost WebSocket server that the MeetCraft browser extension connects to.
 * Text messages are JSON control messages, binary messages are JPEG frames of the Meet video.
 */
public final class BrowserBridge extends WebSocketServer {
	private final Consumer<ByteBuffer> onFrame;
	private final Consumer<@Nullable String> onMeetingChat;
	private volatile @Nullable WebSocket extension;
	private volatile @Nullable Boolean micOn;
	/** "gate" when the extension switches Meet's audio directly, "button" when it has to fall back to Meet's button. */
	private volatile @Nullable String micPath;
	private volatile boolean wantFrames;
	private volatile boolean framesArriving;
	private volatile int videoFps = 30;

	public BrowserBridge(int port, Consumer<ByteBuffer> onFrame, Consumer<@Nullable String> onMeetingChat) {
		super(new InetSocketAddress("127.0.0.1", port));
		this.onFrame = onFrame;
		this.onMeetingChat = onMeetingChat;
		setReuseAddr(true);
		// Mic messages are tiny; don't let Nagle hold them back waiting for more.
		setTcpNoDelay(true);
		setDaemon(true);
	}

	public boolean isConnected() {
		WebSocket ws = extension;
		return ws != null && ws.isOpen();
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
		send(msg);
	}

	private boolean send(JsonObject msg) {
		WebSocket ws = extension;
		if (ws == null || !ws.isOpen()) {
			return false;
		}
		ws.send(msg.toString());
		return true;
	}

	@Override
	public void onOpen(WebSocket conn, ClientHandshake handshake) {
		// Any web page could open ws://127.0.0.1, so only accept the browser extension.
		String origin = handshake.getFieldValue("Origin");
		if (!origin.startsWith("chrome-extension://") && !origin.startsWith("moz-extension://")) {
			conn.close(1008, "MeetCraft only accepts its browser extension");
			return;
		}
		WebSocket previous = extension;
		extension = conn;
		if (previous != null && previous != conn) {
			previous.close(1000, "Replaced by a newer connection");
		}
		framesArriving = false;
		MeetCraft.LOGGER.info("Browser extension connected");
		sendFramesRequest();
	}

	@Override
	public void onClose(WebSocket conn, int code, String reason, boolean remote) {
		if (conn == extension) {
			extension = null;
			micOn = null;
			micPath = null;
			MeetCraft.LOGGER.info("Browser extension disconnected");
		}
	}

	@Override
	public void onMessage(WebSocket conn, String message) {
		if (conn != extension) {
			return;
		}
		JsonObject msg = JsonParser.parseString(message).getAsJsonObject();
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
	public void onMessage(WebSocket conn, ByteBuffer frame) {
		if (conn == extension && wantFrames) {
			if (!framesArriving) {
				framesArriving = true;
				MeetCraft.LOGGER.info("Meet video is arriving ({} KB frames)", frame.remaining() / 1024);
			}
			onFrame.accept(frame);
		}
	}

	@Override
	public void onError(@Nullable WebSocket conn, Exception ex) {
		MeetCraft.LOGGER.warn("Browser bridge error: {}", ex.toString());
	}

	@Override
	public void onStart() {
		MeetCraft.LOGGER.info("Waiting for the MeetCraft browser extension on ws://{}", getAddress());
	}
}
