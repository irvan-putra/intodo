# Bot log access update

This source change improves the intodo bot log view:

- Log text is selectable so lines can be copied.
- The nested log view handles vertical drags it can scroll; at its edge the
  outer page can take over.
- The log view opens at the newest lines.
- Refresh avoids replacing unchanged text and defers changes while text is
  selected.

Verification: the focused Robolectric `BotActivityLogScrollTest` suite passes
6 tests, including nested scrolling, selection preservation, refresh behavior,
tail display, and the empty state. This is not a real-phone test. The bot rename
and on-device behavior remain unverified.
