package dev.meetcraft.client.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * The player's own Google account. The refresh token lives owner-only in {@code ~/.config/meetcraft/}, outside the
 * Minecraft instance so it isn't swept up when instance folders get zipped and shared, and it never leaves this machine.
 */
public final class GoogleAccount {
	static final List<String> SCOPES = List.of(
		// Which spaces and DMs you're in, and who's in them (DMs have no name of their own).
		"https://www.googleapis.com/auth/chat.spaces.readonly",
		"https://www.googleapis.com/auth/chat.memberships.readonly",
		"https://www.googleapis.com/auth/chat.messages.readonly",
		"https://www.googleapis.com/auth/chat.messages.create",
		// Chat leaves sender names out under user auth; the directory fills them in.
		"https://www.googleapis.com/auth/directory.readonly"
	);
	private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(5);

	/** The stored login no longer works (revoked or expired); the player has to log in again. */
	public static final class LoginExpired extends IOException {
		LoginExpired(String message) {
			super(message);
		}
	}

	private static final class TokenEndpointError extends IOException {
		final int status;

		TokenEndpointError(int status, String body) {
			super("HTTP " + status + ": " + body.strip());
			this.status = status;
		}
	}

	record OAuthClient(String id, String secret, String authUri, String tokenUri) {
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final Path dir = configHome().resolve("meetcraft");
	private @Nullable String refreshToken;
	private @Nullable String accessToken;
	private Instant accessTokenExpiry = Instant.EPOCH;

	public GoogleAccount() {
		try {
			Path token = tokenFile();
			if (Files.exists(token)) {
				JsonObject json = JsonParser.parseString(Files.readString(token)).getAsJsonObject();
				String granted = json.has("scope") ? json.get("scope").getAsString() : "";
				// A login from an older MeetCraft lacks newer scopes; treat it as logged out so /gc login fixes it.
				if (SCOPES.stream().allMatch(granted::contains)) {
					refreshToken = json.get("refresh_token").getAsString();
				}
			}
		} catch (Exception e) {
			refreshToken = null;
		}
	}

	public Path clientFile() {
		return dir.resolve("google-client.env");
	}

	/** Whether the app's client ID and secret have been entered yet (see {@link GoogleSetupScreen}). */
	public boolean hasClient() {
		return Files.exists(clientFile());
	}

	/** Stores the app's client ID and secret, owner-only, on this machine. */
	public void saveClient(String id, String secret) throws IOException {
		createDir();
		Path file = clientFile();
		createOwnerOnly(file);
		Files.writeString(file, "GOOGLE_CLIENT_ID=" + id.strip() + "\nGOOGLE_CLIENT_SECRET=" + secret.strip() + "\n");
	}

	public Path tokenFile() {
		return dir.resolve("google-token.json");
	}

	public synchronized boolean isLoggedIn() {
		return refreshToken != null;
	}

	/**
	 * Runs the browser consent flow (loopback redirect + PKCE) on the calling thread, which it blocks until the
	 * player finishes or {@link #LOGIN_TIMEOUT} passes. {@code openBrowser} gets the consent URL.
	 */
	public void login(Consumer<URI> openBrowser) throws IOException {
		OAuthClient client = loadClient();
		String verifier = randomToken(48);
		String state = randomToken(16);
		try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			server.setSoTimeout((int) LOGIN_TIMEOUT.toMillis());
			String redirectUri = "http://127.0.0.1:" + server.getLocalPort();
			openBrowser.accept(URI.create(client.authUri() + "?" + form(Map.of(
				"client_id", client.id(),
				"redirect_uri", redirectUri,
				"response_type", "code",
				"scope", String.join(" ", SCOPES),
				"access_type", "offline",
				"prompt", "consent",
				"state", state,
				"code_challenge", sha256(verifier),
				"code_challenge_method", "S256"
			))));

			Map<String, String> callback = awaitCallback(server, state);
			if (callback.containsKey("error")) {
				throw new IOException("Google said: " + callback.get("error"));
			}
			JsonObject token = postForm(client.tokenUri(), Map.of(
				"grant_type", "authorization_code",
				"code", callback.get("code"),
				"code_verifier", verifier,
				"client_id", client.id(),
				"client_secret", client.secret(),
				"redirect_uri", redirectUri
			));
			// Google lets people untick scopes on the consent screen; a partial grant would fail confusingly later.
			String granted = token.has("scope") ? token.get("scope").getAsString() : "";
			for (String scope : SCOPES) {
				if (!granted.contains(scope)) {
					throw new IOException("Some permissions were not granted (" + scope + "); run the login again and allow all of them.");
				}
			}
			synchronized (this) {
				refreshToken = token.get("refresh_token").getAsString();
				accessToken = token.get("access_token").getAsString();
				accessTokenExpiry = Instant.now().plusSeconds(token.get("expires_in").getAsLong() - 60);
				saveToken(granted);
			}
		}
	}

	/** Forgets the token locally and revokes it at Google, so a copied file is useless too. */
	public void logout() throws IOException {
		String token;
		synchronized (this) {
			token = refreshToken;
			refreshToken = null;
			accessToken = null;
			Files.deleteIfExists(tokenFile());
		}
		if (token != null) {
			try {
				http.send(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/revoke"))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(form(Map.of("token", token))))
					.build(), HttpResponse.BodyHandlers.discarding());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** Drops the cached access token after the API rejected it, so the next call refreshes. */
	public synchronized void invalidateAccessToken() {
		accessToken = null;
	}

	public synchronized String accessToken() throws IOException {
		if (refreshToken == null) {
			throw new LoginExpired("Not logged in to Google");
		}
		if (accessToken != null && Instant.now().isBefore(accessTokenExpiry)) {
			return accessToken;
		}
		OAuthClient client = loadClient();
		JsonObject token;
		try {
			token = postForm(client.tokenUri(), Map.of(
				"grant_type", "refresh_token",
				"refresh_token", refreshToken,
				"client_id", client.id(),
				"client_secret", client.secret()
			));
		} catch (TokenEndpointError e) {
			// 4xx means the grant or the OAuth client itself is bad; retrying won't help, 5xx might.
			if (e.status >= 500) {
				throw e;
			}
			if (e.getMessage().contains("invalid_grant")) {
				refreshToken = null;
				Files.deleteIfExists(tokenFile());
				throw new LoginExpired("Your Google login expired or was revoked");
			}
			throw new LoginExpired("Google rejected the login (" + e.getMessage() + "); check " + clientFile());
		}
		accessToken = token.get("access_token").getAsString();
		accessTokenExpiry = Instant.now().plusSeconds(token.get("expires_in").getAsLong() - 60);
		return accessToken;
	}

	/**
	 * The Desktop-app OAuth client, read from {@code google-client.env}: the {@code GOOGLE_CLIENT_ID} and
	 * {@code GOOGLE_CLIENT_SECRET} lines copied from the team's repo. It stays on this machine and is never part of the
	 * jar; every player still logs in with their own account.
	 */
	OAuthClient loadClient() throws IOException {
		Path file = clientFile();
		if (!Files.exists(file)) {
			throw new IOException("Google Chat isn't set up yet; /gc login opens the setup");
		}
		restrictToOwner(file);
		Map<String, String> vars = new HashMap<>();
		for (String line : Files.readAllLines(file)) {
			int eq = line.indexOf('=');
			if (eq > 0 && !line.strip().startsWith("#")) {
				vars.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip().replaceAll("^[\"']|[\"']$", ""));
			}
		}
		String id = vars.get("GOOGLE_CLIENT_ID");
		String secret = vars.get("GOOGLE_CLIENT_SECRET");
		if (id == null || id.isEmpty() || secret == null || secret.isEmpty()) {
			throw new IOException(file + " needs GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET lines");
		}
		return new OAuthClient(id, secret, "https://accounts.google.com/o/oauth2/auth", "https://oauth2.googleapis.com/token");
	}

	private static void restrictToOwner(Path file) throws IOException {
		if (posix()) {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		}
	}

	private void saveToken(String grantedScopes) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("refresh_token", refreshToken);
		json.addProperty("scope", grantedScopes);
		createDir();
		Path file = tokenFile();
		createOwnerOnly(file);
		Files.writeString(file, json.toString());
	}

	private void createDir() throws IOException {
		if (posix()) {
			Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		} else {
			Files.createDirectories(dir);
		}
	}

	/** Creates the file owner-only from the start, so its contents are never briefly world-readable. */
	private static void createOwnerOnly(Path file) throws IOException {
		if (posix() && !Files.exists(file)) {
			Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		} else {
			restrictToOwner(file);
		}
	}

	private static boolean posix() {
		return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
	}

	private static Map<String, String> awaitCallback(ServerSocket server, String expectedState) throws IOException {
		// Browsers also ask for /favicon.ico and similar; keep answering until the real redirect arrives.
		while (true) {
			try (Socket socket = server.accept()) {
				BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				String requestLine = in.readLine();
				Map<String, String> query = new HashMap<>();
				if (requestLine != null && requestLine.startsWith("GET ")) {
					String target = requestLine.split(" ")[1];
					int q = target.indexOf('?');
					if (q >= 0) {
						for (String pair : target.substring(q + 1).split("&")) {
							int eq = pair.indexOf('=');
							if (eq > 0) {
								query.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
									URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
							}
						}
					}
				}
				boolean done = expectedState.equals(query.get("state"));
				String body = done ? "MeetCraft is linked to your Google account. You can close this tab." : "Waiting for Google...";
				OutputStream out = socket.getOutputStream();
				out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n" + body)
					.getBytes(StandardCharsets.UTF_8));
				out.flush();
				if (done) {
					return query;
				}
			}
		}
	}

	private JsonObject postForm(String url, Map<String, String> fields) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form(fields)))
			.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
		if (response.statusCode() != 200) {
			throw new TokenEndpointError(response.statusCode(), response.body());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private static String form(Map<String, String> fields) {
		StringBuilder sb = new StringBuilder();
		fields.forEach((k, v) -> {
			if (!sb.isEmpty()) {
				sb.append('&');
			}
			sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
		});
		return sb.toString();
	}

	private static String randomToken(int bytes) {
		byte[] b = new byte[bytes];
		new SecureRandom().nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	private static String sha256(String s) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Path configHome() {
		String xdg = System.getenv("XDG_CONFIG_HOME");
		return xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
	}
}
