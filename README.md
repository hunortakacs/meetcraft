# MeetCraft

Google Meet and Google Chat inside Minecraft (Fabric 26.3):

- **Meet view**: your call as a minimap in the corner, or enlarged to most of the screen.
- **Meet mic key**: hold **R** to talk in the call.
- **Screens**: build a wall of Screen blocks and it shows the call the way Meet lays it out.
- **Google Chat (`/gc`)**: read and write your Google Chat spaces and DMs from the Minecraft chat.
- **In-game voice**: proximity voice via the bundled [Plasmo Voice](https://modrinth.com/mod/plasmo-voice).

## Install

Download both files from the [latest release](https://github.com/hunortakacs/meetcraft/releases/latest).

1. In your launcher (Freesm, Prism or the official one), create a **Fabric** instance for **26.3** and put `meetcraft-<version>.jar` in its `mods/` folder. It's the only mod you need.
2. In **Firefox** or a Firefox-based browser (Zen, LibreWolf…, version 128+), drag `meetcraft-<version>.xpi` onto the window and click *Add*. Chrome isn't supported.
3. Open the call in Meet and click **Turn on** in the "⛏ MeetCraft" badge at the bottom left. The page reloads with MeetCraft on, and the badge shows when Minecraft is connected. It starts off again for every new meeting.

Settings: *Options → MeetCraft…*.

## Keys

| Key | What it does |
| --- | --- |
| **R** | Talk in the Meet call (push to talk, or toggle in the settings) |
| **X** | Enlarge the Meet view (press again to shrink, or set it to hold) |
| **Z** | Show or hide the small Meet view |
| Plasmo Voice's key | Talk to players near you in the game |

Rebind them under *Controls → Multiplayer*.

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
- It turns your camera off once when Meet loads, so you never join on camera by surprise. You can turn it back on.

## Screens

- Everyone gets an endless Screen block on joining (also in the Redstone creative tab).
- Any filled rectangle of Screen blocks facing the same way becomes one screen, up to 64×64.
- Each player sees their own Meet tab on it. **Ctrl+click** a tile in Meet to show only that one.
- Colour bars mean there's no feed: Meet isn't open or the extension isn't loaded.
- Frames only flow within about 190 blocks of a screen.

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
```

To release the extension, bump `version` in `extension/manifest.json` (Mozilla won't sign the same version twice) and sign it on addons.mozilla.org as an unlisted add-on, either by uploading a zip of `extension/` or with `scripts/sign-extension.sh` and an AMO API key. Put the jar and the `.xpi` in a GitHub release.

Meet's page isn't an API. When something breaks, start with `micButton()`, `stageRect()` and `meetingChatSpace()` in [`extension/meet.js`](extension/meet.js).

## Bundled software

The jar includes [Fabric API](https://github.com/FabricMC/fabric) (Apache-2.0) and [Plasmo Voice](https://github.com/plasmoapp/plasmo-voice) (LGPL-3.0, source at that link), unmodified.
