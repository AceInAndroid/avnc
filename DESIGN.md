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
  Pointer speed spans 0.25–3× and vertical/horizontal scroll speeds span 0.25–6×. Apply gains once.
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
- Touch scrolling uses 2dp per wheel tick at 1× (four times the former baseline), shared by the desktop, floating pad and scroll strip. Physical mouse wheels retain their existing scale. Pointer sensitivity remains 0.25–3×; vertical/horizontal touch scroll gain expands to 0.25–6× without rewriting saved values.
- Pace wheel events at display frames with a 480 tick/s ceiling and at most 16 per frame. Subtract only sent motion; retain a bounded 48-tick backlog and drain it within 100ms after touch-up, rather than dropping normal fast motion. Reverse direction clears stale movement on that axis; new touches, keys, buttons, cancellation and lifecycle changes stop queued motion and inertia immediately.
- Mac defaults enable the existing velocity-sensitive pointer acceleration, applying one vector gain to both axes to preserve direction. Explicit acceleration choices remain respected; held-button drags remain unaccelerated. Standard Mac scroll inertia uses 240ms exponential decay with a 1s maximum, while Windows keeps its short profile.
- A dedicated 1dp pointer onset enables fine positioning on the app touchpad without waiting for the system's larger touch slop. Tap/double-tap/long-press and held-button ownership remain explicit; gestures cannot click through the panel. Validate small movements, drag masks, scroll displacement across event batching and frames, direction reversal, bounded backlog, inertia interruption, and portrait/landscape function-row visibility.
- Standard RFB wheel events are discrete. This improves response and consistency but does not provide macOS pixel-scroll phases, pressure clicks or hardware haptics; native feel still needs acceptance against a real Mac.
