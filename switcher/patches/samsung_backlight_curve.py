#!/usr/bin/env python3
"""Inject OneOSPanelBacklight helper and wire Samsung display brightness curve."""
import os
import shutil
import sys


def main(args):
    root = args[0]
    helper_smali = args[1] if len(args) > 1 else None
    for dirpath, _, files in os.walk(root):
        for name in files:
            if name.endswith(".smali"):
                rel = os.path.relpath(os.path.join(dirpath, name), root)
                top = rel.split(os.sep)[0]
                if helper_smali and os.path.isfile(helper_smali):
                    dst = os.path.join(root, top, "com", "android", "server", "display", "OneOSPanelBacklight.smali")
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    shutil.copyfile(helper_smali, dst)
                print(top)
                return


if __name__ == "__main__":
    main(sys.argv[1:])
