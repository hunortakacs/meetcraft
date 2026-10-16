// The hard mic gate. Runs in Meet's own page (the MAIN world) before Meet's scripts.
//
// Meet never gets the real microphone. When it asks for one, the gate keeps the real track to itself, sends it over a
// private loopback connection inside the page (two RTCPeerConnections talking to each other), and hands Meet the
// receiving end. Every way Meet can use the mic (sending it, copying it, analysing it for the "speaking" ring, any
// connection made by any code path) starts from that received track, so switching off the real track, which only the
// gate holds, leaves nothing to leak and no back door to the microphone. Meet's own mute still works on its track.
//
// No Web Audio on purpose: an AudioContext needs autoplay permission to run (Zen blocks it by default) and opens an
// output stream of its own, which is one more thing for a Bluetooth headset in call mode to choke on.
//
// Only when you've turned MeetCraft on for the meeting (toggle.js); otherwise it does nothing at all.
//
// Fails closed: it starts muted, and "live" only lasts while meet.js keeps renewing it (the game resends every 500 ms),
// so a crashed game, a dead extension or a missed message all end in silence. "off" opens it for normal Meet use.
//
// meet.js talks to it with "meetcraft:gate" events (detail "live" | "muted" | "off"), which run synchronously, and
// reads back on <html>:
//   data-meetcraft-gate           the mode
//   data-meetcraft-gate-tracks    microphones held
//   data-meetcraft-gate-live      how many are actually passing sound out right now
//   data-meetcraft-gate-meet-live how many Meet itself has switched on (for a safe hand-back to Meet)
//   data-meetcraft-gate-engine    the loopback: "running", "starting", "stalled" (it failed: silence) or "none"

(() => {
  // Only meaningful in the page's own world; as an ordinary content script it would only patch our private view.
  if (typeof browser !== "undefined" || document.documentElement?.hasAttribute("data-meetcraft-gate")) return;
  // Off unless you turned MeetCraft on for this meeting in this tab (toggle.js). While off, Meet gets the real mic.
  try {
    if (sessionStorage.getItem("meetcraft:on") !== location.pathname) return;
  } catch {
    return;
  }

  const LIVE_LEASE_MS = 1500;
  // Our own handles on the browser's originals, taken before any page script runs.
  const PeerConnection = RTCPeerConnection;
  const trackStop = MediaStreamTrack.prototype.stop;
  const enabled = Object.getOwnPropertyDescriptor(MediaStreamTrack.prototype, "enabled");
  const isEnabled = (track) => enabled.get.call(track);
  const setEnabled = (track, on) => enabled.set.call(track, on);

  /** One per microphone Meet opened: the real track (only we hold it), the loopback, and the track Meet got. */
  const mics = new Set();
  let mode = "muted";
  let liveUntil = 0;

  function open() {
    if (mode === "live" && Date.now() > liveUntil) mode = "muted";
    return mode !== "muted";
  }

  function apply() {
    const on = open();
    for (const mic of mics) if (isEnabled(mic.real) !== on) setEnabled(mic.real, on);
    report();
  }

  function loopState(mic) {
    const ice = mic.a.iceConnectionState;
    if (ice === "connected" || ice === "completed") return "running";
    if (ice === "failed" || ice === "disconnected" || ice === "closed") return "stalled";
    return "starting";
  }

  function report() {
    const root = document.documentElement;
    if (!root) return;
    for (const mic of mics) if (mic.real.readyState === "ended" && mic.given.readyState === "ended") mics.delete(mic);
    let live = 0;
    let meetLive = 0;
    const states = new Set();
    for (const mic of mics) {
      const state = loopState(mic);
      states.add(state);
      const meetOn = mic.given.readyState === "live" && isEnabled(mic.given);
      if (meetOn) meetLive++;
      if (meetOn && isEnabled(mic.real) && state === "running") live++;
    }
    const engine = mics.size === 0 ? "none" : ["stalled", "starting", "running"].find((s) => states.has(s));
    const values = [
      ["data-meetcraft-gate", mode],
      ["data-meetcraft-gate-tracks", String(mics.size)],
      ["data-meetcraft-gate-live", String(live)],
      ["data-meetcraft-gate-meet-live", String(meetLive)],
      ["data-meetcraft-gate-engine", engine],
    ];
    // Only on change: meet.js watches these, and a rewrite of the same value still counts as a mutation.
    for (const [name, value] of values) if (root.getAttribute(name) !== value) root.setAttribute(name, value);
  }

  /** Voice quality for the hop, since Meet encodes it once more: high bitrate, no silence suppression. */
  function hiFi(sdp) {
    const pt = /a=rtpmap:(\d+) opus\/48000/i.exec(sdp)?.[1];
    if (!pt) return sdp;
    const params = "maxaveragebitrate=128000;usedtx=0;useinbandfec=1;stereo=0";
    const fmtp = new RegExp(`a=fmtp:${pt} ([^\\r\\n]*)`);
    return fmtp.test(sdp) ? sdp.replace(fmtp, `a=fmtp:${pt} $1;${params}`) : sdp.replace(new RegExp(`(a=rtpmap:${pt} [^\\r\\n]*)`), `$1\r\na=fmtp:${pt} ${params}`);
  }

  /** Swaps one real microphone track for the far end of a private loopback that looks and behaves like it to Meet. */
  async function gate(real) {
    setEnabled(real, open());
    const a = new PeerConnection();
    const b = new PeerConnection();
    a.onicecandidate = (e) => e.candidate && b.addIceCandidate(e.candidate).catch(() => {});
    b.onicecandidate = (e) => e.candidate && a.addIceCandidate(e.candidate).catch(() => {});
    a.addEventListener("iceconnectionstatechange", report);
    const received = new Promise((resolve) => (b.ontrack = (e) => resolve(e.track)));
    a.addTrack(real);
    await a.setLocalDescription(await a.createOffer());
    await b.setRemoteDescription(a.localDescription);
    const answer = await b.createAnswer();
    await b.setLocalDescription({ type: "answer", sdp: hiFi(answer.sdp) });
    await a.setRemoteDescription(b.localDescription);
    const given = await received;
    const mic = { real, given, a, b };
    mics.add(mic);

    // Meet reads the track for device names and settings, and stops it when done: those go to the real one.
    const close = () => {
      trackStop.call(real);
      trackStop.call(given);
      a.close();
      b.close();
      report();
    };
    const forward = {
      label: { get: () => real.label },
      muted: { get: () => real.muted },
      getSettings: { value: () => real.getSettings() },
      getCapabilities: { value: () => real.getCapabilities?.() ?? {} },
      getConstraints: { value: () => real.getConstraints() },
      applyConstraints: { value: (constraints) => real.applyConstraints(constraints) },
      stop: { value: close },
    };
    for (const [name, descriptor] of Object.entries(forward)) Object.defineProperty(given, name, { configurable: true, ...descriptor });
    // A received track goes "muted" whenever packets pause; that's ours, not the microphone's, so Meet only sees the
    // real track's mute and unmute. These run first, so they can stop the received track's own events reaching Meet.
    for (const type of ["mute", "unmute"]) {
      given.addEventListener(type, (event) => event.isTrusted && event.stopImmediatePropagation());
      real.addEventListener(type, () => given.dispatchEvent(new Event(type)));
    }
    // A microphone that goes away (unplugged, revoked) has to end Meet's track too, or Meet waits for it forever.
    real.addEventListener("ended", () => {
      close();
      given.dispatchEvent(new Event("ended"));
    });

    apply();
    return given;
  }

  const getUserMedia = MediaDevices.prototype.getUserMedia;
  MediaDevices.prototype.getUserMedia = async function (...args) {
    const stream = await getUserMedia.apply(this, args);
    const audio = stream.getAudioTracks();
    if (audio.length === 0) return stream;
    return new MediaStream([...stream.getVideoTracks(), ...(await Promise.all(audio.map(gate)))]);
  };

  document.addEventListener("meetcraft:gate", (event) => {
    const next = event.detail;
    if (next !== "live" && next !== "muted" && next !== "off") return;
    mode = next;
    if (next === "live") liveUntil = Date.now() + LIVE_LEASE_MS;
    apply();
  });
  document.addEventListener("meetcraft:gate-check", apply);

  // The lease also runs out by itself. Timers in a hidden tab are slow, but meet.js checks 10 times a second too.
  setInterval(apply, 250);
  if (document.documentElement) report();
  else document.addEventListener("readystatechange", report, { once: true });
})();
