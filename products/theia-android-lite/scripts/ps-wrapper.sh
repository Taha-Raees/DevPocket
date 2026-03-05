#!/system/bin/sh
if [ "$1" = "ax" ]; then
  shift
  exec /system/bin/ps -A "$@"
fi
exec /system/bin/ps "$@"
