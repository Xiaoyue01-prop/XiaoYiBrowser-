# Summit v1.2 Top Insets Fix

Based on Summit-v1.2-Compatibility-Fix-EdgeInsets. Keeps EDGE_JS and all existing compatibility changes.

Top-inset adjustment: only near-fullscreen fixed/sticky containers (>=85% viewport height) receive internal top padding. Smaller fixed/sticky headers are shifted below the status bar instead, avoiding excessive vertical spacing inside search pages.

Build status: not verified in this environment. Run GitHub Actions and test Google home + results pages before treating as stable.
