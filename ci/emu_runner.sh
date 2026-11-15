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

# Guest network isolation is STRUCTURAL: the workflow launches the
# emulator with QEMU `-nic none` (via the Android emulator's `-qemu`
# passthrough, which is documented to be the LAST option - verified
# against the pinned action source, which appends nothing after
# emulator-options; a rejected option trips the action's own
# 'invalid command-line parameter' watcher). The guest kernel then has
# no network device beside loopback. That is enforcement by absence -
# there is no route for ANY protocol, either app UID or any translated
# child, and no host firewall rule whose coverage would need proving.
# ADB/adbd do not traverse a guest NIC, so the artifact route is
# unaffected; the app KEEPS its INTERNET permission, so in-guest
# semantics (including the loopback RPC the legs exercise for real)
# are production-identical. Stated limit: this proves the migration
# core works with ZERO external connectivity; it says nothing about
# behavior with the INTERNET permission revoked (that permission gates
# loopback AF_INET too, so a no-INTERNET manifest variant would need
# its own RPC compatibility proof and was not chosen).
# Fail-closed verification (covers a silently ignored passthrough):
# adb unreadable, any link-layer device besides lo (an UNADDRESSED NIC
# must NOT pass), any non-loopback address, any default route, or a
# successful egress attempt aborts the run - offline is NEVER asserted
# from an unverified setup.
verify_guest_offline() {
  local links ifs routes
  links=$(adb shell ip -o link show 2>&1)
  ifs=$(adb shell ip -o addr show 2>&1)
  routes=$(adb shell 'ip route show; ip -6 route show' 2>&1)
  {
    echo 'isolation=structural (emulator launched with -qemu -nic none; no guest NIC)'
    echo '== guest link devices (ip -o link show) =='
    printf '%s\n' "$links"
    echo '== guest addresses (ip -o addr show) =='
    printf '%s\n' "$ifs"
    echo '== guest routes (ip route show; ip -6 route show) =='
    printf '%s\n' "$routes"
  } > emu-rt-isolation.txt
  if [ -z "$links" ]; then
    echo 'FATAL: guest link listing is empty - cannot verify isolation, aborting' >&2
    exit 1
  fi
  if printf '%s\n%s\n' "$links" "$ifs" | grep -Eiq 'error:|not found|permission denied'; then
    echo 'FATAL: adb could not read guest interfaces - cannot verify isolation, aborting' >&2
    exit 1
  fi
  if printf '%s\n' "$routes" | grep -Eiq 'error:|not found|permission denied'; then
    echo 'FATAL: guest route query failed - cannot verify isolation, aborting' >&2
    exit 1
  fi
  # Link layer: lo must be the ONLY device. An unaddressed NIC has no
  # inet line, so the address check alone would let it pass - it must
  # not. (If a stock image ships an inert kernel-virtual device here,
  # this aborts honestly and the listing says exactly what was seen.)
  if ! printf '%s\n' "$links" | awk '$2 == "lo:" { lo++ } END { exit (lo == 1 ? 0 : 1) }'; then
    echo 'FATAL: guest link listing does not contain exactly one loopback device - cannot verify isolation, aborting' >&2
    exit 1
  fi
  if printf '%s\n' "$links" | awk '{ if ($2 != "lo:") bad=1 } END { exit (bad ? 0 : 1) }'; then
    echo 'FATAL: guest has a non-loopback link device - NOT isolated, aborting' >&2
    exit 1
  fi
  if printf '%s\n' "$ifs" | awk '$3 == "inet" || $3 == "inet6" { if ($2 != "lo") bad=1 } END { exit (bad ? 0 : 1) }'; then
    echo 'FATAL: guest has a non-loopback interface with an address - NOT isolated, aborting' >&2
    exit 1
  fi
  if printf '%s\n' "$routes" | grep -q '^default'; then
    echo 'FATAL: guest has a default route - NOT isolated, aborting' >&2
    exit 1
  fi
  # Sanity demonstration (the listings above are the proof): with no
  # NIC there is no route for any protocol, so any egress attempt fails
  # at route lookup. If one ever succeeds the setup is not what we
  # think.
  if adb shell ping -c 1 -W 2 8.8.8.8 > /dev/null 2>&1; then
    echo 'FATAL: guest egress SUCCEEDED despite -qemu -nic none - setup not understood, aborting' >&2
    exit 1
  fi
  echo 'egress_probe=icmp 8.8.8.8 failed as required (no route)' >> emu-rt-isolation.txt
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
  for pkg in "$OLD_PKG" "$NEW_PKG"; do
    mkdir -p "capture/$pkg"
    for name in smoke-ok.json export-done.json import-done.json import-assertions.json; do
      adb exec-out run-as "$pkg" cat "files/emu-out/$name" > "capture/$pkg/$name" 2>/dev/null
      printf '%s %s pull_status=%s\n' "$pkg" "$name" "$?" >> emu-rt-meta.txt
      [ -s "capture/$pkg/$name" ] || rm -f "capture/$pkg/$name"
    done
    adb shell run-as "$pkg" sh -c 'ls -laR files/emu-out' \
      > "capture/$pkg/emu-out-listing.txt" 2>/dev/null
    printf '%s listing_status=%s\n' "$pkg" "$?" >> emu-rt-meta.txt
  done

  # Old identity untouched: BOUNDED checks on stable fixture markers and
  # expected file presence - deliberately NOT byte/hashes of the account
  # DBs, whose bytes legitimately change when core opens them. Only
  # marker COUNTS and filenames are recorded, never file contents.
  adb shell pm path "$OLD_PKG" > old-app-pm-path.txt 2>&1
  printf 'old_app_installed_status=%s\n' "$?" >> emu-rt-meta.txt
  adb shell run-as "$OLD_PKG" ls files > old-app-files-after.txt 2>&1
  printf 'old_app_files_list_status=%s\n' "$?" >> emu-rt-meta.txt
  local state_hits priv_hits
  state_hits=$(adb shell run-as "$OLD_PKG" sh -c \
    'grep -c EMU-STATE-MARKER-5dca71 files/bot_state.json' 2>/dev/null | tr -d '\r')
  priv_hits=$(adb shell run-as "$OLD_PKG" sh -c \
    'grep -c fixture-priv-EMU-PRIV-MARKER-31c7aa shared_prefs/instinct_widget.xml' 2>/dev/null | tr -d '\r')
  printf 'old_app_state_marker_hits=%s\n' "${state_hits:-capture-failed}" >> emu-rt-meta.txt
  printf 'old_app_priv_marker_hits=%s\n' "${priv_hits:-capture-failed}" >> emu-rt-meta.txt

  # Secret + raw-output wipe: the runner-local handoff copy and every
  # raw instrumentation capture. The apps' private emu-out (bundle,
  # code, screenshots, logs) dies with the ephemeral emulator; there is
  # no host isolation state to tear down (see verify_guest_offline).
  rm -f rt-bundle.dcmig rt-code.txt \
    smoke-instrumentation.txt export-instrumentation.txt import-instrumentation.txt

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
  # FULL FIXTURE ROUND TRIP on ONE emulator launched with QEMU `-nic
  # none` (see verify_guest_offline): the old identity exports, the renamed
  # identity imports, the old identity's data is verified untouched
  # afterwards. There is no host firewall state - nothing to clean up.
  trap capture_roundtrip_outputs EXIT
  require_arm64_abi

  # Build + install BOTH identities. Gradle runs on the HOST (the
  # runner's own network, unaffected by the guest having no NIC);
  # adb install does not traverse a guest NIC either.
  ./gradlew :app:installDebug :app:installDebugAndroidTest --no-daemon
  ./gradlew :app:installDebug :app:installDebugAndroidTest --no-daemon -PemuAppId="$NEW_PKG"

  # Fail-closed isolation verification BEFORE any app leg runs; the
  # smoke leg's loopback RPC health check then doubles as the tested
  # (not guessed) proof that guest loopback/IPC is unaffected.
  verify_guest_offline
  adb shell true  # ADB route alive

  instrument_leg() {
    # instrument one class against one package, JUnit summary gate.
    local pkg=$1 klass=$2 log=$3
    set +e
    adb shell am instrument -w -e class "$klass" "$pkg.test/androidx.test.runner.AndroidJUnitRunner" > "$log" 2>&1
    local st=$?
    set -e
    if [ "$st" -ne 0 ] || ! check_instrumentation "$log"; then
      echo "FATAL: leg failed: $klass" >&2
      exit 1
    fi
  }

  # 1. short gate on the old identity: python + core + fixture seed.
  instrument_leg "$OLD_PKG" com.vanipy.migtest.EmuSmokeTest smoke-instrumentation.txt
  # 2. real export path on the old identity (UI + service mode).
  instrument_leg "$OLD_PKG" com.vanipy.migtest.MigrationEmuExportTest export-instrumentation.txt
  # 3. handoff: pull the bundle + one-time code out of the old app's
  #    private dir. Runner-local ONLY, wiped in the capture trap, never
  #    uploaded or logged: the code is secret by design, fixture or not.
  adb exec-out run-as "$OLD_PKG" cat files/emu-out/dcbot-migration.dcmig > rt-bundle.dcmig
  adb exec-out run-as "$OLD_PKG" cat files/emu-out/code.txt > rt-code.txt
  test -s rt-bundle.dcmig
  test -s rt-code.txt
  # 4. renamed identity: push the bundle into its private dir, run the
  #    real import core.
  adb push rt-bundle.dcmig /data/local/tmp/dcbot-migration.dcmig
  adb push rt-code.txt /data/local/tmp/code.txt
  adb shell run-as "$NEW_PKG" sh -c 'mkdir -p files/emu-in && cp /data/local/tmp/dcbot-migration.dcmig /data/local/tmp/code.txt files/emu-in/'
  adb shell rm -f /data/local/tmp/dcbot-migration.dcmig /data/local/tmp/code.txt
  instrument_leg "$NEW_PKG" com.vanipy.migtest.MigrationEmuImportTest import-instrumentation.txt
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
