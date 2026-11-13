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
  (`DuplicateReply` for the rest), enforced across processes.
- **Crash-safe file writes.** The ledger is written to a temporary file that
  is atomically renamed over the old one, so a kill mid-write cannot leave a
  truncated file. A stable sidecar lock file (`<ledger>.lock`, never
  replaced) serializes readers and writers via `flock` — the lock cannot live
  on the data file itself because the atomic rename swaps its inode.
- **No blind retries.** `plan_reply` refuses to plan over a `pending` or
  `unknown` record. Settling is evidence-based and asymmetric:
  - `reconcile(plan, observed_messages)` is **positive-only**: it marks the
    plan `sent` when the actual reply text is observed in the chat, and
    otherwise returns `"unresolved"` and changes nothing. Absence in a
    possibly stale or partial observation is not failure evidence.
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
- **No plaintext kept.** Only a SHA-256 hash of the reply text is stored —
  enough to detect a different reply to the same inbound, not enough to read
  the conversation.

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

# on startup, settle leftovers from a crash:
for plan_id in ledger.unresolved():
    outcome = ledger.reconcile(plan_id, transport.read_chat(chat_id))
    # "sent" -> done; "unresolved" -> stays blocked, inspect manually or
    # wait for a transport-authoritative failure, then mark_failed(plan_id)
```

`unresolved()` lists plans stuck in `pending` or `unknown` (oldest first),
and `is_answered(chat, msg)` reports whether an inbound has a sent reply.

## State machine

```
plan_reply -> pending --mark_sent--> sent        (terminal)
           pending  --mark_unknown--> unknown
           pending  --mark_failed--> failed      (terminal)
           unknown  --reconcile(observed reply)--> sent
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
