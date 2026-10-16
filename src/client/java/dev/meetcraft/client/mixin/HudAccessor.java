package dev.meetcraft.client.mixin;

import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only: the HUD's tick counter, which the chat fades its lines by. */
@Mixin(Hud.class)
public interface HudAccessor {
	@Accessor("tickCount")
	int meetcraft$tickCount();
}
