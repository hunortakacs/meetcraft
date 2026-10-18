// Relays between Instagram tabs (reels.js) and the Minecraft mod (WebSocket on localhost), frames included. Like the
// Meet extension's, the socket and the frame clock live in this persistent background page: Instagram's CSP doesn't
// apply here, and its timers aren't throttled the way a background tab's are.

const MOD_URL = "ws://127.0.0.1:25600";
const REELS_URL = "https://www.instagram.com/reels/";
const MAX_BUFFERED_BYTES = 2 * 1024 * 1024;
/** How often the watched tab is asked for a frame: twice the usual 30 fps of a reel, so none is missed or late. */
const FRAME_ASK_HZ = 60;
/** For the fallback, pictures of the tab (see captureReel). */
const MAX_FRAME_SIDE = 960;
const JPEG_QUALITY = 70;
/** After opening an Instagram tab for the game, give it this long to load before opening another. */
const OPEN_GRACE_MS = 30000;

let ws = null;
let everConnected = false;
/** What the game wants: the view open (reel playing, frames flowing), frame rate (fallback only), sound off, auto-scroll. */
const game = { view: false, fps: 30, height: 0, muted: false, auto: false };
/** Open Instagram tabs, mapped to what each last reported (null until it does). */
const tabs = new Map();
/** The tab the game is watching; it stays the one until it goes away or another is clearly better. */
let active = null;
/** Tabs this extension muted, so they get their sound back when the game goes. */
const mutedByUs = new Set();
let openedTabAt = 0;
let captureInFlight = false;

function connected() {
  return ws?.readyState === WebSocket.OPEN;
}

function sendToMod(msg) {
  if (connected()) ws.send(JSON.stringify(msg));
}

/** A tab on the reels feed beats any other Instagram page, and one showing a video beats one that isn't. */
function score(port) {
  const report = tabs.get(port);
  return (report?.reelsPage ? 2 : 0) + (report?.video ? 1 : 0);
}

function chooseActive() {
  let best = tabs.has(active) ? active : null;
  for (const port of tabs.keys()) if (best === null || score(port) > score(best)) best = port;
  if (best !== active) {
    active = best;
    pushState();
  }
}

/** Every tab learns what the game wants; only the watched one plays. */
function pushState() {
  const on = connected();
  for (const port of tabs.keys()) {
    port.postMessage({
      type: "state",
      connected: on,
      view: on && game.view && port === active,
      height: game.height,
      muted: game.muted,
      auto: game.auto,
    });
  }
  syncTabMute();
}

/**
 * Sound off is the tab's own mute: it works whatever Instagram's player thinks, and while the tab is in the
 * background. Only the watched tab is muted, and only while the game is there.
 */
function syncTabMute() {
  for (const port of tabs.keys()) {
    const tabId = port.sender.tab.id;
    const want = connected() && game.muted && port === active;
    if (want && !mutedByUs.has(tabId)) {
      mutedByUs.add(tabId);
      browser.tabs.update(tabId, { muted: true }).catch(() => {});
    } else if (!want && mutedByUs.has(tabId)) {
      mutedByUs.delete(tabId);
      browser.tabs.update(tabId, { muted: false }).catch(() => {});
    }
  }
}

/** Opening the view with no Instagram tab around opens one, in the background, on the reels feed. */
function openReelsIfNone() {
  if (!game.view || tabs.size > 0 || Date.now() - openedTabAt < OPEN_GRACE_MS) return;
  openedTabAt = Date.now();
  console.debug("[MeetCraft Reels] no Instagram tab; opening the reels feed");
  browser.tabs.create({ url: REELS_URL, active: false }).catch((error) => console.warn("[MeetCraft Reels] couldn't open Instagram:", error));
}

function reportReel() {
  const report = tabs.get(active);
  sendToMod({
    type: "reel",
    tab: active !== null,
    playing: !!report?.playing,
    muted: !!report?.muted,
    soundBlocked: !!report?.soundBlocked,
    time: report?.time ?? 0,
    duration: report?.duration ?? 0,
    fps: report?.fps ?? 0,
  });
}

// While the game is connected, ask every Instagram tab for news four times a second: messages reach a background tab
// promptly, its own timers don't.
setInterval(() => {
  if (!connected()) return;
  for (const port of tabs.keys()) port.postMessage({ type: "tick" });
}, 250);

function watching() {
  return game.view && active !== null && connected();
}

// The frame clock: the watched tab answers with a frame whenever its reel has a new one.
setInterval(() => {
  if (watching() && !tabs.get(active)?.canvasBlocked) active.postMessage({ type: "frame" });
}, 1000 / FRAME_ASK_HZ);

function sendFrame(jpeg) {
  if (!connected() || ws.bufferedAmount > MAX_BUFFERED_BYTES) return;
  ws.send(jpeg);
  framesSent++;
}

// The same Firefox penalty on failing WebSockets as in the Meet extension: probe with plain HTTP (the mod's socket
// answers it with a 404) and only open the WebSocket once Minecraft is there.
const PROBE_URL = MOD_URL.replace(/^ws:/, "http:");

async function connect() {
  try {
    await fetch(PROBE_URL, { cache: "no-store" });
  } catch {
    setTimeout(connect, 1000);
    return;
  }
  ws = new WebSocket(MOD_URL);
  ws.binaryType = "arraybuffer";
  ws.onopen = () => {
    everConnected = true;
    pushState();
    reportReel();
  };
  ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    if (msg.type === "state") {
      game.view = !!msg.view;
      game.muted = !!msg.muted;
      game.auto = !!msg.auto;
      game.height = typeof msg.height === "number" ? msg.height : 0;
      if (typeof msg.fps === "number") game.fps = Math.min(30, Math.max(1, msg.fps));
      pushState();
      openReelsIfNone();
    } else if (msg.type === "step") {
      active?.postMessage({ type: "step", by: msg.by > 0 ? 1 : -1 });
    }
  };
  ws.onclose = () => {
    const wasOpen = everConnected;
    ws = null;
    everConnected = false;
    // The game is gone: pause what it was watching and give the tab its sound back.
    game.view = false;
    pushState();
    setTimeout(connect, wasOpen ? 250 : 1000);
  };
}

browser.runtime.onConnect.addListener((port) => {
  if (port.name !== "reels" || !port.sender?.tab) return;
  tabs.set(port, null);
  chooseActive();
  pushState();
  port.onMessage.addListener((msg) => {
    if (msg.type === "frame") {
      if (port === active && game.view) sendFrame(msg.jpeg);
      encodeMs += msg.encodeMs ?? 0;
      encodes++;
      return;
    }
    if (msg.type !== "reel") return;
    tabs.set(port, msg);
    chooseActive();
    if (port === active) reportReel();
  });
  port.onDisconnect.addListener(() => {
    tabs.delete(port);
    // Gone from Instagram (or closed): whatever the tab shows now, it isn't ours to keep quiet.
    const tabId = port.sender.tab.id;
    if (mutedByUs.delete(tabId)) browser.tabs.update(tabId, { muted: false }).catch(() => {});
    if (port === active) active = null;
    chooseActive();
    reportReel();
  });
});

// Instagram pauses reels when its tab goes to the background. visible.js keeps the page thinking it's in front while
// the game is watching; it has to be in the page's own world before Instagram's scripts run.
const VISIBLE_ID = "meetcraft-reels-visible";
(async () => {
  const registered = await browser.scripting.getRegisteredContentScripts({ ids: [VISIBLE_ID] });
  if (registered.length === 0) {
    await browser.scripting.registerContentScripts([
      { id: VISIBLE_ID, matches: ["https://www.instagram.com/*"], js: ["visible.js"], runAt: "document_start", world: "MAIN" },
    ]);
  }
})().catch((error) => console.error("[MeetCraft Reels] couldn't install the background-play script:", error));

// What's flowing, for the Inspect console when the game shows no reel.
let framesSent = 0;
let encodes = 0;
let encodeMs = 0;
setInterval(() => {
  if (game.view && connected()) {
    const reelFps = tabs.get(active)?.fps;
    const avg = encodes ? (encodeMs / encodes).toFixed(1) : "-";
    console.debug(`[MeetCraft Reels] ${framesSent / 10} frames/s to Minecraft (reel: ${reelFps || "?"} fps), ${avg} ms per frame in the tab`);
  }
  framesSent = 0;
  encodes = 0;
  encodeMs = 0;
}, 10000);

let lastCaptureError = "";

/**
 * The fallback, for a reel whose pixels the page won't give us: a picture of that part of the rendered tab, which
 * Firefox takes even while the tab is in the background. Slower, and with Instagram's buttons on top.
 */
async function captureReel(port) {
  const rect = tabs.get(port)?.rect;
  if (!rect) return null;
  const { x, y, width, height, dpr } = rect;
  const url = await browser.tabs.captureTab(port.sender.tab.id, {
    format: "jpeg",
    quality: JPEG_QUALITY,
    rect: { x, y, width, height },
    scale: Math.min(dpr, MAX_FRAME_SIDE / Math.max(width, height)),
  });
  return (await fetch(url)).arrayBuffer();
}

// Each frame is scheduled from when the last one started, so a slow capture eats into the wait instead of piling up.
async function captureLoop() {
  const started = performance.now();
  await captureOnce();
  setTimeout(captureLoop, Math.max(0, 1000 / game.fps - (performance.now() - started)));
}

async function captureOnce() {
  if (!watching() || !tabs.get(active)?.canvasBlocked) return;
  if (captureInFlight || ws.bufferedAmount > MAX_BUFFERED_BYTES) return;
  captureInFlight = true;
  try {
    const frame = await captureReel(active);
    if (frame) sendFrame(frame);
  } catch (error) {
    if (String(error) !== lastCaptureError) console.warn("[MeetCraft Reels] couldn't capture the Instagram tab:", error);
    lastCaptureError = String(error);
  } finally {
    captureInFlight = false;
  }
}

captureLoop();

connect();
