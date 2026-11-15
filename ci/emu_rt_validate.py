#!/usr/bin/env python3
"""Allowlist validators for round-trip evidence (public-repo policy).

Three modes:

  emu_rt_validate.py json <name> <path>
      (prints nothing, verdict via exit code only)
      The pulled file must be a top-level JSON object whose keys are
      within <name>'s allowlist, with per-key type and bounded-value
      checks against the known fixture constants. Exact-set files
      (written atomically on success) must match their key set
      exactly; the incremental import-assertions report may hold any
      subset (it grows phase by phase) but must carry finalPhase.
      Anything else exits 1: the caller drops the file and records a
      status only, so unexpected content is never uploaded.

  emu_rt_validate.py code <path>
      The runner-local one-time transfer code must be exactly the
      shape migration.py's generate_transfer_code produces: 44 chars,
      urlsafe-base64 of exactly 32 bytes (43 alphabet chars + '=').
      Checked WITHOUT printing it.
      (prints nothing, verdict via exit code only)

  emu_rt_validate.py isolation <dir>
      DIAGNOSTIC capability classification + gate verdicts over the
      CR-stripped guest network probe copies (.rt-probe-<name>.txt)
      in <dir>, collected by verify_guest_offline before enforcement.
      Prints one harness-authored verdict line per interface and per
      gate (nonsecret: interface names, states, gate names) for the
      artifact. The caller records these as EVIDENCE and never aborts
      on them: the isolation claim is owned by the verified first-
      match kernel filter (filter-order mode), not by inventory - an
      addressed NIC is expected on the emulator, and Android's fwmark
      per-table routing makes inventory a weak reachability signal.
      Name-independent classification: a device demonstrates network
      capability only by holding a routable address or a default
      route, whatever it is called; up/down/carrier state is evidence,
      never a verdict.

  emu_rt_validate.py filter-order <path>
      The kernel OUTPUT listings (IPV4_OUTPUT / IPV6_OUTPUT sections
      in the probe copy at <path>) must each BEGIN with exactly
      '-A OUTPUT -o lo -j ACCEPT' then '-A OUTPUT ! -o lo -j DROP',
      in that order - first-match enforcement ahead of every
      netd-managed jump. Anything else exits 1.

  emu_rt_validate.py drop-counter <v4|v6> <path>
      Parse the unique packet counter of the row matching the enforced
      DROP rule's exact source-grounded shape for that family in
      iptables/ip6tables `-L OUTPUT -v -x -n` output. Prints only the
      numeric packet count; absent/duplicate/malformed rows exit 1.

  emu_rt_validate.py row-shape <path>
      Bounded, privacy-safe diagnostic: for every DROP-candidate row in
      a `-L -v -x -n` listing, print the line number, token count and
      per-token CLASS (fixed vocabulary: num/DROP/all/--/!/*/!lo/lo/
      any/ipv4-cidr/ipv6-cidr/ipv4addr/ipv6addr/other(len)) - never raw
      third-party values. Grounds any future parser correction in the
      actual rendering instead of a guessed schema.

  emu_rt_validate.py phases <path>
      Emit first-seen allowlisted export phases, a bounded unknown-beacon
      count and a fixed category derived only from exact JUnit/runner fields;
      never prints raw exception, result or arbitrary instrumentation text.

  emu_rt_validate.py drop-delta <before> <after>
      Require numeric-only packet-count files (one nonnegative integer
      each) and a strict increase; prints nothing and exits 1 otherwise.
      (prints nothing, verdict via exit code only)
"""
import base64
import json
import os
import re
import sys

ADDR = "fixture-bot@emu.fixture"
MAX_BUNDLE_BYTES = 64 * 1024 * 1024
PHASES = {"handoff", "python-start", "virgin-check", "transaction",
          "state-landed", "settings", "leftovers", "done"}
LEFTOVER_NAMES = {"IMPORT_PENDING.json", "IMPORT_PREFS.json",
                  "IMPORT_PRIOR_PREFS.json", ".import-staging"}
SETTINGS_KEYS = {"priv", "feed", "feed_gen", "feed_url", "status",
                 "feed_at", "canvas_layout"}


def is_bool(v):
    return v is True or v is False


def is_count(v):
    return not is_bool(v) and isinstance(v, int) and 0 <= v <= 8


def is_true(v):
    return v is True


def is_addr(v):
    return v == ADDR


def is_bundle_bytes(v):
    return not is_bool(v) and isinstance(v, int) and 0 < v <= MAX_BUNDLE_BYTES


def is_code_len(v):
    return v == 44 and not is_bool(v) and isinstance(v, int)


def is_ok_str(v):
    return v == "ok"


def is_settings(v):
    return (isinstance(v, dict) and set(v) <= SETTINGS_KEYS
            and all(is_bool(x) for x in v.values()))


def is_leftovers(v):
    return (isinstance(v, list)
            and all(isinstance(x, str) and x in LEFTOVER_NAMES for x in v))


def is_phase(v):
    return isinstance(v, str) and v in PHASES


ASSERTION_SPEC = {
    "bundlePresent": is_bool,
    "codePresent": is_bool,
    "codeShapeOk": is_bool,
    "virginAccountCount": is_count,
    "runningMarkerAbsent": is_bool,
    "prepareAddrMatches": is_bool,
    "journalStagedBeforeMutation": is_bool,
    "prefsStagedBeforeCommit": is_bool,
    "prefsApplied": is_bool,
    "admitted": is_bool,
    "commitAddrMatches": is_bool,
    "accountCountAfter": is_count,
    "importedAddrMatches": is_bool,
    "stateMarkerLanded": is_bool,
    "stateAddrLanded": is_bool,
    "opsFilesLanded": is_count,
    "opsMarkersLanded": is_bool,
    "settings": is_settings,
    "leftoversPresent": is_leftovers,
    "finalPhase": is_phase,
}

SCHEMAS = {
    # name -> (spec, exact_key_set_required)
    "smoke-ok.json": ({"python": is_ok_str, "core": is_ok_str,
                       "fixtureSeed": is_ok_str}, True),
    "export-done.json": ({"bundleBytes": is_bundle_bytes,
                          "codeLen": is_code_len, "addr": is_addr}, True),
    "import-done.json": ({"addr": is_addr, "settingsApplied": is_true,
                          "journalResolved": is_true}, True),
    "import-assertions.json": (ASSERTION_SPEC, False),
}


def check_json(name, path):
    schema = SCHEMAS.get(name)
    if schema is None:
        return False
    spec, exact = schema
    try:
        with open(path) as f:
            data = json.load(f)
    except Exception:
        return False
    if not isinstance(data, dict):
        return False
    keys = set(data)
    if exact:
        if keys != set(spec):
            return False
    else:
        if not keys <= set(spec):
            return False
        if "finalPhase" not in keys:
            return False
    for key, validator in spec.items():
        if key in data and not validator(data[key]):
            return False
    return True


def check_code(path):
    try:
        with open(path, "rb") as f:
            raw = f.read()
        # The export writer emits no newline. Accept exactly that shape,
        # or one terminal LF for a line-oriented transport; reject all
        # other whitespace and standard-base64 '+'/'/' characters.
        if raw.endswith(b"\n"):
            raw = raw[:-1]
        if re.fullmatch(rb"[A-Za-z0-9_-]{43}=", raw) is None:
            return False
        return len(base64.b64decode(raw, altchars=b"-_", validate=True)) == 32
    except Exception:
        return False


# ---------------- isolation evidence gates ----------------

ISO_FILES = ("sysfs_net", "ifstate", "link", "addr", "route4", "route6")
ISO_ERROR_MARKERS = ("error:", "not found", "permission denied")


def check_isolation(dirpath):
    """Returns (ok, lines): per-iface capability classification, then
    one verdict line per gate. Pure text in, verdict text out."""
    lines = []
    probes = {}
    for name in ISO_FILES:
        try:
            with open(os.path.join(
                    dirpath, ".rt-probe-%s.txt" % name)) as f:
                probes[name] = f.read()
        except Exception:
            lines.append("gate evidence_%s: FAIL "
                         "(probe file missing/unreadable)" % name)
            return False, lines
    ok = True

    # Per-interface kind/state, from the remote sysfs loop:
    # "<name> operstate=<o> carrier=<c>" (a carrier read on a down
    # interface fails in-kernel; the harness records readfail).
    ifaces = {}
    for raw in probes["ifstate"].splitlines():
        p = raw.split()
        if (len(p) >= 3 and p[1].startswith("operstate=")
                and p[2].startswith("carrier=")):
            ifaces[p[0]] = (p[1][10:], p[2][8:])
    # ip -o addr: one line per entry, "<idx>: <if> <family> <addr>
    # scope <scope> ...".
    addr_map = {}
    for raw in probes["addr"].splitlines():
        p = raw.split()
        if len(p) >= 4 and p[2] in ("inet", "inet6"):
            addr_map.setdefault(p[1], []).append(
                (p[2], "scope link" in raw))
    routes_by_dev = {}
    for fam in ("route4", "route6"):
        for raw in probes[fam].splitlines():
            p = raw.split()
            if "dev" in p:
                routes_by_dev.setdefault(
                    p[p.index("dev") + 1], []).append(raw)

    # Classification is EVIDENCE, not a gate: a device is reported as
    # network-capable only when it demonstrates capability (a routable
    # address or a default route). State alone (up/down, carrier) is
    # recorded but never a verdict - that would be a state heuristic.
    for name in sorted(set(ifaces) | set(addr_map) | set(routes_by_dev)):
        o, c = ifaces.get(name, ("unlisted", "unlisted"))
        addrs = addr_map.get(name, [])
        rts = routes_by_dev.get(name, [])
        capable = (any(f == "inet" or not ll for f, ll in addrs)
                   or any(r.startswith("default") for r in rts))
        if name == "lo":
            kind = "loopback"
        elif capable:
            kind = "NETWORK-CAPABLE"
        else:
            kind = "no demonstrated capability"
        lines.append("iface %s: operstate=%s carrier=%s addrs=%d "
                     "routes=%d -> %s"
                     % (name, o, c, len(addrs), len(rts), kind))

    def gate(gname, cond, reason=""):
        nonlocal ok
        if cond:
            lines.append("gate %s: ok" % gname)
        else:
            lines.append("gate %s: FAIL %s" % (gname, reason))
            ok = False

    link_lines = [l for l in probes["link"].splitlines() if l.strip()]
    lo_links = [l for l in link_lines if l.split()[1:2] == ["lo:"]]
    gate("lo_link_exactly_one", len(lo_links) == 1,
         "(found %d loopback link lines)" % len(lo_links))
    lo_inet = any(l.split()[1:3] == ["lo", "inet"]
                  for l in probes["addr"].splitlines())
    gate("lo_inet_present", lo_inet, "(no inet address on lo)")
    bad = []
    for raw in probes["addr"].splitlines():
        p = raw.split()
        if len(p) >= 4 and p[1] != "lo" and p[2] in ("inet", "inet6"):
            if p[2] == "inet" or "scope link" not in raw:
                bad.append("%s/%s" % (p[1], p[2]))
    gate("no_nonlo_address", not bad,
         "(%s)" % ",".join(bad) if bad else "")
    badr = [fam for fam in ("route4", "route6")
            if any(l.startswith("default")
                   for l in probes[fam].splitlines())]
    gate("no_default_route", not badr,
         "(%s)" % ",".join(badr) if badr else "")
    marked = [n for n in ISO_FILES
              if any(m in probes[n].lower() for m in ISO_ERROR_MARKERS)]
    gate("no_error_markers", not marked,
         "(%s)" % ",".join(marked) if marked else "")
    return ok, lines


def check_filter_order(path):
    """Require exact first-match rule order for both OUTPUT families."""
    try:
        with open(path) as f:
            text = f.read()
    except Exception:
        return False
    sections = text.split("IPV4_OUTPUT\n")
    if len(sections) != 2:
        return False
    v4_and_v6 = sections[1].split("IPV6_OUTPUT\n")
    if len(v4_and_v6) != 2:
        return False
    expected = ["-A OUTPUT -o lo -j ACCEPT", "-A OUTPUT ! -o lo -j DROP"]
    for body in v4_and_v6:
        rules = [line.strip() for line in body.splitlines()
                 if line.strip().startswith("-A OUTPUT ")]
        if len(rules) < 2 or rules[:2] != expected:
            return False
    return True


# ---------------- DROP counter (structural, source-grounded) ----------------
# Android's Android 14 iptables binary builds xtables-legacy-multi.c.
# The family-specific print_firewall implementations differ: iptables.c
# emits the opt column ("--" for this non-fragment rule), while
# ip6tables.c emits three spaces for that column, so whitespace splitting
# omits it entirely. Both then call shared xshared.c print_ifaces, which
# emits the negated output interface as one glued token, "!lo". The
# source and the bounded run-43718205279 skeleton agree on v6's 8-token
# row: num num DROP all * !lo ipv6-cidr ipv6-cidr. A wrong-family,
# truncated, shifted, duplicate, or otherwise changed row never counts.
DROP_ROW_TEMPLATES = {
    "v4": ("num", "num", "DROP", "all", "--", "*", "!lo",
           "0.0.0.0/0", "0.0.0.0/0"),
    "v6": ("num", "num", "DROP", "all", "*", "!lo",
           "::/0", "::/0"),
}

def _is_num(tok):
    return re.fullmatch(r"[0-9]+", tok, flags=re.ASCII) is not None


def _drop_template_matches(tokens, family):
    tpl = DROP_ROW_TEMPLATES[family]
    if len(tokens) != len(tpl):
        return False
    return all(_is_num(tok) if want == "num" else tok == want
               for tok, want in zip(tokens, tpl))


def parse_drop_counters(text, family):
    """Packets of the UNIQUE row matching the enforced rule's exact
    full-row shape in <family> ('v4' or 'v6'); None on absence,
    duplicates, or a bad family. Foreign DROP rows (other matches,
    conditions, endpoints) never match the template and cannot be
    mistaken for the enforced rule's counter."""
    if family not in DROP_ROW_TEMPLATES:
        return None
    counts = [int(p[0]) for p in (line.split() for line in text.splitlines())
              if _drop_template_matches(p, family)]
    return counts[0] if len(counts) == 1 else None


def drop_counter_failure_reason(text, family):
    """Return only a safe reason code, never the external command line."""
    if family not in DROP_ROW_TEMPLATES:
        return "bad_family"
    matches = sum(1 for line in text.splitlines()
                  if _drop_template_matches(line.split(), family))
    if matches > 1:
        return "duplicate_drop_rows"
    if matches == 1:
        return "drop_row_ambiguous"
    if any(len(line.split()) >= 3 and line.split()[2] == "DROP"
           for line in text.splitlines()):
        return "drop_row_malformed"  # see the row-shape skeleton nearby
    return "drop_row_absent"


def drop_row_shapes(text, max_rows=8):
    """Bounded, privacy-safe skeleton of every DROP-candidate row:
    line number, token count, and each token mapped to a fixed class
    vocabulary - NEVER raw third-party values. This is the diagnostic
    that grounds any future parser correction in the actual rendering.
    """
    def classify(tok):
        if _is_num(tok):
            return "num"
        if tok in ("DROP", "all", "--", "!", "*", "!lo", "lo", "any"):
            return tok
        if re.fullmatch(r"[0-9]+(\.[0-9]+){3}/[0-9]+", tok):
            return "ipv4-cidr"
        if ":" in tok and "/" in tok:
            return "ipv6-cidr"
        if re.fullmatch(r"[0-9]+(\.[0-9]+){3}", tok):
            return "ipv4addr"
        if ":" in tok:
            return "ipv6addr"
        return "other(%d)" % len(tok)

    out = []
    candidates = 0
    for lineno, line in enumerate(text.splitlines(), 1):
        p = line.split()
        if len(p) < 3 or p[2] != "DROP":
            continue
        candidates += 1
        if candidates > max_rows:
            out.append("... further DROP rows elided")
            break
        out.append("drop_row line%d tokens=%d [%s]"
                   % (lineno, len(p), " ".join(classify(t) for t in p)))
    if candidates == 0:
        out.append("no DROP rows in listing")
    return out


# Fixed vocabulary emitted by MigrationEmuExportTest. Keep first occurrence
# order and cap unknown count so artifact output remains bounded even if a
# malformed runner stream repeats phase-looking lines.
EMU_PHASES = ("seeded", "python_ready", "screen_on", "screen_off",
              "keyguard_locked", "keyguard_unlocked", "activity_launching",
              "activity_launched", "focused_app", "focused_systemui",
              "focused_launcher", "focused_permissioncontroller",
              "focused_packageinstaller", "focused_settings", "focused_gms",
              "focused_none", "focused_other",
              "header_timeout", "header_seen", "expand_clicked",
              "setup_open", "button_clicked", "confirm_clicked",
              "result_seen", "code_dialog_seen", "bundle_validated",
              "artifacts_written", "confirm_timeout", "confirm_shown",
              "confirm_wait_error", "confirm_button_missing",
              "confirm_button_wait_error",
              "bundle_read_ok", "bundle_read_error",
              "raw_leak_state", "raw_state_clean",
              "raw_leak_ops", "raw_ops_clean",
              "raw_leak_priv", "raw_priv_clean",
              "bundle_parsed", "bundle_parse_error",
              "state_decrypted", "state_decrypt_error",
              "state_archive_ok", "state_helper_error",
              "state_archive_input_error", "state_archive_bounds_error",
              "state_archive_gzip_error", "state_archive_tar_error",
              "state_archive_read_error",
              "state_members_ok", "state_members_unexpected",
              "state_member_state_missing", "state_member_ops_missing",
              "state_mark_state_ok", "state_mark_state_fail",
              "state_mark_ops_ok", "state_mark_ops_fail",
              "state_logs_clean", "state_logs_leak",
              "prefs_decrypted", "prefs_decrypt_error",
              "prefs_marker_ok", "prefs_marker_fail",
              "wrap_decrypted", "wrap_decrypt_error",
              "migration_module_error", "raw_text_error")
_PHASE_RE = re.compile(r"^INSTRUMENTATION_STATUS: emu_phase=([a-z0-9_]+)$")
MAX_UNKNOWN_PHASES = 100


def extract_phases(text):
    phases = []
    seen = set()
    unknown = 0
    for line in text.splitlines():
        match = _PHASE_RE.fullmatch(line.strip())
        if match is None:
            continue
        name = match.group(1)
        if name in EMU_PHASES:
            if name not in seen:
                seen.add(name)
                phases.append(name)
        elif unknown < MAX_UNKNOWN_PHASES:
            unknown += 1
    return phases, unknown


def failure_category(text):
    """Classify only exact runner/JUnit protocol fields. Never scan
    exception stack, app text, or arbitrary substrings for category words."""
    lines = [line.strip() for line in text.splitlines()]
    if "FAILURES!!!" in lines or "INSTRUMENTATION_STATUS_CODE: -2" in lines:
        return "junit_failure"
    if "INSTRUMENTATION_RESULT: shortMsg=Process crashed." in lines:
        return "process_crashed"
    if any(re.fullmatch(r"OK \([0-9]+ tests?\)", line) for line in lines):
        return "passed"
    return "no_ok_summary"


# Fixed-enum summary for a private uiautomator dump. The caller bounds
# file reads; this function also bounds direct input and emitted counts.
WINDOW_DUMP_MAX_BYTES = 1024 * 1024
WINDOW_PACKAGES = {
    "com.instinct.widget": "app",
    "com.android.systemui": "systemui",
    "com.android.launcher3": "launcher",
    "com.google.android.apps.nexuslauncher": "launcher",
    "com.android.permissioncontroller": "permissioncontroller",
    "com.android.packageinstaller": "packageinstaller",
    "com.android.settings": "settings",
    "com.google.android.gms": "gms",
}
WINDOW_STRINGS = (
    ("str_setup_header", "Setup & account"),
    ("str_export_button", "Export bot + settings"),
    ("str_confirm_text", "pauses the live bot"),
    ("str_code_dialog", "one-time transfer code"),
    ("str_perm_allow", "Allow"),
    ("str_perm_deny", "Don't allow"),
    ("str_start_bot", "Start bot"),
    ("str_stop_bot", "Stop bot"),
)
def window_dump_summary(text):
    """Return only fixed enums: status, focus, packages, strings, node count."""
    import xml.etree.ElementTree as ET
    if len(text.encode("utf-8", errors="replace")) > WINDOW_DUMP_MAX_BYTES:
        return "oversize", "none", [], [], 0
    try:
        root = ET.fromstring(text)
    except (ET.ParseError, ValueError):
        return "malformed", "none", [], [], 0
    if root.tag != "hierarchy":
        return "malformed", "none", [], [], 0
    nodes = [node for node in root.iter() if node.tag == "node"]
    focused = "none"
    packages = []
    other = 0
    shown_text = []
    for node in nodes:
        raw = node.attrib.get("package", "")
        enum = WINDOW_PACKAGES.get(raw)
        if raw and enum is None:
            other += 1
        elif enum is not None and enum not in packages:
            packages.append(enum)
        if focused == "none" and node.attrib.get("focused") == "true":
            focused = enum or "other"
        shown_text.append(node.attrib.get("text", ""))
    if other:
        packages.append("other(x%d%s)" % (min(other, 999), "+" if other > 999 else ""))
    all_text = "\n".join(shown_text)
    strings = [sid for sid, literal in WINDOW_STRINGS if literal in all_text]
    return "ok", focused, packages, strings, min(len(nodes), 9999)


def parse_numeric_counter(text):
    """Parse a numeric-only counter scratch file, never raw command output."""
    if not re.fullmatch(r"[0-9]+\n?", text, flags=re.ASCII):
        return None
    return int(text.strip())


def check_drop_delta(before_path, after_path):
    try:
        before=parse_numeric_counter(open(before_path).read())
        after=parse_numeric_counter(open(after_path).read())
    except Exception:
        return False
    return before is not None and after is not None and after > before


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "json":
        ok = check_json(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 3 and sys.argv[1] == "code":
        ok = check_code(sys.argv[2])
    elif len(sys.argv) == 3 and sys.argv[1] == "isolation":
        ok, out = check_isolation(sys.argv[2])
        for line in out:
            print(line)
    elif len(sys.argv) == 3 and sys.argv[1] == "filter-order":
        ok = check_filter_order(sys.argv[2])
    elif len(sys.argv) == 4 and sys.argv[1] == "drop-delta":
        ok = check_drop_delta(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 4 and sys.argv[1] == "drop-counter":
        count = parse_drop_counters(open(sys.argv[3]).read(), sys.argv[2])
        ok = count is not None
        if ok: print(count)
    elif len(sys.argv) == 3 and sys.argv[1] == "row-shape":
        for line in drop_row_shapes(open(sys.argv[2]).read()):
            print(line)
        ok = True
    elif len(sys.argv) == 3 and sys.argv[1] == "window-dump":
        try:
            with open(sys.argv[2], "rb") as f:
                raw_bytes = f.read(WINDOW_DUMP_MAX_BYTES + 1)
            if len(raw_bytes) > WINDOW_DUMP_MAX_BYTES:
                summary = ("oversize", "none", [], [], 0)
            else:
                summary = window_dump_summary(raw_bytes.decode("utf-8", errors="replace"))
        except OSError:
            summary = ("unavailable", "none", [], [], 0)
        status, focused, packages, strings, count = summary
        print("window_dump_status=%s" % status)
        print("window_focused=%s" % focused)
        print("window_packages=%s" % (",".join(packages) if packages else "none"))
        print("window_strings=%s" % (",".join(strings) if strings else "none"))
        print("window_nodes=%d" % count)
        ok = True
    elif len(sys.argv) == 3 and sys.argv[1] == "phases":
        _text = open(sys.argv[2]).read()
        phases, unknown = extract_phases(_text)
        print("emu_export_phases=%s" % (",".join(phases) if phases else "none"))
        print("emu_export_phase_unknown=%d%s" %
              (unknown, "+" if unknown == MAX_UNKNOWN_PHASES else ""))
        print("emu_export_failure_category=%s" % failure_category(_text))
        ok = True
    else:
        ok = False
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
