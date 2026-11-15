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
  *)
    echo "FATAL: expected mode smoke-export, export, smoke-import or import; got '$mode'" >&2
    exit 64
    ;;
esac

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
  adb shell run-as "$package_id" sh -c 'if [ -d files/emu-out ]; then find files/emu-out -maxdepth 2 -type f -printf "%p %s bytes\n"; else echo "files/emu-out missing"; fi' > emu-capture-files.txt 2> emu-capture-files.stderr
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
trap capture_emulator_outputs EXIT

require_arm64_abi() {
  local abis
  abis=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')
  printf 'cpu abilist: %s\n' "$abis"
  case "$abis" in
    *arm64-v8a*) echo 'arm64-v8a supported (native or translated)' ;;
    *) echo 'FATAL: no arm64-v8a on this image (no ARM translation) - re-dispatch with arch=arm64-v8a' >&2; return 2 ;;
  esac
}
require_arm64_abi

if [ "$mode" = import ]; then
  ./gradlew :app:installDebug :app:installDebugAndroidTest --no-daemon
  adb push emu-in-dl/emu-out/dcbot-migration.dcmig /data/local/tmp/dcbot-migration.dcmig
  adb push emu-in-dl/emu-out/code.txt /data/local/tmp/code.txt
  adb shell run-as com.vanipy.intodo sh -c 'mkdir -p files/emu-in && cp /data/local/tmp/dcbot-migration.dcmig /data/local/tmp/code.txt files/emu-in/'
  set +e
  adb shell am instrument -w -e class "$test_class" com.vanipy.intodo.test/androidx.test.runner.AndroidJUnitRunner
  test_status=$?
else
  set +e
  ./gradlew :app:connectedDebugAndroidTest --no-daemon "-Pandroid.testInstrumentationRunnerArguments.class=$test_class"
  test_status=$?
fi
set -e
exit "$test_status"
