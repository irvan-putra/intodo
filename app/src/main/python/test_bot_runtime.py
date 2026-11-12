"""Offline conformance + logic tests for the OwnerControl wrapper (0.3).

Two layers:
1. API CONFORMANCE against the installed deltachat_rpc_client 2.69.0: every
   client-surface call the wrapper makes must really exist - this is what
   killed earlier wrappers (fake API assumptions). Uses REAL Chat/Message/
   Contact classes over a fake RPC transport, so the rpc METHOD NAMES the
   client actually invokes are exercised too.
2. LOGIC: trust flow - candidates, owner pairing, request ignoring, per-chat
   isolation, one-completion-path downloads (wrapper must NOT intercept
   attachments).
"""
import json
import os
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bot_runtime as br


# ---------------------------------------------------------------- fakes
class FakeRpc:
    """Records calls; returns canned payloads by method name."""
    def __init__(self, canned=None):
        self.calls = []
        self.canned = canned or {}

    def __getattr__(self, name):
        def call(*args):
            self.calls.append((name, args))
            v = self.canned.get(name)
            return v(args) if callable(v) else v
        return call


class FakeAccount:
    """Fake account whose _rpc serves chat/contact data from its chat table,
    using the REAL rpc method names (get_basic_chat_info, get_chat_contacts,
    get_contact, accept_chat, misc_send_text_message...)."""
    def __init__(self, chats, addr="bot@nine.testrun.org", transports=None):
        self.id = 1
        # transports: EnteredLoginParam-shaped dicts, as core >= 2.53
        # multi-transport returns them; defaults to mirroring addr.
        # cid -> (cid, type, name, contacts, is_req, info_texts)
        # info_texts: [(msg_id, system-message text)] affecting membership
        self._chats = {c[0]: tuple(c) + ((),) * (6 - len(c)) for c in chats}
        self.addr = addr
        acct = self

        class _Rpc:
            def __init__(self):
                self.calls = []

            def get_basic_chat_info(self, aid, cid):
                c = acct._chats[cid]
                return {"id": cid, "name": c[2], "chat_type": c[1],
                        "is_contact_request": c[4]}

            def get_chat_contacts(self, aid, cid):
                return list(range(100, 100 + len(acct._chats[cid][3])))

            def get_contact(self, aid, contact_id):
                for c in acct._chats.values():
                    if len(c[3]) > contact_id - 100 >= 0:
                        return {"address": c[3][contact_id - 100][0],
                                "display_name": c[3][contact_id - 100][1]}
                return {"address": "", "display_name": ""}

            def get_message_ids(self, aid, cid, info_only, add_daymarker):
                return [mid for mid, _t in acct._chats[cid][5]]

            def get_message(self, aid, mid):
                for c in acct._chats.values():
                    for iid, text in c[5]:
                        if iid == mid:
                            return {"id": mid, "chat_id": c[0], "text": text,
                                    "is_info": True, "from_id": 999}
                raise KeyError(mid)

            def __getattr__(self, name):
                def call(*args):
                    self.calls.append((name, args))
                    return None
                return call

        self._rpc = _Rpc()
        self.transports = transports if transports is not None else [{"addr": addr}]

    def list_transports(self):
        return self.transports

    def get_config(self, key):
        assert key == "addr"
        return self.addr

    def get_chatlist(self, **kw):
        from deltachat_rpc_client import Chat
        return [Chat(self, cid) for cid in self._chats]


class FakeMsg:
    def __init__(self, sender_addr):
        self._addr = sender_addr

    def get_sender_contact(self):
        addr = self._addr

        class C:
            def get_snapshot(inner):
                return {"address": addr}

        # NOTE: the real Contact class has NO .address property
        # (get_snapshot().address only) - v6.1 bot.py:60 assumed it, now
        # fixed there with a tolerant fallback; this fake keeps the REAL
        # shape (snapshot only) so the fix is exercised.
        return C()


class FakeSnap(dict):
    def __getattr__(self, k):
        try:
            return self[k]
        except KeyError:
            raise AttributeError(k)


def event_for(chat_id, sender, text="", msg_id=1, view_type="text", file=None,
              is_info=False, acct=None):
    snap = FakeSnap(chat_id=chat_id, id=msg_id, text=text,
                    view_type=view_type, file=file, is_info=is_info,
                    message=FakeMsg(sender))
    if acct is not None:
        from deltachat_rpc_client import Chat
        snap["chat"] = Chat(acct, chat_id)  # gate replies land on rpc.send_msg
    return FakeSnap(message_snapshot=snap)


def fresh(tmp=None):
    d = tempfile.mkdtemp()
    return d


# ------------------------------------------------------- 1. API conformance
def test_api_conformance():
    import inspect
    from deltachat_rpc_client import (Account, Bot, Chat, Contact, DeltaChat,
                                      Message, Rpc)
    from deltachat_rpc_client.const import ChatType  # exact class name
    from deltachat_rpc_client.events import NewMessage, RawEvent
    from deltachat_rpc_client import EventType
    for cls, names in [
        (Account, ["get_chatlist", "get_config", "is_configured", "start_io",
                   "add_transport_from_qr", "list_transports", "get_qr_code",
                   "get_qr_code_svg"]),
        (DeltaChat, ["add_account", "get_all_accounts"]),
        (Chat, ["accept", "get_basic_snapshot", "get_contacts", "send_text"]),
        (Contact, ["get_snapshot"]),
        (Message, ["get_snapshot"]),
    ]:
        for n in names:
            assert hasattr(cls, n), "%s.%s missing" % (cls.__name__, n)
    assert hasattr(Rpc, "close") and not hasattr(Rpc, "stop"), "use close()"
    assert str(ChatType.SINGLE.value) == "Single"
    for e in ("WEBXDC_STATUS_UPDATE", "MSGS_CHANGED"):
        assert hasattr(EventType, e), e
    sys.path.insert(0, os.path.expanduser("~/webxdc-storage-bot"))
    from bot import BridgeBot
    for n in ["reconcile_on_boot", "on_new_message", "on_status_update",
              "on_msgs_changed"]:
        assert callable(getattr(BridgeBot, n)), n
    from delivery_bot import DeliveryBot
    params = set(inspect.signature(DeliveryBot.__init__).parameters)
    assert {"account", "store", "owner_addr", "allowed_chat_ids", "list_id",
            "bot_addr"} <= params
    print("api conformance ok")


# ------------------------------------------------------------------ logic
def test_state_and_binding():
    d = fresh()
    assert br.load_state(d) == {}
    br.save_state(d, provisioned=True, bot_addr="b@x")
    assert br.load_state(d)["bot_addr"] == "b@x"
    # read_list_identity
    p = os.path.join(d, "a.xdc")
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("index.html", "hi")
    assert br.read_list_identity(p) is None
    p2 = os.path.join(d, "b.xdc")
    with zipfile.ZipFile(p2, "w") as z:
        z.writestr("list.json", json.dumps({"list_id": "list-abc"}))
    assert br.read_list_identity(p2) == "list-abc"
    print("state/binding ok")


def test_confirm_owner():
    d = fresh()
    br.save_state(d, owner_candidates=[{"addr": "a@x", "name": "A"}])
    try:
        br.confirm_owner(d, "evil@x")
        raise SystemExit("should have refused")
    except ValueError:
        pass
    assert br.confirm_owner(d, "a@x") == "a@x"
    assert br.load_state(d)["owner_addr"] == "a@x"
    print("confirm_owner ok")


def _control(chats, state):
    d = fresh()
    br.save_state(d, **state)
    acct = FakeAccount(chats)
    return br.OwnerControl(acct, d), acct, d


def test_candidate_recording_and_request_ignore():
    # unbound bot; stranger DM arrives as a contact request
    chats = [(10, "Single", "", [("stranger@x", "Stranger")], True)]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(10, "stranger@x", "hi"))
    st = br.load_state(d)
    assert st["owner_candidates"] == [{"addr": "stranger@x", "name": "Stranger"}]
    # no accept, no reply happened
    assert not any(c[0] == "accept_chat" for c in acct._rpc.calls)
    assert not any(c[0] == "misc_send_text_message" for c in acct._rpc.calls)
    print("candidate/request ok")


OWNER_ADD = "Member bot@nine.testrun.org added by me@x."
OWNER_REMOVE = "Member bot@nine.testrun.org removed by me@x."
OTHER_ADD = "Member bot@nine.testrun.org added by someone@x."
# Self-perspective stock strings core 2.69 emits when the BOT is the
# affected member (stock_str.rs MsgAddedBy/MsgAdded/MsgRemovedBy/
# MsgRemoved) - the shapes handled by the parser extension ('no owner-attributed
# add (None)' otherwise):
SELF_ADD_NAMED = "You were added by Alex Example (me@x)."
SELF_ADD_PLAIN = "You were added by me@x."
SELF_ADD_NOACTOR = "You were added."
SELF_REMOVE = "You were removed by me@x."


def test_self_perspective_membership_parse():
    # the failure mode: core renders the bot's own add as
    # 'You were added by <actor>'; the client's parser returns None there
    from deltachat_rpc_client._utils import parse_system_add_remove
    assert parse_system_add_remove(SELF_ADD_PLAIN) is None  # the client gap
    p = br._parse_membership(SELF_ADD_PLAIN, "bot@nine.testrun.org")
    assert p == ("added", "bot@nine.testrun.org", "me@x"), p
    p = br._parse_membership(SELF_ADD_NAMED, "bot@nine.testrun.org")
    assert p == ("added", "bot@nine.testrun.org", "me@x"), p
    p = br._parse_membership(SELF_REMOVE, "bot@nine.testrun.org")
    assert p == ("removed", "bot@nine.testrun.org", "me@x"), p
    p = br._parse_membership(SELF_ADD_NOACTOR, "bot@nine.testrun.org")
    assert p == ("added", "bot@nine.testrun.org", None), p
    # third-person forms still route through the client parser unchanged
    p = br._parse_membership(OWNER_ADD, "bot@nine.testrun.org")
    assert p == ("added", "bot@nine.testrun.org", "me@x"), p
    print("self-perspective parse ok")


def test_self_add_serves_owner_group():
    # the live scenario: owner (from ArcaneChat) adds the bot; the ONLY
    # attribution is the self-perspective info message -> must be served
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (13, "Group", "Shared List", [], False, [(9001, SELF_ADD_NAMED)])]
    ctl, acct, d = _control(chats, {"provisioned": True, "owner_addr": "me@x"})
    ctl.on_new_message(event_for(13, "me@x", "hi bot"))
    assert "13" in br.load_state(d)["chats"]
    assert os.path.exists(os.path.join(d, "ops-13.db"))
    assert any(c[0] == "accept_chat" and c[1][1] == 13 for c in acct._rpc.calls)
    print("self-add served ok")


def test_self_add_without_actor_refused():
    # 'You were added.' records NO adder (e.g. unverified path): unverifiable
    # attribution stays refused - the security boundary is not dropped
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (13, "Group", "Mystery", [], False, [(9001, SELF_ADD_NOACTOR)])]
    ctl, acct, d = _control(chats, {"provisioned": True, "owner_addr": "me@x"})
    ctl.on_new_message(event_for(13, "someone@x", "hi bot"))
    assert "chats" not in br.load_state(d)
    assert not any(c[0] == "accept_chat" and c[1][1] == 13 for c in acct._rpc.calls)
    print("self-add no-actor refused ok")


def test_self_remove_revokes():
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (13, "Group", "Shared List", [], False, [(9001, SELF_ADD_PLAIN)])]
    ctl, acct, d = _control(chats, {"provisioned": True, "owner_addr": "me@x"})
    ctl.on_new_message(event_for(13, "me@x", "hi bot"))
    assert "13" in br.load_state(d)["chats"]
    acct._chats[13][5].append((9004, SELF_REMOVE))
    ctl.on_new_message(event_for(13, "me@x", SELF_REMOVE, msg_id=9004,
                                 is_info=True))
    assert "13" not in br.load_state(d).get("chats", {})
    assert os.path.exists(os.path.join(d, "ops-13.db"))  # data never deleted
    print("self-remove revoke ok")


def test_serve_accepted_group_and_isolation():
    # owner bound; OWNER added the bot to two ACCEPTED groups (attributed)
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)]),
             (78, "Group", "Other List", [], False, [(9002, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True, "owner_addr": "me@x"})
    ctl.on_new_message(event_for(77, "me@x", "hello bot"))
    st = br.load_state(d)
    assert set(st["chats"]) == {"77"}, st
    lid77 = st["chats"]["77"]["list_id"]
    assert lid77.startswith("list-")
    # the other group is NOT served from one event in 77
    assert os.path.exists(os.path.join(d, "ops-77.db"))
    assert not os.path.exists(os.path.join(d, "ops-78.db"))
    assert any(c[0] == "accept_chat" and c[1][1] == 77 for c in acct._rpc.calls)
    # second event in 78 -> separate store + different list id (isolation)
    ctl.on_new_message(event_for(78, "someone@x", "app op"))
    st = br.load_state(d)
    assert set(st["chats"]) == {"77", "78"}
    assert st["chats"]["78"]["list_id"] != lid77
    assert os.path.exists(os.path.join(d, "ops-78.db"))
    print("serve/isolation ok")


def test_group_request_ignored():
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (90, "Group", "Random", [], True)]  # still a request
    ctl, acct, d = _control(chats, {"provisioned": True, "owner_addr": "me@x"})
    ctl.on_new_message(event_for(90, "rando@x", "hello"))
    assert "chats" not in br.load_state(d)
    assert not any(c[0] == "accept_chat" and c[1][1] == 90 for c in acct._rpc.calls)
    print("group request ignored ok")


def test_owner_dm_fans_out_without_attachment_intercept():
    # owner DM carrying an .xdc with file==None (download pending): the
    # wrapper must FORWARD to bridges (v6.1 owns downloads), never intercept.
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True, "owner_addr": "me@x"})
    ctl.on_new_message(event_for(77, "me@x", "added"))
    assert "77" in br.load_state(d)["chats"]
    ev = event_for(5, "me@x", "", msg_id=42, view_type="webxdc", file=None)
    ctl.on_new_message(ev)
    # owner DM accepted; event forwarded to the chat-77 bridge; no pending_dm
    assert any(c[0] == "accept_chat" and c[1][1] == 5 for c in acct._rpc.calls)
    assert "pending_dm" not in br.load_state(d)
    print("owner dm / no-intercept ok")


def test_third_party_add_ignored():
    # bot added to an accepted group by SOMEONE ELSE -> not served
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (91, "Group", "Strangers", [], False, [(9003, OTHER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True, "owner_addr": "me@x"})
    ctl.on_new_message(event_for(91, "someone@x", "hi bot"))
    assert "chats" not in br.load_state(d)
    assert not any(c[0] == "accept_chat" and c[1][1] == 91 for c in acct._rpc.calls)
    print("third-party add ignored ok")


def test_missing_attribution_ignored():
    # accepted group, but the add info message is not visible (e.g. relay
    # retention) -> safe default: ignore, never infer from co-membership
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (92, "Group", "Old Group", [], False)]
    ctl, acct, d = _control(chats, {"provisioned": True, "owner_addr": "me@x"})
    ctl.on_new_message(event_for(92, "someone@x", "hi bot"))
    assert "chats" not in br.load_state(d)
    print("missing attribution ignored ok")


def test_unbound_bot_serves_nothing():
    # no owner bound yet: even an owner-attributed add cannot be verified
    # (no owner to attribute against) -> nothing is served or persisted
    chats = [(77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi bot"))
    assert "chats" not in br.load_state(d)
    print("unbound serves nothing ok")


def test_live_revoke_on_owner_remove():
    # served chat; owner removes the bot -> out of scope, store kept on disk
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True, "owner_addr": "me@x"})
    ctl.on_new_message(event_for(77, "me@x", "added"))
    assert "77" in br.load_state(d)["chats"]
    assert os.path.exists(os.path.join(d, "ops-77.db"))
    # the remove info message lands (and is now the latest membership change)
    acct._chats[77][5].append((9004, OWNER_REMOVE))
    ctl.on_new_message(event_for(77, "me@x", OWNER_REMOVE, msg_id=9004,
                                 is_info=True))
    assert "77" not in br.load_state(d).get("chats", {})
    # the store file survives: the bot never deletes data
    assert os.path.exists(os.path.join(d, "ops-77.db"))
    print("live revoke ok")


def test_boot_discovers_offline_owner_add():
    # owner added the bot while it was offline: on the next boot the
    # discovery pass in boot_runtimes serves it from synced history
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True, "owner_addr": "me@x"})
    for chat in acct.get_chatlist():
        if chat.id == 77:
            rt = ctl._serve_chat(chat, "me@x")
    assert "77" in br.load_state(d)["chats"]
    assert os.path.exists(os.path.join(d, "ops-77.db"))
    print("boot discovery ok")


def test_register_scoping_multichat():
    # two served chats: unqualified /register is refused with instructions;
    # '/register <chat name> <n>' reaches ONLY that chat's gate (one reply).
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)]),
             (78, "Group", "Other List", [], False, [(9002, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True, "owner_addr": "me@x"})
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    ctl.on_new_message(event_for(78, "me@x", "hi"))
    assert set(br.load_state(d)["chats"]) == {"77", "78"}
    acct._rpc.calls.clear()
    ctl.on_new_message(event_for(5, "me@x", "/register 1", acct=acct))
    wrapper = [c for c in acct._rpc.calls if c[0] == "misc_send_text_message"]
    gates = [c for c in acct._rpc.calls if c[0] == "send_msg"]
    assert len(wrapper) == 1 and "more than one chat" in wrapper[0][1][2]
    assert not gates, gates  # refused BEFORE reaching any gate
    acct._rpc.calls.clear()
    ctl.on_new_message(event_for(5, "me@x", "/register Shared List 1", acct=acct))
    wrapper = [c for c in acct._rpc.calls if c[0] == "misc_send_text_message"]
    gates = [c for c in acct._rpc.calls if c[0] == "send_msg"]
    # exactly one gate answered (its listing is empty -> "no instance #1");
    # the other chat's gate stayed silent
    assert not wrapper and len(gates) == 1, (wrapper, gates)
    assert "instance #1" in gates[0][1][2]["text"]
    acct._rpc.calls.clear()
    ctl.on_new_message(event_for(5, "me@x", "/register Nope 1", acct=acct))
    wrapper = [c for c in acct._rpc.calls if c[0] == "misc_send_text_message"]
    assert len(wrapper) == 1 and "no served chat named" in wrapper[0][1][2]
    print("register scoping ok")


def test_register_ingests_existing_history():
    """Migration proof: /register N ingests the instance's PRE-EXISTING
    updates immediately (no new update needed); a restart between register
    and ingest loses nothing; replay stays dedup-clean."""
    import base64
    sys.path.insert(0, os.path.expanduser("~/webxdc-storage-bot"))
    from delivery_bot import DeliveryBot
    from ops_store import OpsStore
    from deltachat_rpc_client import Message

    d = fresh()
    manifest = base64.b64encode(b'version = "3"').decode().rstrip("=")
    updates = [
        {"serial": 1, "payload": {"ops": [
            {"item": "i-1", "field": "title", "value": "old milk",
             "stamp": {"hlc": 100, "device": "alice"}}]}, "info": "added"},
        {"serial": 2, "payload": {"ops": [
            {"item": "i-1", "field": "done", "value": "true",
             "stamp": {"hlc": 101, "device": "alice"}}]}, "info": "done"},
    ]

    class Rpc:
        def get_chat_media(self, aid, cid, vt, a, b):
            return [501]
        def get_message(self, aid, mid):
            return {"id": mid, "chat_id": 77, "from_id": 100,
                    "viewtype": "Webxdc"}
        def get_contact(self, aid, cid):
            return {"address": "me@x"}
        def get_webxdc_blob(self, aid, mid, name):
            assert name == "manifest.toml"
            return manifest
        def get_webxdc_status_updates(self, aid, mid, serial):
            import json as J
            return J.dumps([u for u in updates if u["serial"] > serial])

    class Acct:
        id = 1
        _rpc = Rpc()

    acct = Acct()
    store = OpsStore(os.path.join(d, "ops-77.db"))
    gate = DeliveryBot(acct, store, owner_addr="me@x",
                       allowed_chat_ids=(77,), list_id="list-t",
                       bot_addr="bot@nine.testrun.org")
    assert gate.handle_private_message("me@x", "/instances") is not None
    reply = gate.handle_private_message("me@x", "/register 1")
    assert reply.startswith("instance #1 registered"), reply
    # nothing ingested by the gate itself...
    assert store.count() == 0
    # ...and the pending entry is a PEEK, not a destructive drain: a restart
    # after peeking but before ingesting still finds it
    assert gate.pending_ingest() == [501]
    gate_r = DeliveryBot(acct, store, owner_addr="me@x",
                         allowed_chat_ids=(77,), list_id="list-t",
                         bot_addr="bot@nine.testrun.org")
    assert gate_r.pending_ingest() == [501]  # survived the "crash"
    # the bridge's drain loop (bot.py): ingest, THEN ingest_done
    for mid in gate_r.pending_ingest():
        new = store.apply_updates(
            mid, Message(acct, mid).get_webxdc_status_updates(
                store.watermark(mid)))
        gate_r.ingest_done(mid)
    assert new == 2 and store.count() == 2, (new, store.count())
    assert store.watermark(501) == 2
    assert gate_r.pending_ingest() == []
    # crash AFTER ops commit but BEFORE ingest_done: entry survives, the
    # retry re-ingests from the watermark (dedup no-op), then clears
    gate3 = DeliveryBot(acct, store, owner_addr="me@x",
                        allowed_chat_ids=(77,), list_id="list-t",
                        bot_addr="bot@nine.testrun.org")
    assert gate3.pending_ingest() == []  # ingest_done persisted the clear
    print("register->ingest migration ok")


def test_register_ingest_crash_window():
    """Reviewer window: crash AFTER the ops committed but BEFORE ingest_done
    must lose nothing - the entry survives, the retry is a dedup no-op."""
    import base64
    sys.path.insert(0, os.path.expanduser("~/webxdc-storage-bot"))
    from delivery_bot import DeliveryBot
    from ops_store import OpsStore
    from deltachat_rpc_client import Message

    d = fresh()
    manifest = base64.b64encode(b'version = "3"').decode().rstrip("=")
    updates = [{"serial": 1, "payload": {"ops": [
        {"item": "i-1", "field": "title", "value": "x",
         "stamp": {"hlc": 100, "device": "a"}}]}, "info": "added"}]

    class Rpc:
        def get_chat_media(self, aid, cid, vt, a, b):
            return [501]
        def get_message(self, aid, mid):
            return {"id": mid, "chat_id": 77, "from_id": 100,
                    "viewtype": "Webxdc"}
        def get_contact(self, aid, cid):
            return {"address": "me@x"}
        def get_webxdc_blob(self, aid, mid, name):
            return manifest
        def get_webxdc_status_updates(self, aid, mid, serial):
            import json as J
            return J.dumps([u for u in updates if u["serial"] > serial])

    class Acct:
        id = 1
        _rpc = Rpc()

    acct = Acct()
    store = OpsStore(os.path.join(d, "ops-77.db"))
    gate = DeliveryBot(acct, store, owner_addr="me@x",
                       allowed_chat_ids=(77,), list_id="list-t",
                       bot_addr="bot@nine.testrun.org")
    gate.handle_private_message("me@x", "/instances")
    gate.handle_private_message("me@x", "/register 1")
    # ops commit... then "crash" before ingest_done (never called here)
    new = store.apply_updates(
        501, Message(acct, 501).get_webxdc_status_updates(store.watermark(501)))
    assert new == 1 and store.watermark(501) == 1
    # restart: the entry is still pending, retry re-runs from the watermark
    gate2 = DeliveryBot(acct, store, owner_addr="me@x",
                        allowed_chat_ids=(77,), list_id="list-t",
                        bot_addr="bot@nine.testrun.org")
    assert gate2.pending_ingest() == [501]
    new = store.apply_updates(
        501, Message(acct, 501).get_webxdc_status_updates(store.watermark(501)))
    assert new == 0 and store.count() == 1  # dedup no-op
    gate2.ingest_done(501)
    gate3 = DeliveryBot(acct, store, owner_addr="me@x",
                        allowed_chat_ids=(77,), list_id="list-t",
                        bot_addr="bot@nine.testrun.org")
    assert gate3.pending_ingest() == []

    # partial apply: serial 1 consumed, crash before serial 2 arrives;
    # retry completes the full history with no duplicate
    updates.append({"serial": 2, "payload": {"ops": [
        {"item": "i-2", "field": "title", "value": "y",
         "stamp": {"hlc": 101, "device": "a"}}]}, "info": "added"})
    gate4 = DeliveryBot(acct, store, owner_addr="me@x",
                        allowed_chat_ids=(77,), list_id="list-t",
                        bot_addr="bot@nine.testrun.org")
    gate4._pending_ingest.append(501)  # as if register had just happened
    gate4._persist()
    # crash point: watermark at 1 (from the first scenario), serial 2 unseen
    new = store.apply_updates(
        501, Message(acct, 501).get_webxdc_status_updates(store.watermark(501)))
    assert new == 1 and store.count() == 2 and store.watermark(501) == 2
    gate4.ingest_done(501)
    # belt: a second full retry from 0 stays dedup-clean
    new = store.apply_updates(
        501, Message(acct, 501).get_webxdc_status_updates(0))
    assert new == 0 and store.count() == 2
    print("pending-ingest crash window ok")



# --------------------------------------------------------------- provision
class _ProvRpc:
    def __init__(self):
        self.closed = False

    def close(self):
        self.closed = True


class _ProvAccount:
    """Fake account for provision(): the address is readable via the
    transport list (core multi-transport, "addr" key deprecated 2026-04)
    or via the legacy 'addr' config key."""
    def __init__(self, addr_via="transport", configured=False):
        self.addr_via = addr_via
        self.configured = configured
        self.qr = None

    def is_configured(self):
        return self.configured

    def add_transport_from_qr(self, qr):
        self.qr = qr
        self.configured = True

    def list_transports(self):
        if self.addr_via == "transport":
            return [{"addr": "bot-abc@nine.testrun.org"}]
        return []

    def get_config(self, key):
        assert key == "addr"
        return "legacy@x" if self.addr_via == "config" else None


class _ProvDC:
    def __init__(self, accounts):
        self.accounts = list(accounts)
        self.added = 0

    def get_all_accounts(self):
        return list(self.accounts)

    def add_account(self):
        a = _ProvAccount()
        self.accounts.append(a)
        self.added += 1
        return a


def _run_provision(dc):
    d = fresh()
    rpc = _ProvRpc()
    orig = br._rpc
    br._rpc = lambda *a, **k: (rpc, dc)
    try:
        addr = br.provision("/nonexistent", d)
    finally:
        br._rpc = orig
    return addr, d, rpc


def test_provision_fresh_reads_transport_addr():
    dc = _ProvDC([])
    addr, d, rpc = _run_provision(dc)
    assert addr == "bot-abc@nine.testrun.org"
    assert dc.added == 1
    assert dc.accounts[0].qr == "dcaccount:" + br.RELAY
    st = br.load_state(d)
    assert st["provisioned"] is True and st["bot_addr"] == addr
    assert rpc.closed
    print("provision fresh ok")


def test_provision_existing_account_reused():
    # The on-device recovery case: the account already configured by an
    # earlier tap is reused, and its address comes from the transport list.
    dc = _ProvDC([_ProvAccount(configured=True)])
    addr, d, _rpc = _run_provision(dc)
    assert addr == "bot-abc@nine.testrun.org"
    assert dc.added == 0
    assert br.load_state(d)["bot_addr"] == addr
    print("provision existing ok")


def test_provision_refuses_unconfigured_profile():
    # Uncertain phone state: a profile exists but is_configured() is False.
    # Re-onboarding would mint a NEW relay identity - refuse with a
    # diagnostic instead. No mutation: no add_account, no QR run, no state.
    acct = _ProvAccount(configured=False)
    dc = _ProvDC([acct])
    orig = br._rpc
    br._rpc = lambda *a, **k: (_ProvRpc(), dc)
    d = fresh()
    try:
        try:
            br.provision("/nonexistent", d)
            raise SystemExit("should have raised")
        except RuntimeError as e:
            assert "refusing to re-onboard" in str(e)
            assert "1 existing profile" in str(e)
    finally:
        br._rpc = orig
    assert dc.added == 0, "no new profile"
    assert acct.qr is None, "no re-onboarding, no new relay identity"
    assert "provisioned" not in br.load_state(d), "no false provisioned state"
    print("provision unconfigured-profile refusal ok")


def test_provision_legacy_addr_fallback():
    dc = _ProvDC([_ProvAccount(addr_via="config", configured=True)])
    addr, _d, _rpc = _run_provision(dc)
    assert addr == "legacy@x"
    print("provision legacy fallback ok")


def test_provision_fails_loudly_without_address():
    dc = _ProvDC([_ProvAccount(addr_via=None, configured=True)])
    d_holder = []
    orig = br._rpc
    br._rpc = lambda *a, **k: (_ProvRpc(), dc)
    d = fresh()
    try:
        try:
            br.provision("/nonexistent", d)
            raise SystemExit("should have raised")
        except RuntimeError as e:
            assert "address" in str(e)
    finally:
        br._rpc = orig
    assert "provisioned" not in br.load_state(d), "no false provisioned state"
    print("provision loud failure ok")


def test_control_reads_transport_addr():
    d = fresh()
    acct = FakeAccount([], addr=None,
                       transports=[{"addr": "bot-abc@nine.testrun.org"}])
    ctl = br.OwnerControl(acct, d)
    assert ctl.bot_addr == "bot-abc@nine.testrun.org"
    print("control transport addr ok")


def test_candidate_recorded_from_verified_dm():
    # unbound bot; sender arrives on an ALREADY-ACCEPTED (SecureJoin
    # verified) 1:1 chat - not a contact request
    chats = [(11, "Single", "", [("joiner@x", "Joiner")], False)]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(11, "joiner@x", "hi"))
    st = br.load_state(d)
    assert st["owner_candidates"] == [{"addr": "joiner@x", "name": "Joiner"}]
    assert not any(c[0] == "accept_chat" for c in acct._rpc.calls)
    assert not any(c[0] == "misc_send_text_message" for c in acct._rpc.calls)
    print("candidate/verified-dm ok")


def test_invite_watcher_writes_qr():
    import threading as _th, time as _ti
    d = fresh()

    class Acct:
        def get_qr_code_svg(self):
            return ("OPENPGP4FPR:FPR#a=bot%40nine.testrun.org", "<svg>qr</svg>")

    t = _th.Thread(target=br._invite_watcher, args=(d, Acct()), daemon=True)
    t.start()
    open(os.path.join(d, br.INVITE_REQ), "w").close()
    for _ in range(50):
        if os.path.exists(os.path.join(d, br.INVITE_DONE)):
            break
        _ti.sleep(0.1)
    assert open(os.path.join(d, "invite.svg")).read() == "<svg>qr</svg>"
    assert "OPENPGP4FPR:" in open(os.path.join(d, "invite.txt")).read()
    assert not os.path.exists(os.path.join(d, br.INVITE_REQ))
    open(os.path.join(d, br.STOP_FLAG), "w").close()
    t.join(timeout=5)
    assert not t.is_alive(), "watcher exits on STOP flag"
    print("invite watcher ok")


def test_invite_watcher_text_fallback():
    import threading as _th, time as _ti
    d = fresh()

    class Acct:
        def get_qr_code_svg(self):
            raise RuntimeError("no svg")

        def get_qr_code(self):
            return "OPENPGP4FPR:FPR#a=bot%40nine.testrun.org"

    t = _th.Thread(target=br._invite_watcher, args=(d, Acct()), daemon=True)
    t.start()
    open(os.path.join(d, br.INVITE_REQ), "w").close()
    for _ in range(50):
        if os.path.exists(os.path.join(d, br.INVITE_DONE)):
            break
        _ti.sleep(0.1)
    assert "OPENPGP4FPR:" in open(os.path.join(d, "invite.txt")).read()
    assert not os.path.exists(os.path.join(d, "invite.svg"))
    open(os.path.join(d, br.STOP_FLAG), "w").close()
    t.join(timeout=5)
    print("invite watcher fallback ok")


if __name__ == "__main__":
    test_api_conformance()
    test_state_and_binding()
    test_confirm_owner()
    test_candidate_recording_and_request_ignore()
    test_serve_accepted_group_and_isolation()
    test_group_request_ignored()
    test_owner_dm_fans_out_without_attachment_intercept()
    test_third_party_add_ignored()
    test_missing_attribution_ignored()
    test_self_perspective_membership_parse()
    test_self_add_serves_owner_group()
    test_self_add_without_actor_refused()
    test_self_remove_revokes()
    test_unbound_bot_serves_nothing()
    test_live_revoke_on_owner_remove()
    test_boot_discovers_offline_owner_add()
    test_register_scoping_multichat()
    test_register_ingests_existing_history()
    test_register_ingest_crash_window()
    test_provision_fresh_reads_transport_addr()
    test_provision_existing_account_reused()
    test_provision_refuses_unconfigured_profile()
    test_candidate_recorded_from_verified_dm()
    test_invite_watcher_writes_qr()
    test_invite_watcher_text_fallback()
    test_provision_legacy_addr_fallback()
    test_provision_fails_loudly_without_address()
    test_control_reads_transport_addr()
    print("ALL WRAPPER TESTS PASS")
