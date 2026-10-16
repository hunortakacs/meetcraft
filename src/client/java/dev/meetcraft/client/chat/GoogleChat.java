package dev.meetcraft.client.chat;

import com.google.gson.JsonObject;
import dev.meetcraft.MeetCraft;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * A small Google Chat client living in the Minecraft chat window, running entirely as the player with their own token.
 * You're in at most one chat at a time: opening it shows its recent history, its new messages then arrive in the
 * Minecraft chat like any other line, and what you type goes there. Other chats stay quiet; /gc list shows what's
 * unread. Nothing here touches the Minecraft server.
 *
 * <p>All Google calls and all mutable state live on one background thread; the game thread only reads the volatile
 * snapshots ({@link #current()}, {@link #recent()}, ...) and enqueues work.
 */
public final class GoogleChat {
	private static final long POLL_SECONDS = 4;
	private static final int HISTORY = 10;
	/** Name resolution costs API calls, so only the chats near the top of the list get proper names up front. */
	private static final int LABEL_TOP = 30;
	private static final int LABELS_PER_POLL = 8;
	/** A name the directory couldn't give (People API off, quota, outage) is retried after this, not cached forever. */
	private static final long NAME_RETRY_SECONDS = 120;

	/** What the game thread may read about one chat. */
	public record ChatView(String space, String label, Instant lastActive, int unread) {
	}

	private static final class ChatEntry {
		final String space;
		String type = "";
		String displayName = "";
		Instant lastActive = Instant.EPOCH;
		/** createTime of the newest message already handled. */
		String lastSeen;
		@Nullable String label;
		int unread;

		ChatEntry(String space, String lastSeen) {
			this.space = space;
			this.lastSeen = lastSeen;
		}

		boolean directMessage() {
			return "DIRECT_MESSAGE".equals(type);
		}
	}

	private final GoogleAccount account;
	private final ChatApi api;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "MeetCraft Google Chat");
		t.setDaemon(true);
		return t;
	});

	// Executor thread only.
	private final Map<String, ChatEntry> chats = new HashMap<>();
	private final Map<String, String> people = new HashMap<>();
	private final Map<String, Instant> nameRetryAt = new HashMap<>();
	private final SequencedSet<String> sentByUs = new LinkedHashSet<>();
	private @Nullable ScheduledFuture<?> poll;
	private String myUserId = "";
	private String startedAt = "";
	private @Nullable String autoOpenedMeeting;
	private List<String> lastListed = List.of();
	private @Nullable String lastPollError;

	// Written on the executor, read anywhere.
	private volatile boolean running;
	private volatile @Nullable String current;
	private volatile String currentLabel = "";
	private volatile @Nullable String meetingSpace;
	private volatile List<ChatView> recent = List.of();
	private volatile int unreadTotal;

	public GoogleChat(GoogleAccount account) {
		this.account = account;
		this.api = new ChatApi(account);
	}

	public GoogleAccount account() {
		return account;
	}

	public boolean running() {
		return running;
	}

	/** The chat you're in, or null in the overview. */
	public @Nullable String current() {
		return current;
	}

	public String currentLabel() {
		return currentLabel;
	}

	public int unreadTotal() {
		return unreadTotal;
	}

	public List<ChatView> recent() {
		return recent;
	}

	public @Nullable String meetingSpace() {
		return meetingSpace;
	}

	// --- Lifecycle ---------------------------------------------------------------------------------------------

	/** Starts listening to all chats. Begins in the meeting's chat when the extension found one, else the overview. */
	public void start() {
		executor.execute(() -> {
			if (running || !account.isLoggedIn()) {
				return;
			}
			running = true;
			chats.clear();
			startedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
			autoOpenedMeeting = null;
			lastPollError = null;
			poll = executor.scheduleWithFixedDelay(this::pollSafely, 0, POLL_SECONDS, TimeUnit.SECONDS);
		});
	}

	public void stop() {
		current = null;
		executor.execute(this::stopNow);
	}

	private void stopNow() {
		if (poll != null) {
			poll.cancel(false);
			poll = null;
		}
		running = false;
		current = null;
		currentLabel = "";
		chats.clear();
		recent = List.of();
		unreadTotal = 0;
	}

	/** From the browser extension: the Google Chat space attached to the Meet call, or null. */
	public void setMeetingSpace(@Nullable String space) {
		String normalized = space == null ? null : ChatSpaces.normalize(space).orElse(null);
		executor.execute(() -> {
			meetingSpace = normalized;
			maybeOpenMeeting();
		});
	}

	// --- Commands (all run on the executor) ----------------------------------------------------------------------

	public void open(String query) {
		executor.execute(() -> guarded(() -> {
			Optional<ChatEntry> chat = resolve(query);
			if (chat.isPresent()) {
				enter(chat.get());
			}
		}));
	}

	public void openDirectMessage(String email) {
		executor.execute(() -> guarded(() -> {
			JsonObject space;
			try {
				space = api.findDirectMessage(email);
			} catch (ChatApi.ApiError e) {
				if (e.status == 404) {
					show(ChatStyle.error("You don't have a DM with " + email + " yet. Start it in Google Chat once, then try again."));
					return;
				}
				throw e;
			}
			enter(remember(space));
		}));
	}

	public void openMeeting() {
		executor.execute(() -> guarded(() -> {
			if (meetingSpace == null) {
				show(ChatStyle.error("No meeting chat found. Open the chat panel in your Meet tab, or use /gc open."));
				return;
			}
			enter(entryFor(meetingSpace));
		}));
	}

	public void leave() {
		current = null;
		executor.execute(() -> {
			currentLabel = "";
			show(ChatStyle.info("Left the Google chat; typing goes to Minecraft again."));
		});
	}

	/** Sends a line to the chat you're in. */
	public void send(String text) {
		String target = current;
		if (target == null) {
			return;
		}
		executor.execute(() -> guarded(() -> {
			post(entryFor(target), text);
			if (target.equals(current)) {
				show(ChatStyle.message("you", true, text));
			}
		}));
	}

	public void list(boolean all) {
		executor.execute(() -> guarded(() -> {
			List<ChatEntry> sorted = sortedChats();
			if (sorted.isEmpty()) {
				show(ChatStyle.info("No chats yet; give it a few seconds after logging in."));
				return;
			}
			int count = Math.min(sorted.size(), all ? 50 : 12);
			for (int i = 0; i < count; i++) {
				ensureLabel(sorted.get(i));
			}
			show(ChatStyle.header("Your chats"));
			List<String> listed = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				ChatEntry chat = sorted.get(i);
				listed.add(chat.space);
				show(ChatStyle.listRow(i + 1, chat.space, label(chat), chat.space.equals(current), chat.space.equals(meetingSpace),
					chat.lastActive, chat.unread));
			}
			lastListed = listed;
			show(ChatStyle.hint(sorted.size() > count ? "Click one, or /gc open <number|name>. /gc list all for more." : "Click one, or /gc open <number|name>."));
			publish();
		}));
	}

	// --- Polling -------------------------------------------------------------------------------------------------

	private void pollSafely() {
		try {
			pollOnce();
			lastPollError = null;
		} catch (GoogleAccount.LoginExpired e) {
			stopNow();
			show(ChatStyle.error(e.getMessage() + ". Run /gc login to reconnect Google Chat."));
		} catch (ChatApi.ApiError e) {
			if (e.status == 403) {
				// Missing scope, API disabled or blocked by the admin: polling again won't change that.
				stopNow();
				show(ChatStyle.error("Google Chat refused access (" + e.getMessage() + "). Try /gc login again."));
			} else {
				logOnce(e);
			}
		} catch (Exception e) {
			logOnce(e);
		}
	}

	private void pollOnce() throws IOException {
		if (myUserId.isEmpty() && Instant.now().isAfter(nameRetryAt.getOrDefault("people/me", Instant.EPOCH))) {
			try {
				myUserId = api.myUserId();
			} catch (ChatApi.ApiError e) {
				// Without People API access your own messages aren't recognised as yours; keep trying now and then.
				logOnce(new IOException("Couldn't look up your own Google user: " + e.getMessage(), e));
				nameRetryAt.put("people/me", Instant.now().plusSeconds(NAME_RETRY_SECONDS));
			}
		}
		boolean firstPoll = chats.isEmpty();
		for (JsonObject space : api.listSpaces()) {
			remember(space);
		}
		for (ChatEntry chat : List.copyOf(chats.values())) {
			if (chat.space.equals(current) || chat.lastActive.isAfter(Instant.parse(chat.lastSeen))) {
				// One chat failing (left it, removed, odd space type) mustn't keep the others from being checked.
				try {
					for (JsonObject message : api.messagesAfter(chat.space, chat.lastSeen)) {
						chat.lastSeen = ChatApi.string(message, "createTime");
						deliver(chat, message);
					}
				} catch (ChatApi.ApiError e) {
					logOnce(new IOException(chat.space + ": " + e.getMessage(), e));
				}
			}
		}
		resolveSomeLabels();
		publish();
		if (firstPoll) {
			maybeOpenMeeting();
		}
	}

	private void deliver(ChatEntry chat, JsonObject message) throws IOException {
		if (sentByUs.contains(ChatApi.string(message, "name")) || message.has("deletionMetadata")) {
			return;
		}
		String text = textOf(message);
		if (text.isEmpty()) {
			return;
		}
		JsonObject sender = message.getAsJsonObject("sender");
		boolean mine = ChatApi.string(sender, "name").equals(myUserId);
		if (chat.space.equals(current)) {
			show(ChatStyle.message(mine ? "you" : senderName(sender), mine, text));
		} else if (!mine) {
			chat.unread++;
		}
	}

	private static String textOf(JsonObject message) {
		String text = ChatApi.string(message, "text");
		if (text.isEmpty() && message.has("attachment")) {
			return "[attachment]";
		}
		return text;
	}

	// --- Chats and names -----------------------------------------------------------------------------------------

	private ChatEntry remember(JsonObject space) {
		ChatEntry chat = chats.computeIfAbsent(ChatApi.string(space, "name"), name -> new ChatEntry(name, startedAt));
		chat.type = ChatApi.string(space, "spaceType");
		chat.displayName = ChatApi.string(space, "displayName");
		String lastActive = ChatApi.string(space, "lastActiveTime");
		chat.lastActive = lastActive.isEmpty() ? Instant.EPOCH : Instant.parse(lastActive);
		if (chat.label == null && !chat.displayName.isEmpty()) {
			chat.label = "#" + chat.displayName;
		}
		return chat;
	}

	private ChatEntry entryFor(String space) throws IOException {
		ChatEntry known = chats.get(space);
		return known != null ? known : remember(api.getSpace(space));
	}

	private String label(ChatEntry chat) {
		if (chat.label != null) {
			return chat.label;
		}
		return chat.directMessage() ? "@…" : "#…";
	}

	/** DMs and unnamed group chats are labelled after the other people in them. */
	private void ensureLabel(ChatEntry chat) {
		if (chat.label != null || Instant.now().isBefore(nameRetryAt.getOrDefault(chat.space, Instant.EPOCH))) {
			return;
		}
		nameRetryAt.put(chat.space, Instant.now().plusSeconds(NAME_RETRY_SECONDS));
		List<String> names = new ArrayList<>();
		try {
			for (JsonObject membership : api.listMembers(chat.space)) {
				JsonObject member = membership.getAsJsonObject("member");
				String id = ChatApi.string(member, "name");
				if (!id.isEmpty() && !id.equals(myUserId)) {
					String name = knownName(id);
					if (name == null) {
						return; // Stays "@…" for now; the next try comes once the lookup may work again.
					}
					names.add(name);
				}
			}
		} catch (IOException e) {
			MeetCraft.LOGGER.debug("Couldn't list members of {}: {}", chat.space, e.getMessage());
			return;
		}
		if (names.isEmpty()) {
			chat.label = chat.directMessage() ? "@unknown" : "#" + chat.space.replace("spaces/", "");
		} else if (names.size() > 3) {
			chat.label = "@" + String.join(", ", names.subList(0, 3)) + " +" + (names.size() - 3);
		} else {
			chat.label = "@" + String.join(", ", names);
		}
	}

	private void resolveSomeLabels() {
		int budget = LABELS_PER_POLL;
		List<ChatEntry> sorted = sortedChats();
		for (int i = 0; i < Math.min(LABEL_TOP, sorted.size()) && budget > 0; i++) {
			if (sorted.get(i).label == null) {
				ensureLabel(sorted.get(i));
				budget--;
			}
		}
	}

	private String senderName(@Nullable JsonObject sender) {
		if (sender == null) {
			return "someone";
		}
		String displayName = ChatApi.string(sender, "displayName");
		return displayName.isEmpty() ? personName(ChatApi.string(sender, "name")) : displayName;
	}

	private String personName(String userId) {
		String name = knownName(userId);
		return name != null ? name : "someone";
	}

	/** The person's name, or null while the directory can't say; failures are retried later rather than cached. */
	private @Nullable String knownName(String userId) {
		String cached = people.get(userId);
		if (cached != null || Instant.now().isBefore(nameRetryAt.getOrDefault(userId, Instant.EPOCH))) {
			return cached;
		}
		try {
			String name = api.personName(userId);
			if (name != null && !name.isEmpty()) {
				people.put(userId, name);
				return name;
			}
		} catch (IOException e) {
			MeetCraft.LOGGER.debug("Couldn't look up {}: {}", userId, e.getMessage());
		}
		nameRetryAt.put(userId, Instant.now().plusSeconds(NAME_RETRY_SECONDS));
		return null;
	}

	private List<ChatEntry> sortedChats() {
		return chats.values().stream().sorted(Comparator.comparing((ChatEntry c) -> c.lastActive).reversed()).toList();
	}

	private void publish() {
		List<ChatView> views = new ArrayList<>();
		int unread = 0;
		for (ChatEntry chat : sortedChats()) {
			views.add(new ChatView(chat.space, label(chat), chat.lastActive, chat.unread));
			unread += chat.unread;
		}
		recent = List.copyOf(views);
		unreadTotal = unread;
	}

	// --- Opening chats -------------------------------------------------------------------------------------------

	/** Accepts a number from the last /gc list, a chat name (or the start of one), a space ID, or a Chat link. */
	private Optional<ChatEntry> resolve(String query) throws IOException {
		String q = query.strip();
		if (q.matches("\\d+")) {
			int index = Integer.parseInt(q) - 1;
			List<String> order = lastListed.isEmpty() ? sortedChats().stream().map(c -> c.space).toList() : lastListed;
			if (index < 0 || index >= order.size()) {
				show(ChatStyle.error("No chat number " + q + " in your last /gc list."));
				return Optional.empty();
			}
			return Optional.of(entryFor(order.get(index)));
		}
		if (q.startsWith("spaces/") || q.contains("://")) {
			Optional<String> space = ChatSpaces.normalize(q);
			if (space.isPresent()) {
				return Optional.of(entryFor(space.get()));
			}
		}
		String needle = stripSigil(q).toLowerCase(Locale.ROOT);
		List<ChatEntry> labelled = sortedChats().stream().filter(c -> c.label != null).toList();
		for (var match : List.<Predicate<String>>of(needle::equals, s -> s.startsWith(needle), s -> s.contains(needle))) {
			List<ChatEntry> hits = labelled.stream().filter(c -> match.test(stripSigil(c.label).toLowerCase(Locale.ROOT))).toList();
			if (hits.size() == 1) {
				return Optional.of(hits.getFirst());
			}
			if (hits.size() > 1) {
				show(ChatStyle.info("Which one?"));
				hits.stream().limit(8).forEach(c -> show(ChatStyle.choice(c.space, c.label)));
				return Optional.empty();
			}
		}
		show(ChatStyle.error("No chat matching \"" + q + "\". /gc list shows them; /gc dm <email> opens a DM."));
		return Optional.empty();
	}

	private static String stripSigil(String label) {
		return label.startsWith("#") || label.startsWith("@") ? label.substring(1) : label;
	}

	private void enter(ChatEntry chat) throws IOException {
		ensureLabel(chat);
		current = chat.space;
		currentLabel = label(chat);
		chat.unread = 0;
		show(ChatStyle.header(label(chat)));
		List<JsonObject> history = api.latestMessages(chat.space, HISTORY);
		for (JsonObject message : history) {
			String text = textOf(message);
			if (!text.isEmpty() && !message.has("deletionMetadata")) {
				JsonObject sender = message.getAsJsonObject("sender");
				boolean mine = ChatApi.string(sender, "name").equals(myUserId);
				show(ChatStyle.message(mine ? "you" : senderName(sender), mine, text));
			}
		}
		if (!history.isEmpty()) {
			String newest = ChatApi.string(history.getLast(), "createTime");
			if (Instant.parse(newest).isAfter(Instant.parse(chat.lastSeen))) {
				chat.lastSeen = newest;
			}
		}
		show(ChatStyle.hint("Typing goes here · !text for Minecraft · /gc leave"));
		publish();
	}

	/** Opens the meeting's chat once per meeting, and only if you haven't already gone somewhere yourself. */
	private void maybeOpenMeeting() {
		String meeting = meetingSpace;
		if (!running || chats.isEmpty() || meeting == null || meeting.equals(autoOpenedMeeting) || current != null) {
			return;
		}
		autoOpenedMeeting = meeting;
		guarded(() -> enter(entryFor(meeting)));
	}

	private void post(ChatEntry chat, String text) throws IOException {
		JsonObject created = api.createMessage(chat.space, text);
		sentByUs.add(ChatApi.string(created, "name"));
		if (sentByUs.size() > 500) {
			sentByUs.removeFirst();
		}
	}

	// --- Plumbing ------------------------------------------------------------------------------------------------

	private interface IoAction {
		void run() throws IOException;
	}

	/** Runs a command's work, turning failures into a red line in chat instead of a dead executor task. */
	private void guarded(IoAction action) {
		if (!running) {
			show(ChatStyle.error(account.isLoggedIn() ? "Google Chat is still starting; try again in a moment." : "Not logged in. Run /gc login first."));
			return;
		}
		try {
			action.run();
		} catch (GoogleAccount.LoginExpired e) {
			stopNow();
			show(ChatStyle.error(e.getMessage() + ". Run /gc login to reconnect Google Chat."));
		} catch (IOException e) {
			show(ChatStyle.error(e.getMessage()));
		}
	}

	private void logOnce(Exception e) {
		String message = e.toString();
		if (!message.equals(lastPollError)) {
			lastPollError = message;
			MeetCraft.LOGGER.warn("Google Chat poll failed: {}", message);
		}
	}

	/** Adds a line to this player's own chat window only; nothing is sent to the server. */
	static void show(Component line) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.hud.getChat().addClientSystemMessage(line));
	}
}
