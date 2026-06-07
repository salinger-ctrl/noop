#!/usr/bin/env bash
#
# anonymize-macos-app.sh — scrub the building machine's home path out of a release
# .app, then re-sign it ad-hoc.
#
# Why: Swift/clang bake source-file path literals (e.g. GRDB's `#file` defaults used in
# its precondition/error messages) into the compiled binary. On a release build these
# include the *builder's* home directory — i.e. your username. This strips that out so a
# distributed binary carries no identity. Run it on the Release app before zipping it up:
#
#     xcodebuild -scheme Strand -configuration Release -derivedDataPath build/dd \
#         -destination 'platform=macOS' CODE_SIGNING_ALLOWED=NO build
#     Tools/anonymize-macos-app.sh build/dd/Build/Products/Release/NOOP.app
#
# The replacement is the SAME byte length as the original path, so all Mach-O offsets stay
# valid; only the read-only string section changes. The script reads $HOME at runtime and
# contains no identifying information itself.
set -euo pipefail

APP="${1:?usage: $0 path/to/App.app}"
[ -d "$APP" ] || { echo "no such app bundle: $APP" >&2; exit 1; }

HOME_PATH="$HOME"                       # e.g. /Users/alice
REPL="/Users/builder"                   # generic, anonymous
# Pad or trim REPL to EXACTLY the length of $HOME so byte offsets are preserved.
while [ ${#REPL} -lt ${#HOME_PATH} ]; do REPL="${REPL}_"; done
REPL="${REPL:0:${#HOME_PATH}}"

python3 - "$APP" "$HOME_PATH" "$REPL" <<'PY'
import sys, os, glob
app, home, repl = sys.argv[1], sys.argv[2].encode(), sys.argv[3].encode()
assert len(home) == len(repl), "replacement length must match"
total = 0
for binp in glob.glob(os.path.join(app, "Contents/MacOS/*")):
    if not os.path.isfile(binp):
        continue
    data = open(binp, "rb").read()
    hits = data.count(home)
    if hits:
        open(binp, "wb").write(data.replace(home, repl))
        total += hits
print(f"scrubbed {total} occurrence(s) of the build home path")
PY

# --options runtime applies the Hardened Runtime (CS_RUNTIME), which blocks
# DYLD_INSERT_LIBRARIES dylib injection into the process. Safe: the app uses no JIT.
codesign --force --options runtime --sign - "$APP"
codesign --verify --verbose=1 "$APP"

residual=$(strings -a "$APP/Contents/MacOS/"* 2>/dev/null | grep -c "$HOME" || true)
echo "residual home-path hits: ${residual:-0}"
[ "${residual:-0}" -eq 0 ] && echo "✓ clean" || { echo "✗ residual paths remain" >&2; exit 1; }
