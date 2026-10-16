package dev.meetcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Where the screens are in one world, stored as {@code <world>/meetcraft.json}. */
public final class MeetState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();


	public final List<ScreenDef> screens = new ArrayList<>();
	private final Path file;

	private MeetState(Path file) {
		this.file = file;
	}

	public static MeetState load(Path file) {
		MeetState state = new MeetState(file);
		if (!Files.exists(file)) {
			return state;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			for (var el : root.getAsJsonArray("screens")) {
				JsonObject o = el.getAsJsonObject();
				state.screens.add(new ScreenDef(
					o.get("name").getAsString(),
					o.get("dimension").getAsString(),
					readPos(o, "min"),
					readPos(o, "max"),
					Direction.byName(o.get("facing").getAsString())
				));
			}
		} catch (Exception e) {
			MeetCraft.LOGGER.error("Could not read {}, starting empty", file, e);
		}
		return state;
	}

	public void save() {
		JsonObject root = new JsonObject();
		JsonArray screens = new JsonArray();
		for (ScreenDef screen : this.screens) {
			JsonObject o = new JsonObject();
			o.addProperty("name", screen.name());
			o.addProperty("dimension", screen.dimension());
			o.add("min", writePos(screen.min()));
			o.add("max", writePos(screen.max()));
			o.addProperty("facing", screen.facing().getSerializedName());
			screens.add(o);
		}
		root.add("screens", screens);
		try {
			Files.writeString(file, GSON.toJson(root));
		} catch (IOException e) {
			MeetCraft.LOGGER.error("Could not save {}", file, e);
		}
	}

	private static BlockPos readPos(JsonObject o, String key) {
		JsonArray a = o.getAsJsonArray(key);
		return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
	}

	private static JsonArray writePos(BlockPos pos) {
		JsonArray a = new JsonArray();
		a.add(pos.getX());
		a.add(pos.getY());
		a.add(pos.getZ());
		return a;
	}
}
