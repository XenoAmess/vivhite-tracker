#!/usr/bin/env python3
"""生成 ApkDiffPatch 增量补丁（单跳直达）并写进 version.json。

ApkDiffPatch（sisong/ApkDiffPatch，MIT）服务端生成：
- 对历史 release 的「已发布签名 APK」生成直达补丁（ZipDiff）
- 跨通道：beta-archive 最近 BETA_CROSS_BASES 个存档内测包也生成直达补丁，
  beta 客户端可增量切到 stable（同一 keystore + 同一归一化/重签管线，可字节级回放）
- 回打自验（ZipPatch + 逐字节 cmp），不一致丢弃
- 只对「已装包内含 libapkpatch.so」的 from-version 生成（jbsdiff-only 旧客户端
  打不了 ZiPat1 补丁，跳过 → 自动全量下载，保证「检查更新」按钮始终可用）
- 补丁不小于发布包一半 → 丢弃（省流量无意义）
- 单跳直达，不构建多跳链（ApkDiffPatch 补丁足够小）
- 单个底包的补丁生成/验证失败只丢对应条目，客户端对缺失条目回退全量
- 发布物准备、元数据写入或 Release 上传失败仍会让发布 job 失败

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
# 跨通道底包：最近 N 个 beta-archive 存档内测包也生成直达补丁，
# 让 beta 客户端可以增量切换到 stable，不必全量下载
BETA_ARCHIVE_TAG = "beta-archive"
BETA_HISTORY_FILE = "beta-history.json"
BETA_CROSS_BASES = 4


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


def select_beta_sources(raw, new_vc, limit):
    """从 beta history 解析并筛选最近的可用底包，保持 versionCode 升序。"""
    if not isinstance(raw, dict):
        return []
    out = []
    for key, value in raw.items():
        try:
            vc = int(key)
        except (TypeError, ValueError):
            continue
        if not (0 < vc < new_vc) or not isinstance(value, dict):
            continue
        apk = value.get("apk")
        digest = value.get("sha256")
        if isinstance(apk, str) and apk and isinstance(digest, str) and digest:
            out.append((vc, apk, digest))
    out.sort()
    return out[-limit:]


def build_direct_chains(patches, base_sha, new_vc, new_sha, cur_tag):
    """把已验证的单跳补丁转换为客户端 chains 元数据。"""
    chains = {}
    for from_vc, patch in patches.items():
        if from_vc not in base_sha:
            continue
        chains[str(from_vc)] = {
            "fromApkSha256": base_sha[from_vc],
            "totalSize": patch["size"],
            "hops": [
                {
                    "toVersionCode": new_vc,
                    "url": (
                        f"https://github.com/{REPO}/releases/download/"
                        f"{cur_tag}/{patch['file']}"
                    ),
                    "size": patch["size"],
                    "patchSha256": patch["patchSha256"],
                    "resultSha256": new_sha,
                }
            ],
        }
    return chains


def beta_cross_sources(new_vc):
    """beta-archive 中最近 BETA_CROSS_BASES 个可用 beta 底包：[(vc, apk资产名, sha256)]。

    archive 不存在 / history 缺失或损坏时返回空列表，跨通道补丁静默跳过。
    """
    if not gh_download(BETA_ARCHIVE_TAG, BETA_HISTORY_FILE, "meta-beta"):
        print("skip beta cross bases: beta-history.json unavailable")
        return []
    try:
        with open(os.path.join("meta-beta", BETA_HISTORY_FILE), encoding="utf-8") as f:
            raw = json.load(f)
    except Exception as e:
        print(f"skip beta cross bases: history broken ({e})")
        return []
    return select_beta_sources(raw, new_vc, BETA_CROSS_BASES)


def try_patch(old_apk, new_apk, pf, new_size, label):
    """生成单跳补丁并回打自验；成功返回 {"file","size","patchSha256"}，失败清理后返回 None。"""
    try:
        run([ZIPDIFF, old_apk, new_apk, pf])
        run([ZIPPATCH, old_apk, pf, "verify.apk"])
        ok = filecmp.cmp(new_apk, "verify.apk", shallow=False)
        too_big = ok and os.path.getsize(pf) >= new_size * MIN_PATCH_RATIO
        if not ok:
            print(f"patch {label}: VERIFY FAILED, dropped")
        elif too_big:
            print(f"patch {label}: >= {int(MIN_PATCH_RATIO*100)}% of full apk, dropped")
        else:
            return {"file": pf, "size": os.path.getsize(pf), "patchSha256": sha256(pf)}
    except Exception as e:
        print(f"patch {label}: error {e}, dropped")
    finally:
        if os.path.exists("verify.apk"):
            os.remove("verify.apk")
    if os.path.exists(pf):
        os.remove(pf)
    return None


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
    base_sha = {}  # vc -> 已下载底包的实际 sha256（chains 的 fromApkSha256 单一来源）
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

        p = try_patch(old_apk, new_apk, f"patch-{vc}-to-{new_vc}.patch",
                      new_size, f"{tag}({vc}) -> {new_vc}")
        if p is not None:
            patches[vc] = p
            base_sha[vc] = old_sha
            print(f"patch {tag}({vc}) -> {new_vc}: {p['size']} bytes OK")

    # 跨通道：beta-archive 最近 BETA_CROSS_BASES 个存档内测包 → 本次 stable。
    # 整块防御性包裹：任何异常只丢跨通道条目，绝不影响 stable 通道内补丁与发布。
    try:
        for vc, apk_name, recorded_sha in beta_cross_sources(new_vc):
            d = f"old-beta/{vc}"
            if not gh_download(BETA_ARCHIVE_TAG, apk_name, d):
                print(f"skip beta {vc}: archived apk unavailable")
                continue
            old_apk = os.path.join(d, apk_name)
            old_sha = sha256(old_apk)
            if old_sha != recorded_sha:
                print(f"skip beta {vc}: archived apk hash disagrees with beta history")
                continue
            if not apk_has_native_lib(old_apk):
                print(f"skip beta {vc}: 旧客户端无 libapkpatch.so（jbsdiff-only），走全量")
                continue

            p = try_patch(old_apk, new_apk, f"patch-{vc}-to-{new_vc}.patch",
                          new_size, f"beta({vc}) -> {new_vc}")
            if p is not None:
                patches[vc] = p
                base_sha[vc] = old_sha
                print(f"patch beta({vc}) -> {new_vc}: {p['size']} bytes OK")
    except Exception as e:
        print(f"beta cross bases aborted: {e}")

    # 单跳直达链：from 版本 → 当前版本
    chains = build_direct_chains(patches, base_sha, new_vc, new_sha, cur_tag)
    for vc, p in patches.items():
        if str(vc) not in chains:
            continue
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
