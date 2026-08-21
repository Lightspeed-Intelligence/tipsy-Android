#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: ./scripts/bootstrap-android.sh [--skip-install] [--skip-check]
                                      [--allow-submodule-mismatch]

Initializes the Android shell for the current machine:
  - initializes tipsy-app
  - verifies tipsy-app matches the root repository pin
  - installs its locked npm dependencies
  - creates the repository node_modules symlink
  - records a stable absolute Node executable in local.properties
  - verifies Gradle while PATH deliberately cannot resolve node
EOF
}

skip_install=false
skip_check=false
allow_submodule_mismatch=false
while (($# > 0)); do
  case "$1" in
    --skip-install)
      skip_install=true
      ;;
    --skip-check)
      skip_check=true
      ;;
    --allow-submodule-mismatch)
      allow_submodule_mismatch=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$repo_root"

if [[ ! -f tipsy-app/package.json ]]; then
  echo "Initializing tipsy-app submodule..."
  git submodule update --init tipsy-app
fi

expected_tipsy_app_sha="$(git rev-parse HEAD:tipsy-app)"
actual_tipsy_app_sha="$(git -C tipsy-app rev-parse HEAD)"
if [[ "$expected_tipsy_app_sha" != "$actual_tipsy_app_sha" && "$allow_submodule_mismatch" == false ]]; then
  cat >&2 <<EOF
tipsy-app does not match the commit pinned by this Android checkout.
  expected: $expected_tipsy_app_sha
  actual:   $actual_tipsy_app_sha

Preserve any tipsy-app work in progress, then run:
  git submodule update --init tipsy-app

If the different commit is intentional, rerun with --allow-submodule-mismatch.
EOF
  exit 1
fi

node_candidate="${TIPSY_NODE_EXECUTABLE:-}"
if [[ -z "$node_candidate" ]]; then
  node_candidate="$(command -v node 2>/dev/null || true)"
fi

if [[ -z "$node_candidate" || ! -x "$node_candidate" ]]; then
  cat >&2 <<'EOF'
Node was not found. Install the version used by tipsy-app, make it available in
this terminal, or set TIPSY_NODE_EXECUTABLE to an executable absolute path.
EOF
  exit 1
fi

# process.execPath resolves fnm's per-shell multishell link to its stable installed
# binary, so local.properties does not expire when that terminal exits.
node_executable="$("$node_candidate" --print process.execPath)"
if [[ "$node_executable" != /* || ! -x "$node_executable" ]]; then
  echo "Node did not report an executable absolute process.execPath: $node_executable" >&2
  exit 1
fi

npm_executable="$(dirname "$node_executable")/npm"

if [[ -e node_modules && ! -L node_modules ]]; then
  cat >&2 <<'EOF'
Refusing to replace repository-root node_modules because it is not a symlink.
Move it out of the repository, then rerun this script.
EOF
  exit 1
fi

if [[ "$skip_install" == false ]]; then
  if [[ ! -x "$npm_executable" ]]; then
    echo "npm was not found next to Node: $npm_executable" >&2
    exit 1
  fi
  echo "Installing locked tipsy-app dependencies with $npm_executable..."
  # Invoke npm through the resolved Node binary. npm's own shebang uses
  # `/usr/bin/env node`, which would reintroduce the PATH dependency.
  (cd tipsy-app && "$node_executable" "$npm_executable" ci)
fi

ln -sfn tipsy-app/node_modules node_modules
if [[ ! -f node_modules/react-native/package.json ]]; then
  echo "node_modules symlink is incomplete; tipsy-app dependencies are not installed." >&2
  exit 1
fi

local_properties="$repo_root/local.properties"
local_properties_tmp="$(mktemp "${TMPDIR:-/tmp}/tipsy-local-properties.XXXXXX")"
cleanup() {
  rm -f "$local_properties_tmp"
}
trap cleanup EXIT

if [[ -f "$local_properties" ]]; then
  awk '$0 !~ /^[[:space:]]*tipsy\.node\.executable[[:space:]]*[=:]/ { print }' \
    "$local_properties" > "$local_properties_tmp"
fi
printf 'tipsy.node.executable=%s\n' "$node_executable" >> "$local_properties_tmp"
mv "$local_properties_tmp" "$local_properties"
trap - EXIT

echo "Recorded tipsy.node.executable=$node_executable"

if [[ "$skip_check" == false ]]; then
  ./scripts/check-node-contract.sh --use-local-properties
fi

cat <<'EOF'

Android bootstrap complete. You can open the normal Android Studio application
and sync this repository; no launcher wrapper or GUI PATH customization is needed.
EOF
