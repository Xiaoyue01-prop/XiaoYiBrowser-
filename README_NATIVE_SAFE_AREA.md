# Summit v1.2 — No EDGE_JS baseline

This experimental baseline removes the former EDGE_JS page-header/color heuristic and its JavaScript bridge. The native WindowInsets path remains responsible for the top layout. A separate minimal bottom-only helper is retained to preserve the tested scroll space behind Summit's floating toolbar.

The bottom helper does not inspect or reposition website headers and does not sample page-edge colors. This intentionally reduces behavior so compatibility can be rebuilt in small, testable steps.

Validation performed: ZIP integrity and source/reference inspection. Android `assembleDebug` and on-device testing have not been run in this environment.
