#!/bin/sh
# Renders the README images into screenshots/. Put fresh app screenshots in design/readme/raw/ first:
#   01-auto.png 02-manual.png 03-focus.png 04-menu.png 05-timer.png  (1260×2800 phone screenshots)
set -e
cd "$(dirname "$0")"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
OUT=../../screenshots
render() { "$CHROME" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
  --virtual-time-budget=4000 --window-size="$2" --screenshot="$OUT/$3" "file://$PWD/$1" 2>/dev/null; }
render banner.html 1600,900 banner.png
render screens.html 1800,900 screens.png
render frames.html 1600,820 frames.png
echo "rendered banner.png, screens.png, frames.png into screenshots/"
