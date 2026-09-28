#!/bin/sh
# InfraDesk self-update (macOS). Started by the app right before it quits:
#   update-swap.sh <app pid> <installed .app> <new .app>
# Waits for the app to exit, swaps the bundles (restoring the old one if anything fails),
# then starts whichever is in place. INFRADESK_SWAP_NO_LAUNCH=1 skips the start (tests).
cd /tmp || cd /
pid="$1"
app="$2"
new="$3"
backup="$app.previous"

i=0
while kill -0 "$pid" 2>/dev/null; do
  i=$((i + 1))
  if [ "$i" -gt 240 ]; then
    echo "$(date '+%F %T') app (pid $pid) did not exit; update skipped"
    exit 1
  fi
  sleep 0.25
done

echo "$(date '+%F %T') installing $new -> $app"
rm -rf "$backup"
if mv "$app" "$backup"; then
  if mv "$new" "$app"; then
    xattr -dr com.apple.quarantine "$app" 2>/dev/null
    rm -rf "$backup"
    echo "$(date '+%F %T') updated"
  else
    echo "$(date '+%F %T') could not move the new app in; restoring the old one"
    mv "$backup" "$app"
  fi
else
  echo "$(date '+%F %T') could not move the old app aside; update skipped"
fi
rm -rf "$(dirname "$new")"

if [ "$INFRADESK_SWAP_NO_LAUNCH" != "1" ]; then
  open "$app"
fi
