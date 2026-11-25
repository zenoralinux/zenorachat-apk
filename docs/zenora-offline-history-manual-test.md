# ZenoraChat Offline Local-first History — Device Regression Plan

This build is a Debug Preview (the .beta package and Debug signing), not a production-signed release.

## A. Populate and verify native history

1. Sign in to the same Zenora account on a device with network access.
2. Open a Zenora conversation containing at least 150 plain-text messages.
3. Wait for the latest page to appear, then scroll upward until at least three older pages have been loaded.
4. Send one text message and confirm its optimistic bubble is delivered and becomes canonical after ACK.
5. Record the visible oldest and newest server message IDs from logs or API diagnostics.

Expected: message pages are stored in the Telegram account database. The Outbox is empty for the acknowledged message, and the history cursor moves only after an API page is successfully applied.

## B. Process death and offline startup

1. Force-stop the app from Android system settings; do not merely background it.
2. Enable airplane mode and verify Wi-Fi/mobile data are disabled.
3. Launch the app and open the previously populated Zenora conversation.
4. Wait for the first local page and scroll upward through the pages previously loaded.
5. Confirm that the pending/failed test messages, if any, are also visible.

Expected: the previously cached pages render from MessagesStorage.getMessages() without waiting for HTTP. The API may fail after the local page is displayed, but the visible history remains. No MTProto history request should be emitted for this Zenora dialog. Reaching the end of local cache must not be treated as a confirmed server end.

## C. Reconnect at the local boundary

1. Keep the conversation open at the oldest locally cached message.
2. Disable airplane mode and restore network connectivity.
3. Scroll to the older-history boundary again.
4. Observe the requested before_message_id and compare it with the oldest local message ID.
5. Scroll through the newly fetched page and then repeat until the API reports has_more_older=false.

Expected: the first API page begins strictly before the local boundary (or the older durable server cursor when it is earlier). Cached rows remain visible; the new page appears once, in chronological order. The server end is marked only after a successful response confirms it.

## D. Empty local cache and first online recovery

1. Use a test conversation with no messages cached on the device.
2. Open it offline.
3. Restore connectivity without clearing app data.
4. Reopen the conversation or retry the initial load by leaving and re-entering it.

Expected: the empty SQLite result is not interpreted as a permanent server end. The initial basic-text history is fetched when connectivity returns.

## E. Outbox and live-sync regression

1. Start a text send while offline and force-stop the app before reconnecting.
2. Relaunch offline and open the conversation.
3. Verify the pending bubble is visible and its status is unchanged.
4. Restore connectivity and allow Outbox retry/ACK to complete.
5. While the conversation is open, receive a new text message from another session.
6. Repeat with a FAILED row and an UNKNOWN row; do not manually retry the failed row during the passive-history check.

Expected: SENDING, UNKNOWN, and FAILED rows survive local history refresh. ACK maps the existing local message to the canonical server ID; it does not create a second bubble. Live sync starts from the newest positive local message ID and receives new rows without replaying the full history.

## F. Activity lifecycle and delayed responses

1. Use network throttling or a proxy to delay the history endpoint response.
2. Open a Zenora conversation, then leave it before the response arrives.
3. Open another conversation, then re-enter the first conversation.
4. Release the delayed response.

Expected: a callback belonging to the previous ChatActivity/request generation cannot complete or replace the new Activity's history state.

## G. Migration

1. Install the previous build with a populated database at version 182, including at least one Outbox row and one message row.
2. Install this build over it without clearing data.
3. Open the Zenora conversation and inspect the database version and zenora_history_state.
4. Separately test a clean installation.

Expected: version 182 migrates to 183; existing messages_v2 and zenora_outbox rows survive. A clean installation creates the same version-183 history-state schema.

## Evidence to capture

- Android build version and device ABI (arm64-v8a).
- Logcat tags containing [ZenoraChat] around initial and older history.
- Before/after message IDs for each page.
- Outbox status and client_message_id before and after ACK.
- SQLite PRAGMA user_version, zenora_history_state row, and message counts.
- Network trace confirming no MTProto history request for Zenora dialogs.
