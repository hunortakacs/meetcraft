package dev.meetcraft.client.chat;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ChatSpaces {
	private static final Pattern SPACE_ID = Pattern.compile("(?:spaces?/|room/)?([A-Za-z0-9_-]+)/?$");

	private ChatSpaces() {
	}

	/**
	 * Accepts {@code spaces/AAAA}, a bare {@code AAAA}, or a Chat URL such as
	 * {@code https://chat.google.com/room/AAAA?cls=7}, and returns {@code spaces/AAAA}.
	 */
	static Optional<String> normalize(String input) {
		String trimmed = input.strip();
		int cut = trimmed.indexOf('?');
		if (cut >= 0) {
			trimmed = trimmed.substring(0, cut);
		}
		Matcher m = SPACE_ID.matcher(trimmed);
		return m.find() ? Optional.of("spaces/" + m.group(1)) : Optional.empty();
	}
}
