#!/usr/bin/env python3
"""Run the native suites sequentially on an explicitly selected emulator."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

SUITES = {
    "data": "com.valerogarte.comicsreader.nativeapp.data.NativeDataInstrumentedTest",
    "reader": "com.valerogarte.comicsreader.nativeapp.reader.NativeReaderInstrumentedTest",
    "ui": "com.valerogarte.comicsreader.NativeUiInstrumentedTest",
    "workflow": "com.valerogarte.comicsreader.nativeapp.NativeWorkflowInstrumentedTest",
    "activity": "com.valerogarte.comicsreader.NativeMainActivityInstrumentedTest",
}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--suites", nargs="+", choices=SUITES, default=list(SUITES))
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only explicitly selected emulator serials are allowed")
    adb = [args.adb, "-s", args.serial]
    sdk = subprocess.check_output(adb + ["shell", "getprop", "ro.build.version.sdk"], text=True).strip()
    args.output.mkdir(parents=True, exist_ok=True)
    apk = subprocess.check_output(adb + ["shell", "pm", "path", "com.valerogarte.comicsreader"], text=True).strip().splitlines()[0].removeprefix("package:")
    # Older Android images have no sha256sum executable; hash the installed bytes on the host.
    apk_hash = hashlib.sha256(subprocess.check_output(adb + ["exec-out", "cat", apk])).hexdigest()
    environment = {"serial": args.serial, "api": int(sdk), "apk_sha256": apk_hash,
                   "fingerprint": subprocess.check_output(adb + ["shell", "getprop", "ro.build.fingerprint"], text=True).strip()}
    environment_path = args.output / f"environment-api{sdk}.json"
    results_path = args.output / f"results-api{sdk}.json"
    prior_environment = json.loads(environment_path.read_text()) if environment_path.exists() else None
    results = json.loads(results_path.read_text()) if prior_environment == environment and results_path.exists() else []
    environment_path.write_text(json.dumps(environment, indent=2) + "\n")
    for name in args.suites:
        command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class", SUITES[name],
                         "-e", "sftpQaPort", "22991", "-e", "sftpQaControlPort", "22992"]
        if name == "activity":
            if int(sdk) < 29:
                print("activity: omitted; real MediaStore smoke requires API 29+", flush=True)
                continue
            command += ["-e", "qaAllowUiSmoke", "true"]
        command += ["com.valerogarte.comicsreader.test/androidx.test.runner.AndroidJUnitRunner"]
        path = args.output / f"{name}-api{sdk}.txt"
        with path.open("w") as log:
            completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=900)
        output = path.read_text()
        match = re.search(r"OK \((\d+) tests?\)", output)
        passed = completed.returncode == 0 and match is not None and "FAILURES!!!" not in output
        result = {"suite": name, "api": int(sdk), "passed": passed,
                  "tests": int(match.group(1)) if match else None, "log": path.name}
        results = [entry for entry in results if entry["suite"] != name] + [result]
        results_path.write_text(json.dumps(results, indent=2) + "\n")
        print(f"{name}: {'PASS' if passed else 'FAIL'} ({result['tests']} tests), {path}", flush=True)
        if not passed:
            print(output[-8000:], flush=True)
            return 1
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
