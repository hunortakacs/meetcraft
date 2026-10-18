// Keeps Instagram playing while its tab is in the background, but only while the game is watching. Runs in Instagram's
// own page (the MAIN world) before Instagram's scripts, so it can answer for the page's visibility.
//
// Instagram pauses the reel the moment its tab stops being the visible one, which is always the case while you're in
// Minecraft. So while reels.js says the game is watching ("meetcraft-reels:visible" with detail true), the page is told
// it's visible and doesn't hear about losing focus. The rest of the time Instagram sees the truth.
//
// Firefox also slows a background tab's timers to a crawl once the page has used up a small budget of time, unless
// it's playing sound or has a WebRTC connection open. Instagram runs through that budget in a couple of minutes, after
// which the next reels stop loading. So while the game is watching, the page also holds a WebRTC connection to itself:
// no camera, no microphone, nothing leaves the computer.

(() => {
  // Only meaningful in the page's own world; as an ordinary content script it would only patch our private view.
  if (typeof browser !== "undefined") return;

  const proto = Document.prototype;
  const hidden = Object.getOwnPropertyDescriptor(proto, "hidden");
  const visibilityState = Object.getOwnPropertyDescriptor(proto, "visibilityState");
  const hasFocus = proto.hasFocus;
  const PeerConnection = RTCPeerConnection;
  let pretend = false;
  /** Our own visibilitychange, telling Instagram to look again; that one has to get through. */
  let announcing = false;

  Object.defineProperty(proto, "hidden", {
    configurable: true,
    enumerable: true,
    get() {
      return pretend ? false : hidden.get.call(this);
    },
  });
  Object.defineProperty(proto, "visibilityState", {
    configurable: true,
    enumerable: true,
    get() {
      return pretend ? "visible" : visibilityState.get.call(this);
    },
  });
  proto.hasFocus = function () {
    return pretend || hasFocus.call(this);
  };

  // Registered before any of Instagram's listeners, on the way down, so these can stop the events reaching them.
  addEventListener(
    "visibilitychange",
    (event) => {
      if (pretend && !announcing) event.stopImmediatePropagation();
    },
    true,
  );
  addEventListener(
    "blur",
    (event) => {
      if (pretend && event.target === window) event.stopImmediatePropagation();
    },
    true,
  );

  /** The two ends of the connection to itself while it's held, else null. */
  let awake = null;

  async function stayAwake(on) {
    if (!on) {
      awake?.forEach((pc) => pc.close());
      awake = null;
      return;
    }
    if (awake) return;
    const a = new PeerConnection();
    const b = new PeerConnection();
    const ends = (awake = [a, b]);
    a.onicecandidate = (e) => e.candidate && b.addIceCandidate(e.candidate).catch(() => {});
    b.onicecandidate = (e) => e.candidate && a.addIceCandidate(e.candidate).catch(() => {});
    a.createDataChannel("meetcraft-reels");
    try {
      await a.setLocalDescription(await a.createOffer());
      await b.setRemoteDescription(a.localDescription);
      await b.setLocalDescription(await b.createAnswer());
      await a.setRemoteDescription(b.localDescription);
    } catch (error) {
      console.debug("[MeetCraft Reels] couldn't keep the tab awake:", error);
    }
    // Let go meanwhile: close what was being set up.
    if (awake !== ends) ends.forEach((pc) => pc.close());
  }

  document.addEventListener("meetcraft-reels:visible", (event) => {
    const next = event.detail === true;
    if (next === pretend) return;
    pretend = next;
    stayAwake(next);
    // If the tab really is in the background, the answer just changed: let Instagram know, as if it had come to the
    // front (or gone back).
    if (hidden.get.call(document)) {
      announcing = true;
      try {
        document.dispatchEvent(new Event("visibilitychange", { bubbles: true }));
      } finally {
        announcing = false;
      }
    }
  });
})();
