# MeetCraft

Google Meet, Google Chat and Instagram reels inside Minecraft (Fabric 26.3):

- **Meet view**: your call as a minimap in the corner, or enlarged to most of the screen.
- **Meet mic key**: hold **R** to talk in the call.
- **Screens**: build a wall of Screen blocks and it shows the call the way Meet lays it out.
- **Google Chat (`/gc`)**: read and write your Google Chat spaces and DMs from the Minecraft chat.
- **In-game voice**: proximity voice via the bundled [Plasmo Voice](https://modrinth.com/mod/plasmo-voice).
- **Instagram reels**: watch reels at the side of your screen, scroll them with the arrow keys or let them move on by themselves, and mute them.

## Install

Download both files from the [latest release](https://github.com/hunortakacs/meetcraft/releases/latest).

1. In your launcher (Freesm, Prism or the official one), create a **Fabric** instance for **26.3** and put `meetcraft-<version>.jar` in its `mods/` folder. It's the only mod you need.
2. In **Firefox** or a Firefox-based browser (Zen, LibreWolf…, version 128+), drag `meetcraft-<version>.xpi` onto the window and click *Add*. Chrome isn't supported.
3. Open the call in Meet and click **Turn on** in the "⛏ MeetCraft" badge at the bottom left. The page reloads with MeetCraft on, and the badge shows when Minecraft is connected. It starts off again for every new meeting.
4. For reels, also add `meetcraft-reels-<version>.xpi` the same way. It's a separate extension, so you only need it if you want Instagram in the game.

Settings: *Options → MeetCraft…*.

## Keys

| Key | What it does |
| --- | --- |
| **R** | Talk in the Meet call (push to talk, or toggle in the settings) |
| **X** | Enlarge the Meet view (press again to shrink, or set it to hold) |
| **Z** | Show or hide the small Meet view |
| Plasmo Voice's key | Talk to players near you in the game |
| **I** | Show or hide Instagram reels |
| **↓** / **↑** | Next / previous reel |
| **M** | Mute or unmute reels |
| **N** | Auto-scroll reels on or off |

Rebind them under *Controls → Multiplayer* (the Meet keys) and *Controls → Instagram Reels*.

The mic icon in the top-left is your Meet mic: green when you're live, grey and struck through when muted. While you're live in Meet, your in-game voice is muted, and it comes back as it was when you mute in Meet.

## How the Meet mic works

The R key doesn't click Meet's mic button, because that wasn't safe enough:

- **It's slow.** While you play, the Meet tab is in the background, and Firefox slows background tabs down. Meet can take a second or more to react to its button, so the call misses your first words, or keeps hearing you after you've let go.
- **It doesn't fail safe.** If the game crashed while you were talking, nothing would click mute, and you'd stay live in the call.

Instead, once you've turned MeetCraft on, Meet doesn't get your microphone directly. It gets a copy that passes through a switch inside the Meet page, which only the game can open:

- **You start muted.**
- **Live has to keep being confirmed.** The game confirms it twice a second. If that stops (the game freezes, crashes or disconnects, or the extension reloads), you're muted within about 1.5 seconds.
- **There's a backup.** If sound ever gets through while you should be muted, MeetCraft stops trusting the switch and mutes with Meet's own button instead. The top-left then says *Meet mic is muting slowly: refresh the Meet tab*.
- **Without Minecraft, Meet works as usual.** When the game disconnects, Meet's own button is muted before Meet gets the mic back.

So during the call, **Meet's own mic button stays on live**. The others don't see a muted icon; they just hear nothing until you press R. Trust the icon in the game, not Meet's button.

Your audio only goes to Meet. The game, the rest of the extension and the Minecraft server never receive it.

## What the extension does

- **Until you click Turn on, it does nothing to Meet**: your mic, camera and the call are untouched, and nothing goes to the game. Only the badge with the button is added. Turning it on reloads the page, because the switch can only be put in place when Meet opens your mic. It stays on for that tab and that meeting only.
- It only runs on `meet.google.com`. Firefox warns that it can "access your data for all websites" because Firefox requires that permission to take a picture of a tab, which the Meet view and the screens need. It never touches other sites.
- It sends pictures of the call and your mic state to the game on your own computer (`127.0.0.1`). Nothing leaves your machine, and only browser extensions can connect, so a web page can't control your mic.
- The call's cameras and presentations are drawn live, at their own frame rate, over a picture of the Meet page taken 10 times a second; names and icons on top of the videos come from that picture. If tiles or names ever look wrong, set *Options → MeetCraft… → Meet video* to *Pictures of the page*, which sends whole pictures of the page as before.
- It turns your camera off once when Meet loads, so you never join on camera by surprise. You can turn it back on.

## Screens

- Everyone gets an endless Screen block on joining (also in the Redstone creative tab).
- Any filled rectangle of Screen blocks facing the same way becomes one screen, up to 64×64.
- Each player sees their own Meet tab on it. **Ctrl+click** a tile in Meet to show only that one.
- Colour bars mean there's no feed: Meet isn't open or the extension isn't loaded.
- Frames only flow within about 190 blocks of a screen.

## Instagram reels

With the MeetCraft Reels extension installed, press **I** in the game. If no Instagram tab is open, one opens in the background on the reels feed. The reel you're on shows at the right side of the screen, with a progress bar, the sound and the keys underneath.

- **↓** goes to the next reel and **↑** back to the previous one, as if you'd scrolled in the tab.
- **N** turns on auto-scroll: when a reel finishes, the next one starts by itself. Press it again to stay on one reel, which then loops as usual.
- **M** mutes reels by muting the Instagram tab in Firefox, so it works whatever Instagram's own sound button says.
- Pressing **I** again hides the view and pauses the reel. It also pauses while the Meet view is enlarged (**X**), and when you leave the world or close the game.
- Firefox only plays sound on a page you've clicked at least once. If the game says *No sound until you click the Instagram tab once*, click anywhere in that tab, for example when Firefox opened it for you.

Showing the reels, side, size, sound and auto-scroll are also in *Options → MeetCraft…*, along with all the reels keys. Your choices for sound and auto-scroll are remembered.

Everything stays on your computer and nothing goes through the Minecraft server, so it works on any server and shows only to you. Reels don't show on Screen blocks.

### What the Reels extension does

- It only runs on `www.instagram.com`, and only does something while the game has the reels view open. Firefox warns that it can "access your data for all websites" for the same reason as the Meet extension: it needs that permission to read the video and, as a fallback, to take a picture of a tab.
- While the view is open, it tells the Instagram tab that it's visible, so Instagram doesn't pause reels while Firefox is in the background. When you close the view, Instagram sees the truth again.
- While the view is open, the Instagram page also holds a WebRTC connection to itself, because Firefox doesn't slow down background tabs that have one, and otherwise new reels stop loading after a couple of minutes. No camera or microphone is involved and nothing leaves your computer. If reels still stop loading in the background, set `dom.timeout.enable_budget_timer_throttling` to `false` in `about:config`.
- It sends the frames of the reel on screen, read straight from Instagram's video player, and how far through it is to the game on your own computer (`127.0.0.1`, port 25600). Nothing else leaves the tab. Only if Firefox won't let it read a video does it take pictures of the tab instead, which is slower and includes Instagram's buttons.
- After installing it, reload any Instagram tab you already had open.

Instagram's page isn't an API either. When reels stop showing or scrolling, start with `activeVideo()` and `step()` in [`reels-extension/reels.js`](reels-extension/reels.js).

## Google Chat: `/gc`

| Command | What it does |
| --- | --- |
| `/gc login` / `/gc logout` | Connect or disconnect your Google account |
| `/gc list` | Your spaces and DMs with unread counts; click one to open it |
| `/gc open <number\|name>` | Enter a chat: plain typing now goes there |
| `/gc dm <email>` | Enter your DM with someone |
| `/gc meet` | Enter the meeting's chat |
| `/gc leave` | Typing goes to Minecraft again |

Inside a chat, start a line with `!` to send it to Minecraft instead.

**First-time setup:** `/gc login` asks for your team's Google app client ID and secret (a Desktop-app OAuth client whose Cloud project has the **Google Chat API** and **People API** enabled), then opens the browser to log in. Everything stays on your computer in `~/.config/meetcraft/`. Nothing about Chat goes through the Minecraft server.

## Server

Run a Fabric 26.3 server with the same jar. Voice uses UDP on the Minecraft port, so open that port for UDP too.

Behind a hostname proxy (such as DiscoPanel's), give voice its own port instead: set `port = 24454` under `[host]` in `config/plasmovoice/config.toml`, publish and allow 24454/UDP (in DiscoPanel: *Overview → Additional Ports*), and restart.

## Development

```sh
JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./gradlew build   # → build/libs/meetcraft-<version>.jar
npx web-ext run --source-dir extension                    # Firefox with the unsigned extension
npx web-ext run --source-dir reels-extension              # the same for the Instagram one
```

To release an extension, bump `version` in its `manifest.json` (Mozilla won't sign the same version twice) and sign it on addons.mozilla.org as an unlisted add-on, either by uploading a zip of its folder or with `scripts/sign-extension.sh` (pass `reels-extension` for the Instagram one) and an AMO API key. Put the jar and both `.xpi` files in a GitHub release.

Meet's page isn't an API. When something breaks, start with `micButton()`, `stageRect()` and `meetingChatSpace()` in [`extension/meet.js`](extension/meet.js).

## Bundled software

The jar includes [Fabric API](https://github.com/FabricMC/fabric) (Apache-2.0) and [Plasmo Voice](https://github.com/plasmoapp/plasmo-voice) (LGPL-3.0, source at that link), unmodified.
