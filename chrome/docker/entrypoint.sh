#!/bin/sh
# Starts Xvfb unless a display is given, then fingertips-chrome listening on the container's port.
set -eu

config="${XDG_CONFIG_HOME:-$HOME/.config}/fingertips-chrome"
mkdir -p "$config"
# The published port is fixed, so pairing.json from `token create` matches it.
if [ ! -f "$config/settings.json" ]; then
    printf '{"port":%s}\n' "$FINGERTIPS_PORT" >"$config/settings.json"
fi

jar=/opt/fingertips-chrome/fingertipsmcp-chrome.jar
case "${1:-}" in
token | help | --help | -h | version | --version) exec java -jar "$jar" "$@" ;;
esac

if [ -z "${DISPLAY:-}" ]; then
    export DISPLAY=:99
    Xvfb "$DISPLAY" -screen 0 "$FINGERTIPS_SCREEN" -nolisten tcp >/dev/null 2>&1 &
    xvfb=$!
    # Xvfb has no readiness signal but its socket, so poll for that for up to 5 s.
    i=0
    while [ ! -e /tmp/.X11-unix/X99 ]; do
        if ! kill -0 "$xvfb" 2>/dev/null; then
            echo "fingertips-chrome: Xvfb exited before it started" >&2
            exit 1
        fi
        if [ "$i" -ge 50 ]; then
            echo "fingertips-chrome: Xvfb did not open $DISPLAY within 5 s" >&2
            exit 1
        fi
        sleep 0.1
        i=$((i + 1))
    done
fi

# 0.0.0.0 inside the container; publish it on the host's loopback only:
#   docker run -p 127.0.0.1:47100:47100 ...
exec java -jar "$jar" --host 0.0.0.0 "$@"
