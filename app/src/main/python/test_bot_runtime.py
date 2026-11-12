"""Offline conformance + logic tests for the ChatControl wrapper (0.3 r12).

Two layers:
1. API CONFORMANCE against the installed deltachat_rpc_client 2.69.0: every
   client-surface call the wrapper makes must really exist - this is what
   killed earlier wrappers (fake API assumptions). Uses REAL Chat/Message/
   Contact classes over a fake RPC transport, so the rpc METHOD NAMES the
   client actually invokes are exercised too.
2. LOGIC: r12 trust model - ANY accepted chat is
   served (group -> shared list, DM -> personal list), no owner identity
   anywhere, requests refused, per-chat isolation, removal revokes,
   app-side publishing via the PUBLISH_REQ file, one-completion-path
   downloads (wrapper must NOT intercept attachments).
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
    # NOTE: import the LOCAL v6.1 copies - the ones the APK actually ships
    # (an earlier revision inserted ~/webxdc-storage-bot here, which shadowed
    # the shipped modules with the old checkout in every logic test).
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


def _control(chats, state):
    d = fresh()
    br.save_state(d, **state)
    acct = FakeAccount(chats)
    return br.ChatControl(acct, d), acct, d


def test_contact_request_ignored():
    # a stranger DM arriving as a CONTACT REQUEST: no accept, no reply,
    # no serving - core's boundary is the only refusal left
    chats = [(10, "Single", "", [("stranger@x", "Stranger")], True)]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(10, "stranger@x", "hi"))
    assert "chats" not in br.load_state(d)
    assert not any(c[0] == "accept_chat" for c in acct._rpc.calls)
    assert not any(c[0] == "misc_send_text_message" for c in acct._rpc.calls)
    print("request ignored ok")


OWNER_ADD = "Member bot@nine.testrun.org added by me@x."
OWNER_REMOVE = "Member bot@nine.testrun.org removed by me@x."
OTHER_ADD = "Member bot@nine.testrun.org added by someone@x."
# Self-perspective stock strings core 2.69 emits when the BOT is the
# affected member (stock_str.rs MsgAddedBy/MsgAdded/MsgRemovedBy/
# MsgRemoved):
SELF_ADD_NAMED = "You were added by Alex Example (AC edition) (me@x)."
SELF_ADD_PLAIN = "You were added by me@x."
SELF_ADD_NOACTOR = "You were added."
SELF_REMOVE = "You were removed by me@x."


def test_self_perspective_membership_parse():
    # the phone's exact failure: core renders the bot's own add as
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


def test_self_add_serves_group():
    # the live phone scenario: the ONLY record of the add is the
    # self-perspective info message -> still served (no gating on adds)
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (13, "Group", "Shared List", [], False, [(9001, SELF_ADD_NAMED)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(13, "me@x", "hi bot"))
    assert "13" in br.load_state(d)["chats"]
    assert os.path.exists(os.path.join(d, "ops-13.db"))
    assert any(c[0] == "accept_chat" and c[1][1] == 13 for c in acct._rpc.calls)
    print("self-add served ok")


def test_self_add_without_actor_served():
    # 'You were added.' records NO adder (e.g. unverified path) - irrelevant
    # under the r12 trust model: chatting needs no attribution; the QR is
    # the boundary and isolation is per chat
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (13, "Group", "Mystery", [], False, [(9001, SELF_ADD_NOACTOR)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(13, "someone@x", "hi bot"))
    assert "13" in br.load_state(d)["chats"]
    assert os.path.exists(os.path.join(d, "ops-13.db"))
    assert any(c[0] == "accept_chat" and c[1][1] == 13 for c in acct._rpc.calls)
    print("self-add no-actor served ok")


def test_self_remove_revokes():
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (13, "Group", "Shared List", [], False, [(9001, SELF_ADD_PLAIN)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(13, "me@x", "hi bot"))
    assert "13" in br.load_state(d)["chats"]
    acct._chats[13][5].append((9004, SELF_REMOVE))
    ctl.on_new_message(event_for(13, "me@x", SELF_REMOVE, msg_id=9004,
                                 is_info=True))
    # revoke PAUSES: binding kept (marked revoked), store file untouched
    rec = br.load_state(d)["chats"]["13"]
    assert rec.get("revoked") is True and rec["list_id"].startswith("list-")
    assert os.path.exists(os.path.join(d, "ops-13.db"))  # data never deleted
    assert 13 not in ctl._served_chat_ids()
    print("self-remove revoke ok")


def test_readd_resumes_same_list_and_store():
    # REVIEWER GAP FIX: remove + re-add the SAME group must continue the
    # existing list/store, not reset it. Core chat ids are grpid-keyed and
    # stable across remove/re-add (receive_imf.rs get_chat_id_by_grpid).
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (13, "Group", "Shared List", [], False, [(9001, SELF_ADD_PLAIN)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(13, "me@x", "hi bot"))
    lid = br.load_state(d)["chats"]["13"]["list_id"]
    acct._chats[13][5].append((9004, SELF_REMOVE))
    ctl.on_new_message(event_for(13, "me@x", SELF_REMOVE, msg_id=9004,
                                 is_info=True))
    assert 13 not in ctl._served_chat_ids()
    # re-added: new traffic resumes with the SAME list id (and store file,
    # so bind_list's set-once guard is not tripped and registrations live on)
    acct._chats[13][5].append((9005, SELF_ADD_PLAIN))
    ctl.on_new_message(event_for(13, "me@x", "back again", msg_id=9006))
    rec = br.load_state(d)["chats"]["13"]
    assert not rec.get("revoked")
    assert rec["list_id"] == lid, (rec["list_id"], lid)
    assert 13 in ctl._served_chat_ids()
    print("re-add continuity ok")


def test_serve_accepted_group_and_isolation():
    # two ACCEPTED groups the bot was added to: each is served with its OWN
    # store and list id - histories never mix across chats
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)]),
             (78, "Group", "Other List", [], False, [(9002, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
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
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(90, "rando@x", "hello"))
    assert "chats" not in br.load_state(d)
    assert not any(c[0] == "accept_chat" and c[1][1] == 90 for c in acct._rpc.calls)
    print("group request ignored ok")


def test_chat_publish_impossible():
    # r12 SECURITY: an .xdc sent TO the bot in any chat (DM or group) must
    # NEVER trigger publishing - publishing is app-side only. The DM still
    # gets served as a personal list, but no gate registers anything.
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "added"))
    assert "77" in br.load_state(d)["chats"]
    ev = event_for(5, "me@x", "", msg_id=42, view_type="webxdc", file=None)
    ctl.on_new_message(ev)
    # the DM is served (personal list) but NOTHING was published/registered
    assert "5" in br.load_state(d)["chats"]
    for rt in ctl.runtimes.values():
        assert rt.gate._instances == {}, rt.chat_id
        assert rt.gate.incomplete_publishes() == {}
    print("chat publish impossible ok")


def test_third_party_add_served():
    # bot added to an accepted group by SOMEONE ELSE -> served with its own
    # isolated store/list (any group may chat; histories never mix)
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (91, "Group", "Strangers", [], False, [(9003, OTHER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(91, "someone@x", "hi bot"))
    st = br.load_state(d)
    assert "91" in st["chats"]
    assert os.path.exists(os.path.join(d, "ops-91.db"))
    assert any(c[0] == "accept_chat" and c[1][1] == 91 for c in acct._rpc.calls)
    print("third-party add served ok")


def test_missing_attribution_served():
    # accepted group whose add info message is not visible (relay retention /
    # trash path - the exact phone failure on chat 13) -> still served:
    # no inference needed because chatting is not owner-gated at all
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (92, "Group", "Old Group", [], False)]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(92, "someone@x", "hi bot"))
    assert "92" in br.load_state(d)["chats"]
    assert os.path.exists(os.path.join(d, "ops-92.db"))
    print("missing attribution served ok")


def test_serves_without_any_owner_state():
    # there is no owner concept at all: a bare provisioned state serves
    chats = [(77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi bot"))
    assert "77" in br.load_state(d)["chats"]
    assert os.path.exists(os.path.join(d, "ops-77.db"))
    print("unbound serves chat ok")


def test_dm_personal_list_served():
    # an accepted 1:1 DM gets its own personal list + isolated store
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (6, "Single", "", [("friend@x", "Friend")], False)]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(6, "friend@x", "hi bot"))
    st = br.load_state(d)
    assert "6" in st["chats"]
    assert st["chats"]["6"]["list_id"].startswith("list-")
    assert os.path.exists(os.path.join(d, "ops-6.db"))
    print("dm personal list ok")


def test_every_dm_gets_personal_list():
    # no privileged DM exists: two accepted DMs -> two isolated personal lists
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (6, "Single", "", [("friend@x", "Friend")], False)]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(5, "me@x", "hello"))
    ctl.on_new_message(event_for(6, "friend@x", "hi"))
    st = br.load_state(d)
    assert set(st["chats"]) == {"5", "6"}
    assert st["chats"]["5"]["list_id"] != st["chats"]["6"]["list_id"]
    assert os.path.exists(os.path.join(d, "ops-5.db"))
    assert os.path.exists(os.path.join(d, "ops-6.db"))
    print("every dm personal list ok")


OTHER_REMOVE = "Member bot@nine.testrun.org removed by someone@x."


def test_live_revoke_on_any_removal():
    # served chat; ANY member removes the bot -> out of scope, store kept
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "added"))
    assert "77" in br.load_state(d)["chats"]
    assert os.path.exists(os.path.join(d, "ops-77.db"))
    # the remove info message lands (third-party actor: any removal counts)
    acct._chats[77][5].append((9004, OTHER_REMOVE))
    ctl.on_new_message(event_for(77, "someone@x", OTHER_REMOVE, msg_id=9004,
                                 is_info=True))
    rec = br.load_state(d)["chats"]["77"]
    assert rec.get("revoked") is True
    assert 77 not in ctl._served_chat_ids()
    # the store file survives: the bot never deletes data
    assert os.path.exists(os.path.join(d, "ops-77.db"))
    print("live revoke ok")


def test_boot_discovers_offline_add():
    # the bot was added while offline: on the next boot the discovery pass
    # in boot_runtimes serves it from synced history (no attribution needed)
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    for chat in acct.get_chatlist():
        if chat.id == 77:
            rt = ctl._serve_chat(chat)
    assert "77" in br.load_state(d)["chats"]
    assert os.path.exists(os.path.join(d, "ops-77.db"))
    print("boot discovery ok")


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
    ctl = br.ChatControl(acct, d)
    assert ctl.bot_addr == "bot-abc@nine.testrun.org"
    print("control transport addr ok")


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


def test_app_publish_watcher():
    # the app-side publish IPC: PUBLISH_REQ(basename) -> fan-out to every
    # served chat via the gate's publish_and_restore -> PUBLISH_DONE JSON.
    import threading as _th, time as _ti
    chats = [(77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)]),
             (78, "Group", "Other List", [], False, [(9002, OTHER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    ctl.on_new_message(event_for(78, "someone@x", "hi"))
    assert set(br.load_state(d)["chats"]) == {"77", "78"}
    open(os.path.join(d, "v2.xdc"), "w").write("fake xdc bytes")
    with open(os.path.join(d, br.PUBLISH_REQ), "w") as f:
        f.write("v2.xdc")
    calls = []
    import delivery_bot
    orig = delivery_bot.DeliveryBot.publish_and_restore

    class M:
        id = 4242

    def fake_publish(self, chat, path, send_update, note=""):
        calls.append((chat.id, os.path.basename(path)))
        return M(), 3

    delivery_bot.DeliveryBot.publish_and_restore = fake_publish
    try:
        t = _th.Thread(target=br._publish_watcher, args=(d, ctl), daemon=True)
        t.start()
        for _ in range(200):
            if os.path.exists(os.path.join(d, br.PUBLISH_DONE)):
                break
            _ti.sleep(0.05)
        open(os.path.join(d, br.STOP_FLAG), "w").close()
        t.join(timeout=5)
    finally:
        delivery_bot.DeliveryBot.publish_and_restore = orig
    assert sorted(calls) == [(77, "v2.xdc"), (78, "v2.xdc")], calls
    res = json.load(open(os.path.join(d, br.PUBLISH_DONE)))
    assert sorted(res["published"]) == ["77", "78"], res
    assert res["errors"] == {}, res
    assert res["published"]["77"] == {"msg_id": 4242, "ops_replayed": 3}
    print("app publish watcher ok")


def test_app_publish_bad_request():
    # a malformed / missing publish request yields an error JSON, no fan-out
    import threading as _th, time as _ti
    ctl, acct, d = _control([], {"provisioned": True})
    with open(os.path.join(d, br.PUBLISH_REQ), "w") as f:
        f.write("../escape.xdc")
    t = _th.Thread(target=br._publish_watcher, args=(d, ctl), daemon=True)
    t.start()
    for _ in range(200):
        if os.path.exists(os.path.join(d, br.PUBLISH_DONE)):
            break
        _ti.sleep(0.05)
    open(os.path.join(d, br.STOP_FLAG), "w").close()
    t.join(timeout=5)
    res = json.load(open(os.path.join(d, br.PUBLISH_DONE)))
    assert res["published"] == {} and res["errors"], res
    print("bad publish request ok")


def test_app_publish_retry_dedup_and_resume():
    # crash/retry semantics: a chat already published for the SAME file bytes
    # is never re-published; errored chats are re-attempted; a PUBLISH_PROG
    # from a crashed process is honored.
    chats = [(77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)]),
             (78, "Group", "Other List", [], False, [(9002, OTHER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    ctl.on_new_message(event_for(78, "someone@x", "hi"))
    open(os.path.join(d, "v2.xdc"), "w").write("fake xdc bytes")
    calls = []
    import delivery_bot

    class M:
        id = 4242

    def fake_publish(self, chat, path, send_update, note=""):
        calls.append(chat.id)
        if chat.id == 78:
            raise RuntimeError("relay down")
        return M(), 1

    orig = delivery_bot.DeliveryBot.publish_and_restore
    delivery_bot.DeliveryBot.publish_and_restore = fake_publish
    try:
        r1 = br._app_publish(d, ctl, "v2.xdc")  # 77 ok, 78 errors
        assert r1["published"].keys() == {"77"} and r1["errors"].keys() == {"78"}
        # persist DONE as the watcher would
        with open(os.path.join(d, br.PUBLISH_DONE), "w") as f:
            json.dump(r1, f)
        calls.clear()
        r2 = br._app_publish(d, ctl, "v2.xdc")  # retry: 77 skipped, 78 retried
        assert calls == [78], calls
        assert "77" in r2["skipped"] and r2["errors"].keys() == {"78"}
        # crash-resume: PROG with 77 done -> only 78 attempted
        calls.clear()
        with open(os.path.join(d, br.PUBLISH_PROG), "w") as f:
            json.dump({"sha256": r1["sha256"], "file": "v2.xdc",
                       "done": ["77"]}, f)
        os.unlink(os.path.join(d, br.PUBLISH_DONE))
        r3 = br._app_publish(d, ctl, "v2.xdc")
        assert calls == [78], calls
        # PROG deletion is the WATCHER's job, after the result lands (the
        # checkpoint must outlive the fan-out, not die inside it)
        assert os.path.exists(os.path.join(d, br.PUBLISH_PROG))
        # different bytes -> fresh fan-out (no skip)
        calls.clear()
        open(os.path.join(d, "v3.xdc"), "w").write("different bytes")
        r4 = br._app_publish(d, ctl, "v3.xdc")
        assert sorted(calls) == [77, 78], calls
    finally:
        delivery_bot.DeliveryBot.publish_and_restore = orig
    print("publish retry/resume ok")


def test_publish_skips_revoked_and_correlates():
    # a revoked (bot-removed) chat is out of scope for fan-out; the result
    # echoes the request token so the UI never shows a stale DONE as success
    chats = [(77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)]),
             (78, "Group", "Old List", [], False, [(9002, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    ctl.on_new_message(event_for(78, "me@x", "hi"))
    ctl._revoke_chat(78)
    open(os.path.join(d, "v2.xdc"), "w").write("fake xdc bytes")
    calls = []
    import delivery_bot

    class M:
        id = 4242

    def fake_publish(self, chat, path, send_update, note=""):
        calls.append(chat.id)
        return M(), 0

    orig = delivery_bot.DeliveryBot.publish_and_restore
    delivery_bot.DeliveryBot.publish_and_restore = fake_publish
    try:
        res = br._app_publish(d, ctl, "v2.xdc", request="req-123")
    finally:
        delivery_bot.DeliveryBot.publish_and_restore = orig
    assert calls == [77], calls
    assert res["request"] == "req-123" and res["file"] == "v2.xdc"
    assert sorted(res["published"]) == ["77"]
    print("publish revoked-skip + correlation ok")





def _fake_publish_recording(calls, register=True):
    import delivery_bot

    class M:
        id = 5555

    def fake(self, chat, path, send_update, note=""):
        calls.append((chat.id, os.path.basename(path)))
        if register:  # real flow registers the sent instance with its version
            from delivery_bot import parse_manifest_version
            self._instances[M.id + len(calls)] = {
                "chat_id": chat.id, "list_id": self.list_id,
                "version": parse_manifest_version(path)}
        return M(), 2

    return fake


def _real_xdc(dirpath, name, version):
    import zipfile
    p = os.path.join(dirpath, name)
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("manifest.toml", 'version = "%s"\n' % version)
        z.writestr("index.html", "<html></html>")
    return p


def test_app_publish_records_current_app():
    # a successful app-side publish records {file, sha256, version} as
    # state.current_app (latest-version tracking for lazy healing)
    chats = [(77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    _real_xdc(d, "v2.xdc", "2.0.0")
    import delivery_bot
    orig = delivery_bot.DeliveryBot.publish_and_restore
    calls = []
    delivery_bot.DeliveryBot.publish_and_restore = _fake_publish_recording(calls)
    try:
        res = br._app_publish(d, ctl, "v2.xdc", request="tok1")
    finally:
        delivery_bot.DeliveryBot.publish_and_restore = orig
    assert res["current_version"] == "2.0.0", res
    app = br.load_state(d)["current_app"]
    assert app["file"] == "v2.xdc" and app["version"] == "2.0.0", app
    assert len(app["sha256"]) == 64
    print("current_app recording ok")


def test_lazy_heal_delivers_to_stale_chat_only():
    # chat that missed the publish gets healed on its next traffic; a chat
    # already on the current version is untouched
    chats = [(77, "Group", "A", [], False, [(9001, OWNER_ADD)]),
             (78, "Group", "B", [], False, [(9002, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    _real_xdc(d, "v2.xdc", "2.0.0")
    ctl.on_new_message(event_for(77, "me@x", "hi"))   # no current_app yet: no heal
    ctl.on_new_message(event_for(78, "me@x", "hi"))
    br.save_state(d, current_app={"file": "v2.xdc", "sha256": "x", "version": "2.0.0"})
    # chat 78 already has a registered 2.0.0 instance
    rt78 = ctl.runtimes[78]
    rt78.gate._instances[4242] = {"chat_id": 78, "list_id": rt78.gate.list_id,
                                  "version": "2.0.0"}
    import delivery_bot
    orig = delivery_bot.DeliveryBot.publish_and_restore
    calls = []
    delivery_bot.DeliveryBot.publish_and_restore = _fake_publish_recording(calls)
    try:
        ctl.on_new_message(event_for(77, "me@x", "again"))  # heals 77 only
        assert calls == [(77, "v2.xdc")], calls
        ctl.on_new_message(event_for(77, "me@x", "once more"))  # now current: no re-heal
        ctl.on_new_message(event_for(78, "me@x", "yo"))
        assert calls == [(77, "v2.xdc")], calls
    finally:
        delivery_bot.DeliveryBot.publish_and_restore = orig
    print("lazy heal (stale-only, once) ok")


def test_lazy_heal_no_downgrade():
    # a chat on a NEWER registered version than current_app is left alone
    chats = [(77, "Group", "A", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    _real_xdc(d, "v2.xdc", "2.0.0")
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    br.save_state(d, current_app={"file": "v2.xdc", "sha256": "x", "version": "2.0.0"})
    rt = ctl.runtimes[77]
    rt.gate._instances[1] = {"chat_id": 77, "list_id": rt.gate.list_id,
                             "version": "3.1.0"}
    import delivery_bot
    orig = delivery_bot.DeliveryBot.publish_and_restore
    calls = []
    delivery_bot.DeliveryBot.publish_and_restore = _fake_publish_recording(calls)
    try:
        ctl.on_new_message(event_for(77, "me@x", "hi again"))
        assert calls == [], calls
    finally:
        delivery_bot.DeliveryBot.publish_and_restore = orig
    print("heal no-downgrade ok")


def test_lazy_heal_skips_revoked_and_missing_file():
    chats = [(77, "Group", "A", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    # revoked chat: no heal even though a current_app exists
    br.save_state(d, current_app={"file": "gone.xdc", "sha256": "x", "version": "2.0.0"})
    ctl._revoke_chat(77)
    import delivery_bot
    orig = delivery_bot.DeliveryBot.publish_and_restore
    calls = []
    delivery_bot.DeliveryBot.publish_and_restore = _fake_publish_recording(calls)
    try:
        ctl._maybe_heal(77)
        assert calls == [], calls
        # un-revoke (simulate re-add) but the recorded file is absent: no heal, no crash
        rec = br.load_state(d)["chats"]["77"]
        rec["revoked"] = False
        br.save_state(d, chats={"77": rec})
        ctl._maybe_heal(77)
        assert calls == [], calls
    finally:
        delivery_bot.DeliveryBot.publish_and_restore = orig
    print("heal revoked/missing-file guards ok")


def test_revoked_straggler_does_not_resume():
    # REVIEWER GAP FIX: after a revoke, a late in-flight message with NO
    # re-add evidence must NOT clear the revocation (live dispatch used to
    # resume unconditionally). A grounded self-add still resumes.
    chats = [(5, "Single", "", [("me@x", "Me")], False),
             (13, "Group", "Shared List", [], False, [(9001, SELF_ADD_PLAIN)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(13, "me@x", "hi bot"))
    acct._chats[13][5].append((9004, SELF_REMOVE))
    ctl.on_new_message(event_for(13, "me@x", SELF_REMOVE, msg_id=9004,
                                 is_info=True))
    assert 13 not in ctl._served_chat_ids()
    # straggler: ordinary message, no new add-info in the chat history
    ctl.on_new_message(event_for(13, "me@x", "late op", msg_id=9005))
    rec = br.load_state(d)["chats"]["13"]
    assert rec.get("revoked") is True, rec
    assert 13 not in ctl._served_chat_ids()
    # grounded rejoin: latest membership event is a self-add -> resume
    acct._chats[13][5].append((9006, SELF_ADD_PLAIN))
    ctl.on_new_message(event_for(13, "me@x", "back", msg_id=9007))
    assert not br.load_state(d)["chats"]["13"].get("revoked")
    assert 13 in ctl._served_chat_ids()
    print("revoked straggler guard ok")


def test_publish_prog_preserves_request_token():
    # REVIEWER GAP FIX: a crash mid-fanout resumes WITHOUT the REQ file;
    # the original request token must survive in PUBLISH_PROG so the final
    # DONE still correlates to the UI's request.
    import delivery_bot
    chats = [(77, "Group", "A", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    path = _real_xdc(d, "v2.xdc", "2.0.0")
    sha = br._sha256(path)
    # simulate the crash: PROG checkpoint written by the interrupted run,
    # carrying the original request token; no REQ file any more
    with open(os.path.join(d, br.PUBLISH_PROG), "w") as f:
        json.dump({"sha256": sha, "file": "v2.xdc", "done": [],
                   "request": "tok-orig"}, f)
    orig = delivery_bot.DeliveryBot.publish_and_restore
    calls = []
    delivery_bot.DeliveryBot.publish_and_restore = _fake_publish_recording(calls)
    try:
        res = br._app_publish(d, ctl, "v2.xdc", request=None)  # resume path
    finally:
        delivery_bot.DeliveryBot.publish_and_restore = orig
    assert res["request"] == "tok-orig", res
    assert res["published"].get("77"), res
    print("PROG request-token preservation ok")


def test_publish_done_write_failure_keeps_prog():
    # BUILDER FINDING: if the PUBLISH_DONE write fails, PUBLISH_PROG must
    # SURVIVE (it is the only remaining record + crash-resume checkpoint).
    import threading as _th, time as _ti
    import delivery_bot
    chats = [(77, "Group", "A", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    _real_xdc(d, "v2.xdc", "2.0.0")
    orig_pub = delivery_bot.DeliveryBot.publish_and_restore
    calls = []
    delivery_bot.DeliveryBot.publish_and_restore = _fake_publish_recording(calls)
    real_replace = os.replace
    done_path = os.path.join(d, br.PUBLISH_DONE)
    prog_path = os.path.join(d, br.PUBLISH_PROG)

    def failing_replace(src_p, dst_p):
        if str(dst_p) == done_path:
            raise OSError("simulated DONE write failure")
        return real_replace(src_p, dst_p)

    try:
        br.os.replace = failing_replace
        t1 = _th.Thread(target=br._publish_watcher, args=(d, ctl), daemon=True)
        with open(os.path.join(d, br.PUBLISH_REQ), "w") as f:
            f.write("v2.xdc\ntok-1")
        t1.start()
        for _ in range(200):
            if os.path.exists(prog_path) and calls:
                _ti.sleep(0.3)  # let the failed DONE attempt finish
                break
            _ti.sleep(0.05)
        assert calls == [(77, "v2.xdc")], calls  # the publish itself ran
        assert os.path.exists(prog_path), "PROG must survive the DONE failure"
        assert not os.path.exists(done_path)
        # retry with the DONE path healthy: DONE lands (token correlated),
        # PROG cleared, and the already-published chat is NOT re-sent
        br.os.replace = real_replace
        with open(os.path.join(d, br.PUBLISH_REQ), "w") as f:
            f.write("v2.xdc\ntok-2")
        for _ in range(200):
            if os.path.exists(done_path):
                break
            _ti.sleep(0.05)
        open(os.path.join(d, br.STOP_FLAG), "w").close()
        t1.join(timeout=5)
    finally:
        br.os.replace = real_replace
        delivery_bot.DeliveryBot.publish_and_restore = orig_pub
    assert calls == [(77, "v2.xdc")], calls  # no duplicate send on retry
    res = json.load(open(done_path))
    assert res["request"] == "tok-2", res
    assert not os.path.exists(prog_path)
    print("DONE-failure PROG durability ok")


if __name__ == "__main__":
    test_api_conformance()
    test_state_and_binding()
    test_contact_request_ignored()
    test_serve_accepted_group_and_isolation()
    test_group_request_ignored()
    test_chat_publish_impossible()
    test_third_party_add_served()
    test_missing_attribution_served()
    test_self_perspective_membership_parse()
    test_self_add_serves_group()
    test_self_add_without_actor_served()
    test_self_remove_revokes()
    test_serves_without_any_owner_state()
    test_dm_personal_list_served()
    test_every_dm_gets_personal_list()
    test_live_revoke_on_any_removal()
    test_boot_discovers_offline_add()
    test_app_publish_watcher()
    test_app_publish_bad_request()
    test_app_publish_retry_dedup_and_resume()
    test_publish_skips_revoked_and_correlates()
    test_readd_resumes_same_list_and_store()
    test_provision_fresh_reads_transport_addr()
    test_provision_existing_account_reused()
    test_provision_refuses_unconfigured_profile()
    test_invite_watcher_writes_qr()
    test_invite_watcher_text_fallback()
    test_provision_legacy_addr_fallback()
    test_provision_fails_loudly_without_address()
    test_control_reads_transport_addr()
    test_app_publish_records_current_app()
    test_lazy_heal_delivers_to_stale_chat_only()
    test_lazy_heal_no_downgrade()
    test_lazy_heal_skips_revoked_and_missing_file()
    test_revoked_straggler_does_not_resume()
    test_publish_prog_preserves_request_token()
    test_publish_done_write_failure_keeps_prog()
    print("ALL WRAPPER TESTS PASS")
