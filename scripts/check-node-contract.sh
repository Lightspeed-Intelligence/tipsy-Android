#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$repo_root"

use_local_properties=false
while (($# > 0)); do
  case "$1" in
    --use-local-properties)
      use_local_properties=true
      ;;
    -h|--help)
      echo "Usage: ./scripts/check-node-contract.sh [--use-local-properties]"
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
  shift
done

if [[ "$use_local_properties" == true ]]; then
  node_candidate="$(awk '
    /^[[:space:]]*tipsy\.node\.executable[[:space:]]*[=:]/ {
      value = $0
      sub(/^[[:space:]]*tipsy\.node\.executable[[:space:]]*[=:][[:space:]]*/, "", value)
      print value
      exit
    }
  ' local.properties 2>/dev/null || true)"
else
  node_candidate="${TIPSY_NODE_EXECUTABLE:-}"
  if [[ -z "$node_candidate" ]]; then
    node_candidate="$(command -v node 2>/dev/null || true)"
  fi
fi
if [[ -z "$node_candidate" || ! -x "$node_candidate" ]]; then
  if [[ "$use_local_properties" == true ]]; then
    echo "local.properties must contain an executable tipsy.node.executable path." >&2
  else
    echo "Set TIPSY_NODE_EXECUTABLE to an executable Node path." >&2
  fi
  exit 1
fi

node_executable="$("$node_candidate" --print process.execPath)"
if [[ "$node_executable" != /* || ! -x "$node_executable" ]]; then
  echo "Invalid Node process.execPath: $node_executable" >&2
  exit 1
fi

java_home="${JAVA_HOME:-}"
if [[ -z "$java_home" && -x /usr/libexec/java_home ]]; then
  java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home)"
fi
if [[ -z "$java_home" || ! -x "$java_home/bin/java" ]]; then
  echo "JAVA_HOME must point to a JDK with an executable bin/java." >&2
  exit 1
fi

if [[ ! -f node_modules/react-native/package.json ]]; then
  echo "Run ./scripts/bootstrap-android.sh before checking the Node contract." >&2
  exit 1
fi

stripped_bin="$(mktemp -d "${TMPDIR:-/tmp}/tipsy-node-contract.XXXXXX")"
cleanup() {
  rm -rf "$stripped_bin"
}
trap cleanup EXIT

# Preserve normal system tools but intentionally omit every executable named
# `node`. This also works on Linux hosts where apt installs Node in /usr/bin.
for system_bin in /usr/bin /bin /usr/sbin /sbin; do
  [[ -d "$system_bin" ]] || continue
  for system_tool in "$system_bin"/*; do
    [[ -x "$system_tool" ]] || continue
    tool_name="${system_tool##*/}"
    [[ "$tool_name" == node ]] && continue
    [[ -e "$stripped_bin/$tool_name" ]] || ln -s "$system_tool" "$stripped_bin/$tool_name"
  done
done
stripped_path="$stripped_bin"

if PATH="$stripped_path" command -v node >/dev/null 2>&1; then
  echo "Internal error: stripped PATH still resolves node." >&2
  exit 1
fi

echo "Checking Gradle with PATH=$stripped_path"
echo "Using absolute Node: $node_executable"

export PATH="$stripped_path"
export JAVA_HOME="$java_home"
if [[ "$use_local_properties" == true ]]; then
  unset TIPSY_NODE_EXECUTABLE
else
  export TIPSY_NODE_EXECUTABLE="$node_executable"
fi

./gradlew --no-daemon --rerun-tasks --stacktrace \
    projects \
    :app:generateAutolinkingPackageList \
    :app:generateCodegenArtifactsFromSchema \
    :expo:generatePackagesList \
    :expo-constants:createExpoConfig \
    :react-native-reanimated:preBuild \
    :react-native-worklets:preBuild

echo "Node contract passed: Gradle did not need node on PATH."
