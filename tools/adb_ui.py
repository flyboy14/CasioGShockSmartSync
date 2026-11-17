#!/usr/bin/env python3
"""Drive the app over adb by visible text (uiautomator dump).

usage:
  adb_ui.py list                 # every element with text / content-desc and its bounds
  adb_ui.py tap "<text>" [n]     # tap the n-th (default 0) element whose text equals <text>

Notes (see docs/DW-H5600-DEVELOPMENT.md, "Testing on the phone"):
- the app opens on the "Ride to unlock" cover: `adb shell input swipe 534 1236 1060 1236 600`
- a swipe up on a tab whose content is shorter than the screen locks the app again
- tab bar (1080x2400): y=2232, x = 80 Time, 263 Health, 445 Sport, 630 Alarms, 814 Custom, 998 Settings
"""
import os, re, subprocess, sys, tempfile

def dump():
    tmp = os.path.join(tempfile.gettempdir(), "adb_ui.xml")
    subprocess.run("adb shell uiautomator dump /sdcard/ui.xml", shell=True, capture_output=True)
    subprocess.run(["adb", "pull", "/sdcard/ui.xml", tmp], capture_output=True)
    out = []
    for n in re.findall(r"<node [^>]*>", open(tmp, encoding="utf-8").read()):
        t = re.search(r' text="([^"]*)"', n).group(1) or re.search(r'content-desc="([^"]*)"', n).group(1)
        b = tuple(map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()))
        out.append((t, b))
    return out

if __name__ == "__main__":
    if len(sys.argv) < 2: sys.exit(__doc__)
    if sys.argv[1] == "list":
        for t, b in dump():
            if t: print(t[:80], b)
    elif sys.argv[1] == "tap":
        hits = [b for t, b in dump() if t == sys.argv[2]]
        if not hits: sys.exit(f"not found: {sys.argv[2]}")
        b = hits[int(sys.argv[3]) if len(sys.argv) > 3 else 0]
        x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
        subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
        print("tapped", sys.argv[2], x, y)
    else:
        sys.exit(__doc__)
