#!/usr/bin/env python3
"""Ensure PersistentDataBlockService init task survives a failed FRP deactivation."""
import os
import sys


def main(root):
    for dirpath, _, files in os.walk(root):
        for name in files:
            if name.startswith("PersistentDataBlockService") and name.endswith(".smali"):
                rel = os.path.relpath(os.path.join(dirpath, name), root)
                print(rel.split(os.sep)[0])
                return


if __name__ == "__main__":
    main(sys.argv[1])
