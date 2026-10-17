package dev.meetcraft.client;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;

/** MeetCraft's page, laid out like vanilla's option pages. Reached from Options (top-left) or Mod Menu. */
final class SettingsScreen extends OptionsSubScreen {
	private final ClientConfig config;
	private final KeyMapping micKey;
	private final KeyMapping viewKey;
	private final KeyMapping minimapKey;
	private final ReelsHud reels;
	private final UnaryOperator<Screen> googleSetup;

	SettingsScreen(Screen parent, ClientConfig config, KeyMapping micKey, KeyMapping viewKey, KeyMapping minimapKey, ReelsHud reels,
				   UnaryOperator<Screen> googleSetup) {
		super(parent, Minecraft.getInstance().options, Component.literal("MeetCraft"));
		this.config = config;
		this.micKey = micKey;
		this.viewKey = viewKey;
		this.minimapKey = minimapKey;
		this.reels = reels;
		this.googleSetup = googleSetup;
	}

	@Override
	protected void addOptions() {
		CycleButton<ClientConfig.MicKeyMode> mode = CycleButton.builder(SettingsScreen::label, config.micKey)
			.withValues(ClientConfig.MicKeyMode.values())
			.withTooltip(m -> Tooltip.create(Component.literal(m == ClientConfig.MicKeyMode.PUSH_TO_TALK
				? "Hold the key to talk; let go to mute."
				: "Press the key to go live, press again to mute.")))
			.create(Component.literal("Mic key"), (button, value) -> {
				config.micKey = value;
				config.save();
			});
		// Rebinding stays in vanilla Controls, so it behaves (and conflicts) like every other key.
		Button key = Button.builder(Component.literal("Key: ").append(micKey.getTranslatedKeyMessage()),
				button -> minecraft.gui.setScreen(new KeyBindsScreen(this, options)))
			.build();
		list.addSmall(mode, key);
		CycleButton<ClientConfig.ViewKeyMode> viewMode = CycleButton.builder(SettingsScreen::viewLabel, config.viewKey)
			.withValues(ClientConfig.ViewKeyMode.values())
			.withTooltip(m -> Tooltip.create(Component.literal(m == ClientConfig.ViewKeyMode.HOLD
				? "The Meet view is enlarged while you hold the key."
				: "Press the key to enlarge the Meet view, press again to shrink it.")))
			.create(Component.literal("Meet view key"), (button, value) -> {
				config.viewKey = value;
				config.save();
			});
		Button viewKeyButton = Button.builder(Component.literal("Key: ").append(viewKey.getTranslatedKeyMessage()),
				button -> minecraft.gui.setScreen(new KeyBindsScreen(this, options)))
			.build();
		list.addSmall(viewMode, viewKeyButton);
		CycleButton<Boolean> minimap = CycleButton.onOffBuilder(config.minimap)
			.withTooltip(on -> Tooltip.create(Component.literal("A small live view of your Meet tab in a corner of the screen while you're in a call.")))
			.create(Component.literal("Meet minimap"), (button, value) -> {
				config.minimap = value;
				config.save();
			});
		Button minimapKeyButton = Button.builder(Component.literal("Key: ").append(minimapKey.getTranslatedKeyMessage()),
				button -> minecraft.gui.setScreen(new KeyBindsScreen(this, options)))
			.build();
		list.addSmall(minimap, minimapKeyButton);
		CycleButton<ClientConfig.MinimapCorner> corner = CycleButton.builder(SettingsScreen::cornerLabel, config.minimapCorner)
			.withValues(ClientConfig.MinimapCorner.values())
			.create(Component.literal("Minimap"), (button, value) -> {
				config.minimapCorner = value;
				config.save();
			});
		list.addSmall(corner, new IntSlider(config, 0, ClientConfig.MAX_CORNER_RADIUS, config.cornerRadius,
			r -> "Corners: " + (r == 0 ? "square" : r), r -> config.cornerRadius = r));
		IntSlider fps = new IntSlider(config, ClientConfig.MIN_VIDEO_FPS, ClientConfig.MAX_VIDEO_FPS, config.videoFps,
			f -> "Video frame rate: " + f + " fps", f -> config.videoFps = f);
		fps.setWidth(310);
		fps.setTooltip(Tooltip.create(Component.literal("How often the Meet tab is captured. Higher is smoother, and works the Meet tab harder.")));
		list.addBig(fps);
		addReelsOptions();
		list.addBig(Button.builder(Component.literal("Google Chat setup…"), button -> minecraft.gui.setScreen(googleSetup.apply(this))).build());
	}

	private void addReelsOptions() {
		CycleButton<Boolean> sound = CycleButton.onOffBuilder(!config.reelsMuted)
			.withTooltip(on -> Tooltip.create(Component.literal("Whether Instagram reels play with sound. Mutes the Instagram tab in Firefox.")))
			.create(Component.literal("Reels sound"), (button, value) -> {
				config.reelsMuted = !value;
				config.save();
			});
		list.addSmall(sound, keyButton(reels.muteKey));
		CycleButton<Boolean> auto = CycleButton.onOffBuilder(config.reelsAuto)
			.withTooltip(on -> Tooltip.create(Component.literal("When a reel finishes, scroll to the next one by itself, no key needed.")))
			.create(Component.literal("Reels auto-scroll"), (button, value) -> {
				config.reelsAuto = value;
				config.save();
			});
		list.addSmall(auto, keyButton(reels.autoKey));
		CycleButton<ClientConfig.ReelsSide> side = CycleButton.builder(SettingsScreen::sideLabel, config.reelsSide)
			.withValues(ClientConfig.ReelsSide.values())
			.create(Component.literal("Reels"), (button, value) -> {
				config.reelsSide = value;
				config.save();
			});
		list.addSmall(side, new IntSlider(config, ClientConfig.MIN_REELS_SIZE, ClientConfig.MAX_REELS_SIZE, config.reelsSize,
			s -> "Reels size: " + s + "%", s -> config.reelsSize = s));
	}

	/** Rebinding stays in vanilla Controls, so it behaves (and conflicts) like every other key. */
	private Button keyButton(KeyMapping mapping) {
		return Button.builder(Component.literal("Key: ").append(mapping.getTranslatedKeyMessage()),
				button -> minecraft.gui.setScreen(new KeyBindsScreen(this, options)))
			.build();
	}

	/** A whole-number slider; saved when you let go, so dragging doesn't write the file on every step. */
	private static final class IntSlider extends AbstractSliderButton {
		private final ClientConfig config;
		private final int min;
		private final int max;
		private final IntFunction<String> label;
		private final IntConsumer apply;

		IntSlider(ClientConfig config, int min, int max, int current, IntFunction<String> label, IntConsumer apply) {
			super(0, 0, 150, 20, Component.empty(), (current - min) / (double) (max - min));
			this.config = config;
			this.min = min;
			this.max = max;
			this.label = label;
			this.apply = apply;
			updateMessage();
		}

		private int current() {
			return min + (int) Math.round(value * (max - min));
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(label.apply(current())));
		}

		@Override
		protected void applyValue() {
			apply.accept(current());
		}

		@Override
		public void onRelease(MouseButtonEvent event) {
			super.onRelease(event);
			config.save();
		}
	}

	private static Component viewLabel(ClientConfig.ViewKeyMode mode) {
		return Component.literal(mode == ClientConfig.ViewKeyMode.HOLD ? "Hold" : "Toggle");
	}

	private static Component sideLabel(ClientConfig.ReelsSide side) {
		return Component.literal(side == ClientConfig.ReelsSide.LEFT ? "Left side" : "Right side");
	}

	private static Component cornerLabel(ClientConfig.MinimapCorner corner) {
		return Component.literal(corner == ClientConfig.MinimapCorner.TOP_LEFT ? "Top left" : "Top right");
	}

	private static Component label(ClientConfig.MicKeyMode mode) {
		return Component.literal(mode == ClientConfig.MicKeyMode.PUSH_TO_TALK ? "Push to talk" : "Toggle");
	}
}
