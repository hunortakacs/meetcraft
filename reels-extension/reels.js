// Runs in every Instagram tab. Finds the reel on screen and tells the background where it is (so it can be captured for
// the game) and how far through it is; does what the game asks: play or pause, scroll to the next or previous reel,
// sound on, and the next reel by itself when one finishes (auto-scroll).
// Instagram's DOM isn't an API, so this goes by what's on screen (the biggest <video> in view, the element that
// scrolls it) rather than by class names, which Instagram changes all the time.

const MIN_VIDEO = 120;
/** After the game opens the view or scrolls, keep nudging the reel on screen to play for this long. */
const PLAY_NUDGE_MS = 3000;
/** A reel counts as finished when it jumps back to the start from within this many seconds of its end (reels loop). */
const LOOP_END_S = 1.5;
/** Auto-scroll waits this long after any scroll, so one finish can't skip two reels. */
const STEP_COOLDOWN_MS = 2000;

const REELS_PAGE = /^\/reels?\//;

/** What the game wants, via the background. */
const game = { connected: false, view: false, muted: false, auto: false };
let port = null;
let lastStepAt = 0;
let playUntil = 0;
let lastActive = null;
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

/** The part of the page to capture, in page pixels, or null when there's no reel in view. */
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

// --- Scrolling: by the distance to the next reel in the same column, in whatever element scrolls the feed. Instagram
// snaps to whole reels; if the next one isn't on the page yet, a screen's height is enough for the snap to land on it.

function scrollerOf(el) {
  for (let node = el.parentElement; node; node = node.parentElement) {
    const overflow = getComputedStyle(node).overflowY;
    if ((overflow === "auto" || overflow === "scroll") && node.scrollHeight > node.clientHeight + 1) return node;
  }
  return document.scrollingElement;
}

function step(by) {
  const current = activeVideo();
  if (!current) return;
  const from = current.getBoundingClientRect();
  const column = [...document.querySelectorAll("video")]
    .filter((v) => shown(v) && Math.abs(v.getBoundingClientRect().left - from.left) < from.width / 2)
    .sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top);
  const target = column[column.indexOf(current) + by];
  const scroller = scrollerOf(current);
  const distance = target ? target.getBoundingClientRect().top - from.top : by * scroller.clientHeight;
  // Instant: a smooth scroll needs animation frames, which a background tab barely gets.
  scroller.scrollBy({ top: distance, behavior: "instant" });
  lastStepAt = performance.now();
  playUntil = performance.now() + PLAY_NUDGE_MS;
}

// --- Auto-scroll. Reels loop instead of ending, so a finish is the time jumping back to the start from near the end.
// Media events don't bubble, but they do pass the document on the way down, so one capturing listener sees them all.

const lastTimes = new WeakMap();

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
    const last = lastTimes.get(video) ?? 0;
    lastTimes.set(video, video.currentTime);
    if (video.currentTime + 1 < last && last > video.duration - LOOP_END_S) finished(video);
  },
  true,
);
document.addEventListener("ended", (event) => event.target instanceof HTMLVideoElement && finished(event.target), true);

// --- Playing in a background tab. visible.js keeps Instagram from pausing; this keeps Firefox decoding. Firefox stops
// decoding the video of a background tab after a few seconds, except for videos the page has drawn on a canvas,
// so each reel the game watches gets drawn once, onto a single pixel nobody sees.

const keepDecoding = (() => {
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = 1;
  const drawn = new WeakSet();
  return (video) => {
    if (drawn.has(video) || video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) return;
    drawn.add(video);
    try {
      canvas.getContext("2d").drawImage(video, 0, 0, 1, 1);
    } catch (error) {
      console.debug("[MeetCraft Reels] couldn't mark the reel to keep decoding:", error);
    }
  };
})();

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
  if (video !== lastActive) {
    lastActive = video;
    // A new reel (scrolled by us, by Instagram, or by hand): make sure it starts.
    playUntil = Math.max(playUntil, performance.now() + PLAY_NUDGE_MS);
  }
  if (!game.view || !video) return;
  keepDecoding(video);
  // Sound on means the reel itself unmuted too (Instagram mutes reels on its own); sound off is the tab's mute.
  if (!game.muted && video.muted && soundAllowed()) video.muted = false;
  if (video.paused && performance.now() < playUntil) play(video);
}

function report() {
  const video = activeVideo();
  port?.postMessage({
    type: "reel",
    reelsPage: REELS_PAGE.test(location.pathname),
    rect: captureRect(video),
    playing: !!video && !video.paused,
    muted: !!video?.muted,
    soundBlocked: !game.muted && !soundAllowed(),
    time: video?.currentTime ?? 0,
    duration: Number.isFinite(video?.duration) ? video.duration : 0,
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
    playUntil = performance.now() + PLAY_NUDGE_MS;
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
      onState({ connected: msg.connected, view: msg.view, muted: msg.muted, auto: msg.auto });
    } else if (msg.type === "tick") {
      apply();
      report();
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
