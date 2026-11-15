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
IPTABLES6 = """Chain OUTPUT (policy ACCEPT 0 packets, 0 bytes)
 pkts bytes target     prot opt in     out     source               destination
    3   240 DROP       all      --  *      !lo     ::/0                 ::/0
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


require(validator.parse_drop_counters(IPTABLES4) == 12,
        "documented IPv4 iptables -L form must parse")
with tempfile.TemporaryDirectory() as d:
    listing = os.path.join(d, "listing")
    with open(listing, "w", encoding="utf-8") as f:
        f.write(IPTABLES4)
    cli = subprocess.run(
        [sys.executable, os.path.join(HERE, "emu_rt_validate.py"),
         "drop-counter", listing], check=False, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    require(cli.returncode == 0 and cli.stdout == "12\n" and not cli.stderr,
            "drop-counter CLI must print only the parsed number")
require(validator.parse_drop_counters(IPTABLES6) == 3,
        "documented IPv6 ip6tables -L form must parse")
for bad in (
        "",
        IPTABLES4.replace("12  1024", "not-a-count 1024"),
        IPTABLES4.replace("12  1024", "-1 1024"),
        IPTABLES4.replace("12  1024", "+12 1024"),
        IPTABLES4.replace("12  1024", "1_2 1024"),
        IPTABLES4.replace("DROP       all", "ACCEPT     all"),
        IPTABLES4.replace("!lo", "eth0"),
        IPTABLES4 + IPTABLES4.splitlines()[-1] + "\n"):
    require(validator.parse_drop_counters(bad) is None,
            "absent/malformed/negative/wrong/duplicate DROP must fail closed")
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

print("emu_rt_validate parser fixtures: PASS")
