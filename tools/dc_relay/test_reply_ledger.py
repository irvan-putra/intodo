"""Tests for reply_ledger: dedup, interruption, unknown outcomes, concurrency.

No real DeltaChat account is involved - the ledger is transport-agnostic, so
the tests supply "observed chat messages" directly, exactly what a caller
would read back from its client after an uncertain send.
"""
import json
import os
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from reply_ledger import (ReplyLedger, LedgerError, DuplicateReply,
                          UnknownOutcome)



def ev(mid, text, chat=14, outgoing=True, quote=None):
    """An observed chat message as a transport adapter must supply it."""
    m = {"id": mid, "chat": chat, "outgoing": outgoing, "text": text}
    if quote is not None:
        m["quote"] = quote
    return m


class LedgerCase(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "ledger.json")
        self.ledger = ReplyLedger(self.path)

    def test_inbound_recording_is_idempotent(self):
        self.assertTrue(self.ledger.record_inbound(14, 100, "alice@example.org", "hello"))
        self.assertFalse(self.ledger.is_new_inbound(14, 100))
        self.assertTrue(self.ledger.is_new_inbound(14, 101))
        self.assertFalse(self.ledger.record_inbound(14, 100, "alice@example.org", "hello"))
        self.assertFalse(self.ledger.is_new_inbound(14, 100))

    def test_duplicate_reply_rejected_while_pending(self):
        self.ledger.plan_reply(14, 100, "on it")
        with self.assertRaises(DuplicateReply):
            self.ledger.plan_reply(14, 100, "on it")

    def test_duplicate_reply_rejected_after_sent(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.ledger.mark_sent(pid, 200)
        with self.assertRaises(DuplicateReply):
            self.ledger.plan_reply(14, 100, "on it")
        self.assertTrue(self.ledger.is_answered(14, 100))
        self.assertTrue(self.ledger.is_answered(14, 100, "on it"))
        self.assertFalse(self.ledger.is_answered(14, 100, "different text"))

    def test_one_intent_per_inbound_regardless_of_body(self):
        # a reworded reply to the same inbound must not bypass dedupe
        self.ledger.plan_reply(14, 100, "first")
        with self.assertRaises(DuplicateReply):
            self.ledger.plan_reply(14, 100, "second")
        # ... even when the first attempt's outcome is unknown
        self.ledger.mark_unknown(self.ledger.unresolved()[0]["plan"])
        with self.assertRaises(UnknownOutcome):
            self.ledger.plan_reply(14, 100, "second")

    def test_interrupted_send_reconciles_to_sent_no_duplicate(self):
        # crash after the send succeeded remotely but before mark_sent:
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.assertIn(pid, [r["plan"] for r in self.ledger.unresolved()])
        # the restarted process reads the chat and finds the reply there:
        self.assertEqual(self.ledger.reconcile(pid, [ev(200, "on it", quote=100)]), "sent")
        with self.assertRaises(DuplicateReply):
            self.ledger.plan_reply(14, 100, "on it")

    def test_absent_reply_in_observation_is_not_failure(self):
        # crash after send attempt; the restarted process reads the chat and
        # does NOT see the reply - that alone must settle nothing:
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.assertEqual(self.ledger.reconcile(pid, []), "unresolved")
        self.assertEqual(self.ledger.reconcile(pid, [ev(150, "unrelated", outgoing=False)]), "unresolved")
        self.assertIn(pid, [r["plan"] for r in self.ledger.unresolved()])
        with self.assertRaises(DuplicateReply):  # still pending, still blocked
            self.ledger.plan_reply(14, 100, "on it")

    def test_later_inbound_alone_is_not_send_failure_proof(self):
        # a message arriving after the attempted send, even from the peer,
        # says nothing authoritative about the send's outcome:
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.ledger.mark_unknown(pid)
        self.assertEqual(self.ledger.reconcile(pid, [ev(160, "and another thing", outgoing=False)]),
                         "unresolved")
        self.assertIn(pid, [r["plan"] for r in self.ledger.unresolved()])
        with self.assertRaises(UnknownOutcome):
            self.ledger.plan_reply(14, 100, "on it")

    def test_authoritative_failure_allows_retry(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.assertEqual(self.ledger.reconcile(pid, [ev(150, "unrelated", outgoing=False)]), "unresolved")
        # only the transport's own terminal failure settles it failed:
        self.ledger.mark_failed(pid, "message state failed")
        self.assertNotIn(pid, [r["plan"] for r in self.ledger.unresolved()])
        pid2 = self.ledger.plan_reply(14, 100, "on it")
        # deterministic plan id: the retry reuses it and counts the attempt
        self.assertEqual(pid, pid2)
        with open(self.path) as fh:
            rec = json.load(fh)["replies"][pid]
        self.assertEqual(rec["attempts"], 2)
        self.assertEqual(rec["state"], "pending")

    def test_unknown_outcome_blocks_blind_retry(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.ledger.mark_unknown(pid)
        with self.assertRaises(UnknownOutcome):
            self.ledger.plan_reply(14, 100, "on it")
        self.assertIn(pid, [r["plan"] for r in self.ledger.unresolved()])
        self.assertEqual(self.ledger.reconcile(pid, []), "unresolved")
        with self.assertRaises(UnknownOutcome):  # still blocked: not settled
            self.ledger.plan_reply(14, 100, "on it")
        self.ledger.mark_failed(pid, "message reached terminal failed state")
        self.ledger.plan_reply(14, 100, "on it")  # allowed after settling

    def test_concurrent_planners_exactly_one_wins(self):
        results = []

        def race():
            try:
                results.append(self.ledger.plan_reply(14, 100, "on it"))
            except DuplicateReply:
                results.append(None)

        threads = [threading.Thread(target=race) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(sum(1 for r in results if r is not None), 1)

    def test_corrupt_ledger_fails_closed(self):
        with open(self.path, "w") as fh:
            fh.write("{not json")
        with self.assertRaises(LedgerError):
            self.ledger.is_new_inbound(14, 100)
        with self.assertRaises(LedgerError):
            self.ledger.plan_reply(14, 100, "on it")

    def test_ledger_stores_hashes_not_text(self):
        secret = "the launch codes are blue"
        self.ledger.record_inbound(14, 100, "alice@example.org", secret)
        self.ledger.plan_reply(14, 100, secret)
        with open(self.path) as fh:
            raw = fh.read()
        self.assertNotIn(secret, raw)
        self.assertNotIn("launch", raw)
        json.loads(raw)  # still valid JSON after every mutation

    def test_sent_is_terminal_no_demotion_no_rewrites(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.ledger.mark_sent(pid, 200)
        with self.assertRaises(LedgerError):  # cannot demote to retryable
            self.ledger.mark_failed(pid, "message reached terminal failed state")
        with self.assertRaises(LedgerError):
            self.ledger.mark_unknown(pid)
        with self.assertRaises(LedgerError):  # no late/repeated write
            self.ledger.mark_sent(pid, 201)
        with self.assertRaises(DuplicateReply):  # and no replan
            self.ledger.plan_reply(14, 100, "on it")

    def test_reconcile_requires_exact_association(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        # peer-authored same text: not ours, does not settle
        self.assertEqual(self.ledger.reconcile(pid, [ev(200, "on it", outgoing=False)]), "unresolved")
        # our same text in a DIFFERENT chat: wrong context, does not settle
        self.assertEqual(self.ledger.reconcile(pid, [ev(200, "on it", chat=12, quote=100)]), "unresolved")
        # our same text in the right chat but quoting something else
        self.assertEqual(self.ledger.reconcile(pid, [ev(200, "on it", quote=99)]), "unresolved")
        # our same text in the right chat with no quote linkage at all:
        # an old unrelated bot reply looks exactly like this - no settle
        self.assertEqual(self.ledger.reconcile(pid, [ev(200, "on it")]), "unresolved")
        self.assertIn(pid, [r["plan"] for r in self.ledger.unresolved()])
        # exact association: outgoing, same chat, quotes the inbound
        self.assertEqual(self.ledger.reconcile(pid, [ev(200, "on it", quote=100)]), "sent")
        self.assertTrue(self.ledger.is_answered(14, 100))

    def test_reconcile_requires_text_match_even_with_quote(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.assertEqual(self.ledger.reconcile(pid, [ev(200, "different words", quote=100)]), "unresolved")

    def test_reconcile_leaves_settled_plans_untouched(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.ledger.mark_sent(pid, 200)
        self.assertEqual(self.ledger.reconcile(pid, []), "sent")  # no mutation
        with open(self.path) as fh:
            self.assertEqual(json.load(fh)["replies"][pid]["reply_msg"], 200)

    def test_failed_plan_must_be_replanned_before_send(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.ledger.mark_failed(pid, "send rejected before acceptance")
        with self.assertRaises(LedgerError):  # failed is settled, not sendable
            self.ledger.mark_sent(pid, 200)
        pid2 = self.ledger.plan_reply(14, 100, "on it")  # explicit re-plan
        self.assertEqual(pid, pid2)
        self.ledger.mark_sent(pid, 200)  # now valid from pending
        self.assertTrue(self.ledger.is_answered(14, 100))

    def test_concurrent_init_and_first_write_survive(self):
        def worker(n):
            fresh = ReplyLedger(self.path)
            fresh.record_inbound(14, 500 + n, "alice@example.org", "m%d" % n)
        threads = [threading.Thread(target=worker, args=(n,)) for n in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        for n in range(8):
            self.assertFalse(self.ledger.is_new_inbound(14, 500 + n))

    def test_compound_key_is_unambiguous(self):
        self.assertTrue(self.ledger.record_inbound("a:b", "c", "s@x.org", "one"))
        self.assertTrue(self.ledger.record_inbound("a", "b:c", "s@x.org", "two"))
        self.assertFalse(self.ledger.is_new_inbound("a:b", "c"))
        self.assertFalse(self.ledger.is_new_inbound("a", "b:c"))
        self.assertTrue(self.ledger.is_new_inbound("a:b", "b:c"))

    def test_plan_ids_carry_full_digest(self):
        import hashlib
        pid1 = self.ledger.plan_reply(14, 100, "reply text alpha")
        pid2 = self.ledger.plan_reply(14, 101, "reply text omega")
        self.assertNotEqual(pid1, pid2)
        for pid, text in ((pid1, "reply text alpha"), (pid2, "reply text omega")):
            full = hashlib.sha256(text.encode("utf-8")).hexdigest()
            self.assertTrue(pid.endswith(full), "plan id must carry the full digest")

    def test_legacy_v3_ledger_migrates_and_still_blocks(self):
        # an actual v3-format file: no version, colon keys, truncated plan id
        th = __import__("hashlib").sha256(b"on it").hexdigest()
        legacy = {
            "inbound": {"14:100": {"chat": 14, "msg": 100, "sender": "a@x.org",
                                   "text_hash": th, "ts": 1.0},
                        "14:99": {"chat": 14, "msg": 99, "sender": "a@x.org",
                                  "text_hash": "0" * 64, "ts": 1.2}},
            "replies": {
                "14:100:" + th[:12]: {"inbound": "14:100", "text_hash": th,
                                       "state": "unknown", "reply_msg": None,
                                       "ts": 2.0, "attempts": 1},
                "14:99:" + "0" * 12: {"inbound": "14:99", "text_hash": "0" * 64,
                                       "state": "sent", "reply_msg": 150,
                                       "ts": 1.5, "attempts": 1},
            },
        }
        with open(self.path, "w") as fh:
            fh.write(json.dumps(legacy))
        fresh = ReplyLedger(self.path)  # migrates on open
        # legacy inbound is still seen - no duplicate answer
        self.assertFalse(fresh.is_new_inbound(14, 100))
        # legacy unknown plan is tracked, unresolved, and blocks any retry
        un = fresh.unresolved()
        self.assertEqual(len(un), 1)
        self.assertEqual(un[0]["chat"], "14")
        self.assertEqual(un[0]["msg"], "100")
        self.assertEqual(un[0]["state"], "unknown")
        with self.assertRaises(UnknownOutcome):
            fresh.plan_reply(14, 100, "on it")
        # legacy sent plan still blocks re-planning
        with self.assertRaises(DuplicateReply):
            fresh.plan_reply(14, 99, "anything")
        # and the migrated unknown plan reconciles with quote evidence
        self.assertEqual(fresh.reconcile(un[0]["plan"], [ev(200, "on it", quote=100)]), "sent")
        # the migrated file carries the version marker
        with open(self.path) as fh:
            self.assertEqual(json.load(fh)["version"], 2)

    def test_legacy_stored_ids_authoritative_over_key_string(self):
        # the colon key is ambiguous ("a","b/c" vs "a:b","c"); the stored
        # chat/msg fields decide identity, so no record is re-keyed wrong
        with open(self.path, "w") as fh:
            fh.write(json.dumps({
                "inbound": {"a:b:c": {"chat": "a:b", "msg": "c",
                                      "sender": "s", "text_hash": "1" * 64,
                                      "ts": 1.0}},
                "replies": {}}))
        led = ReplyLedger(self.path)
        self.assertFalse(led.is_new_inbound("a:b", "c"))
        self.assertTrue(led.is_new_inbound("a", "b/c"))

    def test_legacy_reply_without_inbound_record_fails_closed(self):
        with open(self.path, "w") as fh:
            fh.write(json.dumps({
                "inbound": {},
                "replies": {"14:100:" + "a" * 12: {"inbound": "14:100",
                            "text_hash": "a" * 64, "state": "pending",
                            "reply_msg": None, "ts": 2.0, "attempts": 1}}}))
        with open(self.path) as fh:
            before = fh.read()
        with self.assertRaises(LedgerError):
            ReplyLedger(self.path)
        with open(self.path) as fh:
            self.assertEqual(fh.read(), before)

    def test_empty_or_wrong_shape_top_level_rejected_never_reset(self):
        for payload in ({}, [], {"inbound": []}, {"inbound": {}, "replies": []}):
            with open(self.path, "w") as fh:
                fh.write(json.dumps(payload))
            with open(self.path) as fh:
                before = fh.read()
            with self.assertRaises(LedgerError):
                ReplyLedger(self.path)
            with open(self.path) as fh:
                self.assertEqual(fh.read(), before)

    def test_v2_unknown_state_blocks_everything_no_replan(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        with open(self.path) as fh:
            data = json.load(fh)
        data["replies"][pid]["state"] = "weird"
        with open(self.path, "w") as fh:
            json.dump(data, fh)
        with open(self.path) as fh:
            before = fh.read()
        fresh = ReplyLedger(self.path)  # open is lazy; every read validates
        with self.assertRaises(LedgerError):
            fresh.plan_reply(14, 100, "a different body")
        with self.assertRaises(LedgerError):
            fresh.is_new_inbound(14, 100)
        with self.assertRaises(LedgerError):
            fresh.unresolved()
        with open(self.path) as fh:
            self.assertEqual(fh.read(), before)

    def test_v2_key_record_mismatch_rejected(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        with open(self.path) as fh:
            data = json.load(fh)
        rec = data["replies"].pop(pid)
        data["replies"]["tampered:pid"] = rec
        with open(self.path, "w") as fh:
            json.dump(data, fh)
        with self.assertRaises(LedgerError):
            ReplyLedger(self.path).is_new_inbound(1, 1)

    def test_legacy_key_disagreeing_with_stored_ids_rejected(self):
        with open(self.path, "w") as fh:
            fh.write(json.dumps({
                "inbound": {"14:100": {"chat": 15, "msg": 200, "sender": "s",
                                       "text_hash": "1" * 64, "ts": 1.0}},
                "replies": {}}))
        with open(self.path) as fh:
            before = fh.read()
        with self.assertRaises(LedgerError):
            ReplyLedger(self.path)
        with open(self.path) as fh:
            self.assertEqual(fh.read(), before)

    def test_legacy_plan_key_hash_mismatch_rejected(self):
        th = "b" * 64
        with open(self.path, "w") as fh:
            fh.write(json.dumps({
                "inbound": {"14:100": {"chat": 14, "msg": 100, "sender": "s",
                                       "text_hash": "1" * 64, "ts": 1.0}},
                "replies": {"14:100:" + "c" * 12: {"inbound": "14:100",
                            "text_hash": th, "state": "pending",
                            "reply_msg": None, "ts": 2.0, "attempts": 1}}}))
        with open(self.path) as fh:
            before = fh.read()
        with self.assertRaises(LedgerError):
            ReplyLedger(self.path)
        with open(self.path) as fh:
            self.assertEqual(fh.read(), before)

    def test_mark_sent_requires_transport_message_id(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        for bad in (None, "", True):
            with self.assertRaises(LedgerError):
                self.ledger.mark_sent(pid, bad)
        self.assertIn(pid, [r["plan"] for r in self.ledger.unresolved()])
        # and a persisted sent record with a null id is rejected on read
        self.ledger.mark_sent(pid, 300)
        with open(self.path) as fh:
            data = json.load(fh)
        data["replies"][pid]["reply_msg"] = None
        with open(self.path, "w") as fh:
            json.dump(data, fh)
        with self.assertRaises(LedgerError):
            ReplyLedger(self.path).is_answered(14, 100)

    def test_public_api_rejects_bad_ids_without_poisoning(self):
        with open(self.path) as fh:
            before = fh.read()
        for bad_chat, bad_msg in ((None, 1), (True, 1), ("", 1), (1, None),
                                  (1, False), (1, ""), ([1], 1), (1, 2.5)):
            for call in (lambda c, m: self.ledger.record_inbound(c, m, "s", "t"),
                         lambda c, m: self.ledger.plan_reply(c, m, "t"),
                         lambda c, m: self.ledger.is_new_inbound(c, m),
                         lambda c, m: self.ledger.is_answered(c, m)):
                with self.assertRaises(LedgerError):
                    call(bad_chat, bad_msg)
        with self.assertRaises(LedgerError):
            self.ledger.record_inbound(14, 100, "s", None)
        with self.assertRaises(LedgerError):
            self.ledger.plan_reply(14, 100, None)
        with open(self.path) as fh:
            self.assertEqual(fh.read(), before)  # never poisoned
        # and the ledger is fully usable afterwards
        self.assertTrue(self.ledger.record_inbound(14, 100, "s", "hi"))
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.ledger.mark_sent(pid, 300)
        self.assertTrue(self.ledger.is_answered(14, 100))

    def test_unparseable_legacy_key_fails_closed_keeps_file(self):
        with open(self.path, "w") as fh:
            fh.write(json.dumps({"inbound": {"noColonHere": {}}, "replies": {}}))
        with open(self.path) as fh:
            before = fh.read()
        with self.assertRaises(LedgerError):
            ReplyLedger(self.path)
        with open(self.path) as fh:
            self.assertEqual(fh.read(), before)  # never dropped, never reset

    def test_unknown_newer_format_version_fails_closed(self):
        with open(self.path, "w") as fh:
            fh.write(json.dumps({"version": 99, "inbound": {}, "replies": {}}))
        with self.assertRaises(LedgerError):
            ReplyLedger(self.path)

    def test_malformed_evidence_never_settles(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        # missing id: cannot record or correlate - skipped
        self.assertEqual(self.ledger.reconcile(pid, [
            {"chat": 14, "outgoing": True, "text": "on it", "quote": 100}]), "unresolved")
        # truthy-but-not-True authorship flags are rejected
        self.assertEqual(self.ledger.reconcile(pid, [ev(200, "on it", quote=100, outgoing=1)]), "unresolved")
        self.assertEqual(self.ledger.reconcile(pid, [ev(200, "on it", quote=100, outgoing="yes")]), "unresolved")
        # non-dict entries are a caller bug: fail closed, no silent skip
        with self.assertRaises(LedgerError):
            self.ledger.reconcile(pid, [(200, "on it")])
        self.assertIn(pid, [r["plan"] for r in self.ledger.unresolved()])

    def test_readme_recovery_recipe_across_chats(self):
        # execute the README recipe verbatim-ish: pending + unknown, two chats
        p14 = self.ledger.plan_reply(14, 100, "on it")
        p12 = self.ledger.plan_reply(12, 50, "looking now")
        self.ledger.mark_unknown(p12)
        p14b = self.ledger.plan_reply(14, 101, "second answer")
        self.ledger.mark_sent(p14b, 300)
        # recipe: only pending becomes unknown; already-unknown is not touched
        for rec in self.ledger.unresolved():
            if rec["state"] == "pending":
                self.ledger.mark_unknown(rec["plan"])
        # reconcile per-plan chat: chat 12's reply is observed quote-correlated
        for rec in self.ledger.unresolved():
            chat = int(rec["chat"])
            observed = {"12": [ev(60, "looking now", chat=12, quote=50)], "14": []}[rec["chat"]]
            self.ledger.reconcile(rec["plan"], observed)
        self.assertTrue(self.ledger.is_answered(12, 50))
        self.assertFalse(self.ledger.is_answered(14, 100))  # still honestly blocked
        states = {r["plan"]: r["state"] for r in self.ledger.unresolved()}
        self.assertEqual(states, {p14: "unknown"})  # sent plan settled; p14 blocked

    def test_state_survives_reopen(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.ledger.mark_sent(pid, 200)
        reopened = ReplyLedger(self.path)
        self.assertTrue(reopened.is_answered(14, 100))
        with self.assertRaises(DuplicateReply):
            reopened.plan_reply(14, 100, "on it")


if __name__ == "__main__":
    unittest.main()
