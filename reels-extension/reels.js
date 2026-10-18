// Runs in every Instagram tab. Finds the reel on screen, sends its frames to the game (through the background) and how
// far through it is; does what the game asks: play or pause, scroll to the next or previous reel, sound on, and the
// next reel by itself when one finishes (auto-scroll).
// Instagram's DOM isn't an API, so this goes by what's on screen (the biggest <video> in view, the element that
// scrolls it) rather than by class names, which Instagram changes all the time.

const MIN_VIDEO = 120;
/** A reel counts as finished when it jumps back to the start from within this many seconds of its end (reels loop). */
const LOOP_END_S = 1.5;
/** Auto-scroll waits this long after any scroll, so one finish can't skip two reels. */
const STEP_COOLDOWN_MS = 2000;
/** Longest side of a frame; reels are tall, so this is the height. */
const MAX_FRAME_SIDE = 960;
const JPEG_QUALITY = 0.8;
/** A paused reel still gets a frame this often, so the game knows the picture is alive. */
const STILL_FRAME_MS = 1000;

const REELS_PAGE = /^\/reels?\//;

/** What the game wants, via the background. */
const game = { connected: false, view: false, height: 0, muted: false, auto: false };
let port = null;
let lastStepAt = 0;
/** Firefox refused to play a reel with sound (only known this way where it doesn't report user activation). */
let playRefused = false;

/**
 * Firefox only plays sound on a page that's been clicked at least once. Unmuting a reel before that would get it
 * paused, so until then reels play silently and the game says why.
 */
function soundAllowed() {
  return navigator.userActivation?.hasBeenActive ?? !playRefused;
}

function shown(video) {
  const r = video.getBoundingClientRect();
  return r.width >= MIN_VIDEO && r.height >= MIN_VIDEO;
}

function visibleArea(video) {
  const r = video.getBoundingClientRect();
  const w = Math.min(r.right, innerWidth) - Math.max(r.left, 0);
  const h = Math.min(r.bottom, innerHeight) - Math.max(r.top, 0);
  return w > 0 && h > 0 ? w * h : 0;
}

/** The reel on screen: the video with the most of itself in view, a playing one winning a tie. */
function activeVideo() {
  let best = null;
  let bestScore = 0;
  for (const video of document.querySelectorAll("video")) {
    if (!shown(video)) continue;
    const score = visibleArea(video) + (video.paused ? 0 : 1);
    if (score > bestScore) {
      best = video;
      bestScore = score;
    }
  }
  return best;
}

/** The part of the page to take a picture of, in page pixels (the fallback, see grabFrame), or null with no reel in view. */
function captureRect(video) {
  if (!video) return null;
  const r = video.getBoundingClientRect();
  const left = Math.max(0, r.left);
  const top = Math.max(0, r.top);
  const right = Math.min(innerWidth, r.right);
  const bottom = Math.min(innerHeight, r.bottom);
  if (right - left < MIN_VIDEO || bottom - top < MIN_VIDEO) return null;
  return {
    x: Math.round(left + scrollX),
    y: Math.round(top + scrollY),
    width: Math.round(right - left),
    height: Math.round(bottom - top),
    dpr: devicePixelRatio,
  };
}

/**
 * Only the reel on screen plays. Instagram pauses a reel when it notices it scrolling away, but in a background tab it
 * hardly gets to look, so the old reel would keep playing (and talking) under the new one.
 */
function pauseAllBut(video) {
  for (const other of document.querySelectorAll("video")) if (other !== video && !other.paused) other.pause();
}

// --- Scrolling: by the distance to the next reel in the same column, in whatever element scrolls the feed. Instagram
// snaps to whole reels; if the next one isn't on the page yet, a screen's height is enough for the snap to land on it.

function scrollerOf(el) {
  for (let node = el.parentElement; node; node = node.parentElement) {
    const overflow = getComputedStyle(node).overflowY;
    if ((overflow === "auto" || overflow === "scroll") && node.scrollHeight > node.clientHeight + 1) return node;
  }
  return document.scrollingElement;
}

/**
 * Where the last scroll happened and how far one reel was. A reel only gets its <video> once Instagram has made it
 * the current one, which in a background tab can take a while; until then there's no video to measure from, so the
 * next press goes one more reel the same way.
 */
let lastScroll = null;

function step(by) {
  const current = activeVideo();
  let scroller;
  let distance;
  if (current) {
    const from = current.getBoundingClientRect();
    const column = [...document.querySelectorAll("video")]
      .filter((v) => shown(v) && Math.abs(v.getBoundingClientRect().left - from.left) < from.width / 2)
      .sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top);
    const target = column[column.indexOf(current) + by];
    scroller = scrollerOf(current);
    distance = target ? target.getBoundingClientRect().top - from.top : by * scroller.clientHeight;
    lastScroll = { scroller, reel: Math.abs(distance) };
  } else if (lastScroll?.scroller.isConnected) {
    scroller = lastScroll.scroller;
    distance = by * lastScroll.reel;
  } else {
    console.debug("[MeetCraft Reels] nothing to scroll: no reel on screen");
    return;
  }
  // Instant: a smooth scroll needs animation frames, which a background tab barely gets.
  scroller.scrollBy({ top: distance, behavior: "instant" });
  console.debug(`[MeetCraft Reels] scrolled ${by > 0 ? "down" : "up"} ${Math.round(distance)} px${current ? "" : " (no video on screen; same as last time)"}`);
  pauseAllBut(activeVideo());
  lastStepAt = performance.now();
}

// --- Auto-scroll. Reels loop instead of ending, so a finish is the time jumping back to the start from near the end.
// Media events don't bubble, but they do pass the document on the way down, so one capturing listener sees them all.

/** Where each video was at its last timeupdate, and when; Instagram reuses video elements for other reels. */
const lastTimes = new WeakMap();
/** Only a jump between two timeupdates this close together is a loop; anything older is a pause, a reuse, a seek. */
const LOOP_GAP_MS = 1000;

function finished(video) {
  if (!game.view || !game.auto || video !== activeVideo()) return;
  if (performance.now() - lastStepAt < STEP_COOLDOWN_MS) return;
  console.debug("[MeetCraft Reels] reel finished; auto-scrolling to the next one");
  step(1);
}

document.addEventListener(
  "timeupdate",
  (event) => {
    const video = event.target;
    if (!(video instanceof HTMLVideoElement)) return;
    const last = lastTimes.get(video);
    const now = performance.now();
    lastTimes.set(video, { time: video.currentTime, at: now });
    if (!last || now - last.at > LOOP_GAP_MS) return;
    if (video.currentTime + 1 < last.time && last.time > video.duration - LOOP_END_S) finished(video);
  },
  true,
);
// A new reel in the same element: whatever came before isn't this reel's progress.
for (const type of ["emptied", "loadstart"]) {
  document.addEventListener(type, (event) => lastTimes.delete(event.target), true);
}
// Looping is a seek back to the start, so a seek from near the end to the start is a finish too, caught the moment it
// happens. Any other seek (by hand) just starts the count afresh.
document.addEventListener(
  "seeking",
  (event) => {
    const video = event.target;
    if (!(video instanceof HTMLVideoElement)) return;
    const last = lastTimes.get(video);
    lastTimes.delete(video);
    if (last && performance.now() - last.at <= LOOP_GAP_MS && video.currentTime < 1 && last.time > video.duration - LOOP_END_S) {
      finished(video);
    }
  },
  true,
);
document.addEventListener("ended", (event) => event.target instanceof HTMLVideoElement && finished(event.target), true);

// --- Frames, straight from the video: every picture the reel has, without Instagram's buttons and captions on top,
// and not limited by how fast Firefox can take pictures of the tab. The background asks about 60 times a second, twice
// a reel's usual rate. Each ask first draws the video onto a few pixels to see whether the picture changed (the
// video's clock doesn't tick once per frame), and only a new picture is drawn in full and encoded, at the size the
// game draws it. A paused reel sends one a second.
// Drawing the video also keeps it going in a background tab: Firefox stops decoding background videos after a few
// seconds, except for ones the page draws on a canvas.

const frameCanvas = document.createElement("canvas");
/** The few pixels that tell one picture from the next. */
const PRINT_SIZE = 16;
const printCanvas = Object.assign(document.createElement("canvas"), { width: PRINT_SIZE, height: PRINT_SIZE });
const printContext = printCanvas.getContext("2d", { willReadFrequently: true });
let encoding = false;
let lastFrame = { video: null, print: null, at: 0 };

function fingerprint(video) {
  printContext.drawImage(video, 0, 0, PRINT_SIZE, PRINT_SIZE);
  return printContext.getImageData(0, 0, PRINT_SIZE, PRINT_SIZE).data;
}

function samePicture(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

/** The reel's own frame rate, from how many frames the player has gone through per second of the reel. */
const rate = { video: null, frames: 0, time: 0, fps: 0 };

function reelFps(video) {
  const frames = video?.getVideoPlaybackQuality?.().totalVideoFrames;
  if (!video || frames === undefined) return 0;
  // A new reel, or this one looping back to the start: count afresh.
  if (video !== rate.video || video.currentTime < rate.time) {
    if (video !== rate.video) rate.fps = 0;
    Object.assign(rate, { video, frames, time: video.currentTime });
  } else if (video.currentTime - rate.time >= 2) {
    rate.fps = (frames - rate.frames) / (video.currentTime - rate.time);
    Object.assign(rate, { frames, time: video.currentTime });
  }
  return Math.round(rate.fps);
}
/**
 * Firefox wouldn't let us read the video's pixels (it came from somewhere the page can't read either). Then the
 * background takes pictures of the tab instead, the slower way, for as long as this page lives.
 */
let canvasBlocked = false;

async function grabFrame() {
  const video = activeVideo();
  if (!game.view || canvasBlocked || encoding || !video || video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA || !video.videoWidth) return;
  const started = performance.now();
  encoding = true;
  try {
    const print = fingerprint(video);
    const changed = video !== lastFrame.video || !samePicture(print, lastFrame.print);
    if (!changed && started - lastFrame.at < STILL_FRAME_MS) return;
    lastFrame = { video, print, at: started };
    // No bigger than the game draws it (its height in screen pixels), nor than the video itself.
    const fit = game.height > 0 ? game.height / video.videoHeight : 1;
    const scale = Math.min(1, fit, MAX_FRAME_SIDE / Math.max(video.videoWidth, video.videoHeight));
    const width = Math.round(video.videoWidth * scale);
    const height = Math.round(video.videoHeight * scale);
    if (frameCanvas.width !== width || frameCanvas.height !== height) {
      frameCanvas.width = width;
      frameCanvas.height = height;
    }
    frameCanvas.getContext("2d").drawImage(video, 0, 0, width, height);
    const blob = await new Promise((resolve) => frameCanvas.toBlob(resolve, "image/jpeg", JPEG_QUALITY));
    if (blob && port) port.postMessage({ type: "frame", jpeg: await blob.arrayBuffer(), encodeMs: performance.now() - started });
  } catch (error) {
    if (error.name !== "SecurityError") throw error;
    canvasBlocked = true;
    console.warn("[MeetCraft Reels] can't read the reel's pixels; falling back to pictures of the tab");
    report();
  } finally {
    encoding = false;
  }
}

function setPageVisible(on) {
  document.dispatchEvent(new CustomEvent("meetcraft-reels:visible", { detail: on }));
}

function play(video) {
  video.play().catch((error) => {
    if (error.name !== "NotAllowedError" || video.muted) return;
    // Not allowed with sound: play silently rather than not at all.
    playRefused = true;
    video.muted = true;
    video.play().catch(() => {});
  });
}

/** What the game wants, applied to the reel on screen; runs on every tick from the background. */
function apply() {
  const video = activeVideo();
  if (!game.view || !video) return;
  pauseAllBut(video);
  // Sound on means the reel itself unmuted too (Instagram mutes reels on its own); sound off is the tab's mute.
  if (!game.muted && video.muted && soundAllowed()) video.muted = false;
  // While the game is watching, the reel on screen plays, even when its video only turned up seconds after the scroll
  // (hiding the reels is how you pause).
  if (video.paused) play(video);
}

function report() {
  const video = activeVideo();
  port?.postMessage({
    type: "reel",
    reelsPage: REELS_PAGE.test(location.pathname),
    video: !!video,
    canvasBlocked,
    rect: canvasBlocked ? captureRect(video) : null,
    playing: !!video && !video.paused,
    muted: !!video?.muted,
    soundBlocked: !game.muted && !soundAllowed(),
    time: video?.currentTime ?? 0,
    duration: Number.isFinite(video?.duration) ? video.duration : 0,
    fps: reelFps(video),
  });
}

function onState(next) {
  const wasViewing = game.view;
  Object.assign(game, next);
  if (game.view === wasViewing) return;
  setPageVisible(game.view);
  const video = activeVideo();
  if (game.view) {
    console.debug("[MeetCraft Reels] the game is watching");
    if (video) play(video);
  } else {
    // View closed or the game went away: stop, so nothing plays unheard (or heard and unseen).
    console.debug("[MeetCraft Reels] the game stopped watching; pausing");
    for (const v of document.querySelectorAll("video")) if (!v.paused) v.pause();
  }
}

// --- Background connection. The port drops when the extension updates or reloads, so reconnect. ---

function connect() {
  try {
    port = browser.runtime.connect({ name: "reels" });
  } catch {
    return; // Extension was reloaded; this copy of the script is orphaned.
  }
  port.onMessage.addListener((msg) => {
    if (msg.type === "state") {
      onState({ connected: msg.connected, view: msg.view, height: msg.height, muted: msg.muted, auto: msg.auto });
    } else if (msg.type === "tick") {
      apply();
      report();
    } else if (msg.type === "frame") {
      grabFrame();
    } else if (msg.type === "step") {
      step(msg.by);
      report();
    }
  });
  port.onDisconnect.addListener(() => {
    port = null;
    onState({ connected: false, view: false });
    setTimeout(connect, 1000);
  });
  report();
}

connect();
