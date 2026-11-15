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

# Guest network isolation has TWO layers after an empirical correction:
# run 43712263503 (evidence retained in its emu-rt-isolation.txt) showed
# the NIC-ABSENCE premise false: QEMU `-nic none` removes the network
# BACKEND only - the guest kernel still enumerates its virtual hardware
# (eth0, wlan0) plus inert kernel-virtual devices (dummy0, gre0, ...).
# A device inventory can therefore never prove isolation without a
# name allowlist (rejected: a new device name would silently evade it).
#
#  1. STRUCTURAL SURFACE REDUCTION: the workflow still launches the
#     emulator with QEMU `-nic none` (via the Android emulator's `-qemu`
#     passthrough, documented to be the LAST option - verified against
#     the pinned action source, which appends nothing after
#     emulator-options; a rejected option trips the action's own
#     'invalid command-line parameter' watcher). With no backend, even
#     a working guest device has nothing behind it.
#  2. ENFORCED EGRESS DROP: as root (the google_apis target is a
#     userdebug image) the guest filter inserts FIRST `-I OUTPUT 1 -o
#     lo -j ACCEPT` then `-I OUTPUT 2 ! -o lo -j DROP` in BOTH iptables
#     and ip6tables. The validator checks exact first-match order from
#     each kernel listing; any mismatch fails closed. Kernel-level and
#     device-name-independent. Scope
#     (stated limit): AF_INET/AF_INET6 egress only - raw AF_PACKET
#     egress needs NET_ADMIN/NET_RAW, which the app does not hold, and
#     `-nic none` leaves no backend even for that. adbd does not
#     traverse guest inet (empirical: installs and probes worked
#     backend-less in runs 43710243750 and 43712263503), and the
#     adb_alive pre-test step re-proves ADB AFTER the filter is live.
# The app KEEPS its INTERNET permission (revoking it gates loopback
# AF_INET too, killing the loopback RPC the legs exercise for real).
#
# VERIFICATION is fail-closed and evidence-BEFORE-gates:
#  - Phase 1 records EVERY probe (sysfs list, per-interface
#    operstate/carrier, link, addr, v4+v6 routes, rules, and a
#    pre-filter egress attempt) into emu-rt-isolation.txt with its
#    status; NO gate aborts during collection, so one unexpected
#    device can never hide the rest of the picture (v6 aborted at the
#    first sysfs gate and the run showed ONLY the sysfs list).
#  - Phase 2 hands the evidence to `ci/emu_rt_validate.py isolation`,
#    which classifies every interface's DEMONSTRATED capability
#    (routable address or default route - never its name or state)
#    and enforces name-independent gates: exactly one lo link device,
#    lo inet present, NO non-loopback inet or non-link-local inet6
#    address, NO default route, no probe error markers.
#  - A PRE-filter egress attempt must already FAIL (`-nic none` not
#    effective otherwise).
#  - Phase 3 installs + verifies the filter; phase 4 re-proves egress
#    must-fail BEHIND the live filter and loopback must work; the
#    smoke leg's loopback RPC then doubles as the tested (not guessed)
#    proof that guest loopback/IPC is unaffected.
# Changed semantics, explicitly scoped: this run proves the migration
# core works with ALL non-loopback egress kernel-dropped on a guest
# that HAS virtual network devices; it is NOT a hardware-absence claim
# and NOT a revoked-INTERNET test.
# Probe content is nonsecret by construction: guest interface/route/
# filter listings and canned unreachable errors. CR bytes from adb are
# stripped into the checked copies; scratch is wiped in the trap.
verify_guest_offline() {
  : > emu-rt-isolation.txt
  echo 'isolation=enforced egress drop (guest iptables+ip6tables OUTPUT: accept lo, drop rest) + -qemu -nic none backend removal; capability gates over complete pre-filter evidence' >> emu-rt-isolation.txt

  rt_probe() {  # $1=label, $2=remote command (single string)
    local st
    if adb shell "$2" > ".rt-probe-$1.raw" 2>&1; then st=0; else st=$?; fi
    tr -d '\r' < ".rt-probe-$1.raw" > ".rt-probe-$1.txt"
    printf '== probe %s: adb shell %s (status %s) ==\n' "$1" "$2" "$st" >> emu-rt-isolation.txt
    cat ".rt-probe-$1.txt" >> emu-rt-isolation.txt
    printf '\n' >> emu-rt-isolation.txt
    return "$st"
  }

  # PHASE 1 - complete evidence collection, NO aborts: every probe's
  # status+content lands in the artifact before anything is evaluated.
  local coll_fail=0
  rt_probe sysfs_net 'ls /sys/class/net' || coll_fail=1
  # Per-interface kind/state straight from sysfs (no ip binary). One
  # remote loop as ONE single-quoted argument: adb joins argv with
  # bare spaces, so a locally single-quoted string is what the remote
  # shell receives. A carrier read on a down interface fails in-kernel
  # -> readfail is itself recorded evidence, not a probe failure.
  rt_probe ifstate 'for i in /sys/class/net/*; do n=${i##*/}; o=$(cat $i/operstate 2>/dev/null); c=$(cat $i/carrier 2>/dev/null); echo "$n operstate=${o:-readfail} carrier=${c:-readfail}"; done' || coll_fail=1
  rt_probe link 'ip -o link show' || coll_fail=1
  rt_probe addr 'ip -o addr show' || coll_fail=1
  rt_probe route4 'ip route show' || coll_fail=1
  rt_probe route6 'ip -6 route show' || coll_fail=1
  rt_probe rule 'ip rule show' || coll_fail=1
  # Pre-filter egress attempt: EXPECTED to fail with the backend
  # removed. A success means `-nic none` did nothing and the guest can
  # really reach out - abort; the surface-reduction statement and the
  # evidence gates' meaning would both be false.
  if rt_probe egress_prefilter 'ping -c 1 -W 2 8.8.8.8'; then
    echo 'FATAL: guest egress SUCCEEDED with no filter yet - -nic none not effective, setup not understood, aborting' >&2
    return 1
  fi
  if [ "$coll_fail" -ne 0 ]; then
    echo 'FATAL: isolation evidence collection failed - cannot verify, aborting' >&2
    return 1
  fi

  # PHASE 2 - capability gates over the complete evidence (validator
  # verdict lines land in the artifact). Name-independent: any
  # demonstrated address or default route fails, whatever the device
  # is called; an up interface with NO address/route does NOT fail
  # (state is evidence, not capability) - the filter below drops its
  # egress regardless.
  if ! python3 ci/emu_rt_validate.py isolation . >> emu-rt-isolation.txt; then
    echo 'FATAL: isolation evidence gates failed (see emu-rt-isolation.txt) - aborting' >&2
    return 1
  fi

  # PHASE 3 - enforcement. adb root is available on the userdebug
  # google_apis image; rules are verified back from the kernel
  # listing, never trusted on exit status alone.
  printf '== adb root (filter install requires root; userdebug image) ==\n' >> emu-rt-isolation.txt
  if adb root >/dev/null 2>&1 && adb wait-for-device && adb shell true; then
    echo 'adb_root=ok' >> emu-rt-isolation.txt
  else
    echo 'adb_root=FAILED' >> emu-rt-isolation.txt
    echo 'FATAL: adb root unavailable - cannot install the egress filter, aborting' >&2
    return 1
  fi
  rt_probe filter_install 'iptables -I OUTPUT 1 -o lo -j ACCEPT && iptables -I OUTPUT 2 ! -o lo -j DROP && ip6tables -I OUTPUT 1 -o lo -j ACCEPT && ip6tables -I OUTPUT 2 ! -o lo -j DROP && echo FILTER_INSTALLED' \
    || { echo 'FATAL: egress filter install failed - cannot enforce isolation, aborting' >&2; return 1; }
  if ! grep -q FILTER_INSTALLED .rt-probe-filter_install.txt; then
    echo 'FATAL: egress filter install did not confirm - cannot enforce isolation, aborting' >&2
    return 1
  fi
  rt_probe filter_list 'echo IPV4_OUTPUT; iptables -S OUTPUT && echo IPV6_OUTPUT; ip6tables -S OUTPUT' \
    || { echo 'FATAL: egress filter listing failed - cannot verify enforcement, aborting' >&2; return 1; }
  if ! python3 ci/emu_rt_validate.py filter-order .rt-probe-filter_list.txt; then
    echo 'FATAL: first-match OUTPUT policy is not verified accept-lo then drop-rest in both v4+v6 - aborting' >&2
    return 1
  fi

  # PHASE 4 - functional proof behind the live filter: external egress
  # must STILL fail (now by kernel drop) and loopback must work.
  if rt_probe egress_postfilter 'ping -c 1 -W 2 8.8.8.8'; then
    echo 'FATAL: guest egress SUCCEEDED behind the egress filter - enforcement broken, aborting' >&2
    return 1
  fi
  rt_probe loopback 'ping -c 1 -W 2 127.0.0.1' \
    || { echo 'FATAL: loopback broken after filter install - aborting' >&2; return 1; }

  echo 'isolation_verified=yes (complete evidence + capability gates + enforced v4/v6 egress drop + pre/post-filter egress must-fail + loopback positive; fail-closed)' >> emu-rt-isolation.txt
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
    .rt-probe-*.raw .rt-probe-*.txt

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
  # FULL FIXTURE ROUND TRIP on ONE emulator (guest egress drop + QEMU
  # `-nic none` backend removal, see verify_guest_offline): the old
  # identity exports, the renamed identity imports, the old identity's
  # data is verified untouched afterwards. The guest filter dies with
  # the ephemeral emulator - no host state to clean up.
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
