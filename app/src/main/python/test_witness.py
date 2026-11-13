"""Harness tests for witness.py. These prove the module logic against
fakes ONLY - they do NOT prove event delivery on the phone build.
All fixtures are synthetic (.invalid addresses, fictitious ids); no real
account addresses, message ids or chat content appear in this file."""
import json as _json
import os
import tempfile
import unittest

from witness import WitnessStore

PEER = "peer@example.invalid"        # a chat participant
ACTOR = "actor@example.invalid"      # the watched ack actor
M0 = "00000000-0000-4000-8000-000000000000@example.invalid"
M1 = "aaaaaaaa-1111-4111-8111-111111111111@example.invalid"
M2 = "bbbbbbbb-2222-4222-8222-222222222222@example.invalid"


class WitnessTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.w = WitnessStore(self.dir, 14)

    def _grounded_message(self, mid=M1, ts=100):
        """A message from PEER quoting ACTOR's own message: the one
        grounded reason to expect an ack from ACTOR in a group."""
        self.w.record_message(M0, ACTOR, ts - 10, "Text", False)
        self.w.record_message(mid, PEER, ts, "Text", False,
                              quote_mid=M0, text="hi")

    def test_ack_marker_parse_target_and_stage(self):
        found = self.w.record_acks_from_text(
            ACTOR, "got it [ack:%s:received] thanks" % M1, 100)
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0]["target"], M1)
        self.assertEqual(found[0]["stage"], "received")

    def test_freeform_text_is_never_an_ack(self):
        found = self.w.record_acks_from_text(
            ACTOR, "yes I received that one", 100)
        self.assertEqual(found, [])

    def test_unknown_stage_marker_ignored(self):
        self.assertEqual(self.w.record_acks_from_text(
            ACTOR, "[ack:%s:frobnicate]" % M1, 100), [])

    def test_no_ack_is_candidate_not_false_negative(self):
        self.w.live_start(50)
        self._grounded_message()
        rows = self.w.review([ACTOR])
        self.assertEqual(rows[-1]["verdict"], "candidate-no-ack")
        self.assertNotIn("false", rows[-1]["verdict"])

    def test_broadcast_message_is_unknown_not_candidate(self):
        # A plain group message (no reply to the actor, not their direct
        # chat) carries no grounded ack obligation -> "unknown".
        self.w.live_start(50)
        self.w.record_message(M1, PEER, 100, "Text", False, text="hi")
        rows = self.w.review([ACTOR])
        self.assertEqual(rows[0]["verdict"], "unknown")
        self.assertIn("no grounded expectation", rows[0]["reason"])

    def test_direct_chat_grounds_ack_expectation(self):
        # In the actor's direct chat with PEER every incoming message is
        # addressed to the actor (here the bot itself, watched).
        self.w.live_start(50)
        self.w.set_chat_context("Single", PEER)
        self.w.record_message(M1, PEER, 100, "Text", False, text="hi")
        rows = self.w.review([ACTOR], bot_addr=ACTOR)
        self.assertEqual(rows[0]["verdict"], "candidate-no-ack")

    def test_offline_window_is_unknown_never_false(self):
        self.w.live_start(200)  # message sent before any live window
        self._grounded_message()
        rows = {r["mid"]: r for r in self.w.review([ACTOR])}
        self.assertEqual(rows[M1]["verdict"], "unknown")

    def test_ack_observed_when_live(self):
        self.w.live_start(50)
        self._grounded_message()
        self.w.record_acks_from_text(ACTOR, "[ack:%s:received]" % M1, 110)
        rows = {r["mid"]: r for r in self.w.review([ACTOR])}
        self.assertEqual(rows[M1]["verdict"], "ack-observed")
        self.assertEqual(rows[M1]["stages"], ["received"])

    def test_unsupported_stage_is_unknown_not_fact(self):
        # A "read" marker is a sender-attributed claim, never a verified
        # stage fact: it grades "unknown", not ack-observed.
        self.w.live_start(50)
        self._grounded_message()
        self.w.record_acks_from_text(ACTOR, "[ack:%s:read]" % M1, 110)
        rows = {r["mid"]: r for r in self.w.review([ACTOR])}
        self.assertEqual(rows[M1]["verdict"], "unknown")
        self.assertIn("unsupported stage", rows[M1]["reason"])

    def test_unmatched_ack_is_candidate_not_false_positive(self):
        self.w.live_start(50)
        self.w.record_acks_from_text(ACTOR, "[ack:%s:received]" % M2, 100)
        rows = self.w.review([ACTOR])
        self.assertEqual(rows[0]["verdict"], "candidate-unmatched-ack")
        self.assertNotIn("false", rows[0]["verdict"])

    def test_unmatched_unsupported_stage_not_a_candidate(self):
        self.w.live_start(50)
        self.w.record_acks_from_text(ACTOR, "[ack:%s:played]" % M2, 100)
        self.assertEqual(self.w.review([ACTOR]), [])

    def test_own_messages_not_graded(self):
        self.w.live_start(50)
        self.w.record_message(M1, ACTOR, 100, "Text", False)
        self.assertEqual(self.w.review([ACTOR]), [])

    def test_bot_messages_not_graded(self):
        self.w.live_start(50)
        self.w.record_message(M1, "bot@example.invalid", 100, "Text", False)
        self.assertEqual(
            self.w.review([ACTOR], bot_addr="bot@example.invalid"), [])

    def test_reaction_and_delete_are_events_not_messages(self):
        self.w.live_start(50)
        self.w.record_message(M1, PEER, 100, "Text", False)
        self.w.record_event("reaction", M1, PEER, 105, "\U0001F44D")
        self.w.record_event("deleted", M2, PEER, 106)
        self.assertEqual(len(self.w.messages), 1)
        self.assertEqual(len(self.w.events), 2)

    def test_persistence_roundtrip(self):
        self.w.live_start(50)
        self.w.record_message(M1, PEER, 100, "Text", False, text="hi")
        w2 = WitnessStore(self.dir, 14)
        self.assertIn(M1, w2.messages)
        self.assertTrue(w2.is_live_at(100))

    def test_chat_context_persists_and_first_write_wins(self):
        self.w.set_chat_context("Group")
        self.w.set_chat_context("Single", PEER)  # ignored: stable
        w2 = WitnessStore(self.dir, 14)
        self.assertEqual(w2.chat_context["type"], "Group")

    def test_no_raw_text_stored(self):
        self.w.record_message(M1, PEER, 100, "Text", False,
                              text="secret body")
        with open(self.w.path) as f:
            self.assertNotIn("secret body", f.read())

    def test_none_mid_message_unmatchable(self):
        self.w.live_start(50)
        self.w.record_message(None, PEER, 100, "Text", False)
        self.assertEqual(self.w.review([ACTOR]), [])

    def test_crash_freezes_liveness_at_last_heartbeat(self):
        # Bot live at 50, crashes (no stop, no more heartbeats). A message
        # arriving long after the slack must grade "unknown", never a
        # candidate - downtime cannot masquerade as coverage.
        self.w.live_start(50)
        self.w.heartbeat(80)
        self._grounded_message(M1, 80 + 300 + 60)
        rows = {r["mid"]: r for r in self.w.review([ACTOR])}
        self.assertEqual(rows[M1]["verdict"], "unknown")
        # ...but a grounded message inside the slack IS covered.
        self._grounded_message(M2, 80 + 300 - 10)
        rows = {r["mid"]: r for r in self.w.review([ACTOR])}
        self.assertEqual(rows[M2]["verdict"], "candidate-no-ack")

    def test_heartbeat_persistence_roundtrip(self):
        self.w.live_start(50)
        self.w.heartbeat(70)
        w2 = WitnessStore(self.dir, 14)
        self.assertTrue(w2.is_live_at(70))
        self.assertFalse(w2.is_live_at(70 + 300 + 60))

    def test_mid_for_local_id_fallback(self):
        self.w.record_message(M1, PEER, 100, "Text", False, local_id=11)
        self.assertEqual(self.w.mid_for_local_id(11), M1)
        self.assertIsNone(self.w.mid_for_local_id(99))


class FakeRpc:
    def __init__(self, mids, contacts=None, reactions=None):
        self.mids = mids          # local msg id -> rfc724 mid
        self.contacts = contacts or {}
        self.reactions = reactions or {}
    def get_message_info_object(self, account_id, msg_id):
        return {"rfc724Mid": self.mids.get(msg_id)}
    def get_contact(self, account_id, contact_id):
        return {"address": self.contacts.get(contact_id, "")}
    def get_message_reactions(self, account_id, msg_id):
        return self.reactions.get(msg_id, {})

class FakeContact:
    def __init__(self, addr): self._a = addr
    def get_snapshot(self): return {"address": self._a}

class FakeMessage:
    def __init__(self, rpc, msg_id, addr):
        self._rpc = rpc; self.id = msg_id; self._addr = addr
    def get_sender_contact(self): return FakeContact(self._addr)
    def get_snapshot(self):
        # dict-like snapshot, as the core's get_message_snapshot returns
        return {"from_id": 10, "text": "hi", "timestamp": 100,
                "view_type": "Text", "file": None, "quote": None}

class FakeSnapshot:
    """Attribute-access snapshot, like the client's NewMessage event."""
    def __init__(self, rpc, chat_id, msg_id, addr, text, ts, quote=None):
        self.chat_id = chat_id
        self.message = FakeMessage(rpc, msg_id, addr)
        self.text = text
        self.timestamp = ts
        self.view_type = "Text"
        self.file = None
        self.quote = quote

class FakeEvent:
    def __init__(self, snap=None, chat_id=None, msg_id=None):
        self.message_snapshot = snap
        self.chat_id = chat_id
        self.msg_id = msg_id

class FakeAccount:
    id = 1
    def __init__(self, rpc): self._rpc = rpc


class BotWitnessGlueTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        with open(os.path.join(self.dir, "witness_config.json"), "w") as f:
            _json.dump({"chats": [14], "watched": [ACTOR]}, f)
        self.rpc = FakeRpc({11: M1, 12: M2}, contacts={10: PEER})
        import witness
        self.w = witness.BotWitness(
            FakeAccount(self.rpc), self.dir, lambda: {14})

    def test_new_message_records_mid_sender_and_ack(self):
        snap = FakeSnapshot(self.rpc, 14, 11, ACTOR,
                            "seen [ack:%s:received]" % M2, 100)
        self.w.live_start(50)
        self.w.on_new_message(FakeEvent(snap=snap))
        store = self.w.stores[14]
        self.assertIn(M1, store.messages)
        self.assertEqual(store.messages[M1]["sender"], ACTOR)
        self.assertEqual(store.acks[0]["actor"], ACTOR)
        self.assertEqual(store.acks[0]["target"], M2)

    def test_unserved_chat_not_witnessed(self):
        snap = FakeSnapshot(self.rpc, 99, 11, ACTOR, "hi", 100)
        self.w.on_new_message(FakeEvent(snap=snap))
        self.assertNotIn(99, self.w.stores)

    def test_unresolvable_mid_recorded_unknown(self):
        snap = FakeSnapshot(self.rpc, 14, 77, PEER, "hi", 100)
        self.w.live_start(50)
        self.w.on_new_message(FakeEvent(snap=snap))
        store = self.w.stores[14]
        self.assertIn(None, store.messages)
        self.assertEqual(store.review([ACTOR]), [])

    def test_msgs_changed_fetches_by_id(self):
        # The raw event carries ONLY chat_id/msg_id (core v2.69); the
        # witness must fetch the message through the core.
        class Acct(FakeAccount):
            def get_message_by_id(self, msg_id):
                return FakeMessage(self._rpc, msg_id, PEER)
        w = __import__("witness").BotWitness(
            Acct(self.rpc), self.dir, lambda: {14})
        w.live_start(50)
        w.on_msgs_changed(FakeEvent(chat_id=14, msg_id=11))
        self.assertIn(M1, w.stores[14].messages)

    def test_msgs_changed_zero_msg_id_skipped(self):
        self.w.live_start(50)
        self.w.on_msgs_changed(FakeEvent(chat_id=14, msg_id=0))
        # store may exist (liveness) but no message was recorded
        self.assertEqual(self.w.stores[14].messages, {})

    def test_reactions_actors_from_reactions_by_contact(self):
        self.rpc.reactions = {11: {"reactionsByContact": {"10": ["\U0001F44D"]},
                                   "reactions": [{"emoji": "\U0001F44D",
                                                  "count": 1,
                                                  "isFromSelf": False}]}}
        self.w.live_start(50)
        snap = FakeSnapshot(self.rpc, 14, 11, PEER, "hi", 100)
        self.w.on_new_message(FakeEvent(snap=snap))
        self.w.on_reactions_changed(FakeEvent(chat_id=14, msg_id=11))
        events = self.w.stores[14].events
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]["actor"], PEER)
        self.assertEqual(events[0]["mid"], M1)
        self.assertEqual(events[0]["detail"], "\U0001F44D")

    def test_msg_deleted_uses_local_index(self):
        self.w.live_start(50)
        snap = FakeSnapshot(self.rpc, 14, 11, PEER, "hi", 100)
        self.w.on_new_message(FakeEvent(snap=snap))
        self.w.on_msg_deleted(FakeEvent(chat_id=14, msg_id=11))
        events = self.w.stores[14].events
        self.assertEqual(events[0]["event"], "deleted")
        self.assertEqual(events[0]["mid"], M1)

    def test_msg_deleted_unwitnessed_chat_ignored(self):
        self.w.local_index[(99, 11)] = M1
        self.w.on_msg_deleted(FakeEvent(chat_id=99, msg_id=11))
        self.assertNotIn(99, self.w.stores)

    def test_report_and_reset_requests(self):
        self.w.live_start(50)
        snap = FakeSnapshot(self.rpc, 14, 11, PEER, "hi", 100)
        self.w.on_new_message(FakeEvent(snap=snap))
        self._touch(self.w.REPORT_REQUEST)
        self.w.handle_app_requests()
        out = os.path.join(self.dir, self.w.REPORT_OUT)
        self.assertTrue(os.path.exists(out))
        self._touch(self.w.RESET_REQUEST)
        self.w.handle_app_requests()
        self.assertEqual(self.w.stores, {})
        self.assertFalse(os.path.exists(
            os.path.join(self.dir, "witness-14.jsonl")))

    def test_reset_deletes_deselected_stores_and_report_artifacts(self):
        # A store from a chat that is no longer opted in, plus report and
        # choices outputs and pending request files, are all witness data
        # and must all go. Nothing may be resurrected by a heartbeat.
        self.w.live_start(50)
        snap = FakeSnapshot(self.rpc, 14, 11, PEER, "hi", 100)
        self.w.on_new_message(FakeEvent(snap=snap))
        self._touch("witness-99.jsonl",
                    '{"kind": "live_start", "ts": 1}\n')
        # Interrupted-write leftovers: a half-compacted store temp and
        # half-written report/choices temps are witness-owned too.
        for name in ("witness-14.jsonl.tmp", "witness-99.jsonl.tmp",
                     self.w.REPORT_OUT + ".tmp", self.w.CHATS_OUT + ".tmp"):
            self._touch(name, '{"partial"')
        # Unrelated files and the user's config must survive untouched.
        self._touch("bot_state.json", '{"provisioned": true}')
        self._touch("unrelated-notes.txt", "keep me")
        self._touch(self.w.REPORT_REQUEST)
        self.w.handle_app_requests()  # leaves witness-summary.json
        self._touch(self.w.CHATS_REQUEST)
        self._touch(self.w.RESET_REQUEST)
        self.w.handle_app_requests()
        for name in ("witness-14.jsonl", "witness-99.jsonl",
                     "witness-14.jsonl.tmp", "witness-99.jsonl.tmp",
                     self.w.REPORT_OUT, self.w.REPORT_OUT + ".tmp",
                     self.w.CHATS_OUT, self.w.CHATS_OUT + ".tmp",
                     self.w.REPORT_REQUEST, self.w.CHATS_REQUEST,
                     self.w.RESET_REQUEST):
            self.assertFalse(os.path.exists(os.path.join(self.dir, name)),
                             "%s survived reset" % name)
        # ...while the user's opt-in selection is NOT observation data
        # and unrelated files are not witness-owned: all must survive.
        for name in ("witness_config.json", "bot_state.json",
                     "unrelated-notes.txt"):
            self.assertTrue(os.path.exists(os.path.join(self.dir, name)),
                            "%s wrongly deleted" % name)
        self.assertEqual(self.w.stores, {})
        self.assertEqual(self.w.local_index, {})
        self.w.heartbeat()  # must NOT recreate any store file
        self.assertEqual(
            [n for n in os.listdir(self.dir)
             if n.startswith("witness-")
             and (n.endswith(".jsonl") or n.endswith(".jsonl.tmp"))], [])

    def _touch(self, name, content=""):
        with open(os.path.join(self.dir, name), "w") as f:
            f.write(content)

    def test_direct_reset_all_clears_everything_including_requests(self):
        # Direct reset_all() (not via handle_app_requests) must still
        # remove every witness-owned file: all stores, interrupted-write
        # temps, report/choices outputs AND all three pending request
        # files - nothing pending may survive to trigger a later rerun.
        self.w.live_start(50)
        snap = FakeSnapshot(self.rpc, 14, 11, PEER, "hi", 100)
        self.w.on_new_message(FakeEvent(snap=snap))
        self._touch("witness-99.jsonl", '{"kind": "live_start", "ts": 1}\n')
        for name in ("witness-14.jsonl.tmp", "witness-99.jsonl.tmp",
                     self.w.REPORT_OUT, self.w.REPORT_OUT + ".tmp",
                     self.w.CHATS_OUT, self.w.CHATS_OUT + ".tmp",
                     self.w.CHATS_REQUEST, self.w.REPORT_REQUEST,
                     self.w.RESET_REQUEST):
            self._touch(name, '{"partial"')
        self._touch("bot_state.json", '{"provisioned": true}')
        self.w.reset_all()
        left = [n for n in os.listdir(self.dir)
                if n not in ("witness_config.json", "bot_state.json")]
        self.assertEqual(left, [], "witness files survived: %s" % left)
        self.assertEqual(self.w.stores, {})
        self.assertEqual(self.w.local_index, {})
        self.w.heartbeat()  # no resurrection
        self.assertEqual([n for n in os.listdir(self.dir)
                          if n not in ("witness_config.json",
                                       "bot_state.json")], [])

    def test_default_config_witnesses_nothing(self):
        d = tempfile.mkdtemp()  # no config file: default OFF
        w = __import__("witness").BotWitness(
            FakeAccount(self.rpc), d, lambda: {14})
        w.live_start(50)
        snap = FakeSnapshot(self.rpc, 14, 11, PEER, "hi", 100)
        w.on_new_message(FakeEvent(snap=snap))
        self.assertNotIn(14, w.stores)

    def test_no_watched_actor_means_observation_only(self):
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "witness_config.json"), "w") as f:
            _json.dump({"chats": [14], "watched": []}, f)
        w = __import__("witness").BotWitness(
            FakeAccount(self.rpc), d, lambda: {14})
        w.live_start(50)
        snap = FakeSnapshot(self.rpc, 14, 11, PEER, "hi", 100)
        w.on_new_message(FakeEvent(snap=snap))
        open(os.path.join(d, w.REPORT_REQUEST), "w").write("")
        w.handle_app_requests()
        report = _json.load(open(os.path.join(d, w.REPORT_OUT)))
        self.assertEqual(report["chats"]["14"]["candidates"], [])
        self.assertIn("observation only", report["note"])

    def test_choices_request_lists_served_with_flags(self):
        self._touch(self.w.CHATS_REQUEST)
        self.w.handle_app_requests()
        entries = _json.load(open(os.path.join(self.dir, self.w.CHATS_OUT)))
        self.assertEqual(entries, [{"chat_id": 14, "name": "",
                                    "witnessed": True}])

    def test_lazy_store_inherits_live_window(self):
        self.w.live_start(50)
        snap = FakeSnapshot(self.rpc, 14, 11, PEER, "hi", 100)
        self.w.on_new_message(FakeEvent(snap=snap))
        self.assertTrue(self.w.stores[14].is_live_at(100))


class RetentionTest(unittest.TestCase):
    def test_compaction_bounds_growth(self):
        d = tempfile.mkdtemp()
        w = WitnessStore(d, 14)
        w.live_start(1)
        import witness
        old = witness.MAX_BYTES
        witness.MAX_BYTES = 4096
        try:
            for i in range(300):
                w.record_message("mid-%04d@example.invalid" % i, PEER,
                                 100 + i, "Text", False, text="x" * 50)
        finally:
            witness.MAX_BYTES = old
        self.assertLessEqual(os.path.getsize(w.path), 4096 + 4096)
        self.assertIn("mid-0299@example.invalid", w.messages)


if __name__ == "__main__":
    unittest.main()
