package dev.meetcraft.client.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The few Google Chat and People API calls MeetCraft needs, all made as the player. */
final class ChatApi {
	// Overridable only so the client can be exercised against a local stand-in for Google.
	private static final String CHAT = System.getProperty("meetcraft.chatApi", "https://chat.googleapis.com/") + "v1/";
	private static final String PEOPLE = System.getProperty("meetcraft.peopleApi", "https://people.googleapis.com/") + "v1/";
	/** Enough for anyone's chat list without paging forever on a pathological account. */
	private static final int MAX_SPACE_PAGES = 3;

	static final class ApiError extends IOException {
		final int status;

		ApiError(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private final GoogleAccount account;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	ChatApi(GoogleAccount account) {
		this.account = account;
	}

	/** Every space, group chat and DM the player is in (DMs only once they have a message). */
	List<JsonObject> listSpaces() throws IOException {
		List<JsonObject> spaces = new ArrayList<>();
		String pageToken = "";
		for (int page = 0; page < MAX_SPACE_PAGES; page++) {
			JsonObject response = get(CHAT + "spaces?pageSize=1000" + (pageToken.isEmpty() ? "" : "&pageToken=" + encode(pageToken)));
			addAll(spaces, response, "spaces");
			pageToken = string(response, "nextPageToken");
			if (pageToken.isEmpty()) {
				break;
			}
		}
		return spaces;
	}

	JsonObject getSpace(String space) throws IOException {
		return get(CHAT + space);
	}

	/** The existing DM with someone, by email. */
	JsonObject findDirectMessage(String email) throws IOException {
		return get(CHAT + "spaces:findDirectMessage?name=" + encode("users/" + email));
	}

	List<JsonObject> listMembers(String space) throws IOException {
		List<JsonObject> members = new ArrayList<>();
		addAll(members, get(CHAT + space + "/members?pageSize=20"), "memberships");
		return members;
	}

	/** Messages created after {@code afterCreateTime}, oldest first. */
	List<JsonObject> messagesAfter(String space, String afterCreateTime) throws IOException {
		String filter = encode("createTime > \"" + afterCreateTime + "\"");
		List<JsonObject> messages = new ArrayList<>();
		addAll(messages, get(CHAT + space + "/messages?pageSize=100&orderBy=" + encode("createTime asc") + "&filter=" + filter), "messages");
		return messages;
	}

	/** The newest {@code count} messages, oldest first. */
	List<JsonObject> latestMessages(String space, int count) throws IOException {
		List<JsonObject> messages = new ArrayList<>();
		addAll(messages, get(CHAT + space + "/messages?pageSize=" + count + "&orderBy=" + encode("createTime desc")), "messages");
		return messages.reversed();
	}

	JsonObject createMessage(String space, String text) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("text", text);
		return send("POST", CHAT + space + "/messages", body.toString());
	}

	/** Display name of {@code users/123} (or {@code people/me}); null if the directory won't say. */
	@Nullable String personName(String userOrPerson) throws IOException {
		String id = userOrPerson.startsWith("people/") ? userOrPerson : "people/" + userOrPerson.replace("users/", "");
		JsonObject person = get(PEOPLE + id + "?personFields=names");
		JsonArray names = person.getAsJsonArray("names");
		return names == null || names.isEmpty() ? null : string(names.get(0).getAsJsonObject(), "displayName");
	}

	/** The player's own Chat user ID, {@code users/123}. People and Chat share the numeric ID. */
	String myUserId() throws IOException {
		return string(get(PEOPLE + "people/me?personFields=metadata"), "resourceName").replace("people/", "users/");
	}

	private JsonObject get(String url) throws IOException {
		return send("GET", url, null);
	}

	private JsonObject send(String method, String url, @Nullable String json) throws IOException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(20))
			.header("Authorization", "Bearer " + account.accessToken());
		if (json != null) {
			builder.header("Content-Type", "application/json; charset=UTF-8").method(method, HttpRequest.BodyPublishers.ofString(json));
		} else {
			builder.method(method, HttpRequest.BodyPublishers.noBody());
		}
		HttpResponse<String> response;
		try {
			response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
		if (response.statusCode() == 401) {
			account.invalidateAccessToken();
		}
		if (response.statusCode() >= 300) {
			throw new ApiError(response.statusCode(), googleError(response));
		}
		return response.body().isBlank() ? new JsonObject() : JsonParser.parseString(response.body()).getAsJsonObject();
	}

	/** Pulls Google's own error text out of a response, falling back to the status code. */
	private static String googleError(HttpResponse<String> response) {
		try {
			return JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("error").get("message").getAsString();
		} catch (Exception e) {
			return "HTTP " + response.statusCode();
		}
	}

	private static void addAll(List<JsonObject> into, JsonObject response, String key) {
		JsonArray array = response.getAsJsonArray(key);
		if (array != null) {
			for (JsonElement el : array) {
				into.add(el.getAsJsonObject());
			}
		}
	}

	static String string(@Nullable JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private static String encode(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
