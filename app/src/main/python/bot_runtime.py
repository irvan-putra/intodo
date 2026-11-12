"""LIVE bot runtime (scratch 0.3). NETWORK code path - separate from bot_validation.py.

TRUST MODEL:
  - Delta Chat's contact/invitation flow IS the trust boundary. The bot accepts
    chats it is added to and serves them. There is NO separate chat-approval
    ceremony (no /chats, /grant, /revoke).
  - The OWNER alone holds global control (publish, identity). Resolved on
    device with minimal friction: owner DMs the bot once, the sender appears
    in the UI (core-resolved address + name), the user taps to confirm WITH a
    control-scope warning. Candidates are only collected while unbound; no
    member of any chat can become or reset the owner.
  - LIST ISOLATION: each served chat gets its OWN OpsStore (ops-<id>.db), its
    OWN bot-generated stable list id, and its OWN v6.1 gate/bridge. Histories
    never mix across chats. An app-side owner binds identity via the app's
    0.4.2 binding banner; the banner binds identity only, old ops are not
    transferred (the bot's per-chat store starts empty; the owner can register
    an existing instance with v6.1's /instances + /register N DM commands).

Setup order (no deadlock): create account -> START the service (unbound
listening) -> owner DMs the bot -> confirm owner in the UI (warning shown)
-> restart -> owner DMs the .xdc -> every served chat gets its packed copy
published (v6.1 headline flow, per-chat). Conflicting pre-pinned bundles are
refused per chat by reviewed v6.1 code (pack_list_identity), never repinned.

Attachment downloads have ONE completion path: v6.1's own persisted
download reconciler (BridgeBot). The wrapper does NOT intercept attachments.

Custody: profile (bot's own keys) only in the app's private files dir;
android:allowBackup="false". Uninstall/wipe = identity loss: owner re-adds the
bot and re-publishes the current .xdc (current state lives on the owner's
devices). ops-*.db are the only full per-chat op histories.

Relay facts (public pages nine.testrun.org/info.html + /privacy.html):
free, no signup; 60 msgs/min; 700M storage; messages deleted latest 20 days
after arrival (transport, not archive).
"""
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
# state keys: provisioned, owner_addr, owner_name, owner_candidates,
#             chats {str(chat_id): {"list_id": str}}


def _same_addr(a, b):
    return bool(a) and bool(b) and a.strip().lower() == b.strip().lower()


def _parse_membership(text, bot_addr):
    """(action, affected, actor) from a membership system-message text.

    The client's parse_system_add_remove covers the third-person stock
    forms ("Member X added by Y", "You added member X", "Group left by
    Y") but NOT the SELF-perspective forms core 2.69 emits when the BOT is
    the affected member (stock_str.rs MsgAddedBy=180 / MsgAdded=182 /
    MsgRemovedBy=179 / MsgRemoved=181):
        "You were added by <actor>."     "You were added."
        "You were removed by <actor>."   "You were removed."
    The bot being added to a group is exactly the self case - this gap is
    what leaves the adder unresolved ('no
    owner-attributed add (None)'). actor=None means core did not record
    the adder (e.g. unencrypted group); the caller keeps refusing then.
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


def confirm_owner(files_dir, addr):
    """Device-UI confirmation of one DM-sender candidate as the owner."""
    st = load_state(files_dir)
    cand = [c for c in st.get("owner_candidates", []) if c.get("addr") == addr]
    if not cand:
        raise ValueError("not a recorded DM candidate: %r" % addr)
    save_state(files_dir, owner_addr=addr, owner_name=cand[0].get("name", ""))
    return addr


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
    """One isolated v6.1 stack per served chat: own store, id, gate, bridge."""

    def __init__(self, account, files_dir, chat_id, list_id, owner_addr, bot_addr):
        from ops_store import OpsStore
        from delivery_bot import DeliveryBot
        from bot import BridgeBot
        self.chat_id = chat_id
        store = OpsStore(os.path.join(files_dir, "ops-%d.db" % chat_id))
        self.gate = DeliveryBot(account, store, owner_addr=owner_addr,
                                allowed_chat_ids={chat_id}, list_id=list_id,
                                bot_addr=bot_addr)
        self.bridge = BridgeBot(account, store, self.gate)


class OwnerControl:
    """Wrapper owning trust decisions around the per-chat v6.1 stacks.

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

    def _dm_chats(self):
        """(chat_id, addr, display_name) of 1:1 chats, core-resolved."""
        out = []
        for chat in self.account.get_chatlist():
            if self._chat_type(chat) != "Single":
                continue
            contacts = chat.get_contacts()
            addr, cname = "", ""
            if contacts:
                snap0 = contacts[0].get_snapshot()
                addr = snap0.get("address", "") or ""
                cname = snap0.get("display_name", "") or ""
            out.append((chat.id, addr, cname))
        return out

    def _is_owner_dm(self, chat_id, owner_addr):
        for cid, addr, _n in self._dm_chats():
            if cid == chat_id and addr == owner_addr:
                return True
        return False

    def _served_chat_ids(self):
        return {int(k) for k in self._st().get("chats", {})}

    def _bot_add_actor(self, chat_id):
        """(action, actor) of the LATEST membership change affecting the bot.

        Parses the chat's info messages via _parse_membership. None when no
        such message is visible, e.g. the add predates the relay's
        retention window. Same inherited limit as the official client:
        system-message texts are core-generated English here."""
        from deltachat_rpc_client import Chat
        latest = None
        try:
            msgs = Chat(self.account, chat_id).get_messages()
        except Exception as e:
            log.warning("membership scan failed for chat %s: %s", chat_id, e)
            return None
        for m in msgs:
            try:
                snap = m.get_snapshot()
            except Exception:
                continue
            if not snap.get("is_info"):
                continue
            parsed = _parse_membership(snap.get("text", "") or "",
                                       self.bot_addr)
            if not parsed:
                continue
            action, affected, actor = parsed
            if affected and self.bot_addr and affected.lower() == self.bot_addr.lower():
                latest = (action, actor)
        return latest

    def _serve_chat(self, chat, owner):
        """Owner-attributed DeltaChat trust: serve ONLY chats the core shows
        the owner added the bot to (attributed member-add info message).

        Added by anyone else, or attribution lost to relay retention ->
        ignored: no accept, no ingest, no reply. Accepts (once), generates
        the chat's own stable list id, persists it, builds the chat's
        isolated v6.1 stack."""
        from deltachat_rpc_client import Chat
        st = self._st()
        chats = dict(st.get("chats", {}))
        key = str(chat.id)
        if key not in chats:
            membership = self._bot_add_actor(chat.id)
            if (not membership or membership[0] != "added"
                    or not _same_addr(membership[1], owner)):
                log.info("ignoring chat %s: no owner-attributed add (%s)",
                         chat.id, membership)
                return None
            try:
                Chat(self.account, chat.id).accept()
            except Exception as e:
                log.warning("accept failed for chat %s: %s", chat.id, e)
                return None
            chats[key] = {"list_id": "list-" + secrets.token_hex(8)}
            save_state(self.files_dir, chats=chats)
            log.info("now serving chat %s (owner-attributed; isolated store "
                     "+ list %s)", chat.id, chats[key]["list_id"])
        return self._runtime_for(chat.id, chats[key]["list_id"], owner)

    def _revoke_chat(self, chat_id):
        """Symmetric revoke: the owner removed the bot -> out of scope.
        Drops the runtime and the served binding; the chat's store stays on
        disk untouched (the bot never deletes data)."""
        st = self._st()
        chats = dict(st.get("chats", {}))
        if str(chat_id) in chats:
            del chats[str(chat_id)]
            save_state(self.files_dir, chats=chats)
        self.runtimes.pop(chat_id, None)
        log.info("chat %s revoked: owner removed the bot", chat_id)

    def _runtime_for(self, chat_id, list_id, owner):
        rt = self.runtimes.get(chat_id)
        if rt is None:
            rt = ChatRuntime(self.account, self.files_dir, chat_id, list_id,
                             owner, self.bot_addr)
            self.runtimes[chat_id] = rt
        return rt

    def boot_runtimes(self):
        """(Re)build stacks for all served chats; call after start_io."""
        st = self._st()
        owner = st.get("owner_addr")
        if not owner:
            return
        # complete owner pairing if the confirmed owner's DM is still pending
        from deltachat_rpc_client import Chat
        for cid, addr, _n in self._dm_chats():
            if addr == owner:
                try:
                    Chat(self.account, cid).accept()
                except Exception as e:
                    log.warning("owner DM accept failed: %s", e)
                break
        # revoke pass: a served chat the owner has since removed the bot
        # from leaves scope before any runtime is rebuilt
        for key in list(st.get("chats", {}).keys()):
            membership = self._bot_add_actor(int(key))
            if (membership and membership[0] == "removed"
                    and _same_addr(membership[1], owner)):
                self._revoke_chat(int(key))
        # discovery pass: owner-added groups that arrived while the bot was
        # offline or unbound (the attributed info message syncs with history)
        for chat in self.account.get_chatlist():
            if self._chat_type(chat) == "Single":
                continue
            if chat.id in self._served_chat_ids():
                continue
            self._serve_chat(chat, owner)
        for key, rec in self._st().get("chats", {}).items():
            rt = self._runtime_for(int(key), rec["list_id"], owner)
            rt.bridge.reconcile_on_boot()  # needs IO; resumes downloads/publishes

    # -- owner DM command scoping -------------------------------------------
    def _served_chat_by_name(self, name):
        from deltachat_rpc_client import Chat
        want = (name or "").strip().casefold()
        for cid in self._served_chat_ids():
            try:
                got = Chat(self.account, cid).get_basic_snapshot().get("name", "")
            except Exception:
                continue
            if str(got).casefold() == want:
                return cid
        return None

    def _owner_dm(self, event, snap, chat_id):
        """Fan out owner DM traffic with command scoping.

        Publishing (.xdc) fans out to every served chat by design (each gate
        packs its own list id). /instances fans out with a per-chat header
        so the owner can tell the listings apart. /register MUTATES trust
        and each gate numbers its own listing, so with more than one chat
        served it must be aimed: '/register <chat name> <n>' resolves the
        chat by name and reaches only that chat's gate; the unqualified
        form is refused with instructions instead of registering a wrong
        instance into every store."""
        from deltachat_rpc_client import Chat
        text = (snap.get("text") or "").strip()
        bridges = self._all_bridges()
        dm = Chat(self.account, chat_id)
        if text.startswith("/register") and len(bridges) > 1:
            parts = text.split()
            if len(parts) == 2 and parts[1].isdigit():
                dm.send_text(
                    "more than one chat is served; use "
                    "/register <chat name> <number> so the instance lands "
                    "in the right list")
                return
            if len(parts) >= 3 and parts[-1].isdigit():
                name = " ".join(parts[1:-1])
                cid = self._served_chat_by_name(name)
                if cid is None:
                    dm.send_text("no served chat named %r" % name)
                    return
                snap["text"] = "/register " + parts[-1]
                self.runtimes[cid].bridge.on_new_message(event)
                return
        if text == "/instances" and len(bridges) > 1:
            for rt in bridges:
                try:
                    name = Chat(self.account, rt.chat_id) \
                        .get_basic_snapshot().get("name", "")
                except Exception:
                    name = "chat %s" % rt.chat_id
                dm.send_text("— %s —" % name)
                rt.bridge.on_new_message(event)
            return
        for rt in bridges:
            # each gate publishes into ITS chat, packed with ITS list id;
            # conflicting pre-pinned bundles are refused by v6.1 per chat
            rt.bridge.on_new_message(event)

    # -- event entry (single Bot; wrapper first) ----------------------------
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
        from deltachat_rpc_client import Chat
        snap = event.message_snapshot
        sender = snap.message.get_sender_contact().get_snapshot().get("address", "")
        st = self._st()
        owner = st.get("owner_addr")
        chat_id = snap.chat_id

        # 1) owner DM control channel (publish .xdc, /instances, /register N).
        #    The UI confirm pairs the owner; if the DM is still a contact
        #    request, accepting it here completes the pairing.
        if owner and sender == owner:
            for cid, addr, _n in self._dm_chats():
                if cid == chat_id and addr == owner:
                    Chat(self.account, chat_id).accept()
                    self._owner_dm(event, snap, chat_id)
                    return

        # 2) served chat traffic -> that chat's bridge only. An info
        #    message can also be the owner removing the bot: revoke live.
        if chat_id in self._served_chat_ids():
            rt = self.runtimes.get(chat_id)
            if rt is not None:
                rt.bridge.on_new_message(event)
            if snap.get("is_info") and owner:
                membership = self._bot_add_actor(chat_id)
                if (membership and membership[0] == "removed"
                        and _same_addr(membership[1], owner)):
                    self._revoke_chat(chat_id)
            return

        # 3) contact requests are ignored: no ingest, no reply, no accept.
        #    (Group invites arrive accepted once the sender is an accepted/
        #    verified contact - e.g. the paired owner adding the bot.)
        if not owner and sender:
            # 3b) ...except: an UNBOUND bot records 1:1 senders as owner
            # candidates locally (no reply, no accept, no ingest) - whether
            # the chat is still a bare contact request or already verified
            # through the SecureJoin invitation QR.
            for cid, addr, cname in self._dm_chats():
                if cid == chat_id and addr == sender:
                    cands = {c["addr"]: c for c in st.get("owner_candidates", [])}
                    if sender not in cands:
                        cands[sender] = {"addr": sender, "name": cname}
                        save_state(self.files_dir,
                                   owner_candidates=list(cands.values()))
                        log.info("owner candidate recorded: %s (%s)",
                                 sender, cname)
                    break
        if self._is_request(self.account, chat_id):
            return

        # 4) an accepted group -> serve ONLY when the core attributes the
        #    bot's membership to the owner (see _serve_chat). Needs a bound
        #    owner: without one there is nobody to attribute against, and
        #    unbound pairing (3b) is the only thing the bot does.
        if not owner:
            return
        for chat in self.account.get_chatlist():
            if chat.id == chat_id and self._chat_type(chat) != "Single":
                rt = self._serve_chat(chat, owner)
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
    """Live loop. Provision required; owner may still be unbound - the bot
    then listens only for the owner DM and for being added to chats."""
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
        control = OwnerControl(bot_acct, files_dir)
        bot_acct.start_io()
        wd = threading.Thread(target=_watchdog, args=(files_dir, rpc),
                              daemon=True, name="bot-stop-watchdog")
        wd.start()
        iw = threading.Thread(target=_invite_watcher, args=(files_dir, bot_acct),
                              daemon=True, name="bot-invite-qr")
        iw.start()
        ctl_bot = Bot(bot_acct, hooks=[
            (control.on_new_message, NewMessage),
            (control.on_status_update, RawEvent(EventType.WEBXDC_STATUS_UPDATE)),
            (control.on_msgs_changed, RawEvent(EventType.MSGS_CHANGED)),
        ])
        control.boot_runtimes()  # needs IO (download retries)
        log.info("live bot running as %s (owner %s, chats %s)",
                 control.bot_addr, st.get("owner_addr"),
                 sorted(control.runtimes))
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
