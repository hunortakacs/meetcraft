package dev.meetcraft.client.chat;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** {@code /gc}: Google Chat from the Minecraft chat window. Runs only on this client. */
public final class ChatCommands {
	private final GoogleChat chat;
	private final BooleanSupplier inWorld;
	private final AtomicBoolean loginRunning = new AtomicBoolean();

	public ChatCommands(GoogleChat chat, BooleanSupplier inWorld) {
		this.chat = chat;
		this.inWorld = inWorld;
	}

	public void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		SuggestionProvider<FabricClientCommandSource> chatNames = (ctx, builder) -> {
			String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
			chat.recent().stream()
				.map(GoogleChat.ChatView::label)
				.filter(label -> !label.endsWith("…") && label.toLowerCase(Locale.ROOT).contains(typed))
				.limit(30)
				.forEach(builder::suggest);
			return builder.buildFuture();
		};

		dispatcher.register(ClientCommands.literal("gc")
			.executes(this::status)
			.then(ClientCommands.literal("help").executes(this::help))
			.then(ClientCommands.literal("login").executes(this::login))
			.then(ClientCommands.literal("logout").executes(this::logout))
			.then(ClientCommands.literal("list")
				.executes(ctx -> run(() -> chat.list(false)))
				.then(ClientCommands.literal("all").executes(ctx -> run(() -> chat.list(true)))))
			.then(ClientCommands.literal("open")
				.then(ClientCommands.argument("chat", StringArgumentType.greedyString()).suggests(chatNames)
					.executes(ctx -> run(() -> chat.open(StringArgumentType.getString(ctx, "chat"))))))
			.then(ClientCommands.literal("dm")
				.then(ClientCommands.argument("email", StringArgumentType.greedyString())
					.executes(ctx -> run(() -> chat.openDirectMessage(StringArgumentType.getString(ctx, "email").strip())))))
			.then(ClientCommands.literal("meet").executes(ctx -> run(chat::openMeeting)))
			.then(ClientCommands.literal("leave").executes(ctx -> run(chat::leave))));
	}

	private int run(Runnable action) {
		keepChatOpen();
		if (!chat.account().isLoggedIn()) {
			GoogleChat.show(ChatStyle.error("Not logged in. Run /gc login first."));
			return 0;
		}
		action.run();
		return 1;
	}

	/** The chat window closes itself after a command; open it again so lists stay clickable and you can type on. */
	private static void keepChatOpen() {
		Minecraft mc = Minecraft.getInstance();
		mc.schedule(() -> {
			if (mc.gui.screen() == null) {
				mc.gui.setScreen(new ChatScreen("", false));
			}
		});
	}

	private int status(CommandContext<FabricClientCommandSource> ctx) {
		keepChatOpen();
		if (!chat.account().isLoggedIn()) {
			GoogleChat.show(ChatStyle.info("Not logged in. /gc login connects your own Google account; /gc help lists commands."));
			return 1;
		}
		String where = chat.current() == null ? "Not in a Google chat; typing goes to Minecraft" : "You're in " + chat.currentLabel();
		GoogleChat.show(ChatStyle.info(where + (chat.unreadTotal() > 0 ? " · " + chat.unreadTotal() + " unread elsewhere (/gc list)" : "") + "."));
		GoogleChat.show(ChatStyle.info(chat.meetingSpace() == null ? "No meeting chat detected." : "Meeting chat detected: /gc meet."));
		GoogleChat.show(ChatStyle.hint("/gc help for commands"));
		return 1;
	}

	private int help(CommandContext<FabricClientCommandSource> ctx) {
		keepChatOpen();
		GoogleChat.show(ChatStyle.header("Google Chat"));
		GoogleChat.show(ChatStyle.command("/gc list", "your chats, newest first"));
		GoogleChat.show(ChatStyle.command("/gc open <number|name>", "enter a space or DM; typing goes there"));
		GoogleChat.show(ChatStyle.command("/gc dm <email>", "enter your DM with someone"));
		GoogleChat.show(ChatStyle.command("/gc meet", "enter the meeting's chat"));
		GoogleChat.show(ChatStyle.command("/gc leave", "leave the chat; typing goes to Minecraft again"));
		GoogleChat.show(ChatStyle.command("/gc login", "connect your Google account (first time: setup)"));
		GoogleChat.show(ChatStyle.command("/gc logout", "disconnect and revoke access"));
		GoogleChat.show(ChatStyle.hint("Inside a chat, start a line with ! to say it in Minecraft instead."));
		return 1;
	}

	private int login(CommandContext<FabricClientCommandSource> ctx) {
		if (!chat.account().hasClient()) {
			// Queued: the chat screen closes itself right after a command runs and would close ours too.
			Minecraft mc = Minecraft.getInstance();
			mc.schedule(() -> mc.gui.setScreen(setupScreen(null)));
			return 1;
		}
		startLogin();
		return 1;
	}

	/** The one-time setup where the team app's client ID and secret are pasted in; saving it starts the login. */
	public Screen setupScreen(@Nullable Screen parent) {
		return new GoogleSetupScreen(parent, chat.account(), this::startLogin);
	}

	/** Opens the browser consent page and waits for it on a background thread. */
	public void startLogin() {
		if (!loginRunning.compareAndSet(false, true)) {
			GoogleChat.show(ChatStyle.error("A Google login is already waiting in your browser."));
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Thread thread = new Thread(() -> {
			try {
				chat.account().login(uri -> mc.execute(() -> {
					Blaze3D.openUri(uri);
					GoogleChat.show(ChatStyle.info("Finish the login in your browser. If it didn't open, ")
						.append(Component.literal("click here").withStyle(s -> s.withClickEvent(new ClickEvent.OpenUrl(uri))
							.withUnderlined(true).withColor(ChatFormatting.AQUA)))
						.append(Component.literal(".").withStyle(ChatFormatting.GRAY)));
				}));
				GoogleChat.show(ChatStyle.info("Logged in. Your token is stored only on this computer."));
				if (inWorld.getAsBoolean()) {
					chat.start();
				}
			} catch (IOException e) {
				GoogleChat.show(ChatStyle.error("Login failed: " + e.getMessage()));
			} finally {
				loginRunning.set(false);
			}
		}, "MeetCraft Google login");
		thread.setDaemon(true);
		thread.start();
	}

	private int logout(CommandContext<FabricClientCommandSource> ctx) {
		chat.stop();
		try {
			chat.account().logout();
			GoogleChat.show(ChatStyle.info("Logged out and revoked MeetCraft's access to your Google account."));
		} catch (IOException e) {
			GoogleChat.show(ChatStyle.error("Logout failed: " + e.getMessage()));
		}
		return 1;
	}
}
