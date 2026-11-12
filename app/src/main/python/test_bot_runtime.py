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

            def get_chat_media(self, aid, cid, media_type, a, b):
                return []  # no webxdc instances in the fake chat by default

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
    orig_pv = delivery_bot.DeliveryBot.publish_version
    orig_ri = delivery_bot.DeliveryBot.restore_into

    class M:
        id = 4242

    def fake_publish_version(self, chat, path, note=""):
        calls.append((chat.id, os.path.basename(path)))
        return M()

    def fake_restore(self, msg_id, send_update):
        return 3

    delivery_bot.DeliveryBot.publish_version = fake_publish_version
    delivery_bot.DeliveryBot.restore_into = fake_restore
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
        delivery_bot.DeliveryBot.publish_version = orig_pv
        delivery_bot.DeliveryBot.restore_into = orig_ri
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

    def fake_publish_version(self, chat, path, note=""):
        calls.append(chat.id)
        if chat.id == 78:
            raise RuntimeError("relay down")
        return M()

    orig_pv = delivery_bot.DeliveryBot.publish_version
    orig_ri = delivery_bot.DeliveryBot.restore_into
    delivery_bot.DeliveryBot.publish_version = fake_publish_version
    delivery_bot.DeliveryBot.restore_into = lambda self, msg_id, send_update: 1
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
        delivery_bot.DeliveryBot.publish_version = orig_pv
        delivery_bot.DeliveryBot.restore_into = orig_ri
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

    def fake_publish_version(self, chat, path, note=""):
        calls.append(chat.id)
        return M()

    orig_pv = delivery_bot.DeliveryBot.publish_version
    orig_ri = delivery_bot.DeliveryBot.restore_into
    delivery_bot.DeliveryBot.publish_version = fake_publish_version
    delivery_bot.DeliveryBot.restore_into = lambda self, msg_id, send_update: 0
    try:
        res = br._app_publish(d, ctl, "v2.xdc", request="req-123")
    finally:
        delivery_bot.DeliveryBot.publish_version = orig_pv
        delivery_bot.DeliveryBot.restore_into = orig_ri
    assert calls == [77], calls
    assert res["request"] == "req-123" and res["file"] == "v2.xdc"
    assert sorted(res["published"]) == ["77"]
    print("publish revoked-skip + correlation ok")





def _fake_publish_version_recording(calls, register=True):
    """New-seam fake for _app_publish tests: fakes the SEND (publish_version),
    keeps restore real-shaped via a stub; registration mirrors the real flow."""
    import delivery_bot

    class M:
        id = 5555

    def fake_pv(self, chat, path, note=""):
        calls.append((chat.id, os.path.basename(path)))
        if register:  # real flow registers the sent instance with its version
            from delivery_bot import parse_manifest_version
            self._instances[M.id + len(calls)] = {
                "chat_id": chat.id, "list_id": self.list_id,
                "version": parse_manifest_version(path)}
        return M()

    return fake_pv


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
    orig_pv = delivery_bot.DeliveryBot.publish_version
    orig_ri = delivery_bot.DeliveryBot.restore_into
    calls = []
    delivery_bot.DeliveryBot.publish_version = _fake_publish_version_recording(calls)
    delivery_bot.DeliveryBot.restore_into = lambda self, msg_id, send_update: 2
    try:
        res = br._app_publish(d, ctl, "v2.xdc", request="tok1")
    finally:
        delivery_bot.DeliveryBot.publish_version = orig_pv
        delivery_bot.DeliveryBot.restore_into = orig_ri
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
    orig_pv = delivery_bot.DeliveryBot.publish_version
    orig_ri = delivery_bot.DeliveryBot.restore_into
    calls = []
    delivery_bot.DeliveryBot.publish_version = _fake_publish_version_recording(calls)
    delivery_bot.DeliveryBot.restore_into = lambda self, msg_id, send_update: 2
    try:
        res = br._app_publish(d, ctl, "v2.xdc", request=None)  # resume path
    finally:
        delivery_bot.DeliveryBot.publish_version = orig_pv
        delivery_bot.DeliveryBot.restore_into = orig_ri
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
    orig_pv = delivery_bot.DeliveryBot.publish_version
    orig_ri = delivery_bot.DeliveryBot.restore_into
    calls = []
    delivery_bot.DeliveryBot.publish_version = _fake_publish_version_recording(calls)
    delivery_bot.DeliveryBot.restore_into = lambda self, msg_id, send_update: 2
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
        delivery_bot.DeliveryBot.publish_version = orig_pv
        delivery_bot.DeliveryBot.restore_into = orig_ri
    assert calls == [(77, "v2.xdc")], calls  # no duplicate send on retry
    res = json.load(open(done_path))
    assert res["request"] == "tok-2", res
    assert not os.path.exists(prog_path)
    print("DONE-failure PROG durability ok")




def _mk_op(i):
    return {"item": "t%d" % i, "field": "title",
            "stamp": {"hlc": i, "device": "dev1"}, "value": "task %d" % i}


def _versioned_xdc(d, name, version):
    p = os.path.join(d, name)
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("manifest.toml", 'version = "%s"\nname = "t"\n' % version)
        z.writestr("index.html", "hi")
    return p


def test_app_publish_cross_thread_real_store():
    # REAL regression for the r12 phone failure: the chat runtime (and its
    # OpsStore connection) is built on the MAIN/bot-loop thread, then the
    # app-side publish fan-out runs on the publish watcher's OWN thread.
    # Pre-fix the replay died with sqlite3 ProgrammingError. Real OpsStore
    # (forced stdlib sqlite3 = the phone path), real DeliveryBot gate,
    # real restore - only the RPC send and the bridge update send are faked.
    import threading as _th
    os.environ["OPS_STORE_FORCE_SQLITE"] = "1"
    chats = [(77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    rt = ctl._runtime_for(77, br.load_state(d)["chats"]["77"]["list_id"])
    # seed 5 real ops on the MAIN thread, as loop-thread ingest would
    rt.bridge.store.apply_updates(
        4242, [{"serial": i + 1, "payload": {"ops": [_mk_op(i + 1)]}, "info": ""}
               for i in range(5)])
    assert rt.bridge.store.count() == 5
    _versioned_xdc(d, "v3.xdc", "9.9.9-test")

    from deltachat_rpc_client import Chat as RealChat
    orig_sm = RealChat.send_message
    sends = []

    class M:
        id = 4242

    def fake_sm(self, text=None, file=None, viewtype=None):
        sends.append(viewtype)
        return M()

    updates = []
    rt.bridge.send_update = lambda msg_id, payload, info="": updates.append(payload)
    RealChat.send_message = fake_sm
    res = {}
    try:
        t = _th.Thread(target=lambda: res.update(br._app_publish(d, ctl, "v3.xdc")))
        t.start()
        t.join(timeout=30)
        assert not t.is_alive(), "publish fan-out hung"
    finally:
        RealChat.send_message = orig_sm
    assert res["errors"] == {}, res
    assert res["partial"] == {}, res
    assert res["published"]["77"]["ops_replayed"] == 5, res
    assert sends == ["Webxdc"], sends
    assert len(updates) >= 1 and updates[-1]["ops"], "no restore batches sent"
    assert rt.gate._instances[4242]["version"] == "9.9.9-test"
    assert br.load_state(d)["current_app"]["version"] == "9.9.9-test"
    print("cross-thread real-store publish ok")


def test_store_concurrent_ingest_and_replay_reads():
    # loop-thread WRITES (ingest) race watcher-thread READS (replay): two
    # connections, SQLite locking + busy_timeout - no ProgrammingError, no
    # SQLITE_BUSY, no lost rows.
    import threading as _th
    os.environ["OPS_STORE_FORCE_SQLITE"] = "1"
    from ops_store import OpsStore
    d = fresh()
    s = OpsStore(os.path.join(d, "ops.db"))
    s.bind_list("list-t")
    s.apply_updates(1, [{"serial": 1, "payload": {"ops": [_mk_op(1)]}, "info": ""}])
    errors = []

    def writer():
        try:
            for i in range(2, 27):  # 25 appends while the reader replays
                s.apply_updates(1, [{"serial": i,
                                     "payload": {"ops": [_mk_op(i)]}, "info": ""}])
        except Exception as e:
            errors.append(("writer", e))

    def reader():
        try:
            for _ in range(40):
                s.ops_since(0)
        except Exception as e:
            errors.append(("reader", e))

    r = _th.Thread(target=reader)
    w = _th.Thread(target=writer)
    r.start(); w.start()
    w.join(timeout=30); r.join(timeout=30)
    assert not w.is_alive() and not r.is_alive()
    assert not errors, errors
    assert s.count() == 26, s.count()
    print("concurrent ingest/replay ok")


def test_app_publish_partial_then_retry_no_duplicate():
    # send OK + replay fails => honest "partial" (msg_id recorded), an
    # immediate sent-checkpoint in PUBLISH_PROG, current_app still tracked;
    # a SAME-BYTES retry completes into the EXISTING instance - no second
    # bubble, no duplicate send.
    chats = [(77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    _versioned_xdc(d, "v3.xdc", "9.9.9-test")

    from deltachat_rpc_client import Chat as RealChat
    orig_sm = RealChat.send_message
    sends = []

    class M:
        id = 4242

    def fake_sm(self, text=None, file=None, viewtype=None):
        sends.append(viewtype)
        return M()

    import delivery_bot
    orig_ri = delivery_bot.DeliveryBot.restore_into
    state = {"fail": True}

    def flaky_restore(self, msg_id, send_update):
        if state["fail"]:
            raise RuntimeError("replay blew up")
        return 0

    RealChat.send_message = fake_sm
    delivery_bot.DeliveryBot.restore_into = flaky_restore
    try:
        r1 = br._app_publish(d, ctl, "v3.xdc")
        assert r1["published"] == {} and r1["errors"] == {}, r1
        assert r1["partial"]["77"]["msg_id"] == 4242, r1
        assert "replay blew up" in r1["partial"]["77"]["error"], r1
        prog = json.load(open(os.path.join(d, br.PUBLISH_PROG)))
        assert prog["sent"]["77"] == 4242, prog
        assert "77" not in prog.get("done", []), prog
        assert br.load_state(d)["current_app"]["version"] == "9.9.9-test"
        # retry the SAME bytes: replay now works
        state["fail"] = False
        r2 = br._app_publish(d, ctl, "v3.xdc")
        assert len(sends) == 1, "retry sent a duplicate bubble: %r" % sends
        assert r2["errors"] == {} and r2["partial"] == {}, r2
        assert r2["published"]["77"]["msg_id"] == 4242, r2
        assert r2["published"]["77"]["existing"] is True, r2
        prog2 = json.load(open(os.path.join(d, br.PUBLISH_PROG)))
        assert "77" in prog2.get("done", []), prog2
    finally:
        RealChat.send_message = orig_sm
        delivery_bot.DeliveryBot.restore_into = orig_ri
    print("partial + no-duplicate retry ok")




# ---------------------------------------------------------- adoption (r12 upgrade gap)
class _AdoptFakeGate:
    """Minimal gate fake for _app_publish adoption tests: no durable
    registration (registered_instance_for misses), canned adopt result."""
    def __init__(self, adopt_result):
        self._adopt_result = adopt_result
        self.adopt_calls = []
        self.send_calls = []
        self.restore_calls = []
        self.list_id = "list-x"
        self.bot_addr = "bot@nine.testrun.org"
    def registered_instance_for(self, cid, version):
        return None
    def adopt_existing_instance(self, cid, version):
        self.adopt_calls.append((cid, version))
        return self._adopt_result
    def publish_version(self, chat, path, note=""):
        self.send_calls.append(chat.id)
        class M: id = 9999
        return M()
    def restore_into(self, msg_id, send_update):
        self.restore_calls.append(msg_id)
        return 3
    def ingest_done(self, msg_id):
        pass

def _ctl_with_gate(d, gate, chats=None):
    """Control whose _runtime_for(cid, list_id) serves a fake gate."""
    ctl, acct, _ = _control(chats or [(77, "Group", "Shared List", [], False, [])],
                            {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    class RT: pass
    rt = RT(); rt.gate = gate
    rt.bridge = type("B", (), {"send_update": staticmethod(lambda *a, **k: None),
                               "process_instance": staticmethod(lambda mid: 0)})()
    orig = br.ChatControl._runtime_for
    br.ChatControl._runtime_for = lambda self, cid, lid: rt
    return ctl, orig

def test_app_publish_adopts_orphaned_instance():
    # r12 upgrade gap: bubble delivered by an older run, registration lost.
    # Fixed retry must ADOPT (zero sends) and replay into the adopted bubble.
    _real_xdc(d := tempfile.mkdtemp(), "v2.xdc", "2.0.0")
    gate = _AdoptFakeGate(adopt_result=4242)
    ctl, orig = _ctl_with_gate(d, gate)
    try:
        res = br._app_publish(d, ctl, "v2.xdc", request="ad1")
    finally:
        br.ChatControl._runtime_for = orig
    assert gate.send_calls == [], ("duplicate send!", res)
    assert gate.adopt_calls and gate.restore_calls == [4242], gate.restore_calls
    pub = res["published"]["77"]
    assert pub["msg_id"] == 4242 and pub["adopted"] is True and pub["ops_replayed"] == 3, res
    print("adopt orphaned bubble, zero sends ok")

def test_app_publish_adoption_miss_sends_once():
    # nothing adoptable (e.g. only foreign/unpinned bubbles): normal send.
    _real_xdc(d := tempfile.mkdtemp(), "v2.xdc", "2.0.0")
    gate = _AdoptFakeGate(adopt_result=None)
    ctl, orig = _ctl_with_gate(d, gate)
    try:
        res = br._app_publish(d, ctl, "v2.xdc", request="ad2")
    finally:
        br.ChatControl._runtime_for = orig
    assert gate.adopt_calls, "adoption scan must run before send"
    assert gate.send_calls == [77] and "77" in res["published"], res
    assert "adopted" not in res["published"]["77"], res
    print("adoption miss -> single send ok")

def test_app_publish_adopt_replay_failure_is_partial():
    # adopted but replay failed: honest partial, adopted msg_id recorded,
    # no send, and a same-bytes retry finds registration (existing path).
    _real_xdc(d := tempfile.mkdtemp(), "v2.xdc", "2.0.0")
    gate = _AdoptFakeGate(adopt_result=4242)
    gate.restore_into = lambda *a: (_ for _ in ()).throw(RuntimeError("relay down"))
    ctl, orig = _ctl_with_gate(d, gate)
    try:
        res = br._app_publish(d, ctl, "v2.xdc", request="ad3")
    finally:
        br.ChatControl._runtime_for = orig
    assert gate.send_calls == [], res
    assert res["partial"]["77"]["msg_id"] == 4242, res
    print("adopt replay failure -> partial ok")


def test_adopt_existing_instance_strict_checks():
    # delivery_bot-level: SELF-authorship, exact version, exact pin; highest
    # msg id among matches; registration persisted bot-origin.
    import delivery_bot as dbm
    from ops_store import OpsStore
    os.environ["OPS_STORE_FORCE_SQLITE"] = "1"
    store = OpsStore(os.path.join(tempfile.mkdtemp(), "s.db"))
    bot = dbm.DeliveryBot.__new__(dbm.DeliveryBot)
    bot.account = None; bot.store = store; bot.owner_addr = "me@x"
    bot.allowed_chat_ids = {77}; bot.list_id = "list-x"
    bot.bot_addr = "bot@nine.testrun.org"; bot.min_restore_interval = 0
    bot._now = __import__("time").time
    bot._instances = {}; bot._last_restore = {}; bot._send_counter = {}
    bot._last_listing = []; bot._pending_download = None
    bot._publishes = {}; bot._pending_ingest = []
    selfy = {11: True, 22: True, 33: True, 44: False, 55: True}
    vers = {11: "2.0.0", 22: "2.0.0", 33: "1.0.0", 44: "2.0.0", 55: "2.0.0"}
    pins = {11: "list-x", 22: "list-x", 33: "list-x", 44: "list-x", 55: "list-OTHER"}
    bot.list_app_instances = lambda cid: [11, 22, 33, 44, 55]
    bot.instance_sent_by_self = lambda m: selfy[m]
    bot.instance_version = lambda m: vers[m]
    bot.instance_list_id = lambda m: pins[m]
    # 11+22 match (11 older), 33 wrong version, 44 not self, 55 wrong pin
    got = bot.adopt_existing_instance(77, "2.0.0")
    assert got == 22, got  # highest matching msg id
    inst = bot._instances[22]
    assert inst["origin"] == dbm.ORIGIN_BOT and inst["author"] == "bot@nine.testrun.org", inst
    raw = store.get_meta(bot._META_KEY)
    assert raw and '"22"' in raw or "22" in raw  # persisted immediately
    # none qualify -> None (caller sends instead)
    assert bot.adopt_existing_instance(77, "9.9.9") is None
    # ungranted chat -> None
    assert bot.adopt_existing_instance(78, "2.0.0") is None
    # empty version -> None
    assert bot.adopt_existing_instance(77, "") is None
    print("adopt strict checks ok")




def test_candidate_helpers_raise_on_read_failure():
    # Real helpers: a READ failure is UNCERTAIN (raises
    # CandidateUncertainError), never "definitely not qualifying".
    # instance_sent_by_self previously returned False on any exception,
    # which silently skipped an unverifiable candidate - the S4-style
    # mechanism that can adopt an older bubble or fall through to a
    # duplicate send.
    import delivery_bot as dbm
    bot = dbm.DeliveryBot.__new__(dbm.DeliveryBot)
    class _Rpc:
        @staticmethod
        def get_chat_media(*a, **k): raise RuntimeError("relay down")
        @staticmethod
        def get_webxdc_blob(*a, **k): raise RuntimeError("relay down")
        @staticmethod
        def get_message(*a, **k): raise RuntimeError("relay down")
    class _Acct:
        id = 1
        _rpc = _Rpc
    bot.account = _Acct()
    checks = [
        ("list_app_instances", lambda: bot.list_app_instances(77)),
        ("instance_version", lambda: bot.instance_version(11)),
        ("instance_list_id", lambda: bot.instance_list_id(11)),
        ("instance_sent_by_self", lambda: bot.instance_sent_by_self(11)),
    ]
    for name, fn in checks:
        try:
            fn()
            raise AssertionError(name + " must raise CandidateUncertainError")
        except dbm.CandidateUncertainError:
            pass
    print("candidate helpers fail-closed ok")


def test_app_publish_candidate_uncertain_sends_nothing():
    # Publish fan-out: an unverifiable candidate check must land as an
    # explicit retryable error for that chat - NO send, NO adopt, NO
    # skip-to-older.
    import delivery_bot as dbm
    os.environ["OPS_STORE_FORCE_SQLITE"] = "1"
    chats = [(77, "Group", "Shared List", [], False, [])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    _real_xdc(d, "v2.xdc", "2.0.0")
    rt = ctl._runtime_for(77, ctl._st()["chats"]["77"]["list_id"])
    def uncertain(cid, version):
        raise dbm.CandidateUncertainError("snapshot read timed out")
    rt.gate.adopt_existing_instance = uncertain
    sent = []
    rt.gate.publish_version = lambda chat, path: sent.append(1)
    res = br._app_publish(d, ctl, "v2.xdc", request="u1")
    assert res["published"] == {} and res["partial"] == {}, res
    assert "77" in res["errors"], res
    assert "nothing was sent" in res["errors"]["77"] and "retry" in res["errors"]["77"], res
    assert sent == [], res
    # recovery: the same retry succeeds once the read works again
    rt.gate.adopt_existing_instance = lambda cid, version: 4242
    class FakeMessage:
        def __init__(self, account, msg_id): self.msg_id = msg_id
        def get_webxdc_status_updates(self, wm): return []
    import bot as bot_mod
    orig_msg = bot_mod.Message
    bot_mod.Message = FakeMessage
    try:
        res2 = br._app_publish(d, ctl, "v2.xdc", request="u2")
    finally:
        bot_mod.Message = orig_msg
    assert res2["published"]["77"]["msg_id"] == 4242, res2
    print("publish candidate-uncertain fail-closed ok")


def test_candidate_absent_entry_is_definite_negative():
    # The core's STRUCTURED missing-entry error (JsonRpcError code -1,
    # exact message 'no entry found for <name>') is a DEFINITE negative:
    # absent manifest -> version None, absent list.json -> pin ''. Only
    # that exact shape maps to negative; any other error stays
    # CandidateUncertainError (fail-closed). Regression: absent entries
    # must NOT become retryable-forever publish blockers.
    import delivery_bot as dbm
    from deltachat_rpc_client import JsonRpcError
    bot = dbm.DeliveryBot.__new__(dbm.DeliveryBot)
    def missing(name):
        return JsonRpcError({"code": -1, "message": "no entry found for %s" % name})
    class _Rpc:
        @staticmethod
        def get_webxdc_blob(acc, mid, name): raise missing(name)
    class _Acct:
        id = 1
        _rpc = _Rpc
    bot.account = _Acct()
    assert bot.instance_version(11) is None
    assert bot.instance_list_id(11) == ""
    # a code -1 with a DIFFERENT message is NOT a definite negative
    class _Rpc2:
        @staticmethod
        def get_webxdc_blob(acc, mid, name):
            raise JsonRpcError({"code": -1, "message": "io error: timed out"})
    class _Acct2:
        id = 1
        _rpc = _Rpc2
    bot.account = _Acct2()
    for fn in (lambda: bot.instance_version(11), lambda: bot.instance_list_id(11)):
        try:
            fn()
            raise AssertionError("timeout must raise CandidateUncertainError")
        except dbm.CandidateUncertainError:
            pass
    # adoption: an absent-pin candidate is SKIPPED (definite negative),
    # not uncertain - and a same-version pinned candidate still wins
    from ops_store import OpsStore
    os.environ["OPS_STORE_FORCE_SQLITE"] = "1"
    store = OpsStore(os.path.join(tempfile.mkdtemp(), "s.db"))
    bot.store = store; bot.owner_addr = "me@x"
    bot.allowed_chat_ids = {77}; bot.list_id = "list-x"
    bot.bot_addr = "bot@nine.testrun.org"; bot.min_restore_interval = 0
    bot._now = __import__("time").time
    bot._instances = {}; bot._last_restore = {}; bot._send_counter = {}
    bot._last_listing = []; bot._pending_download = None
    bot._publishes = {}; bot._pending_ingest = []
    bot.list_app_instances = lambda cid: [11, 22]
    bot.instance_sent_by_self = lambda m: True
    bot.instance_version = lambda m: "2.0.0"
    bot.account = _Acct()  # list.json provably ABSENT for every candidate
    bot.instance_list_id = dbm.DeliveryBot.instance_list_id.__get__(bot)
    assert bot.adopt_existing_instance(77, "2.0.0") is None  # no raise, no adopt
    assert bot._instances == {}
    print("absent-entry definite negative ok")


def test_app_publish_adoption_ingests_orphan_edits():
    # THE proof for the blocking gap: an edit made in the orphaned instance
    # (registration lost) becomes canonical store history at adoption and
    # is replayed - edit => store => replay, zero duplicate sends.
    import bot as bot_mod
    import delivery_bot as dbm
    os.environ["OPS_STORE_FORCE_SQLITE"] = "1"
    chats = [(77, "Group", "Shared List", [], False, [(9001, OWNER_ADD)])]
    ctl, acct, d = _control(chats, {"provisioned": True})
    ctl.on_new_message(event_for(77, "me@x", "hi"))
    _real_xdc(d, "v2.xdc", "2.0.0")
    orphan_op = {"item": "t1", "field": "title", "value": "task 1",
                 "stamp": {"hlc": 4242, "device": "devZ"}}
    class FakeMessage:
        def __init__(self, account, msg_id): self.msg_id = msg_id
        def get_webxdc_status_updates(self, wm):
            ups = [{"serial": 1, "payload": {"ops": [orphan_op]}, "info": ""}]
            return [u for u in ups if u["serial"] > wm]
    orig_msg = bot_mod.Message
    bot_mod.Message = FakeMessage
    sent_payloads = []
    orig_sm = None
    try:
        rt = ctl._runtime_for(77, ctl._st()["chats"]["77"]["list_id"])
        # adoption scan primitives stubbed to "one valid orphaned bubble"
        rt.gate.list_app_instances = lambda cid: [4242]
        rt.gate.instance_sent_by_self = lambda m: True
        rt.gate.instance_version = lambda m: "2.0.0"
        rt.gate.instance_list_id = lambda m: rt.gate.list_id
        rt.bridge.send_update = lambda mid, payload, info="": sent_payloads.append(payload)
        res = br._app_publish(d, ctl, "v2.xdc", request="proof1")
    finally:
        bot_mod.Message = orig_msg
    pub = res["published"]["77"]
    assert pub.get("adopted") is True and pub["msg_id"] == 4242, res
    assert "ingest_pending" not in pub, res  # clean ingest: no caveat flag
    # edit reached the store (canonical) and watermark advanced
    ops = [json.loads(r[0]) for r in rt.bridge.store.conn.execute(
        "SELECT payload FROM webxdc_ops").fetchall()]
    assert any(o.get("value") == "task 1" for o in ops), ops
    assert rt.bridge.store.watermark(4242) == 1
    # ingest queue cleared after the committed one-shot ingest
    assert 4242 not in rt.gate.pending_ingest()
    # ...and the same edit was replayed into the adopted instance
    replayed = [op for p in sent_payloads for op in (p.get("ops") or [])]
    assert any(o.get("value") == "task 1" for o in replayed), sent_payloads
    print("orphan edit -> store -> replay proof ok")


def test_app_publish_adoption_ingest_failure_still_replays():
    # ingest failure must not block the replay and must leave the one-shot
    # ingest queued for the drain/boot retry.
    _real_xdc(d := tempfile.mkdtemp(), "v2.xdc", "2.0.0")
    gate = _AdoptFakeGate(adopt_result=4242)
    ctl, orig = _ctl_with_gate(d, gate)
    rt = ctl._runtime_for(77, "list-x")
    rt.bridge.process_instance = lambda mid: (_ for _ in ()).throw(RuntimeError("rpc down"))
    gate._pending_ingest = [4242]
    try:
        res = br._app_publish(d, ctl, "v2.xdc", request="proof2")
    finally:
        br.ChatControl._runtime_for = orig
    pub = res["published"]["77"]
    assert pub["adopted"] is True and pub.get("ingest_pending") is True, res
    assert gate.restore_calls == [4242]
    assert gate._pending_ingest == [4242]  # left queued for retry
    print("ingest failure -> replay continues, ingest queued ok")


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
    test_app_publish_cross_thread_real_store()
    test_store_concurrent_ingest_and_replay_reads()
    test_app_publish_partial_then_retry_no_duplicate()
    test_app_publish_adopts_orphaned_instance()
    test_app_publish_adoption_miss_sends_once()
    test_app_publish_adopt_replay_failure_is_partial()
    test_adopt_existing_instance_strict_checks()
    test_app_publish_adoption_ingests_orphan_edits()
    test_app_publish_adoption_ingest_failure_still_replays()
    test_candidate_helpers_raise_on_read_failure()
    test_app_publish_candidate_uncertain_sends_nothing()
    test_candidate_absent_entry_is_definite_negative()
    print("ALL WRAPPER TESTS PASS")
