#!/system/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ "$1" = "-c" ]; then
    shift
    exec "$DIR/bash" -c "$@"
else
    exec "$DIR/bash" "$@"
fi
