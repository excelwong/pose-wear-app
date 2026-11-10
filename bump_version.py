#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""每次重新打包 APK 前运行：versionCode +1，并把 versionName 的修订号 +1。

    python bump_version.py              # 1.0.1 -> 1.0.2
    python bump_version.py --minor      # 1.0.2 -> 1.1.0
    python bump_version.py --name 2.0.0

版本号唯一来源是项目根目录的 version.properties，app/build.gradle 从该文件读取；
设置页底部会显示当前 APK 的 versionName / versionCode，装机后可据此核对。
"""
import argparse
import io
import os

ROOT = os.path.dirname(os.path.abspath(__file__))
PATH = os.path.join(ROOT, "version.properties")


def read():
    data = {}
    for line in io.open(PATH, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, val = line.split("=", 1)
            data[key.strip()] = val.strip()
    return data


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minor", action="store_true", help="递增次版本号而非修订号")
    ap.add_argument("--name", help="直接指定 versionName")
    args = ap.parse_args()

    data = read()
    code = int(data["versionCode"]) + 1
    if args.name:
        name = args.name
    else:
        parts = [int(x) for x in data["versionName"].split(".")]
        while len(parts) < 3:
            parts.append(0)
        if args.minor:
            parts = [parts[0], parts[1] + 1, 0]
        else:
            parts[2] += 1
        name = ".".join(str(x) for x in parts)

    io.open(PATH, "w", encoding="utf-8", newline="\n").write(
        "# APK 版本唯一来源。每次重新打包前运行 python bump_version.py 递增，别手改 app/build.gradle。\n"
        "versionCode=%d\nversionName=%s\n" % (code, name)
    )
    print("versionCode=%d  versionName=%s" % (code, name))


if __name__ == "__main__":
    main()
