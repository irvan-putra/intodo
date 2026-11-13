# tools/dc_relay — durable reply ledger for bots

A small, dependency-free library for bot processes that must avoid duplicate
replies **even across crashes and restarts**, and must never retry a send
blindly.

`reply_ledger.py` keeps a JSON ledger on disk recording which inbound
messages were seen and which replies were planned, sent, failed, or are
stuck in an unknown state. The transport (DeltaChat core or anything else)
is owned by the caller; the ledger only remembers what happened.

## The problem

A bot that receives a message and sends a reply can crash or be interrupted
between "reply sent" and "state saved". A naive in-memory dedupe set forgets
everything on restart, so a redelivered or replayed inbound message gets
answered twice. The tempting fixes are both unsafe: retrying any send whose
outcome was not recorded can duplicate a reply that actually landed, and
treating "the reply is not visible in the chat" as proof of failure can
duplicate it too — the observation may be stale or partial, and a later
message arriving from the peer says nothing authoritative about an earlier
send's outcome.

## Guarantees

- **Durable record.** Every inbound message and every reply attempt survives
  process restart. A reply is recorded as `pending` **before** the caller
  performs the send, so an interrupted send is visible on the next run.
- **Idempotent dedupe.** `record_inbound` and `plan_reply` are idempotent:
  the same message id or the same planned reply never creates a duplicate
  record. Exactly one caller wins a race to plan the same reply
  (`DuplicateReply` for the rest), enforced across processes. There is one
  reply intent per inbound message, irrespective of body: a reworded reply
  cannot bypass the block on a pending, sent or unresolved plan, and the
  planned body is fixed at planning time.
- **Crash-safe file writes.** The ledger is written to a temporary file that
  is atomically renamed over the old one, so a kill mid-write cannot leave a
  truncated file. A stable sidecar lock file (`<ledger>.lock`, never
  replaced) serializes readers and writers via `flock` — the lock cannot live
  on the data file itself because the atomic rename swaps its inode.
- **No blind retries.** `plan_reply` refuses to plan over a `pending` or
  `unknown` record. Settling is evidence-based and asymmetric:
  - `reconcile(plan, observed_messages)` is **positive-only and
    evidence-checked**. Observed messages are dicts with `id`, `chat`,
    `outgoing`, `text` and `quote` keys. A plan settles `sent` only on a
    message that is outgoing per the transport's own authorship, is in the
    plan's chat, carries the planned text, AND explicitly quotes the plan's
    inbound message. A same-text message without that exact association -
    a peer's message, a different chat, an old unrelated bot reply, or
    anything relying on id recency alone - settles nothing: there is no
    ordering fallback. Otherwise reconcile returns `"unresolved"` and
    changes nothing. Absence in a possibly stale or partial observation is
    not failure evidence. A transport that cannot expose quote/reply
    linkage cannot settle plans through reconcile at all; fail closed is
    the design.
  - `mark_failed(plan, reason)` is the only path to `failed`, and is for a
    transport-authoritative terminal failure of that specific message: the
    message reached the transport's own failed state, or the send call was
    rejected before acceptance. A timeout, an ambiguous exception (which
    may have followed server acceptance) or a missing acknowledgement is
    NOT failure evidence - those stay `unknown`. Only a `failed` plan may
    be re-planned.
- **Terminal settled states.** Transitions are checked atomically under the
  lock: `sent` and `failed` reject late or repeated writes, so a sent plan
  can never be demoted into a retryable one and then duplicated. A failed
  plan becomes sendable again only through an explicit `plan_reply`
  re-plan, which counts the attempt.
- **Fails closed.** A corrupt or unreadable ledger raises `LedgerError`
  rather than silently resetting to "nothing seen".
- **Message bodies are hashed, not kept.** Only a SHA-256 hash of inbound
  and reply text is stored. The ledger DOES retain operational metadata:
  sender address, chat and message ids, timestamps, attempt counts and
  failure reasons — protect the file accordingly (it is a record of who
  wrote when, not of what they said).

## Non-guarantees

- **Not exactly-once delivery.** The ledger guarantees a duplicate reply is
  never *planned* from its records, and that an uncertain outcome blocks
  retry until positively settled. It cannot guarantee every inbound gets a
  reply: a crash after a successful send whose reply is never observed back
  leaves the plan `unknown` forever, trading a possible missed reply for
  never risking a duplicate. That trade is deliberate.
- **No delivery guarantee.** `mark_sent` reflects what the transport
  returned or what was observed in the chat, not proof the peer's device
  received anything.
- **No transport logic.** Sending, reconnecting, and reading back the chat
  are the caller's job; the ledger only applies the evidence it is given.
  Feeding it an unauthoritative "failure" via `mark_failed` is a caller bug
  the library cannot detect.
- **No multi-host safety.** The `flock` sidecar serializes processes on one
  host on one filesystem. Coordinating bots across hosts needs a shared store
  this library does not provide.

## Crash recovery recipe

A plan left `pending` when the process restarts means "we intended to send
and do not know whether we did". On startup:

```python
for rec in ledger.unresolved():
    # each record: {"plan": plan_id, "chat": chat_id, "msg": msg_id,
    #               "state": "pending" | "unknown"}
    if rec["state"] == "pending":
        ledger.mark_unknown(rec["plan"])   # pending -> unknown: honest state
    outcome = ledger.reconcile(rec["plan"],
                               transport.read_chat(int(rec["chat"])))
    # "sent" -> the reply is really there (quote-correlated); done
    # "unresolved" -> stays blocked; only a transport-authoritative
    # terminal failure (mark_failed) makes it retryable
```

Only `pending` is passed to `mark_unknown` (it accepts no other state);
already-`unknown` plans go straight to reconcile. Each record carries its
own chat, so plans from different chats are each reconciled against the
right conversation.

## API sketch

```python
ledger = ReplyLedger("/var/lib/mybot/replies.json")

if ledger.record_inbound(chat_id, msg_id, sender, text):   # False = already seen
    plan = ledger.plan_reply(chat_id, msg_id, reply_text)  # DuplicateReply if planned
    try:
        sent = transport.send(chat_id, reply_text)         # caller-owned
    except Exception:
        ledger.mark_unknown(plan)                          # blocks blind retry
        raise
    if sent and sent.msg_id:
        ledger.mark_sent(plan, sent.msg_id)
    else:
        ledger.mark_unknown(plan)

# observed-evidence shape for reconcile():
#   {"id": 201, "chat": 14, "outgoing": True, "text": "...", "quote": 100}
```

## Adapter truth boundary

The evidence fields `id`, `chat`, `outgoing` and `quote` are claims from
the caller's transport adapter. The ledger enforces their shape and
strictness (a real id, `outgoing` exactly `True`, exact chat and quote
match) but cannot verify them against the network. An adapter must report
only what its transport actually exposes: authorship from the transport's
own flag (never inferred from timing or ordering), quote linkage only when
the transport provides it. A transport that cannot expose quote linkage
cannot settle plans through `reconcile()` at all - that is the design,
not a gap.

`unresolved()` returns records for plans stuck in `pending` or `unknown`
(oldest first) - each a dict `{"plan", "chat", "msg", "state"}` so callers
never read internal storage to find a plan's chat. `is_answered(chat, msg)`
reports whether an inbound has a sent reply.

## State machine

```
plan_reply -> pending --mark_sent--> sent        (terminal)
           pending  --mark_unknown--> unknown
           pending  --mark_failed--> failed      (terminal)
           unknown  --reconcile(quote-correlated observed reply)--> sent
           unknown  --mark_failed--> failed
           failed   --plan_reply--> pending      (explicit re-plan, attempts += 1)
```

Every other transition raises `LedgerError` atomically. `reconcile` on a
settled plan is a read-only no-op returning its state.

## Tests

```
python3 -m unittest tools.dc_relay.test_reply_ledger -v
```

Covers duplicate inbound, duplicate plans, interrupted-send reconciliation
(both directions), absence-in-observation and later-inbound not counting as
failure evidence, authoritative-failure retry, unknown blocking blind retry,
concurrent planners (exactly one wins), corrupt-ledger fail-closed,
hash-only storage, and persistence across reopen.
