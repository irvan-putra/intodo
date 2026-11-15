"""Unknown-RPC-event tolerance at the bot_runtime live-loop boundary.

Regression for the 0.5.8 field crash: core 2.69 emits PinnedMessagesChanged
on pin/unpin; the bundled client 2.69.0 enum lacks that kind, its
EventType(kind) conversion raises ValueError inside the client's event
loop, and the whole live loop died ("java-side error (live)" -> Service
stopped). The guard in event_guard.run_forever_tolerant must skip ONLY
that conversion failure and keep the loop alive, without touching normal
hook semantics and without logging event payloads or secrets.

The RealLoopTest drives the REAL deltachat_rpc_client 2.69.0 Bot/Client
event loop over a fake account transport - no stubbed loop internals - so
origin checks, re-entry semantics and hook dispatch are exercised against
the actual library code that runs on device.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import event_guard as eg
from deltachat_rpc_client import Bot, EventType
from deltachat_rpc_client._utils import AttrDict
from deltachat_rpc_client.events import RawEvent

REAL_KIND = "PinnedMessagesChanged"
REAL_MSG = "'%s' is not a valid EventType" % REAL_KIND
SECRET = "secret-payload-not-for-logs"
STOP = RuntimeError("sentinel stop")
KNOWN_WIRE = EventType.MSGS_CHANGED.value  # real wire spelling (camelCase)
# Unknown on every client version (released and fork-patched alike):
UNKNOWN_WIRE = "DefinitelyNotARealEventKind_xyz"


class FakeBot:
    """Stands in for Bot: scripted run_forever calls (stub-level tests)."""
    def __init__(self, outcomes):
        self.outcomes = list(outcomes)
        self.calls = 0

    def run_forever(self):
        self.calls += 1
        out = self.outcomes.pop(0)
        if isinstance(out, Exception):
            raise out
        return out


class StubLevelTest(unittest.TestCase):
    def test_known_path_untouched(self):
        # Normal operation: the loop returns when the library returns, no
        # warnings, exactly one entry.
        bot = FakeBot([None])
        with self.assertNoLogs("event_guard", level="WARNING"):
            eg.run_forever_tolerant(bot)
        self.assertEqual(bot.calls, 1)

    def test_other_valueerror_propagates(self):
        # Same type, different wording/origin: NOT the enum conversion.
        bot = FakeBot([ValueError("something else broke")])
        with self.assertRaises(ValueError):
            eg.run_forever_tolerant(bot)

    def test_non_valueerror_propagates(self):
        # e.g. RPC transport closed by the stop watchdog -> service stops
        # exactly as before the guard existed.
        bot = FakeBot([RuntimeError("transport closed")])
        with self.assertRaises(RuntimeError):
            eg.run_forever_tolerant(bot)

    def test_same_text_wrong_origin_propagates(self):
        # A ValueError with the enum's exact wording raised OUTSIDE the
        # client conversion (here: bare raise, no enum/__new__ origin
        # frames) must NOT be swallowed.
        bot = FakeBot([ValueError(REAL_MSG)])
        with self.assertRaises(ValueError):
            eg.run_forever_tolerant(bot)

    def test_skip_log_carries_kind_name_only_no_payload(self):
        # The logged kind is length-bounded and %r-escaped: a crafted kind
        # cannot forge extra log lines, and event PAYLOADS (never part of
        # the conversion message) never reach the log.
        evil_kind = "KindX\nForgedLine"
        try:
            _process_events(evil_kind)  # real conversion site + enum raise
        except ValueError as e:
            err = e
        self.assertIn("is not a valid EventType", str(err))
        bot = FakeBot([err, None])
        bot.last_payload = SECRET
        with self.assertLogs("event_guard", level="WARNING") as cm:
            eg.run_forever_tolerant(bot)
        self.assertEqual(len(cm.output), 1)
        self.assertIn("KindX", cm.output[0])
        self.assertNotIn("ForgedLine", cm.output[0].split("KindX")[0])
        self.assertNotIn("\nForgedLine", cm.output[0])  # escaped, not raw
        self.assertNotIn(SECRET, cm.output[0])


def _process_events(kind):
    """Mirror of the client's conversion site: gives the origin check a
    real _process_events frame (stub-level tests only; RealLoopTest drives
    the genuine library frame)."""
    EventType(kind)


class FakeAccount:
    """Minimal Account stand-in for the REAL Client event loop."""
    id = 1

    def __init__(self, events):
        self.events = list(events)
        self.started = 0

    def is_configured(self):
        return True

    def start_io(self):
        self.started += 1

    def get_next_messages(self):
        return []

    def wait_for_event(self):
        out = self.events.pop(0)
        if isinstance(out, Exception):
            raise out
        return out


class RealLoopTest(unittest.TestCase):
    """Drive event_guard.run_forever_tolerant around the REAL 2.69.0
    Bot.run_forever -> Client._process_events loop."""

    def _bot(self, events, hook):
        acct = FakeAccount(events)
        bot = Bot(acct, hooks=[(hook, RawEvent(EventType.MSGS_CHANGED))])
        return bot, acct

    def test_unknown_then_known_once_no_duplicate_dispatch(self):
        seen = []

        def hook(event):
            seen.append(event.kind)

        bot, acct = self._bot(
            [AttrDict(kind=UNKNOWN_WIRE), AttrDict(kind=KNOWN_WIRE), STOP],
            hook)
        with self.assertLogs("event_guard", level="WARNING") as cm:
            with self.assertRaises(RuntimeError):
                eg.run_forever_tolerant(bot)
        # unknown kind skipped exactly once, loop re-entered and dispatched
        # the following known event exactly once (no duplicate handlers).
        self.assertEqual(seen, [EventType.MSGS_CHANGED])
        self.assertEqual(acct.started, 2)  # two loop entries, idempotent IO
        self.assertIn("skipping unknown RPC event kind", cm.output[0])
        self.assertIn(UNKNOWN_WIRE, cm.output[0])

    def test_handler_valueerror_same_text_still_propagates_to_hook_log(self):
        # A hook raising ValueError with the enum's exact wording is caught
        # by the client's own _on_event (logged there), never reaches the
        # guard, and the loop continues - no double skip, no loop death.
        seen = []

        def hook(event):
            seen.append(event.kind)
            raise ValueError(REAL_MSG)

        bot, acct = self._bot([AttrDict(kind=KNOWN_WIRE), STOP], hook)
        with self.assertRaises(RuntimeError):
            eg.run_forever_tolerant(bot)
        self.assertEqual(seen, [EventType.MSGS_CHANGED])
        self.assertEqual(acct.started, 1)  # guard never re-entered


if __name__ == "__main__":
    unittest.main()
