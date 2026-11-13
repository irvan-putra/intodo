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

    def test_different_text_is_a_different_reply(self):
        self.ledger.plan_reply(14, 100, "first")
        pid2 = self.ledger.plan_reply(14, 100, "second")
        self.assertTrue(pid2)

    def test_interrupted_send_reconciles_to_sent_no_duplicate(self):
        # crash after the send succeeded remotely but before mark_sent:
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.assertIn(pid, self.ledger.unresolved())
        # the restarted process reads the chat and finds the reply there:
        self.assertEqual(self.ledger.reconcile(pid, [(200, "on it")]), "sent")
        with self.assertRaises(DuplicateReply):
            self.ledger.plan_reply(14, 100, "on it")

    def test_absent_reply_in_observation_is_not_failure(self):
        # crash after send attempt; the restarted process reads the chat and
        # does NOT see the reply - that alone must settle nothing:
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.assertEqual(self.ledger.reconcile(pid, []), "unresolved")
        self.assertEqual(self.ledger.reconcile(pid, [(150, "unrelated")]), "unresolved")
        self.assertIn(pid, self.ledger.unresolved())
        with self.assertRaises(DuplicateReply):  # still pending, still blocked
            self.ledger.plan_reply(14, 100, "on it")

    def test_later_inbound_alone_is_not_send_failure_proof(self):
        # a message arriving after the attempted send, even from the peer,
        # says nothing authoritative about the send's outcome:
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.ledger.mark_unknown(pid)
        self.assertEqual(self.ledger.reconcile(pid, [(160, "and another thing")]),
                         "unresolved")
        self.assertIn(pid, self.ledger.unresolved())
        with self.assertRaises(UnknownOutcome):
            self.ledger.plan_reply(14, 100, "on it")

    def test_authoritative_failure_allows_retry(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.assertEqual(self.ledger.reconcile(pid, [(150, "unrelated")]), "unresolved")
        # only the transport's own terminal failure settles it failed:
        self.ledger.mark_failed(pid, "message state failed")
        self.assertNotIn(pid, self.ledger.unresolved())
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
        self.assertIn(pid, self.ledger.unresolved())
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

    def test_state_survives_reopen(self):
        pid = self.ledger.plan_reply(14, 100, "on it")
        self.ledger.mark_sent(pid, 200)
        reopened = ReplyLedger(self.path)
        self.assertTrue(reopened.is_answered(14, 100))
        with self.assertRaises(DuplicateReply):
            reopened.plan_reply(14, 100, "on it")


if __name__ == "__main__":
    unittest.main()
