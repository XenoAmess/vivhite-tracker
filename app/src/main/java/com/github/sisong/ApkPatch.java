package com.github.sisong;

/**
 * ApkDiffPatch 的 Android JNI 包装（MIT，来自 github.com/sisong/ApkDiffPatch 官方 release）。
 * 原生库 libapkpatch.so + libc++_shared.so 已打包进 jniLibs（4 ABI）。
 */
public class ApkPatch {
    static {
        // 官方 ApkPatch.java 不含加载逻辑，这里补上（libc++_shared 由 Android 加载器自动解析）
        System.loadLibrary("apkpatch");
    }

    // return 0 is ok; patchFilePath file created by ZipDiff
    public static native int patch(String oldApkPath, String patchFilePath, String outNewApkPath,
                                   long maxUncompressMemory, String tempUncompressFilePath, int threadNum);
}
