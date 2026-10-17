package dev.meetcraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.meetcraft.MeetCraft;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * A live picture from the browser: JPEG frames arrive on a WebSocket thread, get decoded there, and go up into a texture
 * on the render thread. One for the Meet call, one for Instagram reels.
 */
final class VideoFeed {
	/** Frames older than this are treated as a lost signal. */
	private static final long SIGNAL_TIMEOUT_MS = 3000;

	private record Frame(int width, int height, ByteBuffer rgba) {
		void free() {
			STBImage.stbi_image_free(rgba);
		}
	}

	/** Linear filtering instead of the default nearest, so video looks smooth rather than blocky up close. */
	private static final class VideoTexture extends DynamicTexture {
		VideoTexture(String label, int width, int height) {
			super(() -> label, width, height, false);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}

	private final String label;
	/**
	 * The texture alternates between two names: when the frame size changes, the new one goes up under the other name
	 * and the old one is only freed on the next frame, because something drawn this frame may still use it.
	 */
	private final Identifier[] ids;
	private int slot;
	private @Nullable Identifier retired;
	private final AtomicReference<@Nullable Frame> pending = new AtomicReference<>();
	private @Nullable DynamicTexture video;
	private volatile long lastFrameAt;
	private volatile boolean warnedUndecodable;

	// Frame rates over the last second: everything that arrived, and how much of it was a new picture. The browser
	// captures at a steady rate, but the video itself (screen shares especially) often changes far less often.
	private long lastChecksum;
	private int received;
	private int fresh;
	private long windowStart = System.currentTimeMillis();
	private volatile int receivedPerSecond;
	private volatile int freshPerSecond;

	/** {@code name} names the textures (dynamic/name_a and _b); {@code label} is what the game's debug tools show. */
	VideoFeed(String name, String label) {
		this.label = label;
		this.ids = new Identifier[] {MeetCraft.id("dynamic/" + name + "_a"), MeetCraft.id("dynamic/" + name + "_b")};
	}

	/** Called on the WebSocket thread; decoding here keeps JPEG work off the render thread. */
	void acceptJpeg(ByteBuffer jpeg) {
		CRC32 crc = new CRC32();
		crc.update(jpeg.duplicate());
		boolean repeat = crc.getValue() == lastChecksum;
		lastChecksum = crc.getValue();
		countFrame(repeat);
		if (repeat) {
			// Same bytes, same picture: keep showing the one we have, just note the signal is still alive.
			lastFrameAt = System.currentTimeMillis();
			return;
		}
		ByteBuffer direct = MemoryUtil.memAlloc(jpeg.remaining());
		try (MemoryStack stack = MemoryStack.stackPush()) {
			direct.put(jpeg).flip();
			IntBuffer w = stack.mallocInt(1);
			IntBuffer h = stack.mallocInt(1);
			IntBuffer channels = stack.mallocInt(1);
			ByteBuffer rgba = STBImage.stbi_load_from_memory(direct, w, h, channels, 4);
			if (rgba == null) {
				if (!warnedUndecodable) {
					warnedUndecodable = true;
					MeetCraft.LOGGER.warn("Dropping {} frames that aren't valid JPEG: {}", label, STBImage.stbi_failure_reason());
				}
				return;
			}
			Frame stale = pending.getAndSet(new Frame(w.get(0), h.get(0), rgba));
			if (stale != null) {
				stale.free();
			}
		} finally {
			MemoryUtil.memFree(direct);
		}
	}

	/** WebSocket thread only, like acceptJpeg. */
	private void countFrame(boolean repeat) {
		long now = System.currentTimeMillis();
		if (now - windowStart >= 1000) {
			receivedPerSecond = received;
			freshPerSecond = fresh;
			received = 0;
			fresh = 0;
			windowStart = now;
		}
		received++;
		if (!repeat) {
			fresh++;
		}
	}

	/** {frames arriving per second, of which new pictures}; zeros once the video has stopped. */
	int[] frameRates() {
		return liveSize() == null ? new int[] {0, 0} : new int[] {receivedPerSecond, freshPerSecond};
	}

	/** The latest frame's size, or null when there's no live video (none yet, or none for a few seconds). */
	int @Nullable [] liveSize() {
		if (video == null || System.currentTimeMillis() - lastFrameAt >= SIGNAL_TIMEOUT_MS) {
			return null;
		}
		return new int[] {video.getPixels().getWidth(), video.getPixels().getHeight()};
	}

	/** Where the current frame is; render thread. */
	Identifier id() {
		return ids[slot];
	}

	/** Puts the newest decoded frame into the texture. Render thread; everything that draws this feed calls it. */
	void upload(Minecraft mc) {
		if (retired != null) {
			mc.getTextureManager().release(retired);
			retired = null;
		}
		Frame frame = pending.getAndSet(null);
		if (frame == null) {
			return;
		}
		try {
			if (video == null || video.getPixels().getWidth() != frame.width() || video.getPixels().getHeight() != frame.height()) {
				video = new VideoTexture(label, frame.width(), frame.height());
				retired = ids[slot];
				slot = 1 - slot;
				mc.getTextureManager().register(ids[slot], video);
			}
			// stb and NativeImage both use tightly packed RGBA bytes, so one memcpy is enough.
			MemoryUtil.memCopy(MemoryUtil.memAddress(frame.rgba()), video.getPixels().getPointer(), (long) frame.width() * frame.height() * 4);
			video.upload();
			lastFrameAt = System.currentTimeMillis();
		} finally {
			frame.free();
		}
	}
}
