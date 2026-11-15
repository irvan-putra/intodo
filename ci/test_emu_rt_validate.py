#!/usr/bin/env python3
"""Fixture tests for the round-trip evidence validator's nonsecret parsers."""
import importlib.util
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(__file__)
spec = importlib.util.spec_from_file_location(
    "emu_rt_validate", os.path.join(HERE, "emu_rt_validate.py"))
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)

IPTABLES4 = """Chain OUTPUT (policy ACCEPT 0 packets, 0 bytes)
 pkts bytes target     prot opt in     out     source               destination
   12  1024 DROP       all  --  *      !lo     0.0.0.0/0            0.0.0.0/0
"""
# AOSP ip6tables.c prints three blank opt-column characters, so the
# v6 DROP row has 8 whitespace tokens, unlike IPv4's 9-token row.
IPTABLES6 = """Chain OUTPUT (policy ACCEPT 0 packets, 0 bytes)
 pkts bytes target prot opt in out source destination
    3   240 DROP       all      *      !lo     ::/0                 ::/0
"""
IPTABLES6_SEPARATE_NEGATION = """Chain OUTPUT (policy ACCEPT 0 packets, 0 bytes)
 pkts bytes target prot opt in out source destination
    3   240 DROP       all      *      ! lo    ::/0                 ::/0
"""
IPTABLES6_ALT_OPT = """Chain OUTPUT (policy ACCEPT 0 packets, 0 bytes)
 pkts bytes target prot opt in out source destination
 4 320 DROP all -- * !lo ::/0 ::/0
"""
GOOD_ORDER = """IPV4_OUTPUT
-A OUTPUT -o lo -j ACCEPT
-A OUTPUT ! -o lo -j DROP
-A OUTPUT -j netd
IPV6_OUTPUT
-A OUTPUT -o lo -j ACCEPT
-A OUTPUT ! -o lo -j DROP
-A OUTPUT -j netd
"""


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def order_passes(text):
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8") as f:
        f.write(text)
        f.flush()
        return validator.check_filter_order(f.name)


# The counter row templates are bound to Android's family-specific
# AOSP legacy print_firewall implementations and shared print_ifaces.
# IPv4 has the opt token; IPv6 prints only spaces there, so split() yields
# an exact 8-token v6 row. The v6 form is also confirmed by the bounded
# structural skeleton from run 43718205279. Deviations fail closed.
require(validator.parse_drop_counters(IPTABLES4, "v4") == 12,
        "source-grounded IPv4 iptables -L form must parse")
with tempfile.TemporaryDirectory() as d:
    listing = os.path.join(d, "listing")
    with open(listing, "w", encoding="utf-8") as f:
        f.write(IPTABLES4)
    cli = subprocess.run(
        [sys.executable, os.path.join(HERE, "emu_rt_validate.py"),
         "drop-counter", "v4", listing], check=False, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    require(cli.returncode == 0 and cli.stdout == "12\n" and not cli.stderr,
            "drop-counter CLI must print only the parsed number")
require(validator.parse_drop_counters(IPTABLES6, "v6") == 3,
        "source-grounded IPv6 ip6tables -L form must parse")
v6shape = validator.drop_row_shapes(IPTABLES6)
require(any("tokens=8 [num num DROP all * !lo ipv6-cidr ipv6-cidr]" in x
            for x in v6shape),
        "IPv6 structural diagnostic must match source-grounded 8-token row")
require(validator.parse_drop_counters(IPTABLES6, "v4") is None
        and validator.parse_drop_counters(IPTABLES4, "v6") is None,
        "family binding: a v6 row must not count as v4 or vice versa")
require(validator.parse_drop_counters(IPTABLES6_SEPARATE_NEGATION, "v6") is None,
        "ungrounded spaced-negation variant must fail closed")
TRUNCATED_V6_TAIL = IPTABLES6.replace("::/0                 ::/0", "::/0")
require(validator.parse_drop_counters(TRUNCATED_V6_TAIL, "v6") is None,
        "truncated IPv6 destination must fail closed")
require(validator.parse_drop_counters(IPTABLES6 + IPTABLES6.splitlines()[-1] + "\n", "v6") is None,
        "duplicate exact IPv6 counter rows must fail closed")
require(validator.parse_drop_counters(IPTABLES6_ALT_OPT, "v6") is None,
        "an IPv4-style opt token in the v6 row must fail closed")
# A truncated row can never be admitted: it may be a damaged read of a
# rule whose tail (match conditions) would disqualify it.
TRUNCATED_TAIL = IPTABLES4.replace("0.0.0.0/0            0.0.0.0/0",
                                   "0.0.0.0/0")
TRUNCATED_AT_IFACE = ("Chain OUTPUT (policy ACCEPT 0 packets, 0 bytes)\n"
                      " pkts bytes target prot opt in out source destination\n"
                      "   12  1024 DROP       all  --  *      !lo\n")
FOREIGN_WITH_MATCH = IPTABLES4 + (
    "    7   512 DROP       tcp  --  *      !lo     0.0.0.0/0            "
    "0.0.0.0/0            tcp dpt:53\n")
require(validator.parse_drop_counters(FOREIGN_WITH_MATCH, "v4") == 12,
        "a foreign DROP row with match conditions must not shadow the enforced rule")
for bad in (
        "",
        TRUNCATED_TAIL,
        TRUNCATED_AT_IFACE,
        IPTABLES4.replace("12  1024", "not-a-count 1024"),
        IPTABLES4.replace("12  1024", "-1 1024"),
        IPTABLES4.replace("12  1024", "+12 1024"),
        IPTABLES4.replace("12  1024", "1_2 1024"),
        IPTABLES4.replace("DROP       all", "ACCEPT     all"),
        IPTABLES4.replace("!lo", "eth0"),
        IPTABLES4 + IPTABLES4.splitlines()[-1] + "\n"):
    require(validator.parse_drop_counters(bad, "v4") is None,
            "absent/truncated/malformed/negative/wrong/duplicate DROP must fail closed")
require(validator.drop_counter_failure_reason(FOREIGN_WITH_MATCH, "v6")
        == "drop_row_malformed",
        "a DROP row matching no template is malformed, not absent")
require(validator.drop_counter_failure_reason("", "v4") == "drop_row_absent"
        and "duplicate" in validator.drop_counter_failure_reason(
            IPTABLES4 + IPTABLES4.splitlines()[-1] + "\n", "v4"),
        "absent vs duplicate reasons must stay distinct")
# The skeleton is the bounded privacy-safe shape diagnostic: classes and
# fixed vocabulary only, never raw third-party values.
shapes = validator.drop_row_shapes(
    "  9  99 DROP all -- * !weird9 10.0.2.15/8 0.0.0.0/0\n")
require(len(shapes) == 1 and "tokens=9" in shapes[0]
        and "other(7)" in shapes[0] and "ipv4-cidr" in shapes[0]
        and "!weird9" not in shapes[0] and "10.0.2.15" not in shapes[0],
        "row-shape must record classes, never raw values")
require(validator.drop_row_shapes("no drops here\n") ==
        ["no DROP rows in listing"],
        "row-shape must say when no DROP row exists")
require(order_passes(GOOD_ORDER), "exact first-match order must pass")
for bad in (
        GOOD_ORDER.replace("-A OUTPUT -o lo -j ACCEPT\n", "", 1),
        GOOD_ORDER.replace("-A OUTPUT ! -o lo -j DROP\n-A OUTPUT -j netd",
                           "-A OUTPUT -j netd\n-A OUTPUT ! -o lo -j DROP"),
        GOOD_ORDER.replace("IPV6_OUTPUT\n", ""),
        GOOD_ORDER.replace("-A OUTPUT -o lo -j ACCEPT\n-A OUTPUT ! -o lo -j DROP",
                           "-A OUTPUT -o lo -j ACCEPT\n-A OUTPUT -o lo -j ACCEPT\n-A OUTPUT ! -o lo -j DROP", 1)):
    require(not order_passes(bad), "missing/reordered/duplicate rule must fail")

with tempfile.TemporaryDirectory() as d:
    before = os.path.join(d, "before")
    after = os.path.join(d, "after")
    cases = (("10\n", "11\n", True),
             ("10\n", "10\n", False),
             ("10\n", "9\n", False),
             ("", "11\n", False),
             ("10\n", "iptables output\n", False),
             ("+10\n", "11\n", False),
             ("10\n10\n", "11\n", False))
    for before_text, after_text, accepted in cases:
        with open(before, "w", encoding="utf-8") as f:
            f.write(before_text)
        with open(after, "w", encoding="utf-8") as f:
            f.write(after_text)
        require(validator.check_drop_delta(before, after) is accepted,
                "numeric-only counter delta must be strict and fail closed")
        cli = subprocess.run(
            [sys.executable, os.path.join(HERE, "emu_rt_validate.py"),
             "drop-delta", before, after], check=False, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        require(cli.returncode == (0 if accepted else 1)
                and cli.stdout == "" and not cli.stderr,
                "drop-delta CLI must be silent and match numeric-only verdict")

# Android image's observed toybox ping form rejects -4/-6. Keep egress
# probes on plain ping and ping6, and validate those binaries on loopback first.
runner = open(os.path.join(HERE, "emu_runner.sh"), encoding="utf-8").read()
require("probe='ping -c 1 -W 2 8.8.8.8'" in runner,
        "IPv4 probe must use supported ping syntax without -4")
require("probe='ping6 -c 1 -W 2 2001:4860:4860::8888'" in runner,
        "IPv6 probe must use ping6 without unsupported -6")
require("record_step probe_tool_check check_probe_tools" in runner
        and "ping6 -c 1 -W 2 ::1" in runner,
        "both families must pass loopback tool/option validation before build")
require("loopback_v4" in runner and "loopback_v6" in runner,
        "post-filter positive loopback checks must cover both families")

# ---------------- export phase beacons (fixed enum only) ----------------
AM_OUTPUT = "\r\n".join([
    "INSTRUMENTATION_STATUS: class=com.vanipy.migtest.MigrationEmuExportTest",
    "INSTRUMENTATION_STATUS: emu_phase=seeded",
    "INSTRUMENTATION_STATUS_CODE: 100",
    "INSTRUMENTATION_STATUS: emu_phase=python_ready",
    "INSTRUMENTATION_STATUS: emu_phase=seeded",
    "INSTRUMENTATION_STATUS: stack=IllegalStateException: SECRET-PAYLOAD",
    "INSTRUMENTATION_STATUS: emu_phase=not_a_phase",
    "INSTRUMENTATION_STATUS: emu_phase=button_clicked extra",
    "INSTRUMENTATION_RESULT: shortMsg=Process crashed.",
    "INSTRUMENTATION_CODE: 0",
    "",
])
phases, unknown = validator.extract_phases(AM_OUTPUT)
require(phases == ["seeded", "python_ready"] and unknown == 1,
        "phase parser must preserve first-seen enums, deduplicate, and count unknown")
with tempfile.TemporaryDirectory() as d:
    logp = os.path.join(d, "log")
    with open(logp, "w", encoding="utf-8") as f:
        f.write(AM_OUTPUT)
    cli = subprocess.run(
        [sys.executable, os.path.join(HERE, "emu_rt_validate.py"),
         "phases", logp], check=False, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    require(cli.returncode == 0
            and cli.stdout == ("emu_export_phases=seeded,python_ready\n"
                               "emu_export_phase_unknown=1\n"
                               "emu_export_failure_category=process_crashed\n"),
            "phases CLI must emit only bounded enum metadata")
    require("SECRET-PAYLOAD" not in cli.stdout + cli.stderr
            and "Process crashed" not in cli.stdout
            and "IllegalStateException" not in cli.stdout
            and "not_a_phase" not in cli.stdout,
            "phase output must never carry raw instrumentation content")
with tempfile.TemporaryDirectory() as d:
    logp = os.path.join(d, "log")
    with open(logp, "w", encoding="utf-8") as f:
        f.write(("INSTRUMENTATION_STATUS: emu_phase=unknown\n" * 2000)
                + "INSTRUMENTATION_CODE: -1\n")
    cli = subprocess.run(
        [sys.executable, os.path.join(HERE, "emu_rt_validate.py"),
         "phases", logp], check=False, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    require(cli.returncode == 0
            and cli.stdout == ("emu_export_phases=none\n"
                               "emu_export_phase_unknown=100+\n"
                               "emu_export_failure_category=no_ok_summary\n"),
            "phase diagnostics must have a fixed output bound")

# Only exact, standalone protocol fields can supply the category; strings
# embedded in a stack, result detail, or arbitrary output are not evidence.
require(validator.failure_category("OK (3 tests)\n") == "passed", "pass")
require(validator.failure_category("FAILURES!!!\nTests run: 1\n")
        == "junit_failure", "exact JUnit failure marker")
require(validator.failure_category("INSTRUMENTATION_STATUS_CODE: -2\n")
        == "junit_failure", "exact instrumentation failure status")
require(validator.failure_category(
        "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n")
        == "process_crashed", "exact crash field")
require(validator.failure_category("INSTRUMENTATION_STATUS: stack=FAILURES!!!\n")
        == "no_ok_summary", "do not match arbitrary stack text")
require(validator.failure_category(
        "INSTRUMENTATION_RESULT: details=Process crashed.\n")
        == "no_ok_summary", "do not match arbitrary result details")
require(validator.failure_category("FAILURES!!!\n"
        "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n")
        == "junit_failure", "explicit JUnit failure outranks crash category")
require(validator.failure_category("OK (1 test)\r\n") == "passed",
        "CR-tolerant exact JUnit summary")
require(all(p in validator.EMU_PHASES for p in
            ("activity_launching", "activity_launched", "header_seen",
             "expand_clicked")), "launch-span beacons must be in the enum")

print("emu_rt_validate parser fixtures: PASS")
