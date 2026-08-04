#!/usr/bin/env python3
"""beta 通道增量更新（ApkDiffPatch）：维护 beta-archive 滚动 release（最近 8 个内测包 + 补丁），
生成单跳直达补丁，把元数据写进 version.json（随后部署到 Pages）。

- beta-archive release 常驻，资产命名：
    beta-<versionCode>.apk                存档内测包（归一化+apksigner34 重签的发布物）
    patch-beta-<from>-to-<to>.patch       补丁（ApkDiffPatch）
    beta-history.json                     滚动元数据（供下次构建引用）
- 回退窗口 1,2,4（存档上限 8 个包）；补丁回打自验，不小于全量一半直接丢弃
- 只对「已装包内含 libapkpatch.so」的 from-版本生成（jbsdiff-only 旧客户端自动全量）
- 单跳直达，不构建多跳链
- 任何失败只丢对应条目，绝不阻断 beta 发布

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
ARCHIVE_TAG = "beta-archive"
HISTORY_FILE = "beta-history.json"
APKDIFF_BIN = os.environ.get("APKDIFF_BIN", ".")
ZIPDIFF = os.path.join(APKDIFF_BIN, "ZipDiff")
ZIPPATCH = os.path.join(APKDIFF_BIN, "ZipPatch")
MAX_KEEP = 8          # 存档内测包上限
BACKOFF = (1, 2, 4)   # 指数回退窗口
MIN_PATCH_RATIO = 0.5 # 补丁不小于全量一半则丢弃


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run(cmd, check=True):
    return subprocess.run(cmd, capture_output=True, text=True, check=check)


def gh(*args, check=True):
    return run(["gh", *args, "-R", REPO], check=check)


def ensure_archive():
    r = gh("release", "view", ARCHIVE_TAG, check=False)
    if r.returncode != 0:
        # tag 必须钉在根 commit：若钉在近期 commit 上，git describe --tags 会
        # 把 beta-archive 当成最近 tag，版本号推导塌成 0.0.0+N（2026-08-02 实发）
        root = run(["git", "rev-list", "--max-parents=0", "HEAD"]).stdout.splitlines()[0]
        gh("release", "create", ARCHIVE_TAG, "--target", root, "--title", "beta-archive",
           "--notes", "内测包滚动存档（增量更新底包与补丁，由 CI 自动维护）")
        print("created beta-archive release")


def upload_asset(path):
    gh("release", "upload", ARCHIVE_TAG, path, "--clobber")
    print(f"uploaded {path}")


def download_asset(name, dest):
    os.makedirs(dest, exist_ok=True)
    r = gh("release", "download", ARCHIVE_TAG, "-p", name,
           "--dir", dest, "--clobber", check=False)
    return r.returncode == 0 and os.path.exists(os.path.join(dest, name))


def delete_asset(name):
    gh("release", "delete-asset", ARCHIVE_TAG, name, "--yes", check=False)
    print(f"pruned asset {name}")


def apk_has_native_lib(apk_path):
    try:
        with zipfile.ZipFile(apk_path) as zf:
            return any(n.endswith("libapkpatch.so") for n in zf.namelist())
    except Exception:
        return False


def main():
    # 当前构建产物（Prepare beta channel files 步骤已生成 = 归一化+重签的发布物）
    new_apk = "vivhite-tracker-beta.apk"
    with open("version.json", encoding="utf-8") as f:
        vj = json.load(f)
    new_vc = int(vj["versionCode"])
    new_sha = sha256(new_apk)
    new_size = os.path.getsize(new_apk)

    if not os.path.exists(ZIPDIFF) or not os.path.exists(ZIPPATCH):
        print("ZipDiff/ZipPatch 缺失（APKDIFF_BIN 未就绪），仅回填 apkSha256/apkSize，跳过补丁生成")
        vj["apkSha256"] = new_sha
        vj["apkSize"] = new_size
        with open("version.json", "w", encoding="utf-8") as f:
            json.dump(vj, f, ensure_ascii=False)
        return 0

    ensure_archive()

    # 滚动历史：vc -> {"apk": asset名, "sha256":..., "size":..., "patches": {...}}
    history = {}
    if download_asset(HISTORY_FILE, "meta"):
        try:
            history = {int(k): v for k, v in
                       json.load(open(f"meta/{HISTORY_FILE}", encoding="utf-8")).items()}
        except Exception as e:
            print(f"history file broken, starting fresh: {e}")
            history = {}

    # 指数回退生成补丁（只对含 libapkpatch.so 的底包）
    vcs = sorted(history)
    patches = {}  # from_vc -> {file,size,patchSha256}
    for back in BACKOFF:
        if back > len(vcs):
            break
        from_vc = vcs[-back]
        entry = history[from_vc]
        d = f"old/{from_vc}"
        if not download_asset(entry["apk"], d):
            print(f"skip {from_vc}: archived apk missing")
            continue
        old_apk = os.path.join(d, entry["apk"])
        if not apk_has_native_lib(old_apk):
            print(f"skip {from_vc}: 旧客户端无 libapkpatch.so（jbsdiff-only），走全量")
            continue
        pf = f"patch-beta-{from_vc}-to-{new_vc}.patch"
        try:
            run([ZIPDIFF, old_apk, new_apk, pf])
            run([ZIPPATCH, old_apk, pf, "verify.apk"])
            ok = filecmp.cmp("verify.apk", new_apk, shallow=False)
            too_big = ok and os.path.getsize(pf) >= new_size * MIN_PATCH_RATIO
            if not ok:
                print(f"patch {from_vc} -> {new_vc}: VERIFY FAILED, dropped")
            elif too_big:
                print(f"patch {from_vc} -> {new_vc}: >= {int(MIN_PATCH_RATIO*100)}% of full apk, dropped")
            else:
                patches[from_vc] = {
                    "file": pf, "size": os.path.getsize(pf),
                    "patchSha256": sha256(pf),
                }
                print(f"patch {from_vc} -> {new_vc}: {patches[from_vc]['size']} bytes OK")
        except Exception as e:
            print(f"patch {from_vc} -> {new_vc}: error {e}, dropped")
        finally:
            for f in (pf, "verify.apk"):
                if os.path.exists(f) and f not in [p["file"] for p in patches.values()]:
                    os.remove(f)

    # 上传当前内测包 + 补丁
    apk_asset = f"beta-{new_vc}.apk"
    run(["cp", new_apk, apk_asset])
    upload_asset(apk_asset)
    os.remove(apk_asset)
    for p in patches.values():
        upload_asset(p["file"])

    # 历史窗口裁剪到最近 MAX_KEEP（当前 + 之前 7 个）
    history[new_vc] = {
        "apk": f"beta-{new_vc}.apk", "sha256": new_sha, "size": new_size,
        "patches": {str(k): v for k, v in patches.items()},
    }
    kept = sorted(history)[-MAX_KEEP:]
    pruned = [vc for vc in history if vc not in kept]
    for vc in pruned:
        delete_asset(history[vc]["apk"])
        for p in (history[vc].get("patches") or {}).values():
            delete_asset(p["file"])
        del history[vc]

    # 单跳直达链：from 版本 → 当前版本
    chains = {}
    for from_vc, p in patches.items():
        chains[str(from_vc)] = {
            "fromApkSha256": history[from_vc]["sha256"],
            "totalSize": p["size"],
            "hops": [
                {
                    "toVersionCode": new_vc,
                    "url": f"https://github.com/{REPO}/releases/download/{ARCHIVE_TAG}/{p['file']}",
                    "size": p["size"],
                    "patchSha256": p["patchSha256"],
                    "resultSha256": new_sha,
                }
            ],
        }
        print(f"chain {from_vc} -> {new_vc}: 1 hop, {p['size']} bytes")

    # 写回 version.json（部署到 Pages）+ 滚动历史（存档）
    vj["apkSha256"] = new_sha
    vj["apkSize"] = new_size
    vj["chains"] = chains
    with open("version.json", "w", encoding="utf-8") as f:
        json.dump(vj, f, ensure_ascii=False)
    with open(HISTORY_FILE, "w", encoding="utf-8") as f:
        json.dump({str(k): v for k, v in history.items()}, f, ensure_ascii=False)
    upload_asset(HISTORY_FILE)
    print(f"beta: {len(patches)} patch(es), {len(chains)} chain(s), keep {len(history)} builds")


if __name__ == "__main__":
    sys.exit(main())
