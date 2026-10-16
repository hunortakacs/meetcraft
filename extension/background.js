// Relays between the Meet tab (content script) and the Minecraft mod (WebSocket on localhost).
// The socket lives in this persistent background page rather than in the content script, so Meet's
// page CSP doesn't apply to it and it keeps running while the Meet tab is in the background.

const MOD_URL = "ws://127.0.0.1:25599";
/** Frames per second the game asked for (its "Video frame rate" setting); captures that run long just lower it. */
let fps = 30;
const MAX_BUFFERED_BYTES = 2 * 1024 * 1024;
const MAX_FRAME_WIDTH = 960;
const JPEG_QUALITY = 70;

let ws = null;
let everConnected = false;
let wantFrames = false;
let captureInFlight = false;
/** Open Meet tabs, mapped to the mic state each last reported (null = not in a call). */
const tabs = new Map();
/** The Google Chat space each Meet tab found for its call, if any. */
const meetingChats = new Map();
/** The part of each Meet tab to put on the screen ({x, y, width, height, dpr} in page pixels), or null for nothing. */
const stages = new Map();

function activeTab() {
  // Prefer a tab that's actually in a call, so a stray second Meet tab doesn't steal the lever.
  let fallback = null;
  for (const [port, mic] of tabs) {
    fallback = port;
    if (mic !== null) return port;
  }
  return fallback;
}

function sendToMod(msg) {
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
}

/** How each Meet tab is controlling the mic: "gate" (direct, instant) or "button" (fallback via Meet's button). */
const micPaths = new Map();

function reportMic() {
  const port = activeTab();
  sendToMod({ type: "mic", on: port ? tabs.get(port) : null, gate: (port && micPaths.get(port)) ?? null });
}

// The game resends its mic intent every 500 ms. If that stops while the socket still looks open (game frozen,
// half-dead connection), mute rather than trust the last "live".
const MIC_DEADLINE_MS = 1500;
let lastMicFromGame = 0;
setInterval(() => {
  if (ws?.readyState === WebSocket.OPEN && lastMicFromGame && Date.now() - lastMicFromGame > MIC_DEADLINE_MS) {
    for (const port of tabs.keys()) port.postMessage({ type: "setMic", on: false });
  }
}, 250);

// While the game is connected, have every Meet tab re-check its mic gate 10 times a second. This page's timers aren't
// throttled the way a background tab's are, so anything that switched audio back on is caught within ~100 ms.
setInterval(() => {
  if (ws?.readyState !== WebSocket.OPEN) return;
  for (const port of tabs.keys()) port.postMessage({ type: "gateCheck" });
}, 100);

// The gate has to be in Meet's own page before Meet's scripts run, which only a MAIN-world registration can do.
const GATE_ID = "meetcraft-gate";
(async () => {
  const registered = await browser.scripting.getRegisteredContentScripts({ ids: [GATE_ID] });
  if (registered.length === 0) {
    await browser.scripting.registerContentScripts([
      { id: GATE_ID, matches: ["https://meet.google.com/*"], js: ["gate.js"], runAt: "document_start", world: "MAIN" },
    ]);
  }
})().catch((error) => console.error("[MeetCraft] couldn't install the mic gate:", error));

// Meet tabs only get the rest of MeetCraft (meet.js) once you've turned it on there; until then toggle.js is all that runs.
browser.runtime.onMessage.addListener((msg, sender) => {
  if (msg.type !== "meetcraftOn" || !sender.tab) return;
  browser.scripting
    .executeScript({ target: { tabId: sender.tab.id, frameIds: [sender.frameId] }, files: ["meet.js"] })
    .catch((error) => console.error("[MeetCraft] couldn't start in the Meet tab:", error));
});

function reportMeetingChat() {
  const port = activeTab();
  sendToMod({ type: "meetChat", space: (port && meetingChats.get(port)) ?? null });
}

function broadcastStatus() {
  const status = { type: "status", mc: ws?.readyState === WebSocket.OPEN, streaming: wantFrames };
  for (const port of tabs.keys()) port.postMessage(status);
}

// Firefox penalises WebSocket connections that keep failing: after a run of refusals it holds back each new attempt
// for longer and longer, up to a minute, so Minecraft starting up went unnoticed for ages. A plain HTTP request doesn't
// count towards that, and the mod's socket answers one (with a 404) whenever it's running. So: probe every second,
// quietly, and only open the WebSocket once Minecraft is actually there.
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
    reportMic();
    reportMeetingChat();
    broadcastStatus();
  };
  ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    if (msg.type === "setMic") {
      lastMicFromGame = Date.now();
      // Every tab follows the game; the active one is just the one whose state gets reported back.
      for (const port of tabs.keys()) port.postMessage(msg);
    } else if (msg.type === "frames") {
      wantFrames = msg.on;
      if (typeof msg.fps === "number") fps = Math.min(30, Math.max(1, msg.fps));
      broadcastStatus();
    }
  };
  ws.onclose = () => {
    const wasOpen = everConnected;
    ws = null;
    lastMicFromGame = 0;
    wantFrames = false;
    broadcastStatus();
    everConnected = false;
    setTimeout(connect, wasOpen ? 250 : 1000);
  };
}

chrome.runtime.onConnect.addListener((port) => {
  if (port.name !== "meet") return;
  tabs.set(port, null);
  port.postMessage({ type: "status", mc: ws?.readyState === WebSocket.OPEN, streaming: wantFrames });
  port.onMessage.addListener((msg) => {
    if (msg.type === "mic") {
      tabs.set(port, msg.on);
      micPaths.set(port, msg.gate ?? null);
      if (port === activeTab()) reportMic();
    } else if (msg.type === "meetChat") {
      meetingChats.set(port, msg.space);
      if (port === activeTab()) reportMeetingChat();
    } else if (msg.type === "stage") {
      stages.set(port, msg.rect);
    }
  });
  port.onDisconnect.addListener(() => {
    tabs.delete(port);
    micPaths.delete(port);
    meetingChats.delete(port);
    stages.delete(port);
    reportMic();
  });
});

// What's flowing, for the Inspect console when the screen stays on colour bars.
let framesSent = 0;
let captureMs = 0;
let captureMaxMs = 0;
setInterval(() => {
  if (wantFrames) {
    const avg = framesSent ? Math.round(captureMs / framesSent) : 0;
    console.debug(`[MeetCraft] ${framesSent / 10} frames/s to Minecraft, capture ${avg} ms avg / ${Math.round(captureMaxMs)} ms max`);
  }
  framesSent = 0;
  captureMs = 0;
  captureMaxMs = 0;
}, 10000);

let lastCaptureError = "";

/**
 * Takes the call straight from the rendered tab, so it looks exactly like Meet: tiles with names and avatars for
 * cameras that are off, presentations, the lot. Firefox renders it for us even while the tab is in the background.
 */
async function captureStage(port) {
  const stage = stages.get(port);
  if (!stage) return null;
  const { x, y, width, height, dpr } = stage;
  const url = await browser.tabs.captureTab(port.sender.tab.id, {
    format: "jpeg",
    quality: JPEG_QUALITY,
    rect: { x, y, width, height },
    scale: Math.min(dpr, MAX_FRAME_WIDTH / width),
  });
  return (await fetch(url)).arrayBuffer();
}

// The frame clock lives here because this page is never throttled, unlike a Meet tab in the background. Each frame
// is scheduled from when the last one started, so a slow capture eats into the wait instead of piling frames up.
async function captureLoop() {
  const started = performance.now();
  await captureOnce();
  setTimeout(captureLoop, Math.max(0, 1000 / fps - (performance.now() - started)));
}

async function captureOnce() {
  const port = activeTab();
  if (!wantFrames || !port || ws?.readyState !== WebSocket.OPEN) return;
  if (captureInFlight || ws.bufferedAmount > MAX_BUFFERED_BYTES) return;
  captureInFlight = true;
  const started = performance.now();
  try {
    const frame = await captureStage(port);
    if (frame && ws?.readyState === WebSocket.OPEN) {
      ws.send(frame);
      framesSent++;
      const took = performance.now() - started;
      captureMs += took;
      captureMaxMs = Math.max(captureMaxMs, took);
    }
  } catch (error) {
    if (String(error) !== lastCaptureError) console.warn("[MeetCraft] couldn't capture the Meet tab:", error);
    lastCaptureError = String(error);
  } finally {
    captureInFlight = false;
  }
}

captureLoop();

connect();
