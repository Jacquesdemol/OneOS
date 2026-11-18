#!/usr/bin/env python3
"""Ensure OplusDefaultPermissionGrantPolicy does not NPE when CUST_LEVEL_LIST is null."""
import os
import sys


def main(root):
    for dirpath, _, files in os.walk(root):
        for name in files:
            if "DefaultPermissionGrant" in name and name.endswith(".smali"):
                rel = os.path.relpath(os.path.join(dirpath, name), root)
                print(rel.split(os.sep)[0])
                return


if __name__ == "__main__":
    main(sys.argv[1])
