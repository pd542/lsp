#!/usr/bin/env python3
"""Inject java_init.list (FPA V2 module entry list) at APK root.

FPA's ModuleLoaderV2 looks for a file literally named `java_init.list`
opened from the module APK (not under assets/). This script rebuilds the
APK adding that entry at zip root. The CI then re-signs with apksigner.
Usage: inject_java_init_list.py <apk> <entry-class>
"""
import sys
import zipfile

apk = sys.argv[1]
entry_class = sys.argv[2].strip()
tmp = apk + ".injected"

with zipfile.ZipFile(apk, "r") as zin, \
     zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        # preserve existing entry order; keep signature block files as-is
        zout.writestr(item, data)
    zout.writestr("java_init.list", entry_class + "\n")

import os
os.replace(tmp, apk)
print("injected java_init.list ->", apk)
with zipfile.ZipFile(apk) as z:
    for n in z.namelist():
        if "java_init" in n or "xposed" in n:
            print("  contains:", n)