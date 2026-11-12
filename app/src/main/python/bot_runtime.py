"""LIVE bot runtime (scratch 0.3 r12). NETWORK code path - separate from bot_validation.py.

TRUST MODEL:
  - Delta Chat's contact/invitation flow IS the boundary: only people the
    user gives the bot's invitation QR can reach the bot at all. ANY chat
    that arrives ACCEPTED under core semantics is served, whoever added the
    bot: a group gets its own shared list, a 1:1 DM gets a personal list.
    NO chat-approval ceremony and NO owner identity on the DeltaChat side.
    Contact requests stay refused - that is core's own boundary.
  - ALL management is app-side (this Android app, which holds the bot
    profile): provisioning, the invitation QR, and PUBLISHING new .xdc
    versions (the activity writes PUBLISH_REQ; the live loop fans the file
    out to every served chat). No chat-originated input can publish or
    administer: the per-chat v6.1 gates are built with owner_addr=None, so
    their DM command surface refuses everyone by construction.
  - LIST ISOLATION: each served chat gets its OWN OpsStore (ops-<id>.db),
    its OWN bot-generated stable list id, and its OWN v6.1 gate/bridge.
    Histories never mix across chats.
  - REMOVAL: when the core records the bot removed from a chat (any actor),
    the chat leaves scope - access pauses. The list id, registrations and
    store file are all KEPT; re-adding the bot to the same chat resumes the
    same list (core chat ids are grpid-keyed, stable across remove/re-add).
    The bot never deletes data.

Setup order: 1 probe, 2 create account, 3 start bot, 4 show the invitation
QR. Whoever gets the QR adds the bot to a group or messages it directly;
the bot accepts and serves. Publishing: the user picks a .xdc in the app;
every served chat gets its packed copy (v6.1 headline flow, per-chat).
Conflicting pre-pinned bundles are refused per chat by reviewed v6.1 code
(pack_list_identity), never repinned.

Attachment downloads have ONE completion path: v6.1's own persisted
download reconciler (BridgeBot). The wrapper does NOT intercept attachments.

Custody: profile (bot's own keys) only in the app's private files dir;
android:allowBackup="false". Uninstall/wipe = identity loss. ops-*.db are
the only full per-chat op histories.

Relay facts (public source: nine.testrun.org/info.html + /privacy.html):
free, no signup; 60 msgs/min; 700M storage; messages deleted latest 20 days
after arrival (transport, not archive).
"""
import hashlib
import json
import logging
import os
import re
import secrets
import threading
import time
import zipfile

log = logging.getLogger("bot_runtime")

RELAY = "nine.testrun.org"


def _account_addr(acct):
    """Address of a configured account.

    Core deprecated the "addr" config key (multi-transport, 2026-04): after
    add_transport_from_qr the address lives in the transport list, and
    get_config("addr") returns None. Read list_transports() first; fall back
    to the legacy key for older cores.
    """
    for t in acct.list_transports() or []:
        addr = (t or {}).get("addr")
        if addr:
            return addr
    return acct.get_config("addr")
STATE_FILE = "bot_state.json"
STOP_FLAG = "STOP"      # written by the Java service on stop; watchdog exits
RUN_LOCK = "RUNNING"    # single-run lock, removed on clean exit
INVITE_REQ = "INVITE_REQ"    # written by the activity; live loop answers it
INVITE_DONE = "INVITE_DONE"  # written by the live loop when invite.svg is fresh
PUBLISH_REQ = "PUBLISH_REQ"    # activity writes basename of a .xdc in files_dir
PUBLISH_DONE = "PUBLISH_DONE"  # live loop writes the publish result JSON
PUBLISH_PROG = "PUBLISH_PROG"  # per-chat progress for crash-resume (sha-keyed)
# state keys: provisioned, bot_addr, chats {str(chat_id): {"list_id": str}}
# (legacy owner_* keys from pre-r12 state are ignored, never read)


def _parse_membership(text, bot_addr):
    """(action, affected, actor) from a membership system-message text.

    The client's parse_system_add_remove covers the third-person stock
    forms ("Member X added by Y", "You added member X", "Group left by
    Y") but NOT the SELF-perspective forms core 2.69 emits when the BOT is
    the affected member (stock_str.rs MsgAddedBy=180 / MsgAdded=182 /
    MsgRemovedBy=179 / MsgRemoved=181):
        "You were added by <actor>."     "You were added."
        "You were removed by <actor>."   "You were removed."
    The bot being added to a group is exactly the self case. r12: adds no
    longer gate anything (any accepted chat is served); the scan's only
    remaining job is REMOVAL detection (a recorded removal of the bot, any
    actor, revokes the chat). actor=None means core did not record the
    adder (e.g. unencrypted group).
    The actor slot carries a display name + address ("Name (addr)") when
    the actor is a known contact; extract_addr handles both forms.
    """
    from deltachat_rpc_client._utils import extract_addr, parse_system_add_remove
    parsed = parse_system_add_remove(text)
    if parsed:
        return parsed
    t = (text or "").strip().lower()
    m = re.match(r"you were (added|removed) by (.+)$", t)
    if m:
        action, actor = m.groups()
        return action, (bot_addr or "me"), extract_addr(actor)
    m = re.match(r"you were (added|removed)\.?$", t)
    if m:
        return m.group(1), (bot_addr or "me"), None
    return None


# --- structural membership attribution (rev9.3) ---------------------------
# Evidence base (core v2.69.0 source):
#  - mimeparser.rs: a Chat-Group-Member-Added/Removed header sets
#    SystemMessage MemberAddedToGroup=4 / MemberRemovedFromGroup=5.
#  - receive_imf.rs: the membership message is inserted with Param::Cmd,
#    Param::ContactAddedRemoved (affected contact id) and from_id = the
#    actor; an EMPTY better_msg sends the whole message to ChatId::TRASH
#    instead of the chat (no visible info message), and implicit
#    member-list reconciliation adds SELF silently (no info message).
#  - message.rs: is_info = from/to INFO or cmd != Unknown;
#    get_info_contact_id reads ContactAddedRemoved; ContactId::SELF == 1;
#    load_from_db has `AND chat_id!=3` - TRASHED MESSAGES ARE UNREADABLE
#    through the public message API, so trashed evidence cannot be
#    recovered client-side (get_message_ids(3) lists ids, get_message
#    refuses each).
# So text parsing CANNOT be the primary mechanism: the visible text may be
# absent (trashed) or carry only a display name. Structure first, text as
# fallback for old cores.
SELF_CONTACT_ID = 1
SPECIAL_CONTACT_IDS = {1, 2, 5}   # SELF, INFO, DEVICE
_CMD_ACTION = {"MemberAddedToGroup": "added",
               "MemberRemovedFromGroup": "removed"}
_STOCK_WORDS = frozenset("""
you were added removed by member group left the a to from image changed
deleted name description messages are end-to-end encrypted me your
""".split())


def _redact_template(text):
    """Stock-string TEMPLATE with addresses/names hidden, for on-screen logs."""
    t = re.sub(r"\S+@\S+", "[e]", (text or "").strip().lower())
    out = []
    for tok in t.split():
        core = tok.strip(".,!\"'")
        # keep ONLY stock words and punctuation: a numeric/symbol contact
        # name (e.g. a phone number) must not leak into the screenshot log
        out.append(tok if (core in _STOCK_WORDS
                           or not any(ch.isalnum() for ch in core))
                   else "[w]")
    return " ".join(out)[:60]


def _state_path(files_dir):
    return os.path.join(files_dir, STATE_FILE)


def load_state(files_dir):
    try:
        with open(_state_path(files_dir)) as f:
            return json.load(f)
    except Exception:
        return {}


def save_state(files_dir, **kw):
    # atomic: never leave a half-written state file after a crash mid-save
    st = load_state(files_dir)
    st.update(kw)
    tmp = _state_path(files_dir) + ".tmp"
    with open(tmp, "w") as f:
        json.dump(st, f)
    os.replace(tmp, _state_path(files_dir))
    return st


def _rpc(native_lib_dir, files_dir):
    from deltachat_rpc_client import DeltaChat, Rpc
    rpc = Rpc(accounts_dir=os.path.join(files_dir, "accounts"),
              rpc_server_path=os.path.join(native_lib_dir, "librpcsrv.so"))
    rpc.start()
    return rpc, DeltaChat(rpc)


def provision(native_lib_dir, files_dir):
    """One-time: create the bot's OWN chatmail account. Explicitly network-using."""
    rpc, dc = _rpc(native_lib_dir, files_dir)
    try:
        accounts = dc.get_all_accounts()
        configured = [a for a in accounts if a.is_configured()]
        if configured:
            addr = _account_addr(configured[0])
            if not addr:
                raise RuntimeError("existing account has no readable transport address")
            save_state(files_dir, provisioned=True, bot_addr=addr)
            return addr
        if accounts:
            # Existing profile in an unconfigured/uncertain state: re-running
            # QR onboarding would mint a NEW relay identity. Refuse loudly so
            # recovery never creates a duplicate server-side account.
            raise RuntimeError(
                "found %d existing profile(s) but none is configured; "
                "refusing to re-onboard (would mint a new relay identity) - "
                "manual review needed" % len(accounts))
        acct = dc.add_account()
        acct.add_transport_from_qr(f"dcaccount:{RELAY}")
        if not acct.is_configured():
            raise RuntimeError("relay did not configure the account")
        addr = _account_addr(acct)
        if not addr:
            raise RuntimeError("account configured but its address is unreadable")
        save_state(files_dir, provisioned=True, bot_addr=addr)
        log.info("bot account created: %s", addr)
        return addr
    finally:
        rpc.close()


def _account(dc):
    for a in dc.get_all_accounts():
        if a.is_configured():
            return a
    raise RuntimeError("no configured account - run provision() first")


def read_list_identity(xdc_path):
    """Read-only list.json identity from a bundle. None if absent."""
    try:
        with zipfile.ZipFile(xdc_path) as z:
            if "list.json" not in z.namelist():
                return None
            data = json.loads(z.read("list.json").decode())
            lid = data.get("list_id") if isinstance(data, dict) else None
            return lid if isinstance(lid, str) and lid else None
    except Exception as e:
        log.warning("list.json unreadable in %s: %s", xdc_path, e)
        return None


class ChatRuntime:
    """One isolated v6.1 stack per served chat: own store, id, gate, bridge.

    owner_addr is ALWAYS None (r12): there is no DeltaChat-side privileged
    identity; the gate's DM command surface therefore refuses everyone and
    publishing only happens app-side via publish_and_restore."""

    def __init__(self, account, files_dir, chat_id, list_id, bot_addr):
        from ops_store import OpsStore
        from delivery_bot import DeliveryBot
        from bot import BridgeBot
        self.chat_id = chat_id
        store = OpsStore(os.path.join(files_dir, "ops-%d.db" % chat_id))
        self.gate = DeliveryBot(account, store, owner_addr=None,
                                allowed_chat_ids={chat_id}, list_id=list_id,
                                bot_addr=bot_addr)
        self.bridge = BridgeBot(account, store, self.gate)


class ChatControl:
    """Wrapper owning trust decisions around the per-chat v6.1 stacks.

    r12: no owner concept at all - every
    accepted chat is served with an isolated stack; management (provisioning,
    invitation QR, publishing) is app-side only.

    Uses ONLY the public rpc-client surface (Account.get_chatlist, Chat,
    Contact.get_snapshot, Message.get_snapshot), no raw rpc calls."""

    def __init__(self, account, files_dir):
        self.account = account
        self.files_dir = files_dir
        self.bot_addr = _account_addr(account)
        self.runtimes = {}        # chat_id -> ChatRuntime

    def _st(self):
        return load_state(self.files_dir)

    @staticmethod
    def _chat_type(chat):
        try:
            return str(chat.get_basic_snapshot().get("chat_type", ""))
        except Exception:
            return ""

    def _served_chat_ids(self):
        return {int(k) for k, rec in self._st().get("chats", {}).items()
                if not rec.get("revoked")}

    def _membership_scan(self, chat_id):
        """Structural membership scan of a chat's visible messages.

        Returns (events, first_visible_ts, n_scanned, n_info). Each event:
        {"action", "affected"("self"/"other"/"unknown"), "actor"(addr|None),
         "ts", "basis"("cmd"/"text"), "tmpl"}. Trashed messages are
        unreachable by design (message.rs load_from_db excludes chat 3),
        so only visible evidence can ever attribute."""
        from deltachat_rpc_client import Chat, Contact
        events, n_scanned, n_info = [], 0, 0
        first_visible_ts = None
        idx = 0   # chat-order tiebreak when snapshots lack timestamps

        def scan(cid, loc):
            nonlocal first_visible_ts, n_scanned, n_info, idx
            try:
                msgs = Chat(self.account, cid).get_messages()
            except Exception as e:
                log.info("membership scan: %s chat %s unreadable: %s",
                         loc, cid, e)
                return
            for m in msgs:
                try:
                    snap = m.get_snapshot()
                except Exception:
                    continue
                n_scanned += 1
                idx += 1
                ts = snap.get("sort_timestamp") or snap.get("timestamp") or 0
                if loc == "chat" and (first_visible_ts is None
                                      or ts < first_visible_ts):
                    first_visible_ts = ts
                cmd = str(snap.get("system_message_type") or "")
                action = _CMD_ACTION.get(cmd)
                text = snap.get("text", "") or ""
                if action:
                    icid = snap.get("info_contact_id")
                    affected = ("self" if icid == SELF_CONTACT_ID
                                else ("other" if icid else "unknown"))
                    actor = None
                    from_id = snap.get("from_id")
                    if from_id and from_id not in SPECIAL_CONTACT_IDS:
                        try:
                            actor = (Contact(self.account, from_id)
                                     .get_snapshot().get("address"))
                        except Exception:
                            actor = None
                    events.append({"action": action, "affected": affected,
                                   "actor": actor, "ts": ts, "idx": idx,
                                   "basis": "cmd",
                                   "tmpl": _redact_template(text)})
                elif snap.get("is_info"):
                    n_info += 1
                    parsed = _parse_membership(text, self.bot_addr)
                    if parsed:
                        action, aff, actor = parsed
                        affected = ("self" if aff and self.bot_addr and
                                    aff.lower() in (self.bot_addr.lower(), "me")
                                    else "other")
                        events.append({"action": action, "affected": affected,
                                       "actor": actor, "ts": ts, "idx": idx,
                                       "basis": "text",
                                       "tmpl": _redact_template(text)})
        scan(chat_id, "chat")
        return events, first_visible_ts, n_scanned, n_info

    def _bot_add_actor(self, chat_id):
        """(action, actor) of the LATEST membership change affecting the bot,
        or None. Structural first (cmd + ContactAddedRemoved + from_id);
        stock-text parse only as fallback for old cores. STRICT boundary:
        only events the core explicitly marks as affecting SELF count;
        unknown-affected events (core dropped the member param, or the add
        was trashed/silent) REFUSE - missing proof never becomes a serve.
        Every decision logs its basis; refusals dump the redacted evidence
        for the on-screen log."""
        events, first_ts, n_scanned, n_info = self._membership_scan(chat_id)
        strong = [e for e in events if e["affected"] == "self"]
        if strong:
            latest = max(strong, key=lambda e: (e["ts"], e["idx"]))
            log.info("chat %s membership: %s by %s (basis %s, %d scanned)",
                     chat_id, latest["action"],
                     latest["actor"] or "no-actor", latest["basis"],
                     n_scanned)
            return latest["action"], latest["actor"]
        log.info("chat %s membership: no attributable add (%d scanned, "
                 "%d other-info)", chat_id, n_scanned, n_info)
        for e in sorted(events, key=lambda e: (e["ts"], e["idx"]))[-4:]:
            log.info("chat %s ev %s affected=%s actor=%s tmpl=%r",
                     chat_id, e["action"], e["affected"],
                     "set" if e["actor"] else "none", e["tmpl"])
        return None

    def _serve_chat(self, chat):
        """Trust model: the QR is the boundary - ANY chat
        that arrives accepted under core semantics is served, whoever added
        the bot (contact requests are refused by the caller). No owner gate
        on chatting; management is app-side only.

        Accepts (once), generates the chat's own stable list id, persists it,
        builds the chat's isolated v6.1 stack.

        RE-ADD CONTINUITY: a chat the bot was removed from keeps its state
        entry (revoked) and its store file; re-serving the SAME core chat
        reuses the existing list id and store, so the group's list data and
        instance registrations continue - removal pauses access, it never
        resets the list. (Core chat ids are grpid-keyed and stable across
        remove/re-add: receive_imf.rs routes group messages via
        get_chat_id_by_grpid, reusing the existing chat row.)"""
        from deltachat_rpc_client import Chat
        st = self._st()
        chats = dict(st.get("chats", {}))
        key = str(chat.id)
        if key not in chats:
            try:
                Chat(self.account, chat.id).accept()
            except Exception as e:
                log.warning("accept failed for chat %s: %s", chat.id, e)
                return None
            chats[key] = {"list_id": "list-" + secrets.token_hex(8)}
            save_state(self.files_dir, chats=chats)
            log.info("now serving chat %s (accepted; isolated store "
                     "+ list %s)", chat.id, chats[key]["list_id"])
        elif chats[key].get("revoked"):
            chats[key] = {"list_id": chats[key]["list_id"]}
            save_state(self.files_dir, chats=chats)
            log.info("resumed serving chat %s (re-added; existing list %s "
                     "and store continue)", chat.id, chats[key]["list_id"])
        return self._runtime_for(chat.id, chats[key]["list_id"])

    @staticmethod
    def _ver_tuple(v):
        """(1,4,2) from "1.4.2"; None when unparseable (then no ordering
        claims are made - heal falls back to plain presence check)."""
        try:
            parts = [int(p) for p in str(v).strip().lstrip("v").split(".")]
            return tuple(parts) if parts else None
        except (TypeError, ValueError):
            return None

    def _maybe_heal(self, chat_id):
        """Lazy per-chat self-healing (idea adapted from the public
        PixelSocial bot; our machinery, NO delete-old and NO downgrade):

        If the app has a current published version (state.current_app, set
        by app-side publishing) and this SERVED chat has no registered
        instance of it, deliver the current .xdc into THIS chat only via the
        v6.1 headline flow (packed with the chat's own list id, registered
        bot-origin, FULL canonical ops replay - not PixelSocial's
        100-recent-posts approximation). Old instances are never deleted; a
        chat already on a NEWER version is left alone (no downgrade).
        """
        st = self._st()
        app = st.get("current_app") or {}
        version = app.get("version")
        if not version or chat_id not in self._served_chat_ids():
            return
        rt = self.runtimes.get(chat_id)
        if rt is None:
            return
        versions = [i.get("version") for i in rt.gate._instances.values()
                    if i.get("chat_id") == chat_id]
        if version in versions:
            return  # chat is current
        cur, want = self._ver_tuple(version), None
        for v in versions:
            tv = self._ver_tuple(v)
            if tv is not None and (want is None or tv > want):
                want = tv
        if cur is not None and want is not None and want > cur:
            log.info("chat %s on newer app v%s than current v%s; not healing",
                     chat_id, want, version)
            return
        path = os.path.join(self.files_dir, app.get("file", ""))
        if not os.path.exists(path):
            log.warning("current app file %r missing; cannot heal chat %s",
                        app.get("file"), chat_id)
            return
        from deltachat_rpc_client import Chat
        try:
            msg, sent = rt.gate.publish_and_restore(
                Chat(self.account, chat_id), path, rt.bridge.send_update)
            log.info("healed chat %s to app v%s (msg %s, %d ops replayed)",
                     chat_id, version, msg.id, sent)
        except Exception as e:
            log.warning("heal failed for chat %s: %s", chat_id, e)

    def _revoke_chat(self, chat_id):
        """The bot was removed from the chat (any actor) -> out of scope.
        PAUSES access only: the state entry is kept (marked revoked), the
        list id is preserved for a later re-add, and the chat's store file
        stays on disk untouched (the bot never deletes data)."""
        st = self._st()
        chats = dict(st.get("chats", {}))
        key = str(chat_id)
        if key in chats and not chats[key].get("revoked"):
            chats[key] = {"list_id": chats[key]["list_id"], "revoked": True}
            save_state(self.files_dir, chats=chats)
        self.runtimes.pop(chat_id, None)
        log.info("chat %s revoked: bot removed (list kept for re-add)",
                 chat_id)

    def _runtime_for(self, chat_id, list_id):
        rt = self.runtimes.get(chat_id)
        if rt is None:
            rt = ChatRuntime(self.account, self.files_dir, chat_id, list_id,
                             self.bot_addr)
            self.runtimes[chat_id] = rt
        return rt

    def boot_runtimes(self):
        """(Re)build stacks for all served chats; call after start_io."""
        st = self._st()
        # revoke pass: a served chat the bot has since been removed from
        # leaves scope before any runtime is rebuilt
        for key in list(st.get("chats", {}).keys()):
            membership = self._bot_add_actor(int(key))
            if membership and membership[0] == "removed":
                self._revoke_chat(int(key))
        # resume pass: a revoked chat the bot was re-added to while offline
        # (scan shows a fresh add) resumes with its preserved list id. When
        # the scan is silent, live traffic resumes it on the next message.
        for key, rec in list(self._st().get("chats", {}).items()):
            if rec.get("revoked"):
                membership = self._bot_add_actor(int(key))
                if membership and membership[0] == "added":
                    from deltachat_rpc_client import Chat as _C
                    self._serve_chat(_C(self.account, int(key)))
        # discovery pass: accepted group chats that arrived while the bot was
        # offline (requests refused here exactly as in live dispatch; 1:1 DMs
        # are served lazily on their next message). REVOKED chats are NOT
        # resumed here without evidence - they resume via the resume pass
        # above (scan shows a re-add) or on live traffic.
        known = set(self._st().get("chats", {}))
        for chat in self.account.get_chatlist():
            if self._chat_type(chat) == "Single":
                continue
            if str(chat.id) in known:
                continue
            if self._is_request(self.account, chat.id):
                continue
            self._serve_chat(chat)
        for key, rec in self._st().get("chats", {}).items():
            if rec.get("revoked"):
                continue
            rt = self._runtime_for(int(key), rec["list_id"])
            rt.bridge.reconcile_on_boot()  # needs IO; resumes downloads/publishes
            self._maybe_heal(int(key))  # catch up chats that missed a publish

    def _all_bridges(self):
        return list(self.runtimes.values())

    def on_status_update(self, event):
        for rt in self._all_bridges():
            rt.bridge.on_status_update(event)

    def on_msgs_changed(self, event):
        for rt in self._all_bridges():
            rt.bridge.on_msgs_changed(event)

    @staticmethod
    def _is_request(account, chat_id):
        from deltachat_rpc_client import Chat
        try:
            snap = Chat(account, chat_id).get_basic_snapshot()
            return bool(snap.get("is_contact_request"))
        except Exception:
            return True  # unknown -> treat as request -> ignore

    def on_new_message(self, event):
        snap = event.message_snapshot
        chat_id = snap.chat_id

        # 1) served chat traffic -> that chat's bridge only. An info
        #    message can also record the bot's removal: revoke live.
        if chat_id in self._served_chat_ids():
            rt = self.runtimes.get(chat_id)
            if rt is not None:
                rt.bridge.on_new_message(event)
                self._maybe_heal(chat_id)
            if snap.get("is_info"):
                membership = self._bot_add_actor(chat_id)
                if membership and membership[0] == "removed":
                    self._revoke_chat(chat_id)
            return

        # 2) contact requests are ignored: no ingest, no reply, no accept
        #    (core's own boundary - the only refusal left).
        if self._is_request(self.account, chat_id):
            return

        # 3) any accepted chat is served (trust model: the QR is
        #    the boundary - anyone holding it may chat with the bot, in a
        #    group or directly). Groups get their own shared list; a 1:1 DM
        #    gets a personal list. Every stack is isolated (own store, own
        #    list id): histories never mix across chats.
        for chat in self.account.get_chatlist():
            if chat.id == chat_id:
                rec = self._st().get("chats", {}).get(str(chat_id))
                if rec and rec.get("revoked"):
                    # REVIEWER GAP FIX: a revoked chat must not be resumed
                    # by a late in-flight straggler message. Access resumes
                    # only on a grounded rejoin signal - the latest self-add
                    # info message (the same evidence the boot resume pass
                    # uses). Core limit: a SILENT re-add (core adds self
                    # with no add-info message) carries no evidence, so the
                    # chat then stays paused until boot or a visible re-add.
                    membership = self._bot_add_actor(chat_id)
                    if not (membership and membership[0] == "added"):
                        log.info("chat %s revoked; straggler message ignored "
                                 "(no re-add evidence)", chat_id)
                        return
                rt = self._serve_chat(chat)
                if rt is not None:
                    rt.bridge.on_new_message(event)
                return

def _invite_watcher(files_dir, acct):
    """Answer the activity's INVITE_REQ with the SecureJoin setup-contact
    QR (SVG + text) of the RUNNING account - the only rpc owner. The QR
    text is a bearer invitation: never log it."""
    req = os.path.join(files_dir, INVITE_REQ)
    done = os.path.join(files_dir, INVITE_DONE)
    flag = os.path.join(files_dir, STOP_FLAG)
    while not os.path.exists(flag):
        time.sleep(1)
        if not os.path.exists(req):
            continue
        try:
            try:
                qr_text, svg = acct.get_qr_code_svg()
            except Exception:
                qr_text, svg = acct.get_qr_code(), None
            with open(os.path.join(files_dir, "invite.txt"), "w") as f:
                f.write(qr_text)
            if svg:
                with open(os.path.join(files_dir, "invite.svg"), "w") as f:
                    f.write(svg)
            with open(done, "w") as f:
                f.write(str(time.time()))
            log.info("invitation QR written on request (contents not logged)")
        except Exception as e:
            log.warning("invitation QR failed: %s", e)
        try:
            os.unlink(req)
        except OSError:
            pass


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _load_json(path):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return {}


def _app_publish(files_dir, control, name, request=None):
    """Fan the named .xdc (basename in the app-private dir) out to every
    SERVED (non-revoked) chat. Crash-resumable and retry-deduplicating BY
    FILE CONTENT:

    - PUBLISH_PROG records per-chat completion keyed by the file's sha256;
      a process crash mid-fanout resumes the remaining chats on the next
      run instead of starting over.
    - A user retry after a COMPLETED run (e.g. some chats errored) skips
      the chats already published for these exact bytes (PUBLISH_DONE is
      consulted the same way) and re-attempts only the rest.
    - The one remaining duplicate window is a crash between a chat's
      send_message and its progress write; that chat would get a second
      webxdc bubble. Data impact of even that: none - both instances carry
      the same packed list id, registration is additive, and the store's
      canonical-op dedup makes ops from either instance apply once.
    """
    result = {"published": {}, "errors": {}, "skipped": {},
              "file": name, "request": request}
    if (not name or "/" in name or name in (".", "..")
            or not name.lower().endswith(".xdc")):
        result["errors"]["*"] = "bad publish request name %r" % name
        return result
    path = os.path.join(files_dir, name)
    if not os.path.exists(path):
        result["errors"]["*"] = "no such file in app dir: %s" % name
        return result
    sha = _sha256(path)
    prog_path = os.path.join(files_dir, PUBLISH_PROG)
    done_path = os.path.join(files_dir, PUBLISH_DONE)
    prog = _load_json(prog_path)
    if prog.get("sha256") != sha:
        prog = {"sha256": sha, "file": name, "done": [], "request": request}
    if request is None:
        # crash resume: recover the ORIGINAL request token from the
        # checkpoint so the UI still correlates the final DONE to the
        # request it sent (a resumed run has no REQ file to re-read)
        request = prog.get("request")
        result["request"] = request
    elif prog.get("request") != request:
        prog["request"] = request
    already = set(prog.get("done", []))
    prior = _load_json(done_path)
    if prior.get("sha256") == sha:
        already |= set(prior.get("published", {}))
        result["skipped"] = {k: prior["published"][k]
                             for k in prior.get("published", {})}
    from deltachat_rpc_client import Chat
    for key, rec in control._st().get("chats", {}).items():
        if key in already or rec.get("revoked"):
            continue  # revoked = out of scope until re-added
        cid = int(key)
        try:
            rt = control._runtime_for(cid, rec["list_id"])
            msg, sent = rt.gate.publish_and_restore(
                Chat(control.account, cid), path, rt.bridge.send_update)
            result["published"][key] = {"msg_id": msg.id,
                                        "ops_replayed": sent}
            prog.setdefault("done", []).append(key)
            tmp = prog_path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(prog, f)
            os.replace(tmp, prog_path)  # atomic per-chat checkpoint
        except Exception as e:
            result["errors"][key] = str(e)
    result["sha256"] = sha
    if result["published"]:
        # latest-version tracking (idea from the public PixelSocial bot):
        # remember the app-side-published current version so chats that
        # missed it (new chat, revoked at the time, relay failure) can be
        # healed LAZILY per chat. The published file stays in files_dir.
        from delivery_bot import parse_manifest_version
        try:
            version = parse_manifest_version(path)
        except Exception as e:
            version = None
            log.warning("current-app version unreadable in %s: %s", name, e)
        if version:
            save_state(files_dir, current_app={
                "file": name, "sha256": sha, "version": version})
            result["current_version"] = version
    # NOTE: PUBLISH_PROG is deleted by the caller only AFTER the result is
    # durably written (checkpoint-before-delete ordering).
    return result


def _publish_watcher(files_dir, control):
    """App-side publishing: new app versions are publishable FROM THE
    ANDROID APP; all bot management is app-side.

    The activity copies the picked .xdc into the app-private files dir and
    writes PUBLISH_REQ containing its basename; the fan-out (_app_publish)
    delivers to every served chat and writes PUBLISH_DONE with a per-chat
    result JSON for the UI. An interrupted fan-out from a previous process
    (PUBLISH_PROG still present) is resumed once at startup.

    Security: the request file lives in the app-private dir, so only the app
    can trigger a fan-out. No chat-originated input can ever mass-publish:
    every gate has owner_addr=None, so its DM command surface refuses
    everyone."""
    req = os.path.join(files_dir, PUBLISH_REQ)
    done = os.path.join(files_dir, PUBLISH_DONE)
    prog_path = os.path.join(files_dir, PUBLISH_PROG)
    flag = os.path.join(files_dir, STOP_FLAG)
    _pending_prog = _load_json(prog_path)
    pending = (_pending_prog.get("file"), _pending_prog.get("request"))
    while not os.path.exists(flag):
        token = None
        if pending[0]:
            name, token = pending
            pending = (None, None)
        else:
            time.sleep(1)
            if not os.path.exists(req):
                continue
            try:
                with open(req) as f:
                    lines = f.read().splitlines()
                name = lines[0].strip() if lines else ""
                # optional second line: activity-generated request token, so
                # the UI can correlate PUBLISH_DONE to ITS request and never
                # shows a stale result as a fresh success
                token = lines[1].strip() if len(lines) > 1 else None
            except Exception:
                name = ""
            try:
                os.unlink(req)
            except OSError:
                pass
        result = _app_publish(files_dir, control, name, request=token)
        done_ok = False
        try:
            tmp = done + ".tmp"
            with open(tmp, "w") as f:
                json.dump(result, f)
            os.replace(tmp, done)  # DONE lands atomically BEFORE...
            done_ok = True
        except Exception as e:
            # keep PUBLISH_PROG: without a durable DONE the checkpoint is
            # the only record of the run, and the startup resume pass
            # re-attempts (already-published chats skip via prog["done"])
            log.warning("publish result write failed: %s; keeping PROG "
                        "for resume", e)
        if done_ok:
            try:
                os.unlink(prog_path)   # ...the progress checkpoint is deleted
            except OSError:
                pass
        log.info("app-side publish done: %d chat(s) ok, %d failed, %d skipped",
                 len(result["published"]), len(result["errors"]),
                 len(result["skipped"]))


def _watchdog(files_dir, rpc):
    """Real stop: Java writes STOP -> close rpc (breaks run_forever)."""
    flag = os.path.join(files_dir, STOP_FLAG)
    while True:
        time.sleep(2)
        if os.path.exists(flag):
            log.info("STOP flag seen - shutting down")
            try:
                rpc.close()
            except Exception:
                pass
            return


def run(native_lib_dir, files_dir):
    """Live loop. Provision required. Serves every accepted chat; management
    (invite QR, publishing) is app-side via the file request watchers."""
    from deltachat_rpc_client import Bot, EventType
    from deltachat_rpc_client.events import NewMessage, RawEvent

    st = load_state(files_dir)
    if not st.get("provisioned"):
        raise RuntimeError("not provisioned")

    lock = os.path.join(files_dir, RUN_LOCK)
    flag = os.path.join(files_dir, STOP_FLAG)
    for f in (lock, flag):
        try:
            os.unlink(f)
        except FileNotFoundError:
            pass
    with open(lock, "w") as f:
        f.write(str(os.getpid()))

    rpc, dc = _rpc(native_lib_dir, files_dir)
    try:
        bot_acct = _account(dc)
        control = ChatControl(bot_acct, files_dir)
        bot_acct.start_io()
        wd = threading.Thread(target=_watchdog, args=(files_dir, rpc),
                              daemon=True, name="bot-stop-watchdog")
        wd.start()
        iw = threading.Thread(target=_invite_watcher, args=(files_dir, bot_acct),
                              daemon=True, name="bot-invite-qr")
        iw.start()
        pw = threading.Thread(target=_publish_watcher, args=(files_dir, control),
                              daemon=True, name="bot-app-publish")
        pw.start()
        ctl_bot = Bot(bot_acct, hooks=[
            (control.on_new_message, NewMessage),
            (control.on_status_update, RawEvent(EventType.WEBXDC_STATUS_UPDATE)),
            (control.on_msgs_changed, RawEvent(EventType.MSGS_CHANGED)),
        ])
        control.boot_runtimes()  # needs IO (download retries)
        log.info("live bot running as %s (chats %s)",
                 control.bot_addr, sorted(control.runtimes))
        ctl_bot.run_forever()
    finally:
        try:
            rpc.close()
        finally:
            try:
                os.unlink(lock)
            except FileNotFoundError:
                pass


def service_entry(native_lib_dir, files_dir, mode):
    """Java entry point. mode: 'provision' | 'live'. Logs to files_dir/bot.log."""
    h = logging.FileHandler(os.path.join(files_dir, "bot.log"))
    h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    # one handler per file per process: the interpreter outlives entry calls
    if not any(getattr(x, "baseFilename", None) == h.baseFilename
               for x in root.handlers):
        root.addHandler(h)
    try:
        if mode == "provision":
            addr = provision(native_lib_dir, files_dir)
            log.info("PROVISION DONE addr=%s", addr)
        elif mode == "live":
            run(native_lib_dir, files_dir)
        elif mode == "export":
            # Activity stops the live service first; the quiescent DB is
            # exported here. The transfer code goes ONLY to EXPORT_RESULT
            # (app-private, read+deleted by the activity) - never logged.
            import migration
            path = migration.export_migration(
                native_lib_dir, files_dir,
                os.path.join(files_dir, "export-out"))
            log.info("EXPORT DONE bundle=%s (transfer code not logged)", path)
        else:
            raise ValueError("unknown mode %r" % mode)
    except Exception as e:
        log.exception("service_entry %s failed: %s", mode, e)
        raise
