#!/usr/bin/env bash
# Fetches llama.cpp at the pinned tag into third_party/llama.cpp (used when git submodules are unavailable).
# Verifies the commit hash so the native build input is exactly the audited source.
set -euo pipefail
TAG="b11514"
COMMIT="de7fa0a3c6a2e1b4cd9f22eb8d6bf5b12dbdb63b"
DEST="$(cd "$(dirname "$0")/.." && pwd)/third_party/llama.cpp"

verify_commit() {
  local got
  got="$(git -C "$DEST" rev-parse HEAD)"
  if [ "$got" != "$COMMIT" ]; then
    echo "FAIL: llama.cpp commit $got != expected $COMMIT (tag $TAG)"
    exit 1
  fi
  echo "llama.cpp $TAG verified commit $COMMIT"
}

if [ -f "$DEST/CMakeLists.txt" ]; then
  echo "llama.cpp already present"
  if [ -d "$DEST/.git" ]; then
    verify_commit
  else
    echo "warning: sources present without .git; cannot verify $COMMIT"
  fi
else
  git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$DEST"
  verify_commit
  rm -rf "$DEST/.git"   # sources only: no VCS metadata in the build input
fi
