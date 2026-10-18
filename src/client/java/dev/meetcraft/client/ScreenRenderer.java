package dev.meetcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.meetcraft.MeetCraft;
import dev.meetcraft.ScreenDef;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

/** Draws the latest Meet frame onto every theatre screen in the current dimension. */
public final class ScreenRenderer {
	private static final Identifier BACKDROP = MeetCraft.id("dynamic/backdrop");
	private static final Identifier NO_SIGNAL = MeetCraft.id("dynamic/no_signal");
	private static final double RENDER_DISTANCE = 192;

	private final VideoFeed video;
	private volatile List<ScreenDef> screens = List.of();
	private boolean staticTexturesReady;

	ScreenRenderer(VideoFeed video) {
		this.video = video;
	}

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

	public void render(LevelRenderContext context) {
		List<ScreenDef> screens = this.screens;
		Minecraft mc = Minecraft.getInstance();
		if (screens.isEmpty() || mc.level == null) {
			return;
		}
		ensureStaticTextures(mc);
		video.upload(mc);

		String dimension = mc.level.dimension().identifier().toString();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		int[] size = video.liveSize();
		SubmitNodeCollector collector = context.submitNodeCollector();
		PoseStack poseStack = context.poseStack();

		for (ScreenDef screen : screens) {
			if (!screen.dimension().equals(dimension) || center(screen).distanceTo(camera) > RENDER_DISTANCE) {
				continue;
			}
			collector.submitCustomGeometry(poseStack, RenderTypes.text(BACKDROP),
				(pose, buffer) -> quad(pose, buffer, screen, camera, 0.01, 0, 0, 1, 1));
			if (size != null) {
				float[] r = letterbox(screen.width() / (float) screen.height(), size[0] / (float) size[1]);
				collector.submitCustomGeometry(poseStack, RenderTypes.text(video.id()),
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
