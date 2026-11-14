# Unfinished: bot log access v3

This branch holds an unfinished patch to the itdapk bot log view
(`app/src/main/java/com/instinct/widget/bot/BotActivity.java`):

- log text selectable, so lines can be copied
- nested log ScrollView only claims vertical drags it can actually scroll,
  so the outer page no longer swallows the gesture
- log box opens scrolled to the tail, where the freshest lines are

Status: NOT finished and NOT released. It has only passed a limited
compile check with stubs; it has never run on a real phone. Do not treat
it as tested or as part of any itdapk release. It is published for
source visibility only, on top of main at ccff71c.
