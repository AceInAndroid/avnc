# Remote input controls

## Gesture mouse revision
- Recommended defaults: long press starts a left-button drag at the original touch point;
  two-finger tap right-clicks at the first finger's original point. Double-tap-and-hold drag
  remains available. Saved gesture mappings retain their behavior. Use a single `left-drag`
  long-press action so this does not depend on a pair of preference values.
- Long press uses system timing/slop, vibrates once, and shows a transient drag indicator.
  Drag owns the gesture until release/cancel; no trailing click, fling or new multi-finger action.
- Direct dragging within 32 dp of a visible edge pans the viewport at up to 480 dp/s, including
  corners. Recompute the held remote position each frame even with a stationary finger; clamp
  at desktop bounds. Relative pointing only pans enough to keep the cursor inside a 32 dp margin.
- Two-finger remote scrolling uses stable-pointer centroid deltas in dp, existing sensitivity
  and inversion. Optional inertia defaults on: system fling threshold and platform-specific decay.
  One wheel tick per 2dp, preserving the saved sensitivity multiplier. Hardware wheels share
  resolved gains/directions, without the touch baseline, inertia or axis lock. Emit at most
  480 wheel ticks/s and 16 per frame; retain at most 48 queued ticks with a 100ms release tail.
  Pinches, canceled gestures and the side scroll strip never start inertia. Stop inertia on any
  new input or layout/lifecycle change. Freeze the remote scroll anchor for the entire session.
- Floating surface supports relative move, tap/double tap, long-press/double-tap-hold drag and
  two-finger right-click/scroll. While a physical panel button is held the surface only moves
  the cursor. Panel header motion never sends remote input.
- Keyboard visibility is user-controlled: toolbar opens it, close/back hides it; no inferred
  remote focus or touchpad expansion may open/hide it. If space is insufficient, retain the
  compact touchpad and explain that the user can close the keyboard manually.
- Cancel animations and release held input at the last valid remote coordinate on multitouch
  during drag, focus loss, view-only/PiP, disconnect, rotation and available-area changes.
  Consume the rest of a canceled touch stream; never restore held input on reconnect.

## Interaction contract
- Default touchscreen mode: tap clicks at the touched location, double tap double-clicks,
  one-finger swipe pans the local viewport, two-finger swipe scrolls the remote application.
- Two-finger pinch zooms locally. Swipe/pinch classification waits for touch slop and is
  latched until either finger lifts. Remaining fingers cannot start a new gesture or click.
- Explicitly saved gesture preferences remain valid. Input settings offer recommended defaults.
- All new input travels through InputHandler, KeyHandler/Dispatcher, and Messenger. Respect
  view-only mode and connection state. Cancel pending repeats and release held input before
  focus loss, hiding controls, backgrounding, PiP, or session teardown.

## Computer keyboard
- App-owned bottom dock; default keyboard actions open it without Android IME. A preference
  retains the system IME option. Hide the legacy auxiliary keys while this dock is open.
- Common page: staggered QWERTY, punctuation, wide space/Enter, Backspace and Shift.
  Ctrl/Alt/Win/Tab/Escape are grouped above it; 123 contains digits and two symbol sets.
  F1–F12 are always available above modifiers. Navigation page: arrows, Home/End, Page Up/Down, Insert/Delete.
- Tap Shift/Ctrl/Alt/Win for a one-shot modifier; long press locks it; tap again releases it.
  Clearly label locked state. Caps Lock is independent. Backspace/arrows repeat while held.
- Input uses existing virtual key mapping. Chinese composition belongs to the remote IME.
- Keyboard occupies layout space rather than overlaying the framebuffer. Back closes an open
  control menu first, then hides the keyboard.
  Restore visibility across configuration changes, never restore held keys.

## Floating touchpad
- Initially hidden on new installs; preserve explicit saved visibility. An expanded panel starts
  at bottom right. Header repositions it locally; collapse returns to the capsule entry.
- Touch surface: relative cursor motion at 3 remote pixels per dp (including dragging), keeping
  fractional coordinates for small adjustments; tap left-click, two-finger horizontal/vertical scroll.
  Side strip: vertical scroll. Bottom: left/right buttons; hold left while moving to drag.
- Panel consumes all its touches, without leaking events through to the desktop. Dragging its
  handle never moves the remote cursor. Persist normalized position, visibility and collapse.
  Clamp into visible bounds after rotation, insets and keyboard changes; no overlay permission.
- Independent pointer commands must not change the main screen's gesture style.

## Visual and accessibility contract
- Use viewer-local light/dark glass colors, blue accents, rounded surfaces and native buttons.
  Match dark/light themes, use readable legends, pressed/selected/locked states and descriptions.
- Compact keyboard rows adapt to screen width/height; function keys scroll horizontally on narrow layouts rather than
  shrinking their touch targets. Floating mouse controls have 48 dp touch targets.
- English and simplified Chinese strings accompany new controls and preferences.

## Acceptance
- Verify actual RFB key symbols, pointer coordinates and button masks with the test server.
- Test gesture arbitration/cancellation, one-shot/locked modifiers, repeats, touchpad dragging,
  repositioning, no click-through, view-only mode, lifecycle resets and rotation/PiP.
- Build a debug APK, run targeted instrumentation and lint, inspect portrait/landscape UI.
  Real-server horizontal scrolling and Chinese remote IME remain environment-dependent checks.

## iOS 26 control UI revision
- Viewer-only light glass palette: warm white/charcoal, readable translucent surfaces, subtle
  borders/shadows, blue selection. No framebuffer capture, real-time blur or rendering changes.
- Bottom capsule: Keyboard, Touchpad, More and Collapse; collapsed state has one 48dp opener.
  Keyboard header owns these actions while typing. No timed auto-hide or inferred keyboard focus.
- More overlays the whole viewer (including the keyboard) with labeled Picture, Interaction
  and Help groups, so landscape keyboards do not squeeze the menu. Outside touches dismiss without
  remote input. Back closes the menu before the keyboard. Retain optional edge-swipe opening.
- New installs show only the capsule (no legacy auxiliary bar or expanded touchpad); preserve
  explicitly saved auxiliary/touchpad/gesture preferences.
  Collapsed pad uses the capsule entry. Avoid overlapping capsule and floating panel.
- Staggered mobile QWERTY: no permanent number row, large space/return, Shift/backspace on third
  row. Separate Ctrl/Alt/Win/Tab/Esc shortcuts. Numeric/symbol and Navigation pages preserve computer keys.
  One-shot modifiers have blue selection; locked modifiers add a lock mark. Caps is independent.
- Keyboard reserves space. In landscape at >=800dp available width, an enabled expanded touchpad
  docks to its right at 220dp; reuse the same view and restore normalized floating position afterward.
  Insufficient space temporarily compacts the pad without changing its saved expansion preference.
- 24dp panels, pill capsules, 8dp keys, 48dp action targets, short system-aware transitions, en/zh
  labels, TalkBack state and light/dark support. Keep all existing input and cancellation contracts.
- Verify RFB events, control no-click-through, layout switches, manual keyboard, preferences,
  rotation/read-only/PiP/disconnect, plus portrait/landscape visual evidence before delivery.

## Mac and Windows remote input templates
- A connection selects Automatic, Mac, Windows or Generic. Automatic uses Mac only when
  the server advertises Apple's RFB 3.889 handshake; unidentified servers stay Generic.
  Offer an explicit chooser in live controls; never infer Windows from a desktop name.
- App settings edit separate platform templates. Saved connections inherit them with optional
  per-field/per-shortcut overrides; unsaved connections keep changes for the session only.
  Null overrides inherit; horizontal speed follows vertical unless explicitly customized.
  Preserve explicitly saved legacy sensitivity/inversion/inertia/three-finger mappings once.
- Relative pointing starts at 3 framebuffer pixels/dp; touch scrolling uses one wheel tick per 2dp.
  Pointer speed spans 0.25–3× and vertical/horizontal scroll speeds span 0.25–10×. Apply gains once.
  Acceleration defaults on for Mac and off elsewhere, and is disabled while a mouse button is held.
- Mac defaults use standard inertia (240ms decay, 1000ms maximum); Windows short inertia
  (100ms decay, 400ms maximum). Direction calibration remains per computer because VNC
  servers may reverse the wheel. Axis lock commits after system slop: 2:1 dominance locks
  an axis, otherwise diagonal scrolling; keep that decision until the gesture ends.
- Mac/Windows default three-finger swipes perform system actions: left/right next/previous
  desktop; up overview; down show desktop. Require max(32dp, 2×touch slop), 1.5:1 dominance,
  and one action per stream. A third finger during scrolling, pinching or dragging only
  cancels. Existing explicit three-finger mappings remain available; Generic pans locally.
- Semantic shortcuts use Command on Mac and Ctrl/Win/Alt on Windows as appropriate.
  Mac keyboard labels become Control/Option/Command; retain existing native Apple Option
  conversion without a second translation. Provide editable single chords and text-navigation
  keys, plus a System shortcuts page in More. External Ctrl/Command swapping is optional
  and never rewrites virtual keys. No automatic keyboard opening or closing.
- Settings dialogs share the viewer blue accent, 24dp panel corners and readable Material
  controls. More menu rows keep at least 48dp touch targets and grow for wrapped labels.
- Live configuration changes and all lifecycle cancellations release held keys/buttons and
  stop scroll/drag/repeat work before applying settings; reconnect never restores held input.
- Validate migration 7→8 and legacy JSON, actual RFB events, modifiers, read-only mode,
  pointer gains, scroll axis/direction, three-finger ownership and keyboard labels. Inspect
  settings and keyboard in portrait/landscape. Native feel, horizontal wheel support and
  customized OS shortcuts need final acceptance on actual Mac/Windows VNC servers.


## Mac desktop controls, displays and streaming (2026-10-30)

- The Mac control row contains Dock, Mission Control, Displays, Quality, Keyboard, Touchpad and More. It uses 48dp touch targets, horizontal scrolling on narrow windows and the existing light/dark glass surfaces. When the keyboard is open this same row lives above its keys; keyboard visibility remains exclusively user controlled.
- Dock sends configurable Control+F3 by default. Optional bottom/left/right edge activation moves the remote pointer without clicking. More exposes Spotlight, previous application, Quick Look, full screen and Show Desktop through the existing shortcut pipeline.
- No Mac helper is installed. ExtendedDesktopSize metadata supplies display IDs and rectangles. Without metadata the complete desktop remains visible; a manual rectangle editor supports naming, equal splits, dragging and numeric correction. Never infer display boundaries from the framebuffer aspect ratio.
- Mac defaults to one display; settings retain the complete remote layout. Rendering, hit testing, pointer motion, dragging and zoom bounds all use the selected rectangle in global framebuffer coordinates. Each display retains session zoom/pan; selection persists per connection. Switching releases all input and inertia, then moves the pointer to its previous target position or center without clicking. Read-only switching changes only the local view.
- Invalidated manual rectangles fall back to the complete framebuffer and require recalibration. Remote resolution following is suspended during Mac display browsing; the app never rearranges physical displays.
- New profiles default to Smooth (Tight JPEG quality 3, compression 1, with lossless fallbacks). HD prefers lossless ZRLE with quality-9 Tight fallback. Existing profiles migrate to Legacy and keep their original encoding behavior until explicitly switched. Pixel format and framebuffer resolution remain unchanged.
- Encoding changes are applied by the receiver at complete protocol message boundaries and acknowledged in the UI. Selected-region requests avoid CopyRect sources outside the region and fully refresh after switching. Pure pending buttonless pointer movements and duplicate refresh requests may be merged, but key/button/wheel ordering is preserved. Preset acknowledgement describes negotiation, not guaranteed server encoding or latency.
- Verification must cover nonzero display origins, invalid metadata/manual resize invalidation, migration, region requests, encoding negotiation, pointer masks, read-only behavior and lifecycle release. Real Mac shortcuts, multi-display servers and weak-network effects remain separate acceptance checks.

## Function keys and responsive touchpad revision (2026-10-30)

- F1–F12 stay in a visible row on every keyboard page, above modifiers. Targets remain at least 48dp; wide layouts show the full row, narrow layouts scroll it with an affordance. The former Fn page becomes Navigation. Plain F keys send their own keysyms, including on Mac; system actions still require explicit shortcuts.
- Touch scrolling uses 2dp per wheel tick at 1× (four times the former baseline), shared by the desktop, floating pad and scroll strip. Physical mouse wheels retain their existing scale. Pointer sensitivity remains 0.25–3×; vertical/horizontal touch scroll gain expands to 0.25–10× without rewriting saved values.
- Pace wheel events at display frames with a 480 tick/s ceiling and at most 16 per frame. Subtract only sent motion; retain a bounded 48-tick backlog and drain it within 100ms after touch-up, rather than dropping normal fast motion. Reverse direction clears stale movement on that axis; new touches, keys, buttons, cancellation and lifecycle changes stop queued motion and inertia immediately.
- Mac defaults enable the existing velocity-sensitive pointer acceleration, applying one vector gain to both axes to preserve direction. Explicit acceleration choices remain respected; held-button drags remain unaccelerated. Standard Mac scroll inertia uses 240ms exponential decay with a 1s maximum, while Windows keeps its short profile.
- A dedicated 1dp pointer onset enables fine positioning on the app touchpad without waiting for the system's larger touch slop. Tap/double-tap/long-press and held-button ownership remain explicit; gestures cannot click through the panel. Validate small movements, drag masks, scroll displacement across event batching and frames, direction reversal, bounded backlog, inertia interruption, and portrait/landscape function-row visibility.
- Standard RFB wheel events are discrete. This improves response and consistency but does not provide macOS pixel-scroll phases, pressure clicks or hardware haptics; native feel still needs acceptance against a real Mac.


## Touchpad appearance, clipboard and file sharing (2026-10-30)

- Input settings expose touchpad size (75–150%) and opacity (30–100%), with clear percentage labels, persistent values and live preview. Size changes adjust pad area while retaining 48dp buttons/handles; floating and keyboard-docked layouts remain constrained to usable bounds, and small windows compact the panel. Release held input before applying layout changes. Settings never change the remote display size or pointer sensitivity. Scroll and horizontal gain now span 0.25–10×; pointer gain stays 0.25–3× and saved choices are preserved.
- More contains a Sharing section with Clipboard and Send file entries, using existing viewer Material surfaces, rounded dialogs and en/zh labels. Keep the main Dock concise. Read-only/PiP/disconnect prevent outgoing clipboard and file actions.
- Clipboard offers manual Send current text, explicit opt-in automatic Android→remote text sync, and a recent list with resend, copy to Android, remove and clear. Sending updates the remote clipboard only; users invoke paste themselves. The existing clipboard_sync master remains the compatibility gate; automatic sending defaults off. Android permits clipboard reads only while focused: observe while foreground/focused, sync on focus return, and never promise background monitoring. Suppress remote-origin echoes and repeated sends. Sensitive clips never enter persistent history. Keep at most 20 entries, 64KiB each and 512KiB total in app-private non-backup storage; never include history in connection exports.
- Standard VNC provides text clipboard but no interoperable Mac file delivery. Reuse the bundled SSH/SFTP client, host-key verification and authentication; Mac Remote Login must be enabled by the user. Support direct VNC connections with a separate file-send connection dialog and never mutate the VNC transport. Select a file through Android's document picker; default to the authenticated user's canonical home/Downloads. Reuse saved SSH keys when configured; newly typed passwords remain session-only. Show destination, progress, cancel, success and actionable errors. Stream bounded buffers, sanitize names, preserve existing files with unique names, publish only after complete upload and clean temporary artifacts on failure/cancel. No remote shell commands or helper install.
- Validate settings bounds/persistence and 10× limits, actual RFB clipboard text without synthesized paste keys, history privacy/dedup/delete/limits, focus/readonly/disconnect behavior, and SFTP streamed bytes/names/nonoverwrite/cancellation/host-key handling. Build/lint plus portrait/landscape light/dark UI checks on an idle connected Android test device (48a1b18d for final acceptance; ac5247d9 was offline); real Mac clipboard UTF-8 support and SFTP permission/Remote Login require real-host acceptance.

## Ordered input tabs and desktop keyboards (2026-10-31)

- The primary control row has three ordered tabs: Computer keyboard, Touchpad, Shortcuts, followed by More. Mac and Windows share this navigation; Dock, Mission Control, displays, quality, sharing, gesture settings and disconnect live in More. Tabs use visible labels, 48dp targets and an explicit blue selected state. Only one input page is open at a time; an explicit close/Back collapses it without sending remote events or automatically reopening another page. Initial connection shows the concise row without opening a keyboard.
- Computer keyboard mirrors a compact desktop keyboard: persistent Esc/F1–F12, number row, Tab/QWERTY, Caps/home, Shift/lower, space/modifiers and arrows. Mac uses Control/Option/Command and Return; Windows uses Ctrl/Win/Alt and Enter. Small screens scroll the physical key deck rather than shrinking keys into unusable targets. On short landscape windows the key deck also scrolls vertically to retain at least 72dp of remote viewing space; Esc/F1–F12 and the main tabs stay visible. Keep armed/locked modifiers, caps, repeat, symbols and navigation through the existing input pipeline.
- Touchpad is a dedicated bottom page with a larger full-width pointing area and a left rail for Copy, Paste, Cut and Undo. Reuse the existing touch surface, mouse buttons, scroll strip, gesture thresholds and inertia. These actions send remote system chords; Paste uses the current remote clipboard and does not implicitly read the Android clipboard. Header/rail events cannot reach the remote desktop. The draggable floating pad remains optional in More; size/opacity preferences still apply there. The page's pointing surface fills its available area independently of floating size. The edit rail keeps 48dp targets and scrolls only if the window cannot fit all four actions.
- Shortcuts provides visible, platform-specific command labels for app switching, copy/paste/cut, select all, undo/redo, search and input-method switching, using configured per-computer mappings. Less frequent desktop/window actions remain in More. No free-form shell/terminal command entry is introduced.
- Pages reserve bottom layout space; portrait and landscape keep a remote viewing region. Switches, focus loss, readonly, PiP and disconnect release held input. Hide pages before PiP/readonly; disconnect explicitly releases controls, cancels file transfer and finishes the existing viewer session. Existing connection cleanup owns protocol shutdown. Preserve saved floating position and prior session configuration.
- Validate tab order/exclusivity and selected state, platform keyboard rows and actual chords, left-rail gestures/no click-through, More-only secondary actions/disconnect, real rotation, readonly/PiP/disconnect release, light/dark and portrait/landscape layouts. Retain prior clipboard/file-sharing changes without committing them automatically.


## Connection reliability (2026-10-31)

- Session stop cancels VNC and SSH I/O immediately, including handshakes, before joining senders or freeing clients. Cancellation is sticky for each client; reconnect creates fresh clients. Report disconnect only after resource cleanup, and keep the disconnect notification even if an error observer fails.
- VNC TCP connection waits default to 10s; stalled reads/writes and TLS progress default to 15s. Reset progress deadlines when bytes arrive, so an idle desktop is not treated as a stalled partial message. Poll cancellation at most every 100ms in native I/O waits. Keep upstream submodules clean by applying tracked patches only to build-local copies.
- Serialize remote input through the existing sender, with a 512-message queue and a 2s queued-event age limit. Surface send failures and disconnect instead of replaying delayed key/button transitions or silently dropping releases. Preserve consecutive pointer-move coalescing and key/button ordering barriers.
- SSH owns a cancellable raw socket independently of SSHLib's synchronized Connection monitor. Bound TCP/key exchange to 10s and authentication/SFTP initialization to 15s; pause network timing while the user confirms a host fingerprint or enters credentials. Configure TCP keepalive where supported. File-upload watchdogs retain their existing behavior.
- Synchronous DNS remains controlled by the platform resolver. Validate actual TCP stalls, TLS failure, prompt cancellation, delayed fingerprint approval, sender saturation, server restart/reconnect and SFTP wire traffic on a disposable endpoint; reserve roaming, silent Wi-Fi loss and prolonged real Mac/Windows sessions for physical-network acceptance.


## Home, settings and connection entry redesign (2026-11-01)

- Native Android Views, no new UI framework or blur dependencies. Shared light/dark tokens: grouped pale-gray/black background, solid white/charcoal cards, translucent navigation, blue actions, 20–24dp card corners, 8dp spacing, system typography and >=48dp controls. Use restrained 150–250ms system-aware motion; respect text scaling and accessibility.
- Home owns two persistent tabs: Connections and Settings. Remove drawer navigation. Connections shows a large title/add action, inline name/address search, My computers and Local network sections. Cards expose explicit overflow actions; discovery is evidence of local visibility, never proof that an undiscovered computer is offline. Empty list and empty search have distinct actions.
- Quick add/edit uses an expandable bottom sheet on phones, centered wide sheet on tablets and full screen on short landscape windows. Address, optional name/password and platform appear first; port5900/platformAuto default. Primary Save and connect, secondary Save only. Fold advanced settings into Connection/authentication, Picture/displays, Keyboard/touchpad and Other. Preserve old advanced-editor preference as initial expansion. Disable IME extract UI; short landscape uses side-by-side save actions. When a large IME leaves under240dp, hide the editor title and place the scrolling fields beside a compact action area so the focused field and both save actions remain visible. Save success alone dismisses, failures preserve the draft; repeated save is suppressed and generated IDs are assigned. Unsaved dismissal asks discard/continue.
- Settings uses a searchable grouped hierarchy: Appearance/display, Keyboard/touchpad, Mac/Windows optimization, Connection/sharing, Backup/restore, Advanced/help. Common settings are first; retain low-frequency options deeper. Search routes to and highlights actual controls. Reuse preference keys, validation, global-template/per-computer overrides, authentication and backup compatibility. Ordinary settings apply immediately without artificial loaders.
- State contracts: profile list loading/content/empty/error(with prior content retained); search results/no match; discovery idle/starting/searching/results/empty/stopped/error, 10s no-results guidance while discovery continues; editor editing/invalid/saving/error/success; import/export waiting/running/success/error/cancelled; connection connecting/awaiting credentials or trust/connected/failed/retry countdown/cancelled. Retry is explicit and cancellation never renders an error. Rotation preserves drafts/tab/navigation/scroll state and does not replay effects.
- Keep existing VNC/SSH/session implementation and viewer controls. Connection feedback shows target, cancel, retry, edit, home and expandable technical reason without fabricated percentages; reflect actual authentication waits and retain auto-reconnect cancellation. Device discovery must handle pending starts/stops and ignore callbacks from prior runs.
- Verification: state transitions/fault injection, save ID/repeated submissions/undo, searchable settings and legacy values, real discovery lifecycle, actual connect/authentication/reconnect, portrait/landscape/light/dark/large fonts/IME, debug build/lint and an idle device with original data/settings restored. No automatic Git commit/push.

## FastVNC identity and release signing (2026-11-02)

- Public product name: FastVNC. Release application ID: `tech.zhangbing.fvnc`; debug uses `.debug`. Retain the existing implementation namespace and JNI class names to avoid an unrelated source/native migration. Independent application IDs have independent Android data; users can import an existing AVNC JSON export.
- Original native vector identity: a silver-white computer with paired directional connection arrows on a blue light-glass gradient. No Apple trademark or literal lettering in the icon. Keep the computer inside the adaptive-icon safe zone; provide legacy round/squircle PNGs, Android adaptive foreground/background, monochrome themed icon, SVG master and store artwork. Generate all from the same geometry with a reproducible script.
- Update current branding, project/help links and listing names, retaining upstream attribution, licenses, historical changelogs and AVNC QR/backup interoperability. About explicitly identifies the GPL-3.0-or-later derivative and modification date.
- Release signing uses a dedicated RSA-4096 JKS key and randomly generated local passwords. Store credentials under ignored `private/signing/` with owner-only permissions. Gradle reads a local properties file or `FASTVNC_SIGNING_PROPERTIES`; never embed credentials in tracked source. CI builds remain unsigned. Verify the signed artifact's identity, certificate and native libraries before delivery; do not publish or push automatically.
