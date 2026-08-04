#!/usr/bin/env python3
"""生成 ApkDiffPatch 增量补丁（单跳直达）并写进 version.json。

ApkDiffPatch（sisong/ApkDiffPatch，MIT）服务端生成：
- 对历史 release 的「已发布签名 APK」生成直达补丁（ZipDiff）
- 回打自验（ZipPatch + 逐字节 cmp），不一致丢弃
- 只对「已装包内含 libapkpatch.so」的 from-version 生成（jbsdiff-only 旧客户端
  打不了 ZiPat1 补丁，跳过 → 自动全量下载，保证「检查更新」按钮始终可用）
- 补丁不小于发布包一半 → 丢弃（省流量无意义）
- 单跳直达，不构建多跳链（ApkDiffPatch 补丁足够小）
- 任何失败只丢对应条目，绝不阻断发布（客户端对缺失条目回退全量）

依赖（环境变量）：
  APKDIFF_BIN  含 ZipDiff/ZipPatch/ApkNormalized 的目录（默认 "."）
"""

import filecmp
import glob
import hashlib
import json
import os
import subprocess
import sys
import zipfile

REPO = os.environ.get("GITHUB_REPOSITORY", "XenoAmess/vivhite-tracker")
APKDIFF_BIN = os.environ.get("APKDIFF_BIN", ".")
ZIPDIFF = os.path.join(APKDIFF_BIN, "ZipDiff")
ZIPPATCH = os.path.join(APKDIFF_BIN, "ZipPatch")
MAX_KEEP = 8          # 只对最近 N 个历史 release 生成
MIN_PATCH_RATIO = 0.5 # 补丁不小于全量一半则丢弃


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(cmd, check=True):
    return subprocess.run(cmd, capture_output=True, text=True, check=check)


def git(*args):
    return run(["git", *args]).stdout.strip()


def gh_download(release_tag, pattern, dest):
    os.makedirs(dest, exist_ok=True)
    r = run(["gh", "release", "download", release_tag, "-p", pattern,
             "--dir", dest, "--clobber", "-R", REPO], check=False)
    return r.returncode == 0


def apk_has_native_lib(apk_path):
    """判断该版本 APK 是否内置 ApkDiffPatch 客户端（libapkpatch.so）。

    只有带它的客户端才能打 ZiPat1 补丁；否则（历史 jbsdiff-only 版本）跳过，
    让客户端走全量下载，避免一次注定失败的补丁下载。
    """
    try:
        with zipfile.ZipFile(apk_path) as zf:
            return any(n.endswith("libapkpatch.so") for n in zf.namelist())
    except Exception:
        return False


def main():
    new_apk = sorted(glob.glob("vivhite-tracker-*.apk"))[0]
    cur_tag = git("describe", "--tags", "--abbrev=0", "--match", "v*")
    # versionCode 单一来源：version.json（由 workflow 从 build.gradle 推导的
    # version-info.properties 写入），与客户端比对一致
    with open("version.json", encoding="utf-8") as f:
        vj = json.load(f)
    new_vc = int(vj["versionCode"])
    new_sha = sha256(new_apk)
    new_size = os.path.getsize(new_apk)

    if not os.path.exists(ZIPDIFF) or not os.path.exists(ZIPPATCH):
        print("ZipDiff/ZipPatch 缺失（APKDIFF_BIN 未就绪），仅回填 apkSha256/apkSize，跳过补丁生成")
        with open("version.json", encoding="utf-8") as f:
            vj = json.load(f)
        vj["apkSha256"] = new_sha
        vj["apkSize"] = new_size
        with open("version.json", "w", encoding="utf-8") as f:
            json.dump(vj, f, ensure_ascii=False)
        return 0

    # 历史 release（tag）按 versionCode 升序，排除当前，只留最近 MAX_KEEP 个
    history = []
    for tag in git("tag", "-l", "v*").splitlines():
        if tag == cur_tag:
            continue
        history.append((int(git("rev-list", "--count", tag)), tag))
    history.sort()
    history = history[-MAX_KEEP:]
    print(f"current: {cur_tag} vc={new_vc}; candidates={[t for _, t in history]}")

    patches = {}
    for vc, tag in history:
        d = f"old/{vc}"
        if not gh_download(tag, "vivhite-tracker-*.apk", d):
            print(f"skip {tag}: old apk unavailable")
            continue
        old_apks = glob.glob(f"{d}/vivhite-tracker-*.apk")
        if not old_apks:
            continue
        old_apk = old_apks[0]
        old_sha = sha256(old_apk)
        if not apk_has_native_lib(old_apk):
            print(f"skip {tag}({vc}): 旧客户端无 libapkpatch.so（jbsdiff-only），走全量")
            continue

        pf = f"patch-{vc}-to-{new_vc}.patch"
        try:
            run([ZIPDIFF, old_apk, new_apk, pf])
            run([ZIPPATCH, old_apk, pf, "verify.apk"])
            if not filecmp.cmp(new_apk, "verify.apk", shallow=False):
                print(f"patch {tag}({vc}) -> {new_vc}: VERIFY FAILED, dropped")
            elif os.path.getsize(pf) >= new_size * MIN_PATCH_RATIO:
                print(f"patch {tag}({vc}) -> {new_vc}: >= {int(MIN_PATCH_RATIO*100)}% of full apk, dropped")
            else:
                patches[vc] = {
                    "file": pf,
                    "size": os.path.getsize(pf),
                    "patchSha256": sha256(pf),
                }
                print(f"patch {tag}({vc}) -> {new_vc}: {patches[vc]['size']} bytes OK")
        except Exception as e:
            print(f"patch {tag}({vc}) -> {new_vc}: error {e}, dropped")
        finally:
            if os.path.exists("verify.apk"):
                os.remove("verify.apk")
            if pf not in [p["file"] for p in patches.values()] and os.path.exists(pf):
                os.remove(pf)

    # 单跳直达链：from 版本 → 当前版本
    chains = {}
    for vc, p in patches.items():
        chains[str(vc)] = {
            "fromApkSha256": sha256(glob.glob(f"old/{vc}/vivhite-tracker-*.apk")[0]),
            "totalSize": p["size"],
            "hops": [
                {
                    "toVersionCode": new_vc,
                    "url": f"https://github.com/{REPO}/releases/download/{cur_tag}/{p['file']}",
                    "size": p["size"],
                    "patchSha256": p["patchSha256"],
                    "resultSha256": new_sha,
                }
            ],
        }
        print(f"chain {vc} -> {new_vc}: 1 hop, {p['size']} bytes")

    # 合并写回 version.json（apkSha256/apkSize 必须指向前述发布包；vj 已在 main 顶部加载）
    vj["apkSha256"] = new_sha
    vj["apkSize"] = new_size
    vj["patches"] = {str(k): v for k, v in patches.items()}
    vj["chains"] = chains
    with open("version.json", "w", encoding="utf-8") as f:
        json.dump(vj, f, ensure_ascii=False)
    print(f"version.json: {len(patches)} direct patch(es), {len(chains)} chain(s)")


if __name__ == "__main__":
    sys.exit(main())
