
<p align="center"><img src="metadata/en-US/branding/wordmark.svg" alt="FastVNC Banner" height="200"></img></a>


<p align="center"> <b>FastVNC</b> is a VNC client for Android. </p>

-------------------------------------------------------------------------------


### Features
- Material Design (with Dark theme)
- Configurable gestures
- Virtual Keys
- Built-in computer keyboard with modifiers, symbols and function keys
- Movable floating touchpad with mouse buttons and scrolling
- VNC Repeater support
- Wake-on-LAN support
- Built-in SSH tunnel (VNC over SSH)
- Picture-in-Picture mode
- View-only mode
- No-video mode
- Automatic Server Discovery (Zeroconf)
- Import/Export servers
- Clipboard Sync with server
- `vnc://` URI support
- TLS support (AnonTLS, VeNCrypt)
- Tight encoding support


FastVNC is an independently maintained derivative of [AVNC](https://github.com/gujjwal00/avnc), originally created by Gaurav Ujjwal and contributors. Original copyright and third-party license notices are retained; FastVNC is distributed under GPL-3.0-or-later. Branding, home/settings and remote-input modifications: 2026-11-02.

Release application ID: `tech.zhangbing.fvnc` (debug: `tech.zhangbing.fvnc.debug`). See [release signing and builds](docs/RELEASING.md). The independent app keeps its own data; import an AVNC JSON export to transfer saved connections. This fork has no announced app-store listing.

### Upstream screenshots (historical)

[<img src="metadata/en-US/images/phoneScreenshots/1.jpg" width="250">](metadata/en-US/images/phoneScreenshots/1.jpg)
[<img src="metadata/en-US/images/phoneScreenshots/2.jpg" width="250">](metadata/en-US/images/phoneScreenshots/2.jpg)
[<img src="metadata/en-US/images/phoneScreenshots/3.jpg" width="250">](metadata/en-US/images/phoneScreenshots/3.jpg)
[<img src="metadata/en-US/images/phoneScreenshots/4.jpg" width="250">](metadata/en-US/images/phoneScreenshots/4.jpg)
[<img src="metadata/en-US/images/phoneScreenshots/5.jpg" width="250">](metadata/en-US/images/phoneScreenshots/5.jpg)
[<img src="metadata/en-US/images/phoneScreenshots/6.jpg" width="250">](metadata/en-US/images/phoneScreenshots/6.jpg)
[<img src="metadata/en-US/images/phoneScreenshots/7.jpg" width="380">](metadata/en-US/images/phoneScreenshots/7.jpg)
[<img src="metadata/en-US/images/phoneScreenshots/8.jpg" width="380">](metadata/en-US/images/phoneScreenshots/8.jpg)

  
Development
===========

### Touch controls

Tap the desktop to click and double-tap to double-click. Swipe with one finger to
pan the local view; swipe with two fingers to scroll the remote application in
either direction. Long press until the haptic feedback, then move to drag a window,
file or text selection; lift to release. Double-tap and hold also drags. A two-finger
tap right-clicks at the first finger's original position. Pinch to zoom. During a
drag, hold near a viewport edge to reveal more of the desktop. Quick two-finger
scrolling continues briefly after release; touch again to stop, or adjust inertia under Remote system optimization in Settings. Existing saved gesture preferences are preserved;
Settings → Input → Restore recommended controls applies these defaults.

The main controls are ordered **Computer keyboard → Touchpad → Shortcuts → More**.
Opening one input page closes the previous page. Close its chevron or press Back to
return to the remote desktop; mouse gestures never automatically open the keyboard.
More contains Dock/Mission Control, displays, picture quality, clipboard/file sharing,
connection settings and Disconnect.

The computer keyboard follows a physical desktop layout: Mac Control/Option/Command
and Return, or Windows Ctrl/Win/Alt and Enter, with numbers and punctuation beside
QWERTY. Esc/F1–F12 stay above the keys. Small screens scroll the key deck horizontally;
short landscape windows also scroll it vertically to retain remote viewing space.
Use ABC, 123 and Nav for letters, extra symbols and navigation. Tap a modifier for
the next key, or hold to lock; tap again to release. Chinese text is composed by
the remote computer's input method. Settings → Input → Use Android keyboard keeps
the system keyboard available.

The Touchpad page has a large pointing surface, mouse buttons, a vertical scroll
strip and Copy/Paste/Cut/Undo down the left. Slide to move, tap/double-tap to click,
long press or double-tap and hold to drag. Two-finger tap right-clicks; two-finger
swipe scrolls in both directions with optional inertia. Hold the left button while
sliding to drag. The Shortcuts page provides common platform chords including app
switching, select all and undo/redo. Paste uses the remote clipboard; use More →
Clipboard to explicitly send Android text or enable foreground automatic sync.

More → Floating touchpad retains the movable overlay, size/opacity preferences and
saved position. Drag its header to reposition it, or collapse it with its chevron.
In landscape windows at least 800dp wide, an enabled floating touchpad docks beside
the keyboard and returns to its saved position when the keyboard closes. New
sessions start with the concise control row unless a floating preference was saved.

### Building

Tools required:

- Git 
- Android Studio
- Android SDK
- NDK (with CMake)
- For vcpkg (depending on your platform): curl, zip, unzip, tar, pkg-config, a C++ compiler

To get started, simply clone the repo and initialize submodules:

```bash
git clone https://github.com/AceInAndroid/avnc.git
cd avnc
git submodule update --init --depth 1
```

Now you can import the project in Android Studio, or build it directly from terminal.

Read [Architecture.kt](app/src/main/java/com/gaurav/avnc/Architecture.kt) (preferably in
Android Studio) to know more about the code.

> [!TIP]
> FastVNC uses [vcpkg](https://learn.microsoft.com/en-us/vcpkg/) to manage C/C++ dependencies.
> `vcpkg` downloads & builds these dependencies on first run.
> So the first time you build/configure FastVNC, it can take a lot of time.
> If you face an error, try adding `android.native.buildOutput=verbose` to `gradle.properties` in project root.


##
FastVNC inherits AVNC translations. The [upstream Weblate project](https://hosted.weblate.org/engage/avnc/) maintains AVNC translations; submit translations for FastVNC-specific text to this repository.

[<img src="https://hosted.weblate.org/widgets/avnc/-/open-graph.png" alt="Translation status" height="200" />](https://hosted.weblate.org/engage/avnc/)


## Credits

- Authors of libraries FastVNC depends on (LibVNCClient, libjpeg-turbo, LibreSSL, sshlib, leakcanary etc.)
- Contributors for reporting issues, providing fixes
- Contributors for translating AVNC, and [Weblate](https://weblate.org/) for translation hosting
- [Browserstack](https://www.browserstack.com/) for providing testing infrastructure

### Mac and Windows controls

Choose the remote system in a connection's editor or More → Controls for this computer.
Automatic detects Apple's Screen Sharing handshake; other servers use Generic until
you select their system. Settings → Remote system optimization edits separate Mac,
Windows and Generic templates. Each computer can override pointer/scroll speeds
(pointer 0.25–3×, scroll 0.25–10×), scroll direction, horizontal gain, acceleration, inertia and axis lock.
Explicitly saved earlier input settings are migrated once. Local view pan sensitivity
is separate from remote pointer/scroll speed. Physical wheels share speeds and direction, without touch inertia or axis lock.

Input settings also adjust floating touchpad size (75–150%) and opacity (30–100%). Buttons retain 48dp touch targets and the panel stays within the available screen area, including keyboard and orientation changes.

More → Sharing → Clipboard sends current Android text to the remote clipboard without pressing Paste. Enable automatic sending to sync changes while FastVNC is focused; return to FastVNC after copying in another app, then paste on the remote computer. Android restricts background clipboard access. The existing clipboard-sharing master switch still applies. Recent local text can be resent, copied, deleted or cleared; up to 20 entries stay in app-private non-backup storage. Clips marked sensitive are not saved to history.

More → Sharing → Send file uses an independent SSH/SFTP connection. On Mac, enable System Settings → General → Sharing → Remote Login and allow your account. Enter its host/account/password, or reuse a configured SSH key; accept the host fingerprint only after checking it. Pick a local document to upload into that account's `~/Downloads`. Existing files are preserved with numbered names. Progress, cancellation and retry are available; typed passwords are session-only, and the VNC transport stays unchanged. A network disconnect may prevent cleanup of a hidden temporary upload directory, but incomplete files are never published under the final name.

Mac uses Command/Option/Control labels and shortcuts, velocity-sensitive pointer acceleration by default and longer scroll inertia. Touch scrolling starts at one wheel tick per 2dp, with frame-paced delivery and a short bounded queue; physical mouse wheels keep their original baseline. Standard VNC wheels remain discrete and cannot reproduce native pixel-scroll phases or pressure/haptic feedback.
Windows uses Ctrl/Alt/Win, short inertia, Task View and window snapping shortcuts.
On either platform, swipe three fingers left/right for next/previous desktop, up for
overview and down to show the desktop. A third finger added after scrolling, pinching
or dragging only cancels that gesture. Existing explicit three-finger mappings remain
available. System shortcuts in More work without opening the keyboard; their single
key combinations can be customized to match the remote OS. External keyboard
Ctrl/Command swapping is optional and leaves the built-in keyboard unchanged.

VNC services and OS shortcuts differ: calibrate scrolling direction per computer and
verify horizontal wheel events, desktop gestures and remote input-method switching
on your actual Mac or Windows host. Keyboard visibility stays under manual control.


### Mac desktop controls

Mac sessions provide Dock and Mission Control buttons, configurable system shortcuts,
and a shared control row above the built-in keyboard. Display metadata from
ExtendedDesktopSize enables individual monitor browsing without installing a Mac
helper. If the server does not publish monitor rectangles, the Displays panel
provides manual calibration. Single-display browsing uses global remote coordinates;
All Displays preserves the server arrangement. Per-display zoom is kept during the
session and the selected monitor is saved per connection.

The Quality panel switches between Smooth (Tight/JPEG first) and HD (lossless ZRLE
first) during a live connection. Server support determines the encoding actually
used. Existing connections retain their previous encoding configuration until a
preset is selected. Real Mac shortcuts, horizontal scrolling and weak-network
performance require acceptance testing against the user's actual VNC server.
