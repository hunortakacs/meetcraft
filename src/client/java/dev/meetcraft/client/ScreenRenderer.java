package dev.meetcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.meetcraft.MeetCraft;
import dev.meetcraft.ScreenDef;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.zip.CRC32;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Draws the latest Meet frame onto every theatre screen in the current dimension. */
public final class ScreenRenderer {
	/**
	 * The video texture alternates between two names: when the frame size changes, the new one goes up under the other
	 * name and the old one is only freed on the next frame, because something drawn this frame may still use it.
	 */
	private static final Identifier[] VIDEO_IDS = {MeetCraft.id("dynamic/video_a"), MeetCraft.id("dynamic/video_b")};
	private int videoSlot;
	private @Nullable Identifier retired;
	private static final Identifier BACKDROP = MeetCraft.id("dynamic/backdrop");
	private static final Identifier NO_SIGNAL = MeetCraft.id("dynamic/no_signal");
	private static final double RENDER_DISTANCE = 192;
	/** Frames older than this are treated as a lost signal. */
	private static final long SIGNAL_TIMEOUT_MS = 3000;

	private record Frame(int width, int height, ByteBuffer rgba) {
		void free() {
			STBImage.stbi_image_free(rgba);
		}
	}

	/** Linear filtering instead of the default nearest, so video looks smooth rather than blocky up close. */
	private static final class VideoTexture extends DynamicTexture {
		VideoTexture(int width, int height) {
			super(() -> "MeetCraft video", width, height, false);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}

	private final AtomicReference<@Nullable Frame> pending = new AtomicReference<>();
	private volatile List<ScreenDef> screens = List.of();
	private @Nullable DynamicTexture video;
	private boolean staticTexturesReady;
	private volatile long lastFrameAt;
	private volatile boolean warnedUndecodable;

	// Frame rates over the last second: everything that arrived, and how much of it was a new picture. The browser
	// captures at a steady rate, but Meet's own video (screen shares especially) often changes far less often.
	private long lastChecksum;
	private int received;
	private int fresh;
	private long windowStart = System.currentTimeMillis();
	private volatile int receivedPerSecond;
	private volatile int freshPerSecond;

	public void setScreens(List<ScreenDef> screens) {
		this.screens = List.copyOf(screens);
		MeetCraft.LOGGER.info("Server lists {} screen(s): {}", screens.size(),
			screens.stream().map(s -> s.width() + "x" + s.height() + " at " + s.min().toShortString()).toList());
	}

	/** True if a screen in the player's dimension is close enough that we should ask for frames. */
	public boolean anyScreenNearby(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return false;
		}
		String dimension = mc.level.dimension().identifier().toString();
		Vec3 eye = mc.player.getEyePosition();
		for (ScreenDef screen : screens) {
			if (screen.dimension().equals(dimension) && center(screen).distanceTo(eye) < RENDER_DISTANCE) {
				return true;
			}
		}
		return false;
	}

	/** Called on the WebSocket thread; decoding here keeps JPEG work off the render thread. */
	public void acceptJpeg(ByteBuffer jpeg) {
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
					MeetCraft.LOGGER.warn("Dropping Meet frames that aren't valid JPEG: {}", STBImage.stbi_failure_reason());
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

	public void render(LevelRenderContext context) {
		List<ScreenDef> screens = this.screens;
		Minecraft mc = Minecraft.getInstance();
		if (screens.isEmpty() || mc.level == null) {
			return;
		}
		ensureStaticTextures(mc);
		uploadPendingFrame(mc);

		String dimension = mc.level.dimension().identifier().toString();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		boolean live = video != null && System.currentTimeMillis() - lastFrameAt < SIGNAL_TIMEOUT_MS;
		SubmitNodeCollector collector = context.submitNodeCollector();
		PoseStack poseStack = context.poseStack();

		for (ScreenDef screen : screens) {
			if (!screen.dimension().equals(dimension) || center(screen).distanceTo(camera) > RENDER_DISTANCE) {
				continue;
			}
			collector.submitCustomGeometry(poseStack, RenderTypes.text(BACKDROP),
				(pose, buffer) -> quad(pose, buffer, screen, camera, 0.01, 0, 0, 1, 1));
			if (live) {
				NativeImage pixels = video.getPixels();
				float[] r = letterbox(screen.width() / (float) screen.height(), pixels.getWidth() / (float) pixels.getHeight());
				collector.submitCustomGeometry(poseStack, RenderTypes.text(videoId()),
					(pose, buffer) -> quad(pose, buffer, screen, camera, 0.02, r[0], r[1], r[2], r[3]));
			} else {
				collector.submitCustomGeometry(poseStack, RenderTypes.text(NO_SIGNAL),
					(pose, buffer) -> quad(pose, buffer, screen, camera, 0.02, 0.1f, 0.1f, 0.9f, 0.9f));
			}
		}
	}

	private void ensureStaticTextures(Minecraft mc) {
		if (staticTexturesReady) {
			return;
		}
		DynamicTexture backdrop = new DynamicTexture(() -> "MeetCraft backdrop", 1, 1, false);
		backdrop.getPixels().setPixel(0, 0, 0xFF000000);
		backdrop.upload();
		mc.getTextureManager().register(BACKDROP, backdrop);

		// Classic colour bars, so a screen without a feed is obviously "on" but has no signal.
		int[] bars = {0xFFC0C0C0, 0xFFC0C000, 0xFF00C0C0, 0xFF00C000, 0xFFC000C0, 0xFFC00000, 0xFF0000C0};
		DynamicTexture noSignal = new DynamicTexture(() -> "MeetCraft no signal", bars.length, 1, false);
		for (int i = 0; i < bars.length; i++) {
			noSignal.getPixels().setPixel(i, 0, bars[i]);
		}
		noSignal.upload();
		mc.getTextureManager().register(NO_SIGNAL, noSignal);
		staticTexturesReady = true;
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
		return liveVideoSize() == null ? new int[] {0, 0} : new int[] {receivedPerSecond, freshPerSecond};
	}

	/** The latest Meet frame's size, or null when there's no live video (none yet, or none for a few seconds). */
	@Nullable int[] liveVideoSize() {
		if (video == null || System.currentTimeMillis() - lastFrameAt >= SIGNAL_TIMEOUT_MS) {
			return null;
		}
		return new int[] {video.getPixels().getWidth(), video.getPixels().getHeight()};
	}

	/** Puts the newest decoded frame into the video texture. Render thread; both the screens and the minimap call it. */
	/** Where the current video frame is; render thread. */
	Identifier videoId() {
		return VIDEO_IDS[videoSlot];
	}

	void uploadPendingFrame(Minecraft mc) {
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
				video = new VideoTexture(frame.width(), frame.height());
				retired = VIDEO_IDS[videoSlot];
				videoSlot = 1 - videoSlot;
				mc.getTextureManager().register(VIDEO_IDS[videoSlot], video);
			}
			// stb and NativeImage both use tightly packed RGBA bytes, so one memcpy is enough.
			MemoryUtil.memCopy(MemoryUtil.memAddress(frame.rgba()), video.getPixels().getPointer(), (long) frame.width() * frame.height() * 4);
			video.upload();
			lastFrameAt = System.currentTimeMillis();
		} finally {
			frame.free();
		}
	}

	/** Fits content of aspect {@code content} centred inside a screen of aspect {@code screen}; returns u0, v0, u1, v1. */
	private static float[] letterbox(float screen, float content) {
		if (content > screen) {
			float h = screen / content;
			return new float[] {0, (1 - h) / 2, 1, (1 + h) / 2};
		}
		float w = content / screen;
		return new float[] {(1 - w) / 2, 0, (1 + w) / 2, 1};
	}

	/**
	 * Emits the part of the screen face between (u0, v0) top-left and (u1, v1) bottom-right, as fractions of the
	 * screen, pushed {@code lift} blocks out of the wall so layers don't z-fight.
	 */
	private static void quad(PoseStack.Pose pose, VertexConsumer buffer, ScreenDef screen, Vec3 camera, double lift,
							 float u0, float v0, float u1, float v1) {
		Vec3 topLeft = topLeft(screen, lift).subtract(camera);
		Direction right = screen.facing().getCounterClockWise();
		Vec3 across = new Vec3(right.getStepX(), 0, right.getStepZ()).scale(screen.width());
		Vec3 down = new Vec3(0, -screen.height(), 0);
		vertex(pose, buffer, topLeft, across, down, u0, v0, 0, 0);
		vertex(pose, buffer, topLeft, across, down, u0, v1, 0, 1);
		vertex(pose, buffer, topLeft, across, down, u1, v1, 1, 1);
		vertex(pose, buffer, topLeft, across, down, u1, v0, 1, 0);
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, Vec3 origin, Vec3 across, Vec3 down,
							   float x, float y, float u, float v) {
		Vec3 p = origin.add(across.scale(x)).add(down.scale(y));
		buffer.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(-1).setUv(u, v).setLight(LightCoordsUtil.FULL_BRIGHT);
	}

	/** World position of the screen's top-left corner as seen by someone facing it. */
	private static Vec3 topLeft(ScreenDef screen, double lift) {
		Direction facing = screen.facing();
		Direction right = facing.getCounterClockWise();
		double x0 = screen.min().getX(), x1 = screen.max().getX() + 1;
		double z0 = screen.min().getZ(), z1 = screen.max().getZ() + 1;
		double x = facing.getStepX() > 0 ? x1 + lift : facing.getStepX() < 0 ? x0 - lift : right.getStepX() > 0 ? x0 : x1;
		double z = facing.getStepZ() > 0 ? z1 + lift : facing.getStepZ() < 0 ? z0 - lift : right.getStepZ() > 0 ? z0 : z1;
		return new Vec3(x, screen.max().getY() + 1, z);
	}

	private static Vec3 center(ScreenDef screen) {
		return Vec3.atLowerCornerOf(screen.min()).add(Vec3.atLowerCornerOf(screen.max())).scale(0.5).add(0.5, 0.5, 0.5);
	}
}
