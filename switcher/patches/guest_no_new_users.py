#!/usr/bin/env python3
"""Block guest secondary user and profile creation (UserManagerService)."""
import os
import re
import sys

HEADER_RE = re.compile(
    r"^\.method (?:private |public |protected |final )*"
    r"(createUserInternalUncheckedNoTracing|createUserEvenWhenDisallowed|canAddMoreUsers|canAddMoreManagedProfiles)"
    r"\([^)]*\)([ZL]|L[^;]+;)\s*$"
)


def main(root):
    changed = set()
    for dirpath, _, files in os.walk(root):
        for name in files:
            if name != "UserManagerService.smali":
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            lines = open(path, encoding="utf-8").read().split("\n")
            dirty = False
            for i, line in enumerate(lines):
                m = HEADER_RE.match(line.strip())
                if m:
                    ret = m.group(2)
                    j = i + 1
                    while j < len(lines) and (not lines[j].strip() or lines[j].lstrip().startswith((".locals", ".param", ".end param"))):
                        j += 1
                    ret_insn = "return v0" if ret == "Z" else "return-object v0"
                    lines.insert(j, f"    # OneOS: guest runs single-user (User 0) only\n    const/4 v0, 0x0\n    {ret_insn}")
                    dirty = True
            if dirty:
                open(path, "w", encoding="utf-8").write("\n".join(lines))
                changed.add(rel.split(os.sep)[0])
    for d in sorted(changed):
        print(d)


if __name__ == "__main__":
    main(sys.argv[1])
