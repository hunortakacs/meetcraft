// The MeetCraft switch on the Meet page, and the only thing that runs on it until you turn MeetCraft on: no mic gate
// (gate.js checks the same flag), no camera change, no video to the game. Switching reloads the tab, because the gate
// can only take the mic when Meet first opens it. "On" lasts for this tab and this meeting only.

const ON_KEY = "meetcraft:on";
const meetcraftOn = (() => {
  try {
    return sessionStorage.getItem(ON_KEY) === location.pathname;
  } catch {
    return false;
  }
})();

const badge = document.createElement("div");
badge.style.cssText = [
  "position:fixed", "left:12px", "bottom:12px", "z-index:2147483647", "display:flex", "align-items:center", "gap:8px",
  "padding:4px 4px 4px 8px", "border-radius:6px", "background:rgba(0,0,0,.65)", "color:#fff",
  "font:12px/1.4 system-ui,sans-serif", "box-sizing:border-box",
].join(";");
/** Written by meet.js while MeetCraft is on. */
const badgeText = document.createElement("span");
badgeText.textContent = meetcraftOn ? "⛏ MeetCraft" : "⛏ MeetCraft is off";
const switchButton = document.createElement("button");
switchButton.textContent = meetcraftOn ? "Turn off" : "Turn on";
switchButton.title = meetcraftOn
  ? "Give the mic back to Meet and disconnect from Minecraft (reloads the page)"
  : "Let Minecraft control your Meet mic and show the call in the game (reloads the page)";
switchButton.style.cssText = [
  "font:inherit", "color:#fff", "background:rgba(255,255,255,.15)", "border:0", "border-radius:4px",
  "padding:2px 8px", "cursor:pointer",
].join(";");
switchButton.addEventListener("click", () => {
  try {
    if (meetcraftOn) {
      sessionStorage.removeItem(ON_KEY);
    } else {
      // Written as the page goes, not now: if Meet asks "leave the call?" and you stay, this page stays off.
      const path = location.pathname;
      addEventListener("pagehide", () => sessionStorage.setItem(ON_KEY, path), { once: true });
    }
  } catch {
    return;
  }
  location.reload();
});
badge.append(badgeText, switchButton);
document.documentElement.append(badge);

if (meetcraftOn) browser.runtime.sendMessage({ type: "meetcraftOn" });
