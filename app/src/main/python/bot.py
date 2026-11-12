"""webxdc storage bridge bot (v3, gated, ack-free).

Runs on the phone (native Android host app embedding Python, or Termux while
in validation) on its OWN DeltaChat profile. It is added explicitly to the
chat holding the webxdc app.

v3 keeps the v2 ingestion gates and drops the
ack machinery entirely:

- B2 gates: accepts and reads ONLY granted chats (plus the owner's DM
  control channel) and ingests ONLY instances the DeliveryBot has already
  trusted (registration via publish_version or owner_register - never via
  mere observation).
- B3 echo guard: the store consumes-and-skips payloads carrying the exact
  bot-origin marker, and dedups ops by canonical identity as a second
  layer. Legitimate app payloads are never dropped for containing words
  like "nonce".
- Continuity is ack-free: a webxdc app cannot send automatic authenticated
  messages (sendUpdate is author-blind broadcast; sendToChat is a
  user-mediated draft), so the bot never waits for acks. Boot hints from
  trusted instances trigger a throttled full-history replay; the app's
  idempotent merge makes replays harmless; no cursor exists that could
  starve a future restore.

The bot never sees message contents beyond what the chat sees; storage
credentials live only in this process's environment on the phone, never in
the .xdc or the public repo.
"""

import logging
import os
import threading
from types import SimpleNamespace

from deltachat_rpc_client import Bot, Chat, EventType, Message
from deltachat_rpc_client.events import NewMessage, RawEvent

from ops_store import OpsStore, is_boot, is_bot_envelope


def _account_addr(acct):
    """Address of a configured account. Keep in sync with
    bot_runtime._account_addr: core deprecated the "addr" config key
    (multi-transport, 2026-04); read list_transports() first, fall back to
    the legacy key for older cores."""
    for t in acct.list_transports() or []:
        addr = (t or {}).get("addr")
        if addr:
            return addr
    return acct.get_config("addr")

log = logging.getLogger("bridge-bot")


class BridgeBot:
    def __init__(self, account, store: OpsStore, gate):
        """`gate` is the DeliveryBot owning trust decisions: chat_allowed,
        instance_trusted, handle_private_message, handle_boot,
        restore_into. Ingestion without the gate's trust is refused."""
        self.account = account
        self.store = store
        self.gate = gate
        self.bot = Bot(account, hooks=[
            (self.on_new_message, NewMessage),
            (self.on_status_update, RawEvent(EventType.WEBXDC_STATUS_UPDATE)),
            # Attachment downloads complete via MsgsChanged, NOT NewMessage.
            (self.on_msgs_changed, RawEvent(EventType.MSGS_CHANGED)),
        ])

    def on_new_message(self, event):
        snap = event.message_snapshot
        # deltachat-rpc-client 2.69.0 Contact has NO .address attribute;
        # the address lives on its snapshot (see line ~211). Resolve it
        # tolerantly so older fakes exposing .address directly still work.
        _contact = snap.message.get_sender_contact()
        sender = getattr(_contact, "address", None)
        if sender is None:
            _csnap = _contact.get_snapshot()
            sender = getattr(_csnap, "address", None) or _csnap.get("address", "")
        # A finished attachment download may also surface here on some
        # cores; completion itself is reconciled on MsgsChanged.
        self._reconcile_pending_download()
        # B2: accept ONLY granted chats or the core-verified owner's DM
        # control channel. Anything else is ignored without accept - the
        # bot never auto-joins.
        if not self.gate.chat_allowed(snap.chat_id):
            # Outside granted chats the bot listens ONLY to the owner's
            # private 1:1 DM (core-derived). A group that merely contains
            # the owner is never auto-accepted and never a control channel.
            if not (self.gate.is_authorized(sender)
                    and self._is_owner_dm(snap.chat_id)):
                log.warning("message in ungranted chat %s from %r ignored (no auto-accept)",
                            snap.chat_id, sender)
                return
        Chat(self.account, snap.chat_id).accept()
        # Headline flow: the owner DMs a new .xdc build -> publish it into
        # every granted chat and replay full history into the fresh
        # instance. Only the DM control channel triggers this (a .xdc file
        # posted inside the granted group is ordinary chat, not a command),
        # and only the core-verified owner.
        if (self.gate.is_authorized(sender)
                and not self.gate.chat_allowed(snap.chat_id)
                and self._maybe_dm_app_message(snap)):
            return
        # DM command surface: with no owner identity (r12) nobody is
        # authorized, so this is always silent - skip the per-message
        # "non-owner" log noise and the call entirely.
        if self.gate.is_authorized(sender):
            reply = self.gate.handle_private_message(
                sender, snap.text or "",
                restore_cb=lambda mid: self.gate.restore_into(mid, self.send_update))
            if reply:
                snap.chat.send_message(text=reply)
        # A fresh owner registration must ingest the instance's EXISTING
        # update history NOW (migration of pre-bot ops), not wait for the
        # next live update. Entries clear only AFTER their ingest commits
        # (ingest_done), so a crash mid-ingest is retried on the next
        # drain or boot; the store watermark/dedup makes retries no-ops.
        for mid in self.gate.pending_ingest():
            try:
                self.process_instance(mid)
            except Exception:
                log.exception("pending ingest of %s failed; will retry", mid)
                continue
            self.gate.ingest_done(mid)
        # webxdc instances are NOT auto-registered here: observing an app in
        # a granted chat is not trust (any participant could attach one).
        # Registration only happens via publish_version / owner_register.

    def _maybe_dm_app_message(self, snap):
        """Handle an owner-DM app message (.xdc / Webxdc). True if consumed."""
        file_path = getattr(snap, "file", None)
        view_type = str(getattr(snap, "view_type", "") or "")
        is_app = ((file_path and str(file_path).lower().endswith(".xdc"))
                  or view_type.endswith("Webxdc"))
        if not is_app:
            return False
        if not self._is_owner_dm(snap.chat_id):
            return True  # defense in depth: never publish outside the owner DM
        if self.gate.publish_known(snap.id):
            return True  # this attachment is tracked already: never twice
        if file_path and str(file_path).lower().endswith(".xdc"):
            self._publish_from_dm(snap, str(file_path))
            return True
        # Large attachments arrive with file=NULL until downloaded.
        ds = str(getattr(snap, "download_state", "") or "")
        if ds.endswith("Available") or ds.endswith("InProgress"):
            if self.gate.begin_pending_download(snap.id, snap.chat_id) == "started":
                self.account._rpc.download_full_message(self.account.id, snap.id)
                snap.chat.send_message(text=(
                    "Downloading the attachment; I will publish it "
                    "here when the download finishes."))
            return True
        if ds.endswith("Failure"):
            snap.chat.send_message(text=(
                "The attachment download failed; nothing published. "
                "Re-send the file to retry."))
            return True
        snap.chat.send_message(text=(
            "That message carries no readable attachment "
            "(download_state=%s); nothing published." % (ds or "?")))
        return True

    def on_msgs_changed(self, event):
        # Attachment downloads complete via MsgsChanged (core may even
        # REPLACE the message id), never via NewMessage.
        if self.gate.pending_download() is not None:
            self._reconcile_pending_download()

    def _reconcile_pending_download(self):
        """Resolve the PENDING attachment - and only that attachment -
        against the core's current state. Identity order: the pending
        message id itself first; only if the core replaced the id, fall
        back to a bounded DM scan, and only adopt the replacement when it
        is UNAMBIGUOUS (a single unseen candidate). A newer attachment
        that already published on its own never resolves this pending
        state, so interleaved sends cannot silently swallow a download."""
        pend = self.gate.pending_download()
        if not pend:
            return
        msg_id, chat_id = pend["msg_id"], pend["chat_id"]
        # 1) the pending message itself, by id
        snap = None
        try:
            snap = self.account.get_message_by_id(msg_id).get_snapshot()
        except Exception:
            snap = None  # core may have replaced the id; scan below
        if snap is not None:
            fp = getattr(snap, "file", None)
            ds = str(getattr(snap, "download_state", "") or "")
            if fp and str(fp).lower().endswith(".xdc"):
                self.gate.clear_pending_download()
                if not self.gate.publish_known(snap.id):
                    self._publish_from_dm(snap, str(fp))
                return
            if ds.endswith("Failure"):
                self.gate.clear_pending_download()
                snap.chat.send_message(text=(
                    "The attachment download failed; nothing published. "
                    "Re-send the file to retry."))
                return
            if ds.endswith("Done"):
                # Downloaded but not an app bundle (or unreadable).
                self.gate.clear_pending_download()
                snap.chat.send_message(text=(
                    "The downloaded attachment is not a readable .xdc app "
                    "bundle; nothing published."))
                return
            return  # still downloading: wait for the next MsgsChanged
        # 2) id replaced by the core: bounded scan, unambiguous only
        try:
            msgs = Chat(self.account, chat_id).get_messages()[-20:]
        except Exception:
            return
        ready, failed = [], []
        for m in msgs:
            try:
                s = m.get_snapshot()
            except Exception:
                continue
            if s.id == msg_id or self.gate.publish_known(s.id):
                continue  # other messages (e.g. a newer published app) are not this pending download
            vt = str(getattr(s, "view_type", "") or "")
            fp = getattr(s, "file", None)
            ds = str(getattr(s, "download_state", "") or "")
            if fp and str(fp).lower().endswith(".xdc"):
                ready.append(s)
            elif ds.endswith("Failure") and vt.endswith("Webxdc"):
                failed.append(s)
        if len(ready) == 1:
            s = ready[0]
            self.gate.clear_pending_download()
            self._publish_from_dm(s, str(s.file))
        elif len(failed) == 1 and not ready:
            self.gate.clear_pending_download()
            failed[0].chat.send_message(text=(
                "The attachment download failed; nothing published. "
                "Re-send the file to retry."))
        # ambiguous or nothing: keep pending; bounded boot retries apply

    def _chat_info(self, chat_id):
        chat = Chat(self.account, chat_id)
        info = chat.get_basic_snapshot()
        chat_type = getattr(info, "chat_type", "")
        chat_type = getattr(chat_type, "value", chat_type)  # unwrap str-Enum
        contacts = [c.get_snapshot().address for c in chat.get_contacts()]
        return {"chat_type": str(chat_type), "contacts": contacts}

    def _is_owner_dm(self, chat_id):
        return self.gate.is_owner_dm(chat_id, self._chat_info)

    def _publish_from_dm(self, snap, file_path):
        """Publish into every granted chat, tracking per-target delivery so
        a crash mid-loop leaves an honest resumable record (retried on the
        next boot) instead of a silently lost partial publish."""
        msg_id = snap.id
        self.gate.begin_publish(msg_id, sorted(self.gate.allowed_chat_ids),
                                file_path, snap.chat_id if hasattr(snap, "chat_id")
                                else snap.chat.id)
        delivered, failed, total = [], [], 0
        for chat_id in self.gate.publish_remaining(msg_id):
            try:
                _msg, n = self.gate.publish_and_restore(
                    Chat(self.account, chat_id), str(file_path), self.send_update)
                self.gate.publish_mark_delivered(msg_id, chat_id)
                delivered.append(chat_id)
                total += n
            except Exception:
                log.exception("publish to granted chat %s failed", chat_id)
                failed.append(chat_id)
        chat = snap.chat if hasattr(snap, "chat") else Chat(self.account, snap.chat_id)
        if failed:
            chat.send_message(text=(
                "published to %d of %d chat(s); replayed %d op(s) into each new "
                "instance. FAILED for %d chat(s) - recoverable: I will retry on "
                "restart, no need to re-send the file."
                % (len(delivered), len(delivered) + len(failed), total, len(failed))))
        else:
            chat.send_message(text=(
                "published to %d chat(s); replayed %d op(s) into each new instance"
                % (len(delivered), total)))

    def reconcile_on_boot(self):
        """Resume interrupted work after a restart: bounded retry of a
        pending attachment download, and retry of partially-delivered
        publishes whose bundle file still exists."""
        pend = self.gate.pending_download()
        if pend:
            res = self.gate.retry_pending_download()
            if res == "exhausted":
                Chat(self.account, pend["chat_id"]).send_message(text=(
                    "The earlier attachment download did not complete after "
                    "%d attempts; nothing published. Re-send the file to retry."
                    % self.gate.MAX_DOWNLOAD_ATTEMPTS))
            else:  # "started"
                self.account._rpc.download_full_message(self.account.id,
                                                        pend["msg_id"])
                self._reconcile_pending_download()
        # finish any register->ingest interrupted by a restart
        for mid in self.gate.pending_ingest():
            try:
                self.process_instance(mid)
            except Exception:
                log.exception("pending ingest of %s failed; will retry", mid)
                continue
            self.gate.ingest_done(mid)
        for msg_id, rec in self.gate.incomplete_publishes().items():
            path = rec.get("file")
            if path and os.path.exists(path):
                snap = SimpleNamespace(id=msg_id,
                                       chat=Chat(self.account, rec["chat_id"]))
                self._publish_from_dm(snap, path)
            else:
                Chat(self.account, rec["chat_id"]).send_message(text=(
                    "Could not finish publishing the earlier app file (bundle "
                    "no longer on disk); please re-send it."))
                self.gate.drop_publish(msg_id)

    def on_status_update(self, event):
        msg_id = event.msg_id
        if not self.gate.instance_trusted(msg_id):
            log.warning("status update for untrusted instance %s ignored", msg_id)
            return
        self.process_instance(msg_id)

    def process_instance(self, msg_id):
        """Fetch updates newer than this instance's watermark, act on boot
        hints, and persist app ops through the store (which applies the
        exact-marker echo guard and canonical dedup itself)."""
        msg = Message(self.account, msg_id)
        updates = msg.get_webxdc_status_updates(self.store.watermark(msg_id))
        for u in updates:
            payload = u.get("payload") or {}
            if is_boot(payload):
                if self.gate.handle_boot(msg_id, payload) == "restore-authorized":
                    self.gate.restore_into(msg_id, self.send_update)
            elif is_bot_envelope(payload):
                log.info("own restore echo on %s consumed, not ingested", msg_id)
        new = self.store.apply_updates(msg_id, updates)
        if new:
            log.info("instance %s: persisted %d op(s), watermark=%d",
                     msg_id, new, self.store.watermark(msg_id))
        return new

    def send_update(self, msg_id, payload, info=""):
        """Publish an update into the shared app (restore replay batches)."""
        Message(self.account, msg_id).send_webxdc_status_update({"payload": payload, "info": info}, info)

    def run_in_thread(self):
        self.reconcile_on_boot()
        t = threading.Thread(target=self.bot.run_forever, daemon=True)
        t.start()
        return t


if __name__ == "__main__":
    import os
    from deltachat_rpc_client import DeltaChat, Rpc

    from delivery_bot import DeliveryBot

    logging.basicConfig(level=logging.INFO)
    rpc = Rpc(accounts_dir=os.environ.get("BOT_ACCOUNTS_DIR", os.path.expanduser("~/.bridge-bot/accounts")))
    rpc.start()
    dc = DeltaChat(rpc)
    accounts = dc.get_all_accounts()
    account = accounts[0] if accounts else dc.add_account()
    if not account.is_configured():
        qr = os.environ.get("DCACCOUNT_QR", "dcaccount:nine.testrun.org")
        account.add_transport_from_qr(qr)
    store = OpsStore(os.environ.get("BOT_DB", os.path.expanduser("~/.bridge-bot/ops.db")))
    gate = DeliveryBot(
        account,
        store,
        owner_addr=os.environ["BOT_OWNER_ADDR"],
        allowed_chat_ids={int(x) for x in os.environ.get("BOT_CHATS", "").split(",") if x},
        list_id=os.environ["BOT_LIST_ID"],
        bot_addr=_account_addr(account),
    )
    bridge = BridgeBot(account, store, gate)
    log.info("bridge bot online as %s", _account_addr(account))
    bridge.bot.run_forever()
