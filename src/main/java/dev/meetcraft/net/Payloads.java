package dev.meetcraft.net;

import dev.meetcraft.MeetCraft;
import dev.meetcraft.ScreenDef;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class Payloads {
	private Payloads() {
	}

	/** Server → client: the full list of theatre screens, resent whenever it changes. */
	public record Screens(List<ScreenDef> screens) implements CustomPacketPayload {
		public static final Type<Screens> TYPE = new Type<>(MeetCraft.id("screens"));
		public static final StreamCodec<ByteBuf, Screens> CODEC = ScreenDef.STREAM_CODEC
			.apply(ByteBufCodecs.list())
			.map(Screens::new, Screens::screens);

		@Override
		public Type<Screens> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry<RegistryFriendlyByteBuf> s2c = PayloadTypeRegistry.clientboundPlay();
		s2c.register(Screens.TYPE, Screens.CODEC);
	}
}
