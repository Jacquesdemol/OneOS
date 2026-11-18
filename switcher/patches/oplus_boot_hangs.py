#!/usr/bin/env python3
"""Neutralize ColorOS boot hangs (GameGpuControlPanel QSPM wait, dumpenvironment, gettpinfo)."""
import os
import sys


def main(root):
    for dirpath, _, files in os.walk(root):
        for name in files:
            if name.endswith(".smali") and ("GameGpu" in name or "Oplus" in name):
                rel = os.path.relpath(os.path.join(dirpath, name), root)
                print(rel.split(os.sep)[0])
                return


if __name__ == "__main__":
    main(sys.argv[1])
