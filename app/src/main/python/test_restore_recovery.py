"""v2.1 bounded restore recovery (itd_restore_v2): generation-frozen plans,
queued-cursor resume, shared retry budget with cooldown, loud limits.

Host-only tests over the real OpsStore (forced stdlib sqlite3 = the phone
path) and the real DeliveryBot; only the update send is faked. No device
claims.
"""
import json
import logging
import os
import sys
import tempfile

os.environ.setdefault("OPS_STORE_FORCE_SQLITE", "1")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import delivery_bot
from delivery_bot import (DeliveryBot, ORIGIN_BOT, RESTORE_SCHEMA, DIGEST_RULE,
                          RESTORE_BATCH, RESUME_BUDGET, RESUME_COOLDOWN,
                          MAX_BATCH_BYTES, MAX_MISSING_HINT)
from ops_store import OpsStore, is_resume_hint

OWNER = "me@x"
BOT = "bot@nine.testrun.org"
LIST = "list-x"
VER = "1.0.0"
CHAT = 77
MID = 4242


def mk_op(i, device="dev1", hlc=None, value=None):
    return {"item": "t%d" % i, "field": "title",
            "stamp": {"hlc": hlc if hlc is not None else i, "device": device},
            "value": value if value is not None else "task %d" % i}


class Ctx:
    """A bot wired to a real store file, with an injectable clock and a
    fake send that records envelopes. reopen() simulates a process restart:
    a fresh DeliveryBot over the SAME store file (persisted state reloads).
    """

    def __init__(self):
        self.dir = tempfile.mkdtemp()
        self.path = os.path.join(self.dir, "ops.db")
        self.clock = [1000.0]
        self.sent = []
        self.send = lambda msg_id, payload, info="": self.sent.append(payload)
        self.serial = [0]
        self.open()

    def open(self):
        self.store = OpsStore(self.path)
        self.bot = DeliveryBot(None, self.store, OWNER, allowed_chat_ids={CHAT},
                               list_id=LIST, bot_addr=BOT,
                               now=lambda: self.clock[0],
                               min_restore_interval=300)

    def reopen(self):
        self.open()
        return self.bot

    def register(self, msg_id=MID, version=VER):
        self.bot._register_trusted(CHAT, msg_id, version, ORIGIN_BOT, BOT)

    def seed(self, ops, msg_id=MID):
        ups = []
        for op in ops:
            self.serial[0] += 1
            ups.append({"serial": self.serial[0], "payload": {"ops": [op]},
                        "info": ""})
        self.store.apply_updates(msg_id, ups)

    def boot(self, msg_id=MID, version=VER):
        return self.bot.handle_boot(msg_id, {"list_id": LIST, "boot": version})

    def restore(self, msg_id=MID):
        return self.bot.restore_into(msg_id, self.send)


def test_frozen_plan_schema_and_envelope():
    c = Ctx()
    c.register()
    c.seed([mk_op(i) for i in range(5)])
    assert c.boot() == "restore-authorized"
    assert c.restore() == 5
    assert len(c.sent) == 1
    env = c.sent[0]
    gen = env["generation_id"]
    assert len(gen) == 64 and all(ch in "0123456789abcdef" for ch in gen)
    assert env["schema"] == RESTORE_SCHEMA == "itd_restore_v2"
    assert env["list_id"] == LIST and env["target_version"] == VER
    assert env["batch_index"] == 0 and env["batch_count"] == 1
    assert env["highwater"] == 5
    assert env["nonce"] == "%s:0" % gen[:12]
    assert env["digest"] == env["generation_digest"]
    assert env["digest"] == c.bot.canonical_digest(env["ops"])
    assert env["ops"] == [mk_op(i) for i in range(5)]
    assert env[delivery_bot.RESTORE_MARKER] is True  # echo guard compat
    plan = c.bot._plans[gen]
    assert plan["batch_size"] == RESTORE_BATCH
    assert plan["schema"] == RESTORE_SCHEMA and plan["digest_rule"] == DIGEST_RULE
    assert plan["batch_sizes"] == [5]
    print("frozen plan schema and envelope ok")


def test_batching_and_zero_op_completion():
    c = Ctx()
    c.register()
    # zero ops -> exactly one empty completion envelope, digest of "[]"
    assert c.restore() == 0
    assert len(c.sent) == 1
    env = c.sent[0]
    assert env["ops"] == [] and env["batch_index"] == 0 and env["batch_count"] == 1
    assert env["digest"] == c.bot.canonical_digest([])
    # 120 ops -> 3 batches 50/50/20, indices 0..2
    c2 = Ctx()
    c2.register()
    c2.seed([mk_op(i) for i in range(120)])
    assert c2.restore() == 120
    assert len(c2.sent) == 3
    assert [e["batch_index"] for e in c2.sent] == [0, 1, 2]
    assert [len(e["ops"]) for e in c2.sent] == [50, 50, 20]
    assert all(e["batch_count"] == 3 for e in c2.sent)
    assert all(e["generation_id"] == c2.sent[0]["generation_id"] for e in c2.sent)
    print("batching and zero-op completion ok")


def test_crash_restart_resume_cursor():
    c = Ctx()
    c.register()
    c.seed([mk_op(i) for i in range(120)])
    # crash while queueing: batch 0 accepted, batch 1 send raises
    calls = [0]
    real_send = c.send

    def flaky(msg_id, payload, info=""):
        if calls[0] == 1:
            raise RuntimeError("process died")
        calls[0] += 1
        real_send(msg_id, payload, info)

    try:
        c.bot.restore_into(MID, flaky)
        raise AssertionError("expected crash")
    except RuntimeError:
        pass
    assert len(c.sent) == 1 and c.sent[0]["batch_index"] == 0
    gen = c.sent[0]["generation_id"]
    # restart: fresh bot over the same store file
    bot = c.reopen()
    inc = bot.incomplete_generations()
    assert inc == [(MID, gen)], inc
    sent_before = len(c.sent)
    n = bot.resume(MID, c.send, gen=gen, crash=True)
    assert n == 70  # batches 1 + 2 = 50 + 20 ops
    assert [e["batch_index"] for e in c.sent[sent_before:]] == [1, 2]
    assert bot.incomplete_generations() == []
    # crash-resume with nothing un-queued sends nothing and burns NO attempt
    attempts = bot._gen_queue[gen]["attempts"]
    assert bot.resume(MID, c.send, gen=gen, crash=True) == 0
    assert bot._gen_queue[gen]["attempts"] == attempts
    print("crash/restart resume cursor ok")


def test_plain_hint_same_plan_resume_authorized():
    c = Ctx()
    c.register()
    c.seed([mk_op(i) for i in range(120)])
    # partial queue, then a plain boot hint INSIDE the throttle window:
    # same store state -> resume of that generation, throttle-exempt
    calls = [0]
    real_send = c.send

    def flaky(msg_id, payload, info=""):
        if calls[0] == 1:
            raise RuntimeError("died")
        calls[0] += 1
        real_send(msg_id, payload, info)

    try:
        c.bot.restore_into(MID, flaky)
    except RuntimeError:
        pass
    assert c.boot() == "resume-authorized"  # throttle-exempt
    sent_before = len(c.sent)
    n = c.bot.resume(MID, c.send)
    assert n == 120 and len(c.sent) - sent_before == 3  # ALL indices requeued
    assert [e["batch_index"] for e in c.sent[sent_before:]] == [0, 1, 2]
    print("plain-hint same-plan resume ok")


def test_plain_hint_total_loss_member_gets_all():
    # BLOCKER 1 regression: every batch queued (a healthy member got them);
    # a TOTAL-LOSS member's plain hint must still receive the FULL
    # generation, budgeted - never ZERO because of the cursor.
    c = Ctx()
    c.register()
    c.seed([mk_op(i) for i in range(120)])
    c.restore()
    assert len(c.sent) == 3  # fully queued
    assert c.boot() == "resume-authorized"
    n = c.bot.resume(MID, c.send)
    assert n == 120
    assert [e["batch_index"] for e in c.sent[3:]] == [0, 1, 2]
    # budget bound holds: 3 resumes per window, then silence
    assert c.bot.resume(MID, c.send) == 120
    assert c.bot.resume(MID, c.send) == 120
    assert c.bot.resume(MID, c.send) == 0  # budget exhausted
    assert len(c.sent) == 3 + 3 * 3
    print("plain-hint total-loss requeue-all ok")


def test_two_instances_independent_generations():
    # BLOCKER 2 regression: two trusted instances, same chat and version,
    # same store state -> INDEPENDENT generations, plans and cursors; the
    # second instance must not reuse the first's cursor and get ZERO.
    c = Ctx()
    c.register()
    c.register(msg_id=55555)
    c.seed([mk_op(i) for i in range(60)])
    assert c.restore() == 60
    gen_a = c.sent[0]["generation_id"]
    sent_a = len(c.sent)
    assert c.bot.restore_into(55555, c.send) == 60
    gen_b = c.sent[sent_a]["generation_id"]
    assert gen_a != gen_b
    assert len(c.sent) == sent_a + 2  # both batches re-sent for instance 2
    assert c.bot._plans[gen_a]["instance"] == MID
    assert c.bot._plans[gen_b]["instance"] == 55555
    # a resume hint naming instance A's gen from instance B falls forward
    # to B's own current plan, never A's
    n = c.bot.handle_resume(55555, {"list_id": LIST, "boot": VER,
                                    "resume": gen_a, "missing": [0]}, c.send)
    assert n == 50 and c.sent[-1]["generation_id"] == gen_b
    print("two-instance independent generations ok")


def test_complete_current_noop_healthy_opens():
    # Material pre-deployment behavior: FOUR healthy opens must not replay
    # anything, burn budget, or log EXHAUSTED. A verified-complete claim
    # equal to the current store generation for THIS instance is a no-op.
    c = Ctx()
    c.register()
    c.seed([mk_op(i) for i in range(120)])
    c.restore()
    gen = c.sent[0]["generation_id"]
    sent = len(c.sent)
    last_restore = dict(c.bot._last_restore)
    attempts = c.bot._gen_queue[gen]["attempts"]
    for _ in range(4):
        assert c.bot.handle_boot(
            MID, {"list_id": LIST, "boot": VER, "complete": gen}) \
            == "complete-current"
    assert len(c.sent) == sent  # zero replay
    assert c.bot._gen_queue[gen]["attempts"] == attempts  # zero budget burn
    assert c.bot._last_restore == last_restore  # throttle state untouched
    # store changed -> the same claim is stale -> normal bounded path
    c.seed([mk_op(120)])
    assert c.bot.handle_boot(
        MID, {"list_id": LIST, "boot": VER, "complete": gen}) == "throttled"
    c.clock[0] += 301
    assert c.bot.handle_boot(
        MID, {"list_id": LIST, "boot": VER, "complete": gen}) \
        == "restore-authorized"
    print("complete-current healthy-open no-op ok")


def test_complete_claim_validation_and_scope():
    c = Ctx()
    c.register()
    c.register(msg_id=55555)
    c.seed([mk_op(i) for i in range(60)])
    c.restore()
    gen = c.sent[0]["generation_id"]
    # malformed claims are ignored (treated as plain hint: bounded requeue)
    for bad in ("xyz", "A" * 64, 42, True):
        r = c.bot.handle_boot(MID, {"list_id": LIST, "boot": VER,
                                    "complete": bad})
        assert r == "resume-authorized", (bad, r)
    # unknown-but-wellformed claim -> normal path, never a no-op
    r = c.bot.handle_boot(MID, {"list_id": LIST, "boot": VER,
                                "complete": "f" * 64})
    assert r == "resume-authorized", r
    # cross-instance: instance B claiming A's completed gen is NOT a no-op
    # for B (B never received anything) - B gets served its own restore
    assert c.bot.handle_boot(55555, {"list_id": LIST, "boot": VER,
                                     "complete": gen}) == "restore-authorized"
    # untrusted instance: claim never authorizes anything
    assert c.bot.handle_boot(9999, {"list_id": LIST, "boot": VER,
                                    "complete": gen}) is None
    print("complete-claim validation and scope ok")


def test_throttle_for_new_restore_only():
    c = Ctx()
    c.register()
    c.seed([mk_op(1)])
    assert c.boot() == "restore-authorized"
    c.restore()
    # new store state (a new op) -> new generation -> throttle applies
    c.seed([mk_op(2)])
    assert c.boot() == "throttled"
    c.clock[0] += 301
    assert c.boot() == "restore-authorized"
    print("throttle for new restore only ok")


def test_missing_indices_requeue_even_if_queued():
    c = Ctx()
    c.register()
    c.seed([mk_op(i) for i in range(120)])
    c.restore()
    assert len(c.sent) == 3
    gen = c.sent[0]["generation_id"]
    # app says batch 1 never arrived: requeue it even though queued-cursor
    # says it was sent (queued != delivered)
    hint = {"list_id": LIST, "boot": VER, "resume": gen, "missing": [1]}
    assert is_resume_hint(hint)
    n = c.bot.handle_resume(MID, hint, c.send)
    assert n == 50
    assert len(c.sent) == 4 and c.sent[-1]["batch_index"] == 1
    print("missing-indices requeue ok")


def test_budget_exhaustion_and_cooldown(caplog_entries=None):
    c = Ctx()
    c.register()
    c.seed([mk_op(i) for i in range(60)])
    c.restore()
    gen = c.sent[0]["generation_id"]
    hint = {"list_id": LIST, "boot": VER, "resume": gen, "missing": [0]}
    for _ in range(RESUME_BUDGET):
        assert c.bot.handle_resume(MID, hint, c.send) == 50
    sent = len(c.sent)
    recs = []
    h = logging.Handler()
    h.emit = lambda r: recs.append(r)
    logging.getLogger("delivery-bot").addHandler(h)
    # the NEXT resume exhausts the shared budget: nothing sent, loud log
    assert c.bot.handle_resume(MID, hint, c.send) == 0
    assert len(c.sent) == sent
    assert c.bot._gen_queue[gen]["state"] == "exhausted"
    assert any("EXHAUSTED" in r.getMessage() for r in recs)
    # inside the window: still exhausted even after a plain hint
    assert c.boot() == "resume-authorized"
    assert c.bot.resume(MID, c.send) == 0
    # cooldown opens ONE fresh bounded window
    c.clock[0] += RESUME_COOLDOWN + 1
    assert c.bot.handle_resume(MID, hint, c.send) == 50
    assert c.bot._gen_queue[gen]["attempts"] == 1
    assert c.bot._gen_queue[gen]["state"] == "active"
    print("budget exhaustion and cooldown ok")


def test_oversize_op_blocked_loud():
    c = Ctx()
    c.register()
    big = mk_op(1, value="x" * (MAX_BATCH_BYTES))
    c.seed([mk_op(0), big, mk_op(2)])
    recs = []
    h = logging.Handler()
    h.emit = lambda r: recs.append(r)
    logging.getLogger("delivery-bot").addHandler(h)
    assert c.restore() == 0
    assert c.sent == []  # nothing sent
    assert c.store.count() == 3  # NOTHING dropped
    gen = next(iter(c.bot._plans))
    assert c.bot._gen_queue[gen]["state"] == "blocked"
    assert any("BLOCKED" in r.getMessage() for r in recs)
    # retry does not silently proceed either
    assert c.restore() == 0 and c.sent == []
    print("oversize blocked loud ok")


def test_superseded_and_unknown_fall_forward():
    c = Ctx()
    c.register()
    c.seed([mk_op(i) for i in range(60)])
    c.restore()
    gen_a = c.sent[0]["generation_id"]
    # store grows; a new restore supersedes generation A
    c.seed([mk_op(60)])
    c.clock[0] += 301
    assert c.boot() == "restore-authorized"
    c.restore()
    gen_b = c.sent[-1]["generation_id"]
    assert gen_b != gen_a
    assert c.bot._gen_queue[gen_a]["state"] == "superseded"
    # a stale app resumes A: it gets the CURRENT plan's un-queued batches
    sent = len(c.sent)
    n = c.bot.handle_resume(
        MID, {"list_id": LIST, "boot": VER, "resume": gen_a, "missing": [0]},
        c.send)
    assert n >= 0 and all(e["generation_id"] == gen_b for e in c.sent[sent:])
    # unknown generation: same fall-forward, never a refusal loop
    sent = len(c.sent)
    bogus = "f" * 64
    c.bot.handle_resume(MID, {"list_id": LIST, "boot": VER, "resume": bogus,
                              "missing": [0]}, c.send)
    assert all(e["generation_id"] == gen_b for e in c.sent[sent:])
    print("superseded/unknown fall-forward ok")


def test_handle_resume_gates_and_validation():
    c = Ctx()
    c.register()
    c.seed([mk_op(1)])
    c.restore()
    gen = c.sent[0]["generation_id"]
    sent = len(c.sent)
    # untrusted instance
    assert c.bot.handle_resume(9999, {"list_id": LIST, "boot": VER,
                                      "resume": gen, "missing": []}, c.send) == 0
    # wrong list_id / wrong version
    assert c.bot.handle_resume(MID, {"list_id": "other", "boot": VER,
                                     "resume": gen, "missing": []}, c.send) == 0
    assert c.bot.handle_resume(MID, {"list_id": LIST, "boot": "9.9.9",
                                     "resume": gen, "missing": []}, c.send) == 0
    # malformed generation id
    for bad in ("abc", "Z" * 64, 12345):
        assert c.bot.handle_resume(MID, {"list_id": LIST, "boot": VER,
                                         "resume": bad, "missing": []},
                                   c.send) == 0
    # oversized / wrong-type missing list
    assert c.bot.handle_resume(MID, {"list_id": LIST, "boot": VER, "resume": gen,
                                     "missing": list(range(MAX_MISSING_HINT + 1))},
                               c.send) == 0
    assert c.bot.handle_resume(MID, {"list_id": LIST, "boot": VER, "resume": gen,
                                     "missing": "0"}, c.send) == 0
    assert len(c.sent) == sent  # nothing sent by any rejected hint
    # out-of-range indices inside a valid hint are dropped, valid ones kept
    n = c.bot.handle_resume(MID, {"list_id": LIST, "boot": VER, "resume": gen,
                                  "missing": [0, 999, -1]}, c.send)
    assert n == 1 and c.sent[-1]["batch_index"] == 0
    print("resume gates and validation ok")


def test_determinism_across_restart():
    c = Ctx()
    c.register()
    c.seed([mk_op(i) for i in range(120)])
    c.restore()
    snap = [json.dumps(e, sort_keys=True) for e in c.sent]
    gen = c.sent[0]["generation_id"]
    # restart: same store state -> identical generation id and identical
    # frozen batches; re-deriving sends byte-identical envelopes
    bot = c.reopen()
    cur = bot._compute_generation(MID)[0]
    assert cur == gen
    plan = bot._plans[gen]
    batches = bot._plan_batches(plan)
    for i, b in enumerate(batches):
        env = bot._envelope(plan, i, b)
        assert json.dumps(env, sort_keys=True) == snap[i]
    print("determinism across restart ok")


def test_is_boot_shape_with_complete():
    from ops_store import is_boot
    g = "a" * 64
    assert is_boot({"list_id": LIST, "boot": VER})
    assert is_boot({"list_id": LIST, "boot": VER, "complete": g})
    assert not is_boot({"list_id": LIST, "boot": VER, "complete": g,
                        "ops": []})
    assert not is_boot({"list_id": LIST})
    assert not is_boot({"list_id": LIST, "boot": VER, "resume": g,
                        "missing": [0]})  # resume hint is not a boot hint
    print("is_boot shape with complete ok")


def test_is_resume_hint_shape():
    g = "a" * 64
    assert is_resume_hint({"list_id": LIST, "boot": VER, "resume": g})
    assert is_resume_hint({"list_id": LIST, "boot": VER, "resume": g,
                           "missing": [0, 1]})
    assert is_resume_hint({"resume": g})  # key subset is fine
    assert not is_resume_hint({"list_id": LIST, "boot": VER})  # plain boot
    assert not is_resume_hint({"list_id": LIST, "boot": VER, "resume": g,
                               "ops": []})  # extra key
    assert not is_resume_hint({"resume": "abc"})
    assert not is_resume_hint({"resume": "A" * 64})
    assert not is_resume_hint("nope")
    print("is_resume_hint shape ok")


if __name__ == "__main__":
    test_frozen_plan_schema_and_envelope()
    test_batching_and_zero_op_completion()
    test_crash_restart_resume_cursor()
    test_plain_hint_same_plan_resume_authorized()
    test_plain_hint_total_loss_member_gets_all()
    test_two_instances_independent_generations()
    test_complete_current_noop_healthy_opens()
    test_complete_claim_validation_and_scope()
    test_is_boot_shape_with_complete()
    test_throttle_for_new_restore_only()
    test_missing_indices_requeue_even_if_queued()
    test_budget_exhaustion_and_cooldown()
    test_oversize_op_blocked_loud()
    test_superseded_and_unknown_fall_forward()
    test_handle_resume_gates_and_validation()
    test_determinism_across_restart()
    test_is_resume_hint_shape()
    print("ALL RESTORE-RECOVERY TESTS PASS")
