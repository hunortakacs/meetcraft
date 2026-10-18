// Runs inside meet.google.com once you've turned MeetCraft on there (toggle.js; the background injects it): drives the
// mic and tells the background which part of the page is the call.
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

// --- Live tiles. The background still takes pictures of the stage, but only about 10 times a second ("layout"); for
// each frame ("compose") this draws the latest picture and paints the call's videos (cameras, presentations) over it
// straight from their <video> elements, so they move at their own frame rate. Whatever Meet puts over a video (names,
// mic icons, the speaking ring) is copied back on top from the picture, so it lags the video by up to a tenth of a
// second, which nobody sees on a name. The game's "Meet video" setting switches back to whole pictures of the page.

const LAYOUT_STALE_MS = 1500;
const JPEG_QUALITY = 0.7;
const MAX_PATCHES = 24;
const composeCanvas = document.createElement("canvas");
/** The latest picture of the stage and what's painted over it: { bitmap, tiles, at }. */
let layout = null;
let composing = false;
let liveFailed = false;

function intersect(a, b) {
  const left = Math.max(a.left, b.left);
  const top = Math.max(a.top, b.top);
  const right = Math.min(a.right, b.right);
  const bottom = Math.min(a.bottom, b.bottom);
  return right > left && bottom > top ? { left, top, right, bottom } : null;
}

function alpha(color) {
  if (!color || color === "transparent") return 0;
  const parts = /rgba?\(([^)]*)\)/.exec(color)?.[1].split(/[\s,/]+/).filter(Boolean);
  return parts?.length > 3 ? parseFloat(parts[3]) : 1;
}

/** From `el` up to its call tile, or a few levels when it isn't in one. */
function* upToTile(el) {
  for (let node = el, depth = 0; node && depth < 12; node = node.parentElement, depth++) {
    yield node;
    if (node.hasAttribute("data-participant-id")) return;
  }
}

function hiddenStyle(style) {
  return style.display === "none" || style.visibility === "hidden" || Number(style.opacity) < 0.05;
}

function hidden(el) {
  for (const node of upToTile(el)) if (hiddenStyle(getComputedStyle(node))) return true;
  return false;
}

/** Your own camera is shown mirrored, with a CSS flip on the video or round it. */
function mirrored(video) {
  let flip = false;
  for (const node of upToTile(video)) {
    const a = /^matrix\(([^,]+)/.exec(getComputedStyle(node).transform)?.[1];
    if (a !== undefined && parseFloat(a) < 0) flip = !flip;
  }
  return flip;
}

/** Where the video is cut off, and how round its corners are: the nearest box round it that clips (the tile). */
function clipOf(video) {
  let rect = video.getBoundingClientRect();
  let radius = parseFloat(getComputedStyle(video).borderTopLeftRadius) || 0;
  for (const node of upToTile(video)) {
    if (node === video) continue;
    const style = getComputedStyle(node);
    if (style.overflowX !== "visible" || style.overflowY !== "visible") {
      rect = intersect(rect, node.getBoundingClientRect());
      radius = Math.max(radius, parseFloat(style.borderTopLeftRadius) || 0);
      break;
    }
  }
  return { rect, radius };
}

/** Something over the video at this point that hides it, like the avatar of a camera that's off. */
function coveredAt(video, x, y) {
  for (const el of document.elementsFromPoint(x, y)) {
    if (el === video || el.contains(video)) return false;
    if (["IMG", "CANVAS", "VIDEO"].includes(el.tagName)) return true;
    const style = getComputedStyle(el);
    if (alpha(style.backgroundColor) > 0.9 || style.backgroundImage !== "none") return true;
  }
  return false;
}

/** Whether an element puts anything on screen of its own: text, an icon, a background, a border. */
function paints(el, style) {
  if (["IMG", "CANVAS", "I"].includes(el.tagName) || el instanceof SVGSVGElement) return true;
  for (const child of el.childNodes) if (child.nodeType === Node.TEXT_NODE && child.textContent.trim()) return true;
  return alpha(style.backgroundColor) > 0.05 || style.backgroundImage !== "none" || frames(style);
}

function frames(style) {
  return style.boxShadow !== "none" || (parseFloat(style.borderTopWidth) > 0 && alpha(style.borderTopColor) > 0.05);
}

function grow(rect, by, within) {
  return intersect({ left: rect.left - by, top: rect.top - by, right: rect.right + by, bottom: rect.bottom + by }, within);
}

/**
 * The bits of the tile Meet paints over the video: elements after it in the page (so on top) that show something.
 * Small ones are copied whole; one as big as the tile (the speaking ring, a frame) only along its edges.
 */
function overlaysOver(video, visible) {
  const tile = video.closest("[data-participant-id]");
  if (!tile) return [];
  const area = (visible.right - visible.left) * (visible.bottom - visible.top);
  const patches = [];
  for (const el of tile.querySelectorAll("*")) {
    if (patches.length >= MAX_PATCHES) break;
    if (el === video || el.contains(video) || el.ownerSVGElement) continue;
    if (!(video.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING)) continue;
    const r = el.getBoundingClientRect();
    const over = r.width >= 1 && r.height >= 1 && intersect(r, visible);
    if (!over) continue;
    const style = getComputedStyle(el);
    if (hiddenStyle(style) || !paints(el, style)) continue;
    if (r.width * r.height <= area / 2) {
      patches.push(grow(over, 2, visible));
    } else if (frames(style)) {
      const edge = Math.max(4, parseFloat(style.borderTopWidth) || 0) + 1;
      const { left, top, right, bottom } = over;
      patches.push(
        { left, top, right, bottom: Math.min(bottom, top + edge) },
        { left, top: Math.max(top, bottom - edge), right, bottom },
        { left, top, right: Math.min(right, left + edge), bottom },
        { left: Math.max(left, right - edge), top, right, bottom },
      );
    }
  }
  return patches.filter(Boolean);
}

/** Where each live video goes in a picture of `stage` that's `bitmap` big, measured now, as the picture arrives. */
function liveTiles(stage, bitmap) {
  const view = { left: stage.x - scrollX, top: stage.y - scrollY };
  view.right = view.left + stage.width;
  view.bottom = view.top + stage.height;
  const sx = bitmap.width / stage.width;
  const sy = bitmap.height / stage.height;
  const toFrame = (r) => ({ x: (r.left - view.left) * sx, y: (r.top - view.top) * sy, w: (r.right - r.left) * sx, h: (r.bottom - r.top) * sy });
  const tiles = [];
  for (const video of document.querySelectorAll("video")) {
    if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA || !video.videoWidth) continue;
    const clip = clipOf(video);
    const visible = clip.rect && intersect(clip.rect, view);
    if (!visible || visible.right - visible.left < 16 || visible.bottom - visible.top < 16 || hidden(video)) continue;
    if (coveredAt(video, (visible.left + visible.right) / 2, (visible.top + visible.bottom) / 2)) continue;
    tiles.push({
      video,
      box: toFrame(video.getBoundingClientRect()),
      clip: toFrame(visible),
      radius: clip.radius * sx,
      mirrored: mirrored(video),
      fit: getComputedStyle(video).objectFit,
      patches: overlaysOver(video, visible).map(toFrame),
    });
  }
  return tiles;
}

/** Where the picture sits inside the video element, following its object-fit (centred, as Meet has it). */
function placed(tile) {
  const { box, fit, video } = tile;
  if (fit === "fill") return box;
  const scale = (fit === "cover" ? Math.max : Math.min)(box.w / video.videoWidth, box.h / video.videoHeight);
  const w = video.videoWidth * scale;
  const h = video.videoHeight * scale;
  return { x: box.x + (box.w - w) / 2, y: box.y + (box.h - h) / 2, w, h };
}

async function onLayout(jpeg, stage) {
  const bitmap = await createImageBitmap(new Blob([jpeg], { type: "image/jpeg" }));
  const tiles = liveTiles(stage, bitmap);
  layout?.bitmap.close();
  layout = { bitmap, tiles, at: performance.now() };
}

async function compose() {
  if (composing || liveFailed || !layout || !port || performance.now() - layout.at > LAYOUT_STALE_MS) return;
  composing = true;
  try {
    const { bitmap, tiles } = layout;
    if (composeCanvas.width !== bitmap.width || composeCanvas.height !== bitmap.height) {
      composeCanvas.width = bitmap.width;
      composeCanvas.height = bitmap.height;
    }
    const ctx = composeCanvas.getContext("2d");
    ctx.drawImage(bitmap, 0, 0);
    for (const tile of tiles) {
      if (!tile.video.isConnected || tile.video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) continue;
      const { clip, box } = tile;
      const at = placed(tile);
      ctx.save();
      ctx.beginPath();
      ctx.roundRect(clip.x, clip.y, clip.w, clip.h, tile.radius);
      ctx.clip();
      if (tile.mirrored) {
        // Flip round the middle of the video element, as the page does.
        ctx.translate(2 * box.x + box.w, 0);
        ctx.scale(-1, 1);
      }
      ctx.drawImage(tile.video, at.x, at.y, at.w, at.h);
      ctx.restore();
      for (const p of tile.patches) ctx.drawImage(bitmap, p.x, p.y, p.w, p.h, p.x, p.y, p.w, p.h);
    }
    const blob = await new Promise((resolve) => composeCanvas.toBlob(resolve, "image/jpeg", JPEG_QUALITY));
    if (blob && port) port.postMessage({ type: "frame", jpeg: await blob.arrayBuffer() });
  } catch (error) {
    liveFailed = true;
    console.warn("[MeetCraft] couldn't draw the call's videos; sending pictures of the page instead:", error);
    port?.postMessage({ type: "liveFailed" });
  } finally {
    composing = false;
  }
}


// --- Status in the MeetCraft badge (toggle.js), so it's obvious from the Meet tab whether Minecraft is hooked up. ---

/** known: whether the background has told us yet; until then the mic gate stays shut. */
const status = { known: false, mc: false, streaming: false };

function renderBadge() {
  if (!status.mc) {
    badgeText.textContent = "⛏ MeetCraft: Minecraft not running";
  } else if (status.streaming) {
    badgeText.textContent = `⛏ MeetCraft: on screen · ${pinned ? "pinned, Ctrl+click to unpin" : "Ctrl+click a tile to pin it"}`;
  } else {
    badgeText.textContent = "⛏ MeetCraft: connected";
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
    } else if (msg.type === "layout") {
      onLayout(msg.jpeg, msg.stage).catch((error) => console.debug("[MeetCraft] couldn't read a picture of the stage:", error));
    } else if (msg.type === "compose") {
      compose();
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
