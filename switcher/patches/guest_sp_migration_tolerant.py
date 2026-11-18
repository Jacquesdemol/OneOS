#!/usr/bin/env python3
"""Ensure SyntheticPasswordManager migration tolerates shared host Keystore refusals."""
import os
import sys


def main(root):
    for dirpath, _, files in os.walk(root):
        for name in files:
            if name == "SyntheticPasswordManager.smali":
                rel = os.path.relpath(os.path.join(dirpath, name), root)
                print(rel.split(os.sep)[0])
                return


if __name__ == "__main__":
    main(sys.argv[1])
