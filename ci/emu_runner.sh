#!/usr/bin/env bash
# One-line entry point for reactivecircus/android-emulator-runner. That
# action intentionally executes each input line in a separate `sh -c`, so
# stateful shell flow must live in a checked-in script like this one.
set -euo pipefail

mode=${1:-}
case "$mode" in
  smoke-export)
    package_id=com.instinct.widget
    test_class=com.vanipy.migtest.EmuSmokeTest
    log_file=smoke-logcat.txt
    tar_file=smoke-out.tar
    required_output=emu-out/smoke-ok.json
    ;;
  export)
    package_id=com.instinct.widget
    test_class=com.vanipy.migtest.MigrationEmuExportTest
    log_file=emu-logcat.txt
    tar_file=emu-out.tar
    required_output=emu-out/dcbot-migration.dcmig
    ;;
  smoke-import)
    package_id=com.vanipy.intodo
    test_class=com.vanipy.migtest.EmuSmokeTest
    log_file=smoke-logcat.txt
    tar_file=smoke-out.tar
    required_output=emu-out/smoke-ok.json
    ;;
  import)
    package_id=com.vanipy.intodo
    test_class=com.vanipy.migtest.MigrationEmuImportTest
    log_file=emu-logcat.txt
    tar_file=emu-out.tar
    required_output=emu-out/import-done.json
    ;;
  roundtrip)
    ;;
  *)
    echo "FATAL: expected mode smoke-export, export, smoke-import, import or roundtrip; got '$mode'" >&2
    exit 64
    ;;
esac

OLD_PKG=com.instinct.widget
NEW_PKG=com.vanipy.intodo
PRETEST=emu-rt-pretest.txt

# Guest network isolation is ENFORCED, not structural. Empirical record
# from two runs (evidence retained in their emu-rt-isolation.txt):
#  - run 43712263503: QEMU `-nic none` does not remove the guest's
#    virtual network DEVICES (eth0, wlan0, plus inert kernel-virtual
#    devices are still enumerated).
#  - run 43713588792: it does not remove the BACKEND either - the
#    emulator's virtual router still provisions eth0 (10.0.2.15/8) and
#    wlan0 (10.0.2.16/24) with IPv4+IPv6 addresses and per-device
#    routing tables. Android selects routing tables by fwmark via
#    `ip rule`, so a main-table route listing proves nothing either
#    way; neither does a shell-UID ping verdict.
# `-nic none` stays in the launch line as harmless surface reduction
# but is NOT part of the isolation claim.
#
# The claim: ALL non-loopback AF_INET/AF_INET6 egress from the guest is
# dropped by a FIRST-MATCH kernel filter. As root (the google_apis
# target is a userdebug image; uid 0 verified before anything
# privileged) the guest filter inserts `-I OUTPUT 1 -o lo -j ACCEPT`
# then `-I OUTPUT 2 ! -o lo -j DROP` in BOTH iptables and ip6tables -
# ahead of every netd-managed jump - and `ci/emu_rt_validate.py
# filter-order` verifies those exact two rules, in that order, at the
# top of each kernel listing; any mismatch fails closed. Scope (stated
# limit): AF_INET/AF_INET6 egress only - raw AF_PACKET egress needs
# NET_ADMIN/NET_RAW, which the app does not hold. The filter is
# installed before any app leg and not removed; netd does not rebuild
# the OUTPUT base chain during the short test window, and the app
# itself cannot (no NET_ADMIN). adbd does not traverse guest inet (the
# emulator's adb transport is the qemu pipe/vsock; the ss listener
# inventory corroborates), the adb_alive pre-test step re-proves ADB
# behind the live filter, and the post-filter loopback ping plus the
# smoke leg's real loopback RPC prove local IPC is unaffected. The app
# KEEPS its INTERNET permission (revoking it gates loopback AF_INET
# too, killing the RPC the legs exercise for real).
#
# VERIFICATION ORDER (fail-closed; evidence before any gate can abort):
#  - Phase 0: acquire + verify root (uid 0) FIRST - `ip` netlink link
#    dumps and sysfs operstate/carrier reads are Permission-denied for
#    the shell user on this image (run 43713588792), and the filter
#    install needs root anyway.
#  - Phase 1 (DIAGNOSTIC ONLY, no aborts): the complete inventory -
#    sysfs list, per-interface operstate/carrier, link, addresses, ALL
#    routing tables (v4+v6, not just main), ip rule, TCP listeners,
#    and a pre-filter egress attempt recorded for context. An
#    addressed NIC here is EXPECTED on this emulator, not a failure:
#    enforcement owns the isolation claim, inventory informs it.
#    ci/emu_rt_validate.py isolation classifies every interface's
#    demonstrated capability into the artifact as evidence.
#  - Phase 2: install + verify the first-match filter (the actual
#    control; any failure aborts).
#  - Phase 3: functional proof behind the live filter - external
#    egress must FAIL (a kernel drop demonstrably working on a guest
#    WITH real addresses and routes is the strongest evidence the deny
#    functions), loopback must SUCCEED.
# Changed semantics, explicitly scoped: this run proves the migration
# core works with all non-loopback egress kernel-dropped; it is NOT a
# hardware-absence, route-absence or backend-removal claim and NOT a
# revoked-INTERNET test.
# Probe content is nonsecret by construction: guest interface/route/
# filter/listener listings and canned unreachable errors. CR bytes from
# adb are stripped into the checked copies; scratch is wiped in the trap.
check_probe_tools() {
  # Validate the commands/options on this Android image before Gradle and
  # package installation consume the long emulator window. The IPv4
  # usage captured in run 43715097351 shows toybox `ping` has no -4/-6
  # selector; use the IPv4 literal with `ping`, and require `ping6` for
  # IPv6. Loopback only: this is a syntax/availability check, not an
  # egress or counter proof. Raw help/errors stay local and are wiped.
  if ! adb shell 'ping -c 1 -W 2 127.0.0.1' > .rt-probe-tool-v4.raw 2>&1; then
    echo 'FATAL: Android IPv4 ping command/options unavailable on loopback' >&2
    return 1
  fi
  if ! adb shell 'ping6 -c 1 -W 2 ::1' > .rt-probe-tool-v6.raw 2>&1; then
    echo 'FATAL: Android ping6 command/options unavailable on loopback; IPv6 counter proof cannot run' >&2
    return 1
  fi
  printf 'probe_tools=IPv4 ping and IPv6 ping6 validated on loopback\n'
}

verify_filter_order() {
  local label=$1 st
  if adb shell 'echo IPV4_OUTPUT; iptables -S OUTPUT && echo IPV6_OUTPUT; ip6tables -S OUTPUT' > ".rt-filter-${label}.raw" 2>&1; then st=0; else st=$?; fi
  tr -d '\r' < ".rt-filter-${label}.raw" > ".rt-filter-${label}.txt"
  if [ "$st" -ne 0 ] || ! python3 ci/emu_rt_validate.py filter-order ".rt-filter-${label}.txt"; then
    echo "FATAL: exact v4/v6 first-match order check failed: $label" >&2
    return 1
  fi
  printf 'filter_order_%s=verified\n' "$label" >> emu-rt-isolation.txt
}

snapshot_drop_counters() {  # label, v4|v6
  local label=$1 fam=$2 cmd
  if [ "$fam" = v4 ]; then
    cmd='iptables -L OUTPUT -v -x -n'
  else
    cmd='ip6tables -L OUTPUT -v -x -n'
  fi
  if ! adb shell "$cmd" > ".rt-counter-${label}-${fam}.raw" 2>&1; then
    echo "drop_counter_${fam}_${label}=unavailable (command_failed)" >> emu-rt-isolation.txt
    return 1
  fi
  tr -d '\r' < ".rt-counter-${label}-${fam}.raw" > ".rt-counter-${label}-${fam}.txt"
  local value
  if ! value=$(python3 ci/emu_rt_validate.py drop-counter "$fam" ".rt-counter-${label}-${fam}.txt") || [[ ! "$value" =~ ^[0-9]+$ ]]; then
    # Keep the reason explicit without uploading the raw family
    # listing, and record the bounded token-CLASS skeleton of every
    # DROP-candidate row: any future parser correction is then grounded
    # in the actual rendering (fixed vocabulary, never raw values),
    # not in another guessed schema.
    local reason
    reason=$(python3 -c 'import sys; sys.path.insert(0,"ci"); import emu_rt_validate as v; print(v.drop_counter_failure_reason(open(sys.argv[1]).read(), sys.argv[2]))' ".rt-counter-${label}-${fam}.txt" "$fam")
    echo "drop_counter_${fam}_${label}=unavailable (${reason})" >> emu-rt-isolation.txt
    python3 ci/emu_rt_validate.py row-shape ".rt-counter-${label}-${fam}.txt" >> emu-rt-isolation.txt || true
    return 1
  fi
  printf '%s\n' "$value" > ".rt-probe-counter_${label}_${fam}.txt"
  printf 'drop_counter_%s_%s=%s\n' "$fam" "$label" "$value" >> emu-rt-isolation.txt
}

verify_guest_offline() {
  : > emu-rt-isolation.txt
  echo 'isolation=enforced first-match kernel egress deny (guest iptables+ip6tables OUTPUT: accept lo, drop all non-lo), verified in rule order + functionally; interface/route inventory is DIAGNOSTIC ONLY' >> emu-rt-isolation.txt

  rt_probe() {  # $1=label, $2=remote command (single string)
    local st
    if adb shell "$2" > ".rt-probe-$1.raw" 2>&1; then st=0; else st=$?; fi
    tr -d '\r' < ".rt-probe-$1.raw" > ".rt-probe-$1.txt"
    printf '%s' "$st" > ".rt-probe-$1.status"
    printf '== probe %s: adb shell %s (status %s) ==\n' "$1" "$2" "$st" >> emu-rt-isolation.txt
    cat ".rt-probe-$1.txt" >> emu-rt-isolation.txt
    printf '\n' >> emu-rt-isolation.txt
    return "$st"
  }

  # PHASE 0 - root FIRST, verified: `ip` netlink dumps, sysfs state
  # reads and the filter install are ALL privileged on this image (run
  # 43713588792: as shell, `ip -o link` is Permission denied and every
  # operstate/carrier read failed). google_apis is a userdebug image;
  # if root is unavailable the run aborts here, before anything else.
  printf '== adb root + uid check (inventory + filter are privileged) ==\n' >> emu-rt-isolation.txt
  if adb root >/dev/null 2>&1 && adb wait-for-device && [ "$(adb shell id -u | tr -d '\r')" = "0" ]; then
    echo 'adb_root=ok (uid 0 verified)' >> emu-rt-isolation.txt
  else
    echo 'adb_root=FAILED' >> emu-rt-isolation.txt
    echo 'FATAL: adb root unavailable - cannot inventory or enforce, aborting' >&2
    return 1
  fi

  # PHASE 1 - DIAGNOSTIC inventory as root, NO aborts: every probe's
  # status+content lands in the artifact, and no finding here aborts.
  # This is the complete picture for diagnosis, NOT the isolation
  # proof: Android routes via per-device tables selected by fwmark
  # rules (see the ip rule listing), so main-table route absence
  # proves nothing, and ADDRESSED NICs are expected on this emulator
  # (run 43713588792: eth0=10.0.2.15/8, wlan0=10.0.2.16/24 despite
  # -nic none). The verified first-match kernel deny (phase 2) owns
  # the isolation claim; the inventory informs it.
  rt_probe sysfs_net 'ls /sys/class/net' || true
  # Per-interface kind/state straight from sysfs (no ip binary). One
  # remote loop as ONE single-quoted argument: adb joins argv with
  # bare spaces, so a locally single-quoted string is what the remote
  # shell receives. A carrier read on a down interface fails in-kernel
  # -> readfail is itself recorded evidence, not a probe failure.
  rt_probe ifstate 'for i in /sys/class/net/*; do n=${i##*/}; o=$(cat $i/operstate 2>/dev/null); c=$(cat $i/carrier 2>/dev/null); echo "$n operstate=${o:-readfail} carrier=${c:-readfail}"; done' || true
  rt_probe link 'ip -o link show' || true
  rt_probe addr 'ip -o addr show' || true
  # ALL routing tables, not just main: Android keeps the real defaults
  # in per-device tables (1015/1016/...) selected by fwmark rules.
  rt_probe route4 'ip route show table all' || true
  rt_probe route6 'ip -6 route show table all' || true
  rt_probe rule 'ip rule show' || true
  # TCP listeners (adb transport visibility: adbd on the qemu pipe /
  # vsock does not appear here). Non-fatal if ss is unavailable.
  rt_probe listeners 'ss -tlnp' || true
  # Pre-filter egress attempt, recorded for context only: with
  # Android's fwmark routing a shell-UID ping verdict is not
  # representative of app-UID reachability, so neither outcome gates.
  rt_probe egress_prefilter 'ping -c 1 -W 2 8.8.8.8' || true
  # Capability classification of every interface, as EVIDENCE (verdict
  # lines land in the artifact; non-fatal by design - enforcement owns
  # the claim).
  python3 ci/emu_rt_validate.py isolation . >> emu-rt-isolation.txt || true
  echo 'evidence_classification=diagnostic only (enforcement is the verified kernel deny, not inventory)' >> emu-rt-isolation.txt

  # PHASE 2 - enforcement: install first-match rules in both families.
  rt_probe filter_install 'iptables -I OUTPUT 1 -o lo -j ACCEPT && iptables -I OUTPUT 2 ! -o lo -j DROP && ip6tables -I OUTPUT 1 -o lo -j ACCEPT && ip6tables -I OUTPUT 2 ! -o lo -j DROP && echo FILTER_INSTALLED' \
    || { echo 'FATAL: egress filter install failed - cannot enforce isolation, aborting' >&2; return 1; }
  if ! grep -q FILTER_INSTALLED .rt-probe-filter_install.txt; then
    echo 'FATAL: egress filter install did not confirm' >&2; return 1
  fi
  verify_filter_order installed || return 1

  # PHASE 3 - controlled family-specific probes. Raw counter listings
  # stay in local scratch; only numeric values and verdicts are retained.
  # A counter delta is REQUIRED for BOTH families: a failed ping with no
  # route is not proof, and a no-route family cannot produce a delta at
  # all (its probe dies at route lookup before OUTPUT) - that case FAILS
  # CLOSED like any other missing proof; route_diagnostic only records
  # WHY, it never converts the failure into a pass.
  route_diagnostic() {  # v4|v6 -> one safe diagnostic phrase (never a verdict)
    local rtbase=".rt-probe-route${1#v}"
    if [ "$(cat "$rtbase.status" 2>/dev/null)" != "0" ]; then
      printf 'route-evidence-unavailable'
    elif grep -q '^default ' "$rtbase.txt"; then
      printf 'default-route-present'
    else
      printf 'no-default-route-observed (specific routes may still exist; diagnostic only)'
    fi
  }
  for fam in v4 v6; do
    if [ "$fam" = v4 ]; then
      probe='ping -c 1 -W 2 8.8.8.8'
    else
      probe='ping6 -c 1 -W 2 2001:4860:4860::8888'
    fi
    snapshot_drop_counters before "$fam" || {
      echo "drop_counter_delta_${fam}=unproven (route_diagnostic=$(route_diagnostic "$fam"))" >> emu-rt-isolation.txt
      echo "FATAL: $fam OUTPUT DROP counter unavailable before probe; no counter proof" >&2
      return 1
    }
    if rt_probe "egress_${fam}" "$probe"; then
      echo "egress_${fam}=unexpected_success" >> emu-rt-isolation.txt
      echo "FATAL: $fam egress probe succeeded behind the DROP filter" >&2
      return 1
    fi
    snapshot_drop_counters after "$fam" || {
      echo "drop_counter_delta_${fam}=unproven (route_diagnostic=$(route_diagnostic "$fam"))" >> emu-rt-isolation.txt
      echo "FATAL: $fam OUTPUT DROP counter unavailable after probe; no counter proof" >&2
      return 1
    }
    if ! python3 ci/emu_rt_validate.py drop-delta \
        ".rt-probe-counter_before_${fam}.txt" ".rt-probe-counter_after_${fam}.txt"; then
      echo "drop_counter_delta_${fam}=unproven (route_diagnostic=$(route_diagnostic "$fam"))" >> emu-rt-isolation.txt
      echo "FATAL: $fam OUTPUT DROP counter did not increase; no counter proof (possibly no route)" >&2
      return 1
    fi
    echo "drop_counter_delta_${fam}=positive" >> emu-rt-isolation.txt
    verify_filter_order "after_${fam}_probe" || return 1
  done

  rt_probe loopback_v4 'ping -c 1 -W 2 127.0.0.1' \
    || { echo 'FATAL: IPv4 loopback broken after filter install - aborting' >&2; return 1; }
  rt_probe loopback_v6 'ping6 -c 1 -W 2 ::1' \
    || { echo 'FATAL: IPv6 loopback broken after filter install - aborting' >&2; return 1; }
  verify_filter_order final || return 1
  echo 'isolation_verified=yes (uid-0 verified; first-match v4+v6 accept-lo/drop-rest verified at install, after each probe and final; v4+v6 DROP counter deltas positive; loopback positive; inventory diagnostic only; fail-closed)' >> emu-rt-isolation.txt
}

check_instrumentation() {
  # JUnit summary gate: a missing OK line or an explicit failure marker
  # fails the leg. The raw output is NEVER echoed to the Actions console
  # and NEVER uploaded (a failure trace can carry app text and redaction
  # is not fail-closed while the one-time code is unknown); the file is
  # runner-local and wiped in the capture trap.
  local file=$1
  if grep -q 'FAILURES!!!' "$file" || grep -q 'INSTRUMENTATION_STATUS_CODE: -2' "$file"; then
    return 1
  fi
  grep -Eq 'OK \([0-9]+ tests?\)' "$file"
}

capture_emulator_outputs() {
  local original_status=$?
  local log_status pull_status extract_status=0 inspect_status=0 files_status=0
  trap - EXIT
  set +e
  adb logcat -d > "$log_file" 2>&1
  log_status=$?
  {
    printf 'test_status=%s\n' "$original_status"
    printf 'logcat_status=%s\n' "$log_status"
    printf 'capture_command=adb exec-out run-as %s tar cf - -C files emu-out\n' "$package_id"
  } > emu-capture-meta.txt
  adb exec-out run-as "$package_id" tar cf - -C files emu-out > "$tar_file" 2>"$tar_file.stderr"
  pull_status=$?
  printf 'pull_status=%s\n' "$pull_status" >> emu-capture-meta.txt
  printf 'tar_bytes=' >> emu-capture-meta.txt
  wc -c < "$tar_file" >> emu-capture-meta.txt
  printf 'tar_header_hex=' >> emu-capture-meta.txt
  od -An -tx1 -N64 "$tar_file" >> emu-capture-meta.txt
  if [ -s "$tar_file.stderr" ]; then
    printf 'tar_stderr_first_4096_bytes:\n' >> emu-capture-meta.txt
    head -c 4096 "$tar_file.stderr" >> emu-capture-meta.txt
    printf '\n' >> emu-capture-meta.txt
  fi
  if [ "$pull_status" -eq 0 ]; then
    tar tf "$tar_file" > emu-capture-tar-list.txt 2> emu-capture-tar-list.stderr
    inspect_status=$?
    printf 'tar_list_status=%s\n' "$inspect_status" >> emu-capture-meta.txt
    tar xf "$tar_file"
    extract_status=$?
    printf 'tar_extract_status=%s\n' "$extract_status" >> emu-capture-meta.txt
  else
    printf 'Could not pull app output from %s; archive and stderr retained.\n' "$package_id" >&2
    head -c 4096 "$tar_file.stderr" >&2
  fi
  adb shell run-as "$package_id" sh -c 'ls -laR files/emu-out' > emu-capture-files.txt 2> emu-capture-files.stderr
  files_status=$?
  printf 'files_listing_status=%s\n' "$files_status" >> emu-capture-meta.txt
  if [ -s emu-capture-files.stderr ]; then
    printf 'files_listing_stderr_first_2048_bytes:\n' >> emu-capture-meta.txt
    head -c 2048 emu-capture-files.stderr >> emu-capture-meta.txt
  fi
  if [ "$original_status" -eq 0 ]; then
    if [ "$log_status" -ne 0 ]; then
      original_status=$log_status
    elif [ "$pull_status" -ne 0 ]; then
      original_status=$pull_status
    elif [ "$inspect_status" -ne 0 ]; then
      original_status=$inspect_status
    elif [ "$extract_status" -ne 0 ]; then
      original_status=$extract_status
    elif [ ! -f "$required_output" ]; then
      echo "FATAL: expected emulator output is missing: $required_output" >&2
      original_status=1
    fi
  fi
  if [ -d emu-out ]; then ls -laR emu-out; fi
  exit "$original_status"
}

capture_roundtrip_outputs() {
  # Round-trip capture with a PUBLIC-repo artifact policy:
  #  - UPLOADABLE (allowlist, no fallback): the harness's OWN
  #    self-authored outputs with known nonsecret fields - success/
  #    assertion JSON (smoke-ok, export-done, import-done,
  #    import-assertions), directory listings (names+sizes only), the
  #    isolation metadata (guest interface/route listing + probe
  #    verdict), the bounded old-app checks (marker COUNTS, filenames).
  #  - NEVER uploaded or logged: raw logs of any kind - logcat main or
  #    crash buffers, instrumentation/JUnit output (a failure trace can
  #    carry app text and redaction is NOT fail-closed while the
  #    one-time code is unknown), the .dcmig bundle, the code,
  #    EXPORT_RESULT (holds the code), bot.log, screenshots (the export
  #    test's dialog shot shows the code), raw app-dir archives. Raw
  #    instrumentation output goes to runner-local files, gates the
  #    JUnit summary, and is wiped here; the rest stays in app-private
  #    storage on the ephemeral emulator, or runner-local for the
  #    handoff, and is wiped here.
  #  - Consequence: native-crash evidence is RPC liveness plus the
  #    JUnit gates, not log inspection; no logcat/tombstone claim.
  local original_status=$?
  trap - EXIT
  set +e
  : > emu-rt-meta.txt
  printf 'test_status=%s\n' "$original_status" >> emu-rt-meta.txt

  # Allowlisted app outputs ONLY: self-authored JSON + listings.
  # adb exec-out does NOT propagate the remote exit status (a failed
  # cat still exits 0 and its error text lands in the file), so the
  # remote file's existence is probed FIRST (absent vs malformed are
  # distinct statuses) and every pulled file must pass ci/
  # emu_rt_validate.py's per-filename FIELD allowlist (exact top-level
  # dict, per-key types, bounded expected values) or it is dropped,
  # never uploaded - content never influences the verdict text.
  for pkg in "$OLD_PKG" "$NEW_PKG"; do
    mkdir -p "capture/$pkg"
    for name in smoke-ok.json export-done.json import-done.json import-assertions.json; do
      adb shell "run-as $pkg test -f files/emu-out/$name" > /dev/null 2>&1
      if [ "$?" -ne 0 ]; then
        printf '%s %s remote=absent\n' "$pkg" "$name" >> emu-rt-meta.txt
        rm -f "capture/$pkg/$name"
        continue
      fi
      adb exec-out run-as "$pkg" cat "files/emu-out/$name" > "capture/$pkg/$name" 2>/dev/null
      printf '%s %s pull_status=%s\n' "$pkg" "$name" "$?" >> emu-rt-meta.txt
      if [ -s "capture/$pkg/$name" ] && python3 ci/emu_rt_validate.py json "$name" "capture/$pkg/$name"; then
        printf '%s %s schema=ok\n' "$pkg" "$name" >> emu-rt-meta.txt
      else
        printf '%s %s schema=malformed (dropped)\n' "$pkg" "$name" >> emu-rt-meta.txt
        rm -f "capture/$pkg/$name"
      fi
    done
    # One double-quoted remote string: adb joins argv with bare spaces,
    # so a locally single-quoted sh -c would lose its grouping.
    adb shell "run-as $pkg ls -laR files/emu-out" > "capture/$pkg/emu-out-listing.txt" 2>/dev/null
    local lst_st=$?
    printf '%s listing_status=%s\n' "$pkg" "$lst_st" >> emu-rt-meta.txt
    if [ "$lst_st" -ne 0 ]; then rm -f "capture/$pkg/emu-out-listing.txt"; fi
  done

  # Old identity untouched: BOUNDED checks on stable fixture markers and
  # expected file presence - deliberately NOT byte/hashes of the account
  # DBs, whose bytes legitimately change when core opens them. Only
  # marker COUNTS and filenames are recorded, never file contents.
  adb shell pm path "$OLD_PKG" > old-app-pm-path.txt 2>&1
  printf 'old_app_installed_status=%s\n' "$?" >> emu-rt-meta.txt
  adb shell "run-as $OLD_PKG ls files" > old-app-files-after.txt 2>/dev/null
  local files_st=$?
  printf 'old_app_files_list_status=%s\n' "$files_st" >> emu-rt-meta.txt
  if [ "$files_st" -ne 0 ]; then rm -f old-app-files-after.txt; fi
  local state_hits priv_hits
  state_hits=$(adb shell "run-as $OLD_PKG grep -c EMU-STATE-MARKER-5dca71 files/bot_state.json" 2>/dev/null | tr -d '\r')
  priv_hits=$(adb shell "run-as $OLD_PKG grep -c fixture-priv-EMU-PRIV-MARKER-31c7aa shared_prefs/instinct_widget.xml" 2>/dev/null | tr -d '\r')
  printf 'old_app_state_marker_hits=%s\n' "${state_hits:-capture-failed}" >> emu-rt-meta.txt
  printf 'old_app_priv_marker_hits=%s\n' "${priv_hits:-capture-failed}" >> emu-rt-meta.txt

  # Secret + raw-output wipe: the runner-local handoff copy, every
  # raw instrumentation capture and the probe scratch. The apps'
  # private emu-out (bundle, code, screenshots, logs) dies with the
  # ephemeral emulator; the guest egress filter dies with it too, and
  # there is no host isolation state to tear down
  # (see verify_guest_offline).
  rm -f rt-bundle.dcmig rt-code.txt \
    smoke-instrumentation.txt export-instrumentation.txt import-instrumentation.txt \
    .rt-probe-*.raw .rt-probe-*.txt .rt-probe-*.status .rt-counter-*.raw .rt-counter-*.txt .rt-filter-*.raw .rt-filter-*.txt

  if [ "$original_status" -eq 0 ]; then
    if [ ! -f "capture/$OLD_PKG/export-done.json" ]; then
      echo "FATAL: old identity export artifact missing after capture" >&2
      original_status=1
    elif [ ! -f "capture/$NEW_PKG/import-done.json" ]; then
      echo "FATAL: renamed identity import artifact missing after capture" >&2
      original_status=1
    elif [ "${state_hits:-0}" != "1" ] || [ "${priv_hits:-0}" != "1" ]; then
      echo "FATAL: old identity fixture markers damaged or unreadable (state=${state_hits:-?} priv=${priv_hits:-?})" >&2
      original_status=1
    fi
  fi
  exit "$original_status"
}

require_arm64_abi() {
  local abis
  abis=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')
  printf 'cpu abilist: %s\n' "$abis"
  case "$abis" in
    *arm64-v8a*) echo 'arm64-v8a supported (native or translated)' ;;
    *) echo 'FATAL: no arm64-v8a on this image (no ARM translation) - re-dispatch with arch=arm64-v8a' >&2; return 2 ;;
  esac
}

if [ "$mode" = roundtrip ]; then
  # FULL FIXTURE ROUND TRIP on ONE emulator behind the verified guest
  # egress drop (see verify_guest_offline; `-nic none` is kept but NOT
  # relied upon): the old identity exports, the renamed identity
  # imports, the old identity's data is verified untouched afterwards.
  # The guest filter dies with the ephemeral emulator - no host state
  # to clean up.
  #
  # Every pre-test step and leg appends its label+status to
  # emu-rt-pretest.txt BEFORE and AFTER running (uploaded, nonsecret):
  # a failing step always leaves the exact step that failed instead of
  # an unexplained exit. Command output streams as before; the statuses
  # are the harness's own.
  trap capture_roundtrip_outputs EXIT
  : > "$PRETEST"

  record_step() {  # $1=label, rest=command; output streams through
    local label=$1; shift
    local st
    printf 'step_%s=running\n' "$label" >> "$PRETEST"
    if "$@"; then st=0; else st=$?; fi
    printf 'step_%s_status=%s\n' "$label" "$st" >> "$PRETEST"
    if [ "$st" -ne 0 ]; then
      echo "FATAL: pre-test step failed: $label (status $st)" >&2
      exit "$st"
    fi
  }

  record_step abi_check require_arm64_abi
  # Validate Android's actual family-specific ping syntax on loopback
  # before the longer Gradle/build/install section.
  record_step probe_tool_check check_probe_tools
  # Build + install BOTH identities. Gradle runs on the HOST (the
  # runner's own network, unaffected by the guest having no NIC);
  # adb install does not traverse a guest NIC either.
  record_step install_old_identity ./gradlew :app:installDebug :app:installDebugAndroidTest --no-daemon
  record_step install_new_identity ./gradlew :app:installDebug :app:installDebugAndroidTest --no-daemon -PemuAppId="$NEW_PKG"

  # Fail-closed isolation verification BEFORE any app leg runs; the
  # smoke leg's loopback RPC health check then doubles as the tested
  # (not guessed) proof that guest loopback/IPC is unaffected.
  record_step isolation_verify verify_guest_offline
  record_step adb_alive adb shell true

  instrument_leg() {
    # instrument one class against one package, JUnit summary gate,
    # status recorded incrementally.
    local pkg=$1 klass=$2 log=$3 label=$4
    local st
    printf 'leg_%s=running\n' "$label" >> "$PRETEST"
    if adb shell am instrument -w -e class "$klass" "$pkg.test/androidx.test.runner.AndroidJUnitRunner" > "$log" 2>&1; then st=0; else st=$?; fi
    local verdict=fail
    if [ "$st" -eq 0 ] && check_instrumentation "$log"; then
      verdict=ok
    fi
    printf 'leg_%s_status=%s am_exit=%s\n' "$label" "$verdict" "$st" >> "$PRETEST"
    if [ "$verdict" != ok ]; then
      echo "FATAL: leg failed: $klass" >&2
      exit 1
    fi
    verify_filter_order "after_${label}_leg" || exit 1
  }

  # 1. short gate on the old identity: python + core + fixture seed.
  instrument_leg "$OLD_PKG" com.vanipy.migtest.EmuSmokeTest smoke-instrumentation.txt smoke
  # 2. real export path on the old identity (UI + service mode).
  instrument_leg "$OLD_PKG" com.vanipy.migtest.MigrationEmuExportTest export-instrumentation.txt export
  # 3. handoff: pull the bundle + one-time code out of the old app's
  #    private dir. Runner-local ONLY, wiped in the capture trap, never
  #    uploaded or logged: the code is secret by design, fixture or not.
  #    adb exec-out does NOT propagate the remote exit status, so the
  #    pulled content is validated by SHAPE, not just size.
  record_step pull_bundle adb exec-out run-as "$OLD_PKG" cat files/emu-out/dcbot-migration.dcmig > rt-bundle.dcmig
  record_step pull_code adb exec-out run-as "$OLD_PKG" cat files/emu-out/code.txt > rt-code.txt
  record_step check_bundle_nonempty test -s rt-bundle.dcmig
  record_step check_bundle_size test "$(wc -c < rt-bundle.dcmig)" -gt 1024
  # Exact generate_transfer_code shape (44 chars = 43 url-safe +
  # '=' padding over 32 bytes), decoded WITHOUT printing it.
  record_step check_code_shape python3 ci/emu_rt_validate.py code rt-code.txt
  # 4. renamed identity: push the bundle into its private dir, run the
  #    real import core. The remote command is ONE double-quoted string
  #    so the remote shell keeps the sh -c grouping (adb joins argv
  #    with bare spaces - single quotes would be lost).
  record_step push_bundle adb push rt-bundle.dcmig /data/local/tmp/dcbot-migration.dcmig
  record_step push_code adb push rt-code.txt /data/local/tmp/code.txt
  record_step stage_handoff adb shell "run-as $NEW_PKG sh -c 'mkdir -p files/emu-in && cp /data/local/tmp/dcbot-migration.dcmig /data/local/tmp/code.txt files/emu-in/'"
  record_step clear_tmp adb shell rm -f /data/local/tmp/dcbot-migration.dcmig /data/local/tmp/code.txt
  instrument_leg "$NEW_PKG" com.vanipy.migtest.MigrationEmuImportTest import-instrumentation.txt import
  verify_filter_order final_closeout || exit 1
  exit 0
fi

trap capture_emulator_outputs EXIT

require_arm64_abi

if [ "$mode" = import ]; then
  ./gradlew :app:installDebug :app:installDebugAndroidTest --no-daemon -PemuAppId=com.vanipy.intodo
  adb push emu-in-dl/emu-out/dcbot-migration.dcmig /data/local/tmp/dcbot-migration.dcmig
  adb push emu-in-dl/emu-out/code.txt /data/local/tmp/code.txt
  adb shell run-as com.vanipy.intodo sh -c 'mkdir -p files/emu-in && cp /data/local/tmp/dcbot-migration.dcmig /data/local/tmp/code.txt files/emu-in/'
  set +e
  adb shell am instrument -w -e class "$test_class" com.vanipy.intodo.test/androidx.test.runner.AndroidJUnitRunner > import-instrumentation.txt 2>&1
  test_status=$?
  set -e
elif [ "$mode" = smoke-export ]; then
  # connectedDebugAndroidTest uninstalls both APKs before this script's EXIT
  # trap can capture app-private emu-out. Install and invoke instrumentation
  # directly so the bounded EXIT capture runs while the fixture app exists.
  ./gradlew :app:installDebug :app:installDebugAndroidTest --no-daemon
  set +e
  adb shell am instrument -w -e class "$test_class" "$package_id.test/androidx.test.runner.AndroidJUnitRunner" > smoke-instrumentation.txt 2>&1
  test_status=$?
  set -e
  if grep -q 'FAILURES!!!' smoke-instrumentation.txt || grep -q 'INSTRUMENTATION_STATUS_CODE: -2' smoke-instrumentation.txt; then
    test_status=1
  elif ! grep -Eq 'OK \([0-9]+ tests?\)' smoke-instrumentation.txt; then
    echo 'FATAL: instrumentation output did not contain a passing JUnit summary; preserving logs' >&2
    test_status=1
  fi
  tail -n 80 smoke-instrumentation.txt
else
  set +e
  ./gradlew :app:connectedDebugAndroidTest --no-daemon "-Pandroid.testInstrumentationRunnerArguments.class=$test_class"
  test_status=$?
fi
set -e
exit "$test_status"
