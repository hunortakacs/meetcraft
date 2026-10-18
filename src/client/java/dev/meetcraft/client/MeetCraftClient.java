package dev.meetcraft.client;

import dev.meetcraft.MeetCraft;
import dev.meetcraft.client.chat.ChatCommands;
import dev.meetcraft.client.chat.GoogleAccount;
import dev.meetcraft.client.chat.GoogleChat;
import dev.meetcraft.net.Payloads;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import dev.meetcraft.client.voice.PlasmoGuard;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public class MeetCraftClient implements ClientModInitializer {
	/** Inside a Google chat, a line starting with this goes to Minecraft instead. */
	private static final String MINECRAFT_ESCAPE = "!";
	private static final String OPTIONS_BUTTON = "MeetCraft…";

	private static ClientConfig config;
	private static MicKey micKey;
	private static MeetViewHud meetView;
	private static ReelsHud reels;
	private static ChatCommands commands;
	/** Keeps Plasmo Voice muted while the Meet mic is live; does nothing without Plasmo. */
	private Consumer<Boolean> plasmoUpdate = live -> {
	};

	private final VideoFeed meetVideo = new VideoFeed("video", "MeetCraft video");
	private final VideoFeed reelsVideo = new VideoFeed("reels", "MeetCraft reels");
	private final ScreenRenderer screens = new ScreenRenderer(meetVideo);
	private final GoogleChat chat = new GoogleChat(new GoogleAccount());
	private BrowserBridge bridge;
	private volatile boolean inWorld;

	static Screen settingsScreen(Screen parent) {
		return new SettingsScreen(parent, config, micKey.mapping, meetView.mapping, meetView.minimapKey, reels, commands::setupScreen);
	}

	/** Earlier defaults: K, J, H (out of reach with the right hand on the mouse), then R, Z, Y. */
	private static final int KEY_DEFAULTS = 2;
	private static final String[][] OLD_DEFAULT_KEYS = {
		{"key.meetcraft.mic", "key.keyboard.k"},
		{"key.meetcraft.view", "key.keyboard.j"},
		{"key.meetcraft.view", "key.keyboard.z"},
		{"key.meetcraft.minimap", "key.keyboard.h"},
		{"key.meetcraft.minimap", "key.keyboard.y"},
	};

	/**
	 * Minecraft saves every key to options.txt, so new defaults alone never reach existing players. Once, after the
	 * options have loaded, move keys still on an old default to the new one; anything rebound by hand stays put.
	 */
	private static void updateDefaultKeys(Minecraft client) {
		if (config.keyDefaults >= KEY_DEFAULTS) {
			return;
		}
		for (KeyMapping mapping : new KeyMapping[] {micKey.mapping, meetView.mapping, meetView.minimapKey}) {
			for (String[] old : OLD_DEFAULT_KEYS) {
				if (mapping.getName().equals(old[0]) && mapping.saveString().equals(old[1])) {
					mapping.setKey(mapping.getDefaultKey());
				}
			}
		}
		KeyMapping.resetMapping();
		client.options.save();
		config.keyDefaults = KEY_DEFAULTS;
		config.save();
	}

	/** From the moment the key asks for it (so the first word doesn't go to both), for as long as Meet is sending. */
	private boolean meetMicLive() {
		return Boolean.TRUE.equals(bridge.micOn()) || (micKey.wantLive() && bridge.micOn() != null);
	}

	@Override
	public void onInitializeClient() {
		config = ClientConfig.load();
		micKey = new MicKey();
		bridge = new BrowserBridge(config.port, meetVideo::acceptJpeg, chat::setMeetingSpace);
		bridge.start();
		ReelsBridge reelsBridge = new ReelsBridge(config.reelsPort, reelsVideo);
		reelsBridge.start();

		ClientPlayNetworking.registerGlobalReceiver(Payloads.Screens.TYPE, (payload, context) -> screens.setScreens(payload.screens()));

		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> {
			inWorld = true;
			chat.start();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
			inWorld = false;
			screens.setScreens(List.of());
			chat.stop();
		});
		// Inside a Google chat, typed lines go there and never reach the Minecraft server.
		ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
			if (chat.current() == null || message.startsWith(MINECRAFT_ESCAPE)) {
				return true;
			}
			chat.send(message);
			return false;
		});
		ClientSendMessageEvents.MODIFY_CHAT.register(message ->
			chat.current() != null && message.startsWith(MINECRAFT_ESCAPE) ? message.substring(MINECRAFT_ESCAPE.length()).stripLeading() : message);

		meetView = new MeetViewHud(meetVideo, bridge, config);
		reels = new ReelsHud(reelsVideo, reelsBridge, meetView, config);
		// The in-game mic sends nothing while the Meet one is live: from the moment the key asks for it (so the first
		// word doesn't go to both), for as long as Meet is actually sending.
		if (FabricLoader.getInstance().isModLoaded("plasmovoice")) {
			plasmoUpdate = PlasmoGuard.install(this::meetMicLive, () -> config.plasmoMuteRestore, restore -> {
				config.plasmoMuteRestore = restore;
				config.save();
			});
		}
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			updateDefaultKeys(client);
			plasmoUpdate.accept(meetMicLive());
			meetView.tick();
			bridge.setVideoFps(config.videoFps);
			bridge.setLiveTiles(config.meetLiveTiles);
			bridge.setWantFrames(inWorld && (screens.anyScreenNearby(client) || meetView.wantsVideo()));
			micKey.tick(client, bridge, config.micKey);
			reels.tick(client);
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(screens::render);
		HudElementRegistry.addLast(MeetCraft.id("status"), new StatusHud(bridge, chat, meetView));
		HudElementRegistry.addLast(MeetCraft.id("mic"), new MicIconHud(bridge, meetView));
		HudElementRegistry.addLast(MeetCraft.id("reels"), reels);
		HudElementRegistry.addLast(MeetCraft.id("meet_view"), meetView);
		commands = new ChatCommands(chat, () -> inWorld);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> commands.register(dispatcher));

		// Settings without needing Mod Menu: one more full-width row under vanilla's Options grid. This also fires on
		// resize, when vanilla only moves its widgets, so the old button is swapped out instead of piling up.
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof OptionsScreen)) {
				return;
			}
			List<AbstractWidget> widgets = Screens.getWidgets(screen);
			widgets.removeIf(w -> w.getMessage().getString().equals(OPTIONS_BUTTON));
			// The grid is every 150-wide option button below the header; the FOV row above it is the same width.
			List<AbstractWidget> grid = widgets.stream().filter(w -> w.getWidth() == 150 && w.getY() > 61).toList();
			if (grid.isEmpty()) {
				return;
			}
			int left = grid.stream().mapToInt(AbstractWidget::getX).min().orElseThrow();
			int right = grid.stream().mapToInt(AbstractWidget::getRight).max().orElseThrow();
			int top = grid.stream().mapToInt(AbstractWidget::getY).min().orElseThrow();
			int bottom = grid.stream().mapToInt(AbstractWidget::getBottom).max().orElseThrow();
			// Found by its label: after a resize it may still carry the narrower width we gave it last time.
			AbstractWidget done = widgets.stream().filter(w -> w.getMessage().equals(CommonComponents.GUI_DONE))
				.max(Comparator.comparingInt(AbstractWidget::getY)).orElse(null);
			int free = (done != null ? done.getY() - 4 : height) - 61;
			Button button;
			if (bottom - top + 24 <= free) {
				// Room for one more row: centre the grid plus our row between the header and Done.
				int shift = 61 + (free - (bottom - top + 24)) / 2 - top;
				grid.forEach(w -> w.setY(w.getY() + shift));
				if (done != null) {
					done.setWidth(200);
					done.setX(width / 2 - 100);
				}
				button = Button.builder(Component.literal(OPTIONS_BUTTON), b -> client.gui.setScreen(settingsScreen(screen)))
					.bounds(left, bottom + shift + 4, right - left, 20)
					.build();
			} else if (done != null) {
				// A short window: share the footer with Done instead, the way vanilla lays out two footer buttons.
				done.setWidth(150);
				done.setX(width / 2 - 154);
				button = Button.builder(Component.literal(OPTIONS_BUTTON), b -> client.gui.setScreen(settingsScreen(screen)))
					.bounds(width / 2 + 4, done.getY(), 150, 20)
					.build();
			} else {
				return;
			}
			widgets.add(button);
		});
	}
}
