#!/usr/bin/env python3
"""生成 bsdiff 增量补丁并构建跨版本升级链，拍平写进 version.json。

规则：
- 对历史第 1,2,4,8... 个 release 生成直达补丁（bsdiff），每个都 bspatch 回打自验
- 其余历史版本按二进制分解组成多跳链（跳数 = 距离二进制中 1 的个数）
- version.json 新增：
    apkSha256/apkSize   本版全量 APK 校验
    patches             本 release 托管的补丁（供未来 release 构建链时引用）
    chains              拍平的升级链：fromVersionCode -> {fromApkSha256,totalSize,hops[]}
- 任何一步失败只丢对应条目，绝不阻断发布（客户端对缺失条目回退全量下载）
"""

import filecmp
import glob
import hashlib
import json
import os
import subprocess
import sys

REPO = os.environ.get("GITHUB_REPOSITORY", "XenoAmess/vivhite-tracker")


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


def main():
    new_apk = sorted(glob.glob("vivhite-tracker-*.apk"))[0]
    cur_tag = git("describe", "--tags", "--abbrev=0", "--match", "v*")
    new_vc = int(git("rev-list", "--count", "HEAD"))
    new_sha = sha256(new_apk)
    new_size = os.path.getsize(new_apk)

    # 历史 release（tag）按 versionCode 升序，排除当前
    history = []
    for tag in git("tag", "-l", "v*").splitlines():
        if tag == cur_tag:
            continue
        history.append((int(git("rev-list", "--count", tag)), tag))
    history.sort()
    n = len(history)
    print(f"current: {cur_tag} vc={new_vc}; history={n} releases")

    # 历史 version.json（含 apkSha256/patches 元数据；老版本没有则为 None）
    meta = {}
    for vc, tag in history:
        d = f"meta/{vc}"
        if gh_download(tag, "version.json", d):
            try:
                meta[vc] = json.load(open(f"{d}/version.json", encoding="utf-8"))
            except Exception:
                meta[vc] = None
        else:
            meta[vc] = None

    # 指数回退直达补丁：倒数第 1,2,4,8... 个 release
    patches = {}   # from_vc -> {file,size,patchSha256}
    apk_hash = {}  # 已下载旧 APK 的 sha256
    back = 1
    while back <= n:
        vc, tag = history[n - back]
        d = f"old/{vc}"
        old_apks = []
        if gh_download(tag, "vivhite-tracker-*.apk", d):
            old_apks = glob.glob(f"{d}/vivhite-tracker-*.apk")
        if not old_apks:
            print(f"skip {tag}: old apk unavailable")
            back *= 2
            continue
        old_apk = old_apks[0]
        apk_hash[vc] = sha256(old_apk)
        pf = f"patch-{vc}-to-{new_vc}.bspatch"
        try:
            run(["bsdiff", old_apk, new_apk, pf])
            run(["bspatch", old_apk, "verify.apk", pf])
            if not filecmp.cmp("verify.apk", new_apk, shallow=False):
                os.remove(pf)
                print(f"patch {tag}({vc}) -> {new_vc}: VERIFY FAILED, dropped")
            elif os.path.getsize(pf) >= new_size:
                # 补丁比全量还大就没存在意义（远古版本跨度太大时会发生）
                os.remove(pf)
                print(f"patch {tag}({vc}) -> {new_vc}: larger than full apk, dropped")
            else:
                patches[vc] = {
                    "file": pf,
                    "size": os.path.getsize(pf),
                    "patchSha256": sha256(pf),
                }
                print(f"patch {tag}({vc}) -> {new_vc}: {patches[vc]['size']} bytes OK")
        except Exception as e:
            print(f"patch {tag}({vc}) -> {new_vc}: error {e}, dropped")
            if os.path.exists(pf):
                os.remove(pf)
        finally:
            if os.path.exists("verify.apk"):
                os.remove("verify.apk")
        back *= 2

    # 链构建：索引 0..n-1 为历史，n 为当前
    def vc_of(i):
        return history[i][0] if i < n else new_vc

    def tag_of(i):
        return history[i][1] if i < n else cur_tag

    def known_apk_sha(vc):
        if vc == new_vc:
            return new_sha
        if vc in apk_hash:
            return apk_hash[vc]
        m = meta.get(vc)
        return (m or {}).get("apkSha256")

    def hop_patch(from_i, to_i):
        """from_i -> to_i 的补丁元数据（存在才返回）。"""
        fvc = vc_of(from_i)
        if to_i == n:
            return patches.get(fvc)
        m = meta.get(vc_of(to_i))
        return ((m or {}).get("patches") or {}).get(str(fvc))

    chains = {}
    for i in range(n):
        from_sha = known_apk_sha(vc_of(i))
        if not from_sha:
            continue  # 无法校验底包，不出链（客户端走全量）
        hops = []
        cur = i
        ok = True
        while cur < n:
            # 贪心跳最大 2 的幂步长
            step = None
            s = 1
            while cur + s <= n:
                if hop_patch(cur, cur + s):
                    step = s
                s *= 2
            if step is None:
                ok = False
                break
            tgt = cur + step
            pm = hop_patch(cur, tgt)
            rsha = known_apk_sha(vc_of(tgt))
            if not rsha:
                ok = False
                break
            hops.append({
                "toVersionCode": vc_of(tgt),
                "url": f"https://github.com/{REPO}/releases/download/{tag_of(tgt)}/{pm['file']}",
                "size": pm["size"],
                "patchSha256": pm["patchSha256"],
                "resultSha256": rsha,
            })
            cur = tgt
        if ok and hops:
            chains[str(vc_of(i))] = {
                "fromApkSha256": from_sha,
                "totalSize": sum(h["size"] for h in hops),
                "hops": hops,
            }
            print(f"chain {vc_of(i)} -> {new_vc}: {len(hops)} hop(s), {chains[str(vc_of(i))]['totalSize']} bytes")

    # 合并写回 version.json
    with open("version.json", encoding="utf-8") as f:
        vj = json.load(f)
    vj["apkSha256"] = new_sha
    vj["apkSize"] = new_size
    vj["patches"] = {str(k): v for k, v in patches.items()}
    vj["chains"] = chains
    with open("version.json", "w", encoding="utf-8") as f:
        json.dump(vj, f, ensure_ascii=False)
    print(f"version.json: {len(patches)} direct patch(es), {len(chains)} chain(s)")


if __name__ == "__main__":
    sys.exit(main())
