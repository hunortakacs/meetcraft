package dev.meetcraft;

import dev.meetcraft.block.MeetBlocks;
import dev.meetcraft.net.Payloads;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server's only job: know where the screens are and tell every client. What a screen shows is up to each
 * player's own client, so everyone sees their own Meet tab on every screen.
 */
public class MeetCraft implements ModInitializer {
	public static final String MOD_ID = "meetcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Server-thread state for the running world; ScreenBlock reaches it through screenBlockChanged.
	private static @Nullable MinecraftServer server;
	private static @Nullable MeetState state;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		Payloads.register();
		MeetBlocks.register();

		ServerLifecycleEvents.SERVER_STARTED.register(started -> {
			server = started;
			state = MeetState.load(started.getWorldPath(LevelResource.ROOT).resolve("meetcraft.json"));
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
			server = null;
			state = null;
		});
		ServerPlayConnectionEvents.JOIN.register((listener, sender, joined) -> {
			if (state != null && ServerPlayNetworking.canSend(listener.player, Payloads.Screens.TYPE)) {
				sender.sendPacket(new Payloads.Screens(state.screens));
			}
			giveScreenBlock(listener.player);
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> giveScreenBlock(newPlayer));
	}

	/** Everyone keeps one never-ending Screen block (see ScreenItem); a full inventory just means next time. */
	private static void giveScreenBlock(ServerPlayer player) {
		if (!player.getInventory().contains(stack -> stack.is(MeetBlocks.SCREEN.asItem()))) {
			player.getInventory().add(new ItemStack(MeetBlocks.SCREEN));
		}
	}

	public static void screenBlockChanged(ServerLevel level, BlockPos pos, Direction facing) {
		if (state == null || server == null) {
			return;
		}
		ScreenWalls.update(level, pos, facing, state.screens);
		state.save();
		Payloads.Screens payload = new Payloads.Screens(state.screens);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, Payloads.Screens.TYPE)) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}
}
