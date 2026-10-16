package dev.meetcraft.client.chat;

import java.time.Duration;
import java.time.Instant;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * How Google Chat looks in the Minecraft chat window. Deliberately few colours: dark aqua marks gc's own lines,
 * aqua is people, gray is secondary, and gold is unread counts.
 */
final class ChatStyle {
	private ChatStyle() {
	}

	static MutableComponent info(String text) {
		return tag().append(Component.literal(text).withStyle(ChatFormatting.GRAY));
	}

	static MutableComponent error(String text) {
		return tag().append(Component.literal(text).withStyle(ChatFormatting.RED));
	}

	/** A message in the chat you're in: {@code │ Alice: hello}. The header already says which chat it is. */
	static MutableComponent message(String sender, boolean mine, String text) {
		return Component.literal("│ ").withStyle(ChatFormatting.DARK_AQUA)
			.append(Component.literal(sender).withStyle(mine ? ChatFormatting.GRAY : ChatFormatting.AQUA))
			.append(Component.literal(": " + text).withStyle(ChatFormatting.WHITE));
	}

	static MutableComponent header(String chatLabel) {
		return Component.literal("── ").withStyle(ChatFormatting.DARK_AQUA)
			.append(Component.literal(chatLabel).withStyle(ChatFormatting.AQUA))
			.append(Component.literal(" ──").withStyle(ChatFormatting.DARK_AQUA));
	}

	static MutableComponent hint(String text) {
		return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
	}

	/** {@code  3  @Alice · 5m  2 new}; the chat you're in is marked with ▸. */
	static MutableComponent listRow(int index, String space, String chatLabel, boolean current, boolean meeting, Instant lastActive, int unread) {
		MutableComponent row = Component.literal(String.format("%3d ", index)).withStyle(ChatFormatting.DARK_GRAY)
			.append(Component.literal(current ? "▸ " : "  ").withStyle(ChatFormatting.AQUA))
			.append(Component.literal(chatLabel).withStyle(current ? ChatFormatting.AQUA : ChatFormatting.WHITE));
		if (meeting) {
			row.append(Component.literal(" (meeting)").withStyle(ChatFormatting.DARK_AQUA));
		}
		row.append(Component.literal(" · " + age(lastActive)).withStyle(ChatFormatting.DARK_GRAY));
		if (unread > 0) {
			row.append(Component.literal("  " + unread + " new").withStyle(ChatFormatting.GOLD));
		}
		return opens(row, space, Component.literal("Click to open " + chatLabel));
	}

	/** A clickable choice when a name matched several chats. */
	static MutableComponent choice(String space, String chatLabel) {
		return opens(Component.literal("   " + chatLabel).withStyle(ChatFormatting.WHITE, ChatFormatting.UNDERLINE), space,
			Component.literal("Click to open " + chatLabel));
	}

	static MutableComponent command(String command, String description) {
		return Component.literal("  " + command).withStyle(s -> s.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.SuggestCommand(command.contains("<") ? command.substring(0, command.indexOf('<')) : command)))
			.append(Component.literal("  " + description).withStyle(ChatFormatting.GRAY));
	}

	private static MutableComponent opens(MutableComponent line, String space, Component hover) {
		return line.withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("gc open " + space)).withHoverEvent(new HoverEvent.ShowText(hover)));
	}

	private static MutableComponent tag() {
		return Component.literal("[gc] ").withStyle(ChatFormatting.DARK_AQUA);
	}

	private static String age(Instant then) {
		if (then.equals(Instant.EPOCH)) {
			return "—";
		}
		Duration d = Duration.between(then, Instant.now());
		if (d.toMinutes() < 1) {
			return "now";
		}
		if (d.toHours() < 1) {
			return d.toMinutes() + "m";
		}
		if (d.toDays() < 1) {
			return d.toHours() + "h";
		}
		return d.toDays() + "d";
	}
}
