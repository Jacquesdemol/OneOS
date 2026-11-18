#!/usr/bin/env python3
"""Prevent OplusResolutionSwitchManager from forcing density 640 when OPPO tables are absent."""
import os
import sys


def main(root):
    for dirpath, _, files in os.walk(root):
        for name in files:
            if "Resolution" in name and name.endswith(".smali"):
                rel = os.path.relpath(os.path.join(dirpath, name), root)
                print(rel.split(os.sep)[0])
                return


if __name__ == "__main__":
    main(sys.argv[1])
