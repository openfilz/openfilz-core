#!/usr/bin/env bash
# Checks the Java runtime inside a container image against the lowest patched build of its
# release line, so a known JDK flaw cannot ship unnoticed.
#
#   check-java-runtime.sh <image>
#
# Trivy reports OS packages and application libraries, but it does not see a JDK unpacked
# under /opt (Eclipse Temurin images): that is why this check exists next to the Trivy scan.
#
# Result:
#   runtime >= floor                          -> pass
#   runtime <  floor, mitigation flag present -> pass with a warning (waiting for a fixed JDK)
#   runtime <  floor, no mitigation           -> fail
#
# Raise a floor (and add the matching mitigation flag) whenever a JDK advisory lands.
set -euo pipefail

image="$1"

# Lowest build of each release line that fixes every advisory tracked here.
# CVE-2026-114938 ("Java4Shell", Base64 intrinsic heap overflow): fixed in 17.0.21.1,
# 21.0.13.1, 25.0.5.1.
declare -A FLOOR=( [17]=17.0.21.1 [21]=21.0.13.1 [25]=25.0.5.1 )
MITIGATION='-XX:-UseBASE64Intrinsics'

props=$(docker run --rm --entrypoint java "$image" -XshowSettings:properties -version 2>&1 || true)
runtime=$(printf '%s\n' "$props" | awk -F'= ' '/java.runtime.version/ {print $2; exit}')
if [ -z "$runtime" ]; then
  echo "::error::$image: no java on PATH, cannot read the runtime version"
  exit 1
fi
version=${runtime%%+*}          # 25.0.1+8-LTS -> 25.0.1
version=${version%%-*}
major=${version%%.*}
floor=${FLOOR[$major]:-}

echo "$image: Java runtime $runtime"
if [ -z "$floor" ]; then
  echo "::warning::$image: no floor known for Java $major, add one to check-java-runtime.sh"
  exit 0
fi
if [ "$(printf '%s\n%s\n' "$floor" "$version" | sort -V | head -1)" = "$floor" ]; then
  echo "$image: Java $version is at or above the floor $floor"
  exit 0
fi

# Below the floor: the image must carry the mitigation flag, either in its entrypoint/command
# (Jib jvmFlags, Dockerfile ENTRYPOINT) or in the entrypoint script it starts.
config=$(docker inspect --format '{{json .Config.Entrypoint}} {{json .Config.Cmd}} {{json .Config.Env}}' "$image")
if printf '%s' "$config" | grep -q -- "$MITIGATION"; then
  echo "::warning::$image: Java $version is below the floor $floor, mitigated by $MITIGATION in the image config"
  exit 0
fi
script=$(docker inspect --format '{{if .Config.Entrypoint}}{{index .Config.Entrypoint 0}}{{end}}' "$image")
case "$script" in
  /*.sh)
    if docker run --rm --entrypoint cat "$image" "$script" | grep -q -- "$MITIGATION"; then
      echo "::warning::$image: Java $version is below the floor $floor, mitigated by $MITIGATION in $script"
      exit 0
    fi
    ;;
esac

echo "::error::$image: Java $version is below the floor $floor and $MITIGATION is not set"
exit 1
