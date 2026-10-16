// Runs inside meet.google.com: drives the mic button and tells the background which part of the page is the call.
// Meet's DOM isn't a public API, so the selectors here (data-is-muted, data-participant-id) may need fixing one day.

/** Meet marks the mic and camera buttons with data-is-muted; the mic one mentions it in its label. */
function micButton() {
  // Meet keeps stale copies around (the pre-join screen's, hidden menus), and clicking those does nothing.
  const buttons = [...document.querySelectorAll("[data-is-muted]")].filter((b) => b.getClientRects().length > 0);
  return buttons.find((b) => /mic|mikro/i.test(b.getAttribute("aria-label") ?? "")) ?? buttons[0] ?? null;
}

function micState() {
  const button = micButton();
  return button ? button.getAttribute("data-is-muted") === "false" : null;
}

// --- What goes on the theatre screen: the part of the page where Meet lays out the call (the participant tiles, or
// the presentation plus the small tiles). The extension's background captures that area of the rendered tab, so it
// looks exactly like Meet, names and camera-off tiles included. Ctrl+click a tile to show just that one; Ctrl+click it
// again to go back to the layout.

const MIN_TILE = 32;
const STAGE_PADDING = 12;
let pinned = null;

/** Meet tags each participant's tile (and the presentation's) with data-participant-id. */
function tileAt(x, y) {
  for (const el of document.elementsFromPoint(x, y)) {
    const tile = el.closest("[data-participant-id]");
    if (tile) return tile;
  }
  return null;
}

document.addEventListener(
  "click",
  (event) => {
    if (!event.ctrlKey) return;
    const tile = tileAt(event.clientX, event.clientY);
    if (!tile) return;
    event.preventDefault();
    event.stopPropagation();
    pinned = pinned === tile ? null : tile;
    renderBadge();
    reportStage();
  },
  true,
);

function shown(el) {
  const r = el.getBoundingClientRect();
  return r.width >= MIN_TILE && r.height >= MIN_TILE && getComputedStyle(el).visibility !== "hidden";
}

/** The area to capture, in page pixels, or null when not in a call. */
function stageRect() {
  if (micState() === null) return null;
  if (pinned && !(pinned.isConnected && shown(pinned))) pinned = null;
  // Participant tiles only exist in an actual call, so the lobby and other pages never end up on the screen.
  const boxes = pinned ? [pinned] : [...document.querySelectorAll("[data-participant-id]")].filter(shown);
  if (boxes.length === 0) return null;
  const rects = boxes.map((b) => b.getBoundingClientRect());
  // Keep a margin of Meet's own grey round the tiles, so the call doesn't look cut off at its edges; but stop above the
  // call controls at the bottom.
  const pad = pinned ? 0 : STAGE_PADDING;
  let left = Math.min(...rects.map((r) => r.left)) - pad;
  let top = Math.min(...rects.map((r) => r.top)) - pad;
  let right = Math.max(...rects.map((r) => r.right)) + pad;
  let bottom = Math.min(Math.max(...rects.map((r) => r.bottom)) + pad, micButton().getBoundingClientRect().top - 4);
  left = Math.max(0, left);
  top = Math.max(0, top);
  right = Math.min(innerWidth, right);
  bottom = Math.min(innerHeight, bottom);
  if (right - left < MIN_TILE || bottom - top < MIN_TILE) return null;
  return {
    x: Math.round(left + scrollX),
    y: Math.round(top + scrollY),
    width: Math.round(right - left),
    height: Math.round(bottom - top),
    dpr: devicePixelRatio,
  };
}

let lastStage;

function reportStage() {
  const stage = stageRect();
  const key = JSON.stringify(stage);
  if (port && key !== lastStage) {
    lastStage = key;
    port.postMessage({ type: "stage", rect: stage });
  }
}

setInterval(reportStage, 500);


// --- Status badge, so it's obvious from the Meet tab whether Minecraft is hooked up. ---

/** known: whether the background has told us yet; until then the mic gate stays shut. */
const status = { known: false, mc: false, streaming: false };
const badge = document.createElement("div");
badge.style.cssText = [
  "position:fixed", "left:12px", "bottom:12px", "z-index:2147483647", "pointer-events:none",
  "padding:4px 8px", "border-radius:6px", "background:rgba(0,0,0,.65)", "color:#fff",
  "font:12px/1.4 system-ui,sans-serif", "box-sizing:border-box",
].join(";");
document.documentElement.append(badge);

function renderBadge() {
  if (!status.mc) {
    badge.textContent = "⛏ MeetCraft: Minecraft not running";
  } else if (status.streaming) {
    badge.textContent = `⛏ MeetCraft: on screen · ${pinned ? "pinned, Ctrl+click to unpin" : "Ctrl+click a tile to pin it"}`;
  } else {
    badge.textContent = "⛏ MeetCraft: connected";
  }
}

// --- The mic follows Minecraft. The game keeps resending what it wants (muted unless the key says otherwise).
// The real switch is the gate (gate.js), which turns Meet's outgoing audio tracks on and off directly: instant, and
// not at the mercy of Meet reacting to its button in a throttled background tab. While the gate holds Meet's audio,
// Meet's own button is kept on live, so the gate alone decides. If the gate isn't holding anything (it failed to load,
// or Meet changed how it sends audio), Meet's button is driven instead, which is slower but still follows the game.

const root = document.documentElement;
const gate = {
  holdsAudio: () => root.hasAttribute("data-meetcraft-gate") && Number(root.getAttribute("data-meetcraft-gate-tracks")) > 0,
  sending: () => Number(root.getAttribute("data-meetcraft-gate-live")) > 0,
  mode: () => root.getAttribute("data-meetcraft-gate"),
  /** Synchronous: by the time this returns, the tracks are already switched. */
  set: (mode) => document.dispatchEvent(new CustomEvent("meetcraft:gate", { detail: mode })),
  check: () => document.dispatchEvent(new CustomEvent("meetcraft:gate-check")),
};

/**
 * Last line of defence: the game wants you muted, yet the gate says audio is still going out. Then the gate isn't
 * working on this page, and from here on Meet's own button does the muting, slower but certain.
 */
let gateFailed = false;
let leakSince = null;
const LEAK_MS = 800;

function checkForLeak() {
  if (gateFailed || wantMic !== false || !gate.holdsAudio() || !gate.sending()) {
    leakSince = null;
    return;
  }
  leakSince ??= performance.now();
  if (performance.now() - leakSince > LEAK_MS) {
    gateFailed = true;
    console.error("[MeetCraft] the mic gate didn't silence Meet's audio; muting with Meet's own button from now on");
  }
}

/** What Minecraft wants: true live, false muted, null when it isn't connected. */
let wantMic = null;
/** The game has had the mic on this page, so handing it back to Meet means muting Meet's button first. */
let gameHadMic = false;
let lastClickAt = 0;
let stateAtClick = null;
/** Handing back after the game left: since when Meet's button has shown muted. */
let meetMutedSince = null;
const HANDBACK_FALLBACK_MS = 3000;
/** In a hidden tab Meet can take a while to show a click on its button; until then, don't click again. */
const CLICK_SETTLE_MS = 2500;

/** Where Meet's own button should be: live, muted, or null to leave it alone. */
function buttonTarget() {
  if (wantMic !== null) return gate.holdsAudio() && !gateFailed ? true : wantMic;
  return gameHadMic ? false : null;
}

/**
 * Meet loads in stages and asks for permissions in dialogs; clicking its buttons in the middle of that can wedge it.
 * So no clicks while a dialog is up, nor until the buttons have been on the page for a moment.
 */
const SETTLE_MS = 1500;
let buttonsSeenAt = null;

function meetIsSettled() {
  if (micState() === null) {
    buttonsSeenAt = null;
    return false;
  }
  buttonsSeenAt ??= performance.now();
  const dialog = [...document.querySelectorAll('[role="dialog"], [role="alertdialog"]')].some((d) => d.getClientRects().length > 0);
  return !dialog && performance.now() - buttonsSeenAt > SETTLE_MS;
}

function clickTowards(target) {
  const state = micState();
  if (target === null || state === null || state === target) return;
  // Muting is never held back: that's the safe direction. Only switching things on waits for Meet to settle.
  if (target === true && !meetIsSettled()) return;
  if (state === stateAtClick && Date.now() - lastClickAt < CLICK_SETTLE_MS) return;
  console.debug(`[MeetCraft] clicking Meet's mic → ${target ? "live" : "muted"} (tab ${document.visibilityState})`);
  lastClickAt = Date.now();
  stateAtClick = state;
  micButton().click();
}

function onGameMic(on) {
  wantMic = on;
  gameHadMic = true;
  gate.set(on ? "live" : "muted"); // also renews the gate's live lease, which only the game may do
  syncMic();
}

function onGameGone() {
  wantMic = null;
  syncMic();
}

let lastLoggedGate;

// --- Camera off on arrival: once per page load, if Meet comes up with the camera on, switch it off. Turning it on
// yourself afterwards is left alone, and so is the camera when you move from the lobby into the call.

let cameraChecked = false;

function cameraOffOnArrival() {
  if (cameraChecked || !meetIsSettled()) return;
  const camera = [...document.querySelectorAll("[data-is-muted]")]
    .filter((b) => b.getClientRects().length > 0)
    .find((b) => /camera|kamera|video|vidé/i.test(b.getAttribute("aria-label") ?? ""));
  if (!camera) return;
  cameraChecked = true;
  if (camera.getAttribute("data-is-muted") === "false") {
    console.debug("[MeetCraft] turning the camera off, as on every page load");
    camera.click();
  }
}

function syncMic() {
  cameraOffOnArrival();
  gate.check();
  checkForLeak();
  if (wantMic === null) {
    if (!gameHadMic) {
      // Minecraft isn't involved, so Meet works as usual; until we know that, the gate stays shut.
      if (status.known && !status.mc && gate.mode() !== "off") gate.set("off");
    } else if (micState() !== true) {
      // Meet's button says muted. Hand back once Meet has really switched its audio off too (its button can update
      // first), or after a few seconds of it showing muted, or straight away if we've left the call.
      meetMutedSince ??= performance.now();
      const meetSilent = Number(root.getAttribute("data-meetcraft-gate-meet-live")) === 0;
      if (micState() === null || meetSilent || performance.now() - meetMutedSince > HANDBACK_FALLBACK_MS) {
        gate.set("off");
        gameHadMic = false;
        meetMutedSince = null;
      } else {
        gate.set("muted");
      }
    } else {
      meetMutedSince = null;
      gate.set("muted");
    }
  }
  clickTowards(buttonTarget());

  const inCall = micState() !== null;
  const viaGate = gate.holdsAudio() && !gateFailed;
  const mic = inCall ? (viaGate ? gate.sending() : micState() || (gate.holdsAudio() && gate.sending())) : null;
  // Meet may let go of the microphone entirely while muted, so holding nothing is only a problem while it's live.
  const blind = gateFailed || !root.hasAttribute("data-meetcraft-gate") || (!viaGate && micState() === true);
  // The gate's audio engine not running means silence (safe), but then holding the key does nothing either.
  // The gate's loopback failing means silence (safe), but then holding the key does nothing either.
  const stalled = !blind && viaGate && root.getAttribute("data-meetcraft-gate-engine") === "stalled";
  const how = blind ? "button" : stalled ? "stalled" : "gate";
  if (how !== lastLoggedGate && inCall) {
    lastLoggedGate = how;
    console.debug(how === "gate" ? "[MeetCraft] mic gate holds Meet's audio" : "[MeetCraft] mic gate isn't holding Meet's audio; using Meet's button");
  }
  const report = JSON.stringify({ mic, how });
  if (port && report !== lastReportedMic) {
    lastReportedMic = report;
    port.postMessage({ type: "mic", on: mic, gate: how });
  }
}

// --- Background connection. The port drops when the extension updates or reloads, so reconnect. ---

let port = null;
let lastReportedMic;

function connect() {
  try {
    port = chrome.runtime.connect({ name: "meet" });
  } catch {
    return; // Extension was reloaded; this copy of the script is orphaned.
  }
  lastReportedMic = undefined;
  lastReportedChat = undefined;
  lastStage = undefined;
  port.onMessage.addListener((msg) => {
    if (msg.type === "setMic") {
      onGameMic(msg.on);
    } else if (msg.type === "gateCheck") {
      syncMic(); // the background's clock isn't throttled like this tab's timers, so checks keep coming
    } else if (msg.type === "status") {
      Object.assign(status, msg, { known: true });
      if (!status.mc) onGameGone();
      renderBadge();
    }
  });
  port.onDisconnect.addListener(() => {
    port = null;
    onGameGone();
    setTimeout(connect, 1000);
  });
}

// React the moment Meet flips its button: timers in a hidden tab only fire once a second, observers don't wait.
new MutationObserver(syncMic).observe(document.documentElement, {
  subtree: true,
  attributes: true,
  attributeFilter: ["data-is-muted", "data-meetcraft-gate", "data-meetcraft-gate-tracks", "data-meetcraft-gate-live"],
});
setInterval(syncMic, 300); // still needed for joining and leaving calls, when the button itself comes and goes

// --- The call's Google Chat space. Every Meet call's in-call messages live in a Chat space, scheduled or ad hoc,
// continuous chat or not, and each message in Meet's chat panel names it: data-message-id="spaces/AAAA/messages/…".
// So it shows up once the panel has been open with at least one message in it. The older "open in Chat" link of
// continuous chat is still checked too. Best effort, like the selectors above.

const CHAT_LINK = /(?:\/room\/|chat\/space\/|\/app\/chat\/)([A-Za-z0-9_-]{8,})/;
const MESSAGE_SPACE = /^(spaces\/[A-Za-z0-9_-]+)\//;
let lastReportedChat;
/** Sticky for the life of the page: the messages disappear again when the chat panel is closed. */
let foundChat = null;

function meetingChatSpace() {
  for (const el of document.querySelectorAll('[data-message-id^="spaces/"]')) {
    const m = MESSAGE_SPACE.exec(el.getAttribute("data-message-id"));
    if (m) return m[1];
  }
  for (const a of document.querySelectorAll('a[href*="chat"]')) {
    const m = CHAT_LINK.exec(a.href);
    if (m) return `spaces/${m[1]}`;
  }
  return null;
}

setInterval(() => {
  foundChat = meetingChatSpace() ?? foundChat;
  const space = foundChat;
  if (port && space !== lastReportedChat) {
    lastReportedChat = space;
    port.postMessage({ type: "meetChat", space });
  }
}, 3000);

renderBadge();
connect();
