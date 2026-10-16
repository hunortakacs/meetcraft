package dev.meetcraft.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu's gear button opens MeetCraft's settings. Only loaded when Mod Menu is installed. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return MeetCraftClient::settingsScreen;
	}
}
