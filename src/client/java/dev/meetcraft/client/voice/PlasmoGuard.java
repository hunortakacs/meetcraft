package dev.meetcraft.client.voice;

import dev.meetcraft.MeetCraft;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import su.plo.config.entry.BooleanConfigEntry;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.AddonLoaderScope;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.client.PlasmoVoiceClient;
import su.plo.voice.api.client.event.audio.capture.AudioCaptureEvent;
import su.plo.voice.api.event.EventPriority;

/**
 * While your Meet mic is live, Plasmo Voice is muted, visibly, with its own mute, so its icon shows it. When the Meet
 * mic goes quiet, Plasmo's mute goes back to exactly what it was before (already muted stays muted). Nothing else of
 * Plasmo's is touched.
 *
 * <p>Two layers: the mute setting, and asking Plasmo to flush its activations for every captured chunk while live,
 * which Plasmo treats like being muted too. The flush works from the very first chunk, before the setting has caught
 * up, and keeps working if anything flips the setting back mid-call.
 *
 * <p>The state to restore is saved as soon as it's taken, so a crash or quit while live is undone on the next start.
 */
@Addon(id = "meetcraft", name = "MeetCraft", version = "0.1.0", authors = {"Hunor"}, scope = AddonLoaderScope.CLIENT)
public final class PlasmoGuard implements AddonInitializer {
	@InjectPlasmoVoice
	private @Nullable PlasmoVoiceClient voiceClient;
	private final BooleanSupplier meetMicLive;
	private final Supplier<@Nullable Boolean> savedRestore;
	private final Consumer<@Nullable Boolean> saveRestore;
	/** Plasmo's mute before we muted it; null while we haven't touched it. Game thread only. */
	private @Nullable Boolean restoreTo;

	private PlasmoGuard(BooleanSupplier meetMicLive, Supplier<@Nullable Boolean> savedRestore, Consumer<@Nullable Boolean> saveRestore) {
		this.meetMicLive = meetMicLive;
		this.savedRestore = savedRestore;
		this.saveRestore = saveRestore;
	}

	/**
	 * Registers with Plasmo and returns the per-tick update (game thread). {@code meetMicLive} is also read on Plasmo's
	 * capture thread, so it has to be safe to call from there.
	 */
	public static Consumer<Boolean> install(BooleanSupplier meetMicLive, Supplier<@Nullable Boolean> savedRestore,
											Consumer<@Nullable Boolean> saveRestore) {
		PlasmoGuard guard = new PlasmoGuard(meetMicLive, savedRestore, saveRestore);
		PlasmoVoiceClient.getAddonsLoader().load(guard);
		return guard::update;
	}

	@Override
	public void onAddonInitialize() {
		voiceClient.getEventBus().register(this, AudioCaptureEvent.class, EventPriority.HIGHEST, event -> {
			if (meetMicLive.getAsBoolean()) {
				event.setFlushActivations(true);
			}
		});
		// Left over from a crash or quit while the Meet mic was live.
		Boolean leftover = savedRestore.get();
		if (leftover != null) {
			muted().set(leftover);
			saveRestore.accept(null);
		}
		MeetCraft.LOGGER.info("Plasmo Voice: muted while your Meet mic is live, then put back as it was");
	}

	@Override
	public void onAddonShutdown() {
		update(false);
	}

	private void update(boolean meetLive) {
		if (voiceClient == null) {
			return; // Plasmo hasn't started yet
		}
		BooleanConfigEntry muted = muted();
		if (meetLive) {
			if (restoreTo == null) {
				restoreTo = muted.value();
				saveRestore.accept(restoreTo);
			}
			if (!muted.value()) {
				muted.set(true);
			}
		} else if (restoreTo != null) {
			muted.set(restoreTo);
			restoreTo = null;
			saveRestore.accept(null);
		}
	}

	private BooleanConfigEntry muted() {
		return voiceClient.getConfig().getVoice().getMicrophoneDisabled();
	}
}
