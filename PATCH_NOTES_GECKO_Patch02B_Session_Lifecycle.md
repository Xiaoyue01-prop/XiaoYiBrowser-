# Summit Gecko Patch 02B — Session Lifecycle & Tab Safety

## Scope
This patch hardens Gecko prototype tab/session ownership and transitions. It does not modify the stable `:app` module.

## Changes
- TabManager now enforces a single-active-tab invariant.
- Added a 100-tab ceiling and duplicate-session guard.
- Closing the active tab selects the most recently active remaining tab (MRU).
- Added lookup helpers for tab IDs and GeckoSession membership.
- Tab creation now opens/configures a new GeckoSession before switching away from the current tab.
- Failed tab creation rolls back registry membership, session state, and per-session maps.
- Tab switching validates that the target session is open and registered, and restores the prior tab if activation fails.
- Closing a tab explicitly deactivates/unfocuses its session, releases the visible GeckoDisplay before closing, and clears all per-session state maps.
- Popup creation no longer forces a background source tab into the foreground before creating the popup.
- Popup creation is transactional and respects the same tab limit.
- Activity teardown explicitly deactivates and unfocuses every session before closing it.

## Not included
- Private browsing profile/storage isolation.
- Download progress/cancel notifications.
- Fullscreen/PiP expansion.
- Gesture-driven tab swipe and pull-to-refresh.

These are intentionally not claimed as complete in this patch.

## Validation
- Source-level consistency checks and ZIP integrity checks performed.
- Android Gradle compilation has not been run in this environment. GitHub Actions remains the build authority.
