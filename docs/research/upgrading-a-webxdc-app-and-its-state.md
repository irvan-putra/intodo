# Upgrading a webxdc app: what Delta Chat's forum says about carrying state over

Status: forum thread read 2026-11-16 (posts from May 2023 to March 2026). The forum is discussion, not a specification, and the thread was read as a page of 15 posts. This note says what people there reported; it does not test any Delta Chat version.

## What the thread says
- The question (May 2023): shared apps such as notes or a calendar are "sticky", so a new version of the app file would ideally replace the old one while keeping all state updates.
- A Delta Chat developer replied that it was considered and "postponed because there are so many open questions", for example: apps cannot be expected to be perfectly backward compatible, so a backup or downgrade route would be needed; and in a peer-to-peer setting you cannot be sure everyone has received or installed the new version.
- Ideas listed there: send a new message or instance that replays the old updates with the new app version; or export and import state between instances. The same reply says apps are pinned to a single message.
- A later user-level workaround in the thread: export the state from the original app and import it into the new version, using the app's console (which the thread says is disabled by default as a security measure and can be turned on in advanced settings). The poster notes it may not work for apps that call `webxdc.sendUpdate` immediately on first open.
- Later posts describe the cost of the manual route: everyone has to install the new app file again, and home-screen shortcuts for the old instance are lost.
Source: https://forum.delta.chat/t/upgrading-a-webxdc-app-migrating-state-while-keeping-pinned-apps-unchanged/2573

## What the API page says
`sendUpdate` sends an update to the chat's peers, and all receiving peers (including the sender) get it through the callback given to `setUpdateListener()`. https://webxdc.org/docs/spec/sendUpdate.html . The page read does not discuss app versions.

## What this means for an app author
- Sending a new version of the app file into the same chat should be treated as starting a new instance, unless you have confirmed otherwise on your target Delta Chat version. The thread supports that reading but is not an official statement for every version.
- If an app needs to survive upgrades, plan an export and import path in the app itself, and design update messages so newer versions can ignore fields they do not know.
