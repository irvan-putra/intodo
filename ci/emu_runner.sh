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
  local log_status pull_status extract_status=0
  trap - EXIT
  set +e
  adb logcat -d > "$log_file" 2>&1
  log_status=$?
  adb exec-out run-as "$package_id" tar cf - -C files emu-out > "$tar_file" 2>"$tar_file.stderr"
  pull_status=$?
  if [ "$pull_status" -eq 0 ]; then
    tar xf "$tar_file"
    extract_status=$?
    rm -f "$tar_file"
  else
    printf 'Could not pull app output from %s:\n' "$package_id" >&2
    cat "$tar_file.stderr" >&2
  fi
  rm -f "$tar_file.stderr"
  if [ "$original_status" -eq 0 ]; then
    if [ "$log_status" -ne 0 ]; then
      original_status=$log_status
    elif [ "$pull_status" -ne 0 ]; then
      original_status=$pull_status
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
