package dev.meetcraft.client.chat;

import java.io.IOException;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * One-time Google Chat setup: paste the team app's client ID and secret, then log in. Saved owner-only on this
 * machine, so nobody has to edit files by hand.
 */
public final class GoogleSetupScreen extends Screen {
	private final @Nullable Screen parent;
	private final GoogleAccount account;
	private final Runnable login;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private EditBox clientId;
	private EditBox clientSecret;
	private StringWidget status;

	public GoogleSetupScreen(@Nullable Screen parent, GoogleAccount account, Runnable login) {
		super(Component.literal("Google Chat setup"));
		this.parent = parent;
		this.account = account;
		this.login = login;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);
		LinearLayout fields = layout.addToContents(LinearLayout.vertical().spacing(6));
		fields.addChild(new StringWidget(Component.literal("Paste the team app's client ID and secret.").withStyle(ChatFormatting.GRAY), font));
		clientId = fields.addChild(new EditBox(font, fieldWidth(), 20, Component.literal("Client ID")));
		clientId.setMaxLength(256);
		clientId.setHint(Component.literal("…apps.googleusercontent.com").withStyle(ChatFormatting.DARK_GRAY));
		clientSecret = fields.addChild(new EditBox(font, fieldWidth(), 20, Component.literal("Client secret")));
		clientSecret.setMaxLength(256);
		clientSecret.setHint(Component.literal("Client secret").withStyle(ChatFormatting.DARK_GRAY));
		// Shown as stars, so it doesn't sit on screen for anyone looking over your shoulder or streaming.
		clientSecret.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
		status = fields.addChild(new StringWidget(fieldWidth(), 12, Component.empty(), font));
		if (account.hasClient()) {
			try {
				GoogleAccount.OAuthClient saved = account.loadClient();
				clientId.setValue(saved.id());
				clientSecret.setValue(saved.secret());
			} catch (IOException e) {
				status.setMessage(Component.literal("Couldn't read the saved setup: " + e.getMessage()).withStyle(ChatFormatting.RED));
			}
		}

		LinearLayout buttons = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		buttons.addChild(Button.builder(Component.literal("Save & log in"), button -> save()).width(150).build());
		buttons.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).width(150).build());
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
		setInitialFocus(clientId);
	}

	private int fieldWidth() {
		return width - 2 * 20;
	}

	@Override
	protected void repositionElements() {
		clientId.setWidth(fieldWidth());
		clientSecret.setWidth(fieldWidth());
		status.setWidth(fieldWidth());
		layout.arrangeElements();
	}

	private void save() {
		String id = clientId.getValue().strip();
		String secret = clientSecret.getValue().strip();
		if (!id.endsWith(".apps.googleusercontent.com") || secret.isEmpty()) {
			status.setMessage(Component.literal("That doesn't look like a Google client ID and secret.").withStyle(ChatFormatting.RED));
			return;
		}
		try {
			account.saveClient(id, secret);
		} catch (IOException e) {
			status.setMessage(Component.literal("Couldn't save: " + e.getMessage()).withStyle(ChatFormatting.RED));
			return;
		}
		onClose();
		login.run();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
