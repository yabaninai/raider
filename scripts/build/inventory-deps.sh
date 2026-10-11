#!/bin/sh
# Dependency inventory for RAI-003 acceptance (BUILD-01: clean compile, dependency trees).
# Exit codes: 0=success, 1=error, 2=compiler/JLine found in headless dist.
set -u
cd "$(dirname "$0")/../.."

mkdir -p artifacts/build

echo "=== BUILD-01: dependency inventory ===" > artifacts/build/manifest.txt
echo "Date: $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> artifacts/build/manifest.txt
echo "Command: $0 $*" >> artifacts/build/manifest.txt

# Test 1: Clean compile all skeleton modules
echo "Test 1: Clean compile all skeleton modules"
sbt --batch clean compile > artifacts/build/compile.log 2>&1
COMPILE_EXIT=$?
echo "COMPILE_EXIT:$COMPILE_EXIT" >> artifacts/build/manifest.txt
if [ $COMPILE_EXIT -ne 0 ]; then
    echo "FAIL: clean compile failed"
    exit 1
fi

# Test 2: Dependency trees for core and cli (no compiler/JLine in cli)
echo "Test 2: Dependency trees"
echo "=== raider-core dependencies ===" > artifacts/build/dep-core.txt
sbt --batch "show raiderCore/Compile/dependencyClasspath" >> artifacts/build/dep-core.txt 2>&1

echo "=== raider-cli dependencies ===" > artifacts/build/dep-cli.txt
sbt --batch "show raider-cli/Compile/dependencyClasspath" >> artifacts/build/dep-cli.txt 2>&1

# Test 3: Verify headless distribution rule (no compiler/JLine in cli)
echo "Test 3: Headless distribution rule (no compiler/JLine in cli)"
if grep -q "scala3-compiler" artifacts/build/dep-cli.txt; then
    echo "FAIL: scala3-compiler found in headless distribution"
    echo "HEADLESS_EXIT:1" >> artifacts/build/manifest.txt
    exit 2
fi

if grep -q "jline-terminal" artifacts/build/dep-cli.txt; then
    echo "FAIL: jline-terminal found in headless distribution"
    echo "HEADLESS_EXIT:1" >> artifacts/build/manifest.txt
    exit 2
fi

echo "PASS: headless distribution excludes compiler/JLine"
echo "HEADLESS_EXIT:0" >> artifacts/build/manifest.txt

echo "=== BUILD-01 SUCCESS ===" >> artifacts/build/manifest.txt
exit 0