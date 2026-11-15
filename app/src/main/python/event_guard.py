"""Unknown-RPC-event tolerance for Bot event loops (shared by bot_runtime's
live loop and bot.py's legacy import-mode loops).

The bundled deltachat-rpc-client converts every incoming event with
EventType(kind) inside Client._process_events. A core newer than the
pinned client can emit kinds the client's enum does not know (core 2.63.0
added PinnedMessagesChanged; client 2.69.0's enum lacks it) and that
ValueError escapes the library's per-hook error handling, killing the
whole loop (0.5.8 log view: "java-side error (live)" -> Service stopped).

The unknown event has already been consumed from the RPC queue when the
conversion raises, so re-entering the loop simply continues with the next
event (start_io is idempotent in core and _process_messages only picks up
not-yet-seen messages, so re-entry neither re-registers hooks nor
re-dispatches handled messages). Anything else still stops the loop
exactly as before.

Unknown events are SKIPPED, never acted on: no hook sees them and nothing
here claims coverage of the affected feature (witness pin tracking stays
"not covered", as documented in witness.py). The log line carries ONLY
the kind NAME from the enum's own message, length-bounded and %r-escaped
(no payloads, no chat data, no secrets, no log-line injection via a
crafted kind).
"""
import logging
import re
import traceback

log = logging.getLogger("event_guard")

_UNKNOWN_KIND_RE = re.compile(r"'([^']{1,128})' is not a valid EventType")


def _is_unknown_event_kind_error(e):
    """True ONLY for the deltachat-rpc-client's own EventType(kind)
    conversion failing inside Client._process_events on a kind the pinned
    enum does not know.

    Client._on_event already catches every hook exception, so this
    conversion is the only ValueError that can escape the loop in
    practice - but the ORIGIN is verified from the traceback rather than
    the message text: the innermost frame must be enum's __new__ and the
    chain must pass through the client's _process_events. A hook (or
    anything else) raising a ValueError with the same wording does NOT
    match and still stops the loop as before.
    """
    if not isinstance(e, ValueError):
        return False
    if "is not a valid EventType" not in str(e):
        return False
    frames = traceback.extract_tb(e.__traceback__)
    if not frames or frames[-1].name != "__new__" or \
            not frames[-1].filename.endswith("enum.py"):
        return False
    return any(f.name == "_process_events" for f in frames)


def run_forever_tolerant(bot):
    """Run Bot.run_forever(), tolerating UNKNOWN RPC event kinds.

    See module docstring. Re-enters the loop only after a verified
    unknown-kind conversion error; every other exception propagates and
    stops the loop exactly as an unguarded run_forever() would.
    """
    while True:
        try:
            bot.run_forever()
            return
        except ValueError as e:
            if not _is_unknown_event_kind_error(e):
                raise
            m = _UNKNOWN_KIND_RE.search(str(e))
            if m:
                log.warning("skipping unknown RPC event kind %r; live loop "
                            "continues", m.group(1))
            else:
                log.warning("skipping unknown RPC event kind; live loop "
                            "continues")
