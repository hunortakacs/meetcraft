package dev.meetcraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.meetcraft.MeetCraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/** {@code config/meetcraft-client.json}; field names double as the JSON keys. */
final class ClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	enum MinimapCorner {
		TOP_LEFT,
		TOP_RIGHT
	}

	enum ViewKeyMode {
		/** Each press enlarges or shrinks the Meet view. */
		TOGGLE,
		/** Enlarged only while the key is held. */
		HOLD
	}

	enum MicKeyMode {
		/** Hold the key to talk; releasing mutes. */
		PUSH_TO_TALK,
		/** Each press flips between live and muted. */
		TOGGLE
	}

	enum ReelsSide {
		LEFT,
		RIGHT
	}

	static final int MAX_CORNER_RADIUS = 16;

	/** Which default key layout this config has been brought up to (see MeetCraftClient.updateDefaultKeys). */
	int keyDefaults = 0;
	/** Plasmo Voice's own mute from before the Meet mic muted it, while that's in effect; restored after a crash. */
	@Nullable Boolean plasmoMuteRestore;

	int port = 25599;
	MicKeyMode micKey = MicKeyMode.PUSH_TO_TALK;
	/** The small live Meet view in the top-right corner while you're in a call. */
	boolean minimap = true;
	ViewKeyMode viewKey = ViewKeyMode.TOGGLE;
	MinimapCorner minimapCorner = MinimapCorner.TOP_LEFT;
	static final int MIN_VIDEO_FPS = 5;
	static final int MAX_VIDEO_FPS = 30;

	/** How many frames per second the browser captures of the call. */
	int videoFps = 30;
	/** Corner rounding of the Meet view in GUI pixels (0 = square); the enlarged view rounds a bit more. */
	int cornerRadius = 6;

	/** Where the MeetCraft Reels extension connects (Instagram); separate from the Meet extension's port. */
	int reelsPort = 25600;
	/** Instagram reels play without sound. */
	boolean reelsMuted = false;
	/** When a reel finishes, scroll to the next one without a key press. */
	boolean reelsAuto = false;
	ReelsSide reelsSide = ReelsSide.RIGHT;
	static final int MIN_REELS_SIZE = 30;
	static final int MAX_REELS_SIZE = 95;
	/** Height of the reels view, in percent of the screen's. */
	int reelsSize = 70;

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("meetcraft-client.json");
	}

	static ClientConfig load() {
		try {
			if (Files.exists(file())) {
				ClientConfig config = GSON.fromJson(Files.readString(file()), ClientConfig.class);
				// Gson leaves a misspelled or missing enum as null.
				if (config.micKey == null) {
					config.micKey = MicKeyMode.PUSH_TO_TALK;
				}
				config.cornerRadius = Math.clamp(config.cornerRadius, 0, MAX_CORNER_RADIUS);
				config.videoFps = Math.clamp(config.videoFps, MIN_VIDEO_FPS, MAX_VIDEO_FPS);
				if (config.viewKey == null) {
					config.viewKey = ViewKeyMode.TOGGLE;
				}
				if (config.minimapCorner == null) {
					config.minimapCorner = MinimapCorner.TOP_LEFT;
				}
				if (config.reelsSide == null) {
					config.reelsSide = ReelsSide.RIGHT;
				}
				config.reelsSize = Math.clamp(config.reelsSize, MIN_REELS_SIZE, MAX_REELS_SIZE);
				return config;
			}
		} catch (Exception e) {
			MeetCraft.LOGGER.error("Could not read {}", file(), e);
		}
		ClientConfig config = new ClientConfig();
		config.save();
		return config;
	}

	void save() {
		try {
			Files.writeString(file(), GSON.toJson(this));
		} catch (IOException e) {
			MeetCraft.LOGGER.error("Could not save {}", file(), e);
		}
	}
}
