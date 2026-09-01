#!/usr/bin/env python3
"""Strip editor tool-output lines that leak into source files.

Some editing paths append their own pager footer into the file they just wrote,
which lands in the tree as syntactically invalid source. Removes those lines and
exits non-zero when anything was found, so it can also be used as a CI guard.
"""

from __future__ import annotations

import os
import re
import sys

ARTIFACT = re.compile(
    r"^[ \t]*\[(?:Showing lines \d+-\d+ of \d+\. Use :\d+ to continue"
    r"|You have received this identical output.*"
    r"|\u2026\d+ln elided.*)\][ \t]*$",
    re.MULTILINE,
)

SUFFIXES = (
    ".kt", ".kts", ".swift", ".rs", ".toml", ".md", ".py", ".yml", ".yaml",
    ".plist", ".pbxproj", ".xcconfig", ".pro", ".entitlements", ".json", ".sh",
)

SKIP_DIRS = {".git", "build", "target", ".gradle", ".idea", "terminals", "DerivedData"}


def main(root: str = ".") -> int:
    hits: list[str] = []
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            if not name.endswith(SUFFIXES):
                continue
            path = os.path.join(base, name)
            try:
                original = open(path, encoding="utf-8").read()
            except (UnicodeDecodeError, OSError):
                continue
            cleaned = ARTIFACT.sub("", original)
            # The footer is written without a trailing newline, so it can also
            # be glued to the end of the file rather than sitting on its own.
            cleaned = re.sub(r"\n*\[(?:Showing lines|You have received)[^\n]*\]\s*\Z", "\n", cleaned)
            if cleaned != original:
                open(path, "w", encoding="utf-8").write(cleaned)
                hits.append(path)
    for path in hits:
        print(f"stripped tool artifact: {path}")
    return 1 if hits else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
