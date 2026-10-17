package dev.meetcraft.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.meetcraft.MeetCraft;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.jspecify.annotations.Nullable;

/**
 * A localhost WebSocket server for one of MeetCraft's browser extensions, which holds at most one connection: a newer
 * one replaces the old. Text messages are JSON, binary messages are JPEG frames.
 */
abstract class ExtensionServer extends WebSocketServer {
	/** For the log: "Meet", "Instagram". */
	private final String name;
	private volatile @Nullable WebSocket extension;

	ExtensionServer(String name, int port) {
		super(new InetSocketAddress("127.0.0.1", port));
		this.name = name;
		setReuseAddr(true);
		// Control messages are tiny; don't let Nagle hold them back waiting for more.
		setTcpNoDelay(true);
		setDaemon(true);
	}

	public boolean isConnected() {
		WebSocket ws = extension;
		return ws != null && ws.isOpen();
	}

	protected boolean send(JsonObject msg) {
		WebSocket ws = extension;
		if (ws == null || !ws.isOpen()) {
			return false;
		}
		ws.send(msg.toString());
		return true;
	}

	/** The extension has just connected (and replaced any earlier connection). */
	protected abstract void onConnected();

	protected abstract void onDisconnected();

	protected abstract void onJson(JsonObject msg);

	protected abstract void onFrame(ByteBuffer frame);

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
		MeetCraft.LOGGER.info("{} browser extension connected", name);
		onConnected();
	}

	@Override
	public void onClose(WebSocket conn, int code, String reason, boolean remote) {
		if (conn == extension) {
			extension = null;
			MeetCraft.LOGGER.info("{} browser extension disconnected", name);
			onDisconnected();
		}
	}

	@Override
	public void onMessage(WebSocket conn, String message) {
		if (conn == extension) {
			onJson(JsonParser.parseString(message).getAsJsonObject());
		}
	}

	@Override
	public void onMessage(WebSocket conn, ByteBuffer frame) {
		if (conn == extension) {
			onFrame(frame);
		}
	}

	@Override
	public void onError(@Nullable WebSocket conn, Exception ex) {
		MeetCraft.LOGGER.warn("{} browser bridge error: {}", name, ex.toString());
	}

	@Override
	public void onStart() {
		MeetCraft.LOGGER.info("Waiting for the {} browser extension on ws://{}", name, getAddress());
	}
}
