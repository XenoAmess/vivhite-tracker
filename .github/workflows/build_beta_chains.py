#!/usr/bin/env python3
"""beta 通道增量更新：维护 beta-archive 滚动 release（最近 8 个内测包 + 补丁），
生成指数回退补丁与多跳链，把元数据写进 version.json（随后部署到 Pages）。

- beta-archive release 常驻，资产命名：
    beta-<versionCode>.apk                存档内测包
    patch-beta-<from>-to-<to>.bspatch     补丁
    beta-history.json                     滚动元数据（供下次构建引用）
- 回退窗口 1,2,4（存档上限 8 个包）；补丁回打自验，比全量还大直接丢弃
- 链构建规则与 stable（build_delta_chains.py）一致：二进制分解多跳
- 任何失败只丢对应条目，绝不阻断 beta 发布
"""

import filecmp
import glob
import hashlib
import json
import os
import subprocess
import sys

REPO = os.environ.get("GITHUB_REPOSITORY", "XenoAmess/vivhite-tracker")
ARCHIVE_TAG = "beta-archive"
HISTORY_FILE = "beta-history.json"
MAX_KEEP = 8          # 存档内测包上限
BACKOFF = (1, 2, 4)   # 指数回退窗口


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
        gh("release", "create", ARCHIVE_TAG, "--title", "beta-archive",
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


def list_assets():
    r = gh("release", "view", ARCHIVE_TAG, "--json", "assets")
    return [a["name"] for a in json.loads(r.stdout)["assets"]]


def delete_asset(name):
    gh("release", "delete-asset", ARCHIVE_TAG, name, "--yes", check=False)
    print(f"pruned asset {name}")


def main():
    # 当前构建产物（Prepare beta channel files 步骤已生成）
    new_apk = "vivhite-tracker-beta.apk"
    with open("version.json", encoding="utf-8") as f:
        vj = json.load(f)
    new_vc = int(vj["versionCode"])
    new_sha = sha256(new_apk)
    new_size = os.path.getsize(new_apk)

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

    # 指数回退生成补丁
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
        pf = f"patch-beta-{from_vc}-to-{new_vc}.bspatch"
        try:
            run(["bsdiff", old_apk, new_apk, pf])
            run(["bspatch", old_apk, "verify.apk", pf])
            ok = filecmp.cmp("verify.apk", new_apk, shallow=False)
            too_big = ok and os.path.getsize(pf) >= new_size
            if not ok:
                print(f"patch {from_vc} -> {new_vc}: VERIFY FAILED, dropped")
            elif too_big:
                print(f"patch {from_vc} -> {new_vc}: larger than full apk, dropped")
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

    # 链构建：索引 0..m-1 为历史（含当前的最后一个），目标 = 当前
    ordered = sorted(history)
    m = len(ordered)
    target = ordered.index(new_vc)

    def hop_patch(from_i, to_i):
        return (history[ordered[to_i]].get("patches") or {}).get(str(ordered[from_i]))

    chains = {}
    for i in range(target):
        from_sha = history[ordered[i]]["sha256"]
        hops = []
        cur = i
        ok = True
        while cur < target:
            step = None
            s = 1
            while cur + s <= target:
                if hop_patch(cur, cur + s):
                    step = s
                s *= 2
            if step is None:
                ok = False
                break
            tgt = cur + step
            pm = hop_patch(cur, tgt)
            hops.append({
                "toVersionCode": ordered[tgt],
                "url": f"https://github.com/{REPO}/releases/download/{ARCHIVE_TAG}/{pm['file']}",
                "size": pm["size"],
                "patchSha256": pm["patchSha256"],
                "resultSha256": history[ordered[tgt]]["sha256"],
            })
            cur = tgt
        if ok and hops:
            chains[str(ordered[i])] = {
                "fromApkSha256": from_sha,
                "totalSize": sum(h["size"] for h in hops),
                "hops": hops,
            }
            print(f"chain {ordered[i]} -> {new_vc}: {len(hops)} hop(s)")

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
