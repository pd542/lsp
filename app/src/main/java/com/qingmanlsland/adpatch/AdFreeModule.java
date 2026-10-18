package com.qingmanlsland.adpatch;

import android.content.Context;
import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 清漫 LS 免广告模块 (LSPosed / LSPlant)
 *
 * 目标 App : com.qingmanlsland (混淆类短名)
 *
 * 补丁点 (全部为"上游方法 hook", 不碰 if-nez 指令级 patch):
 *   1. l2.m.g(Context):I        -> 恒 return 0   (服务端开屏广告开关永远关,
 *                                   SplashActivity.A() 里 if-eqz v3,:cond_75 直接走无广告分支)
 *   2. c.c(Context):Z           -> 恒 return true (全局免广告门常开, 涵盖 c.J 插屏 /
 *                                   b$d 章节间插屏 / AdFreeActivity 判定)
 *   3. MangaReaderActivity.C2()  -> 强制 D0=true (阅读器永久免广告, 不依赖 prefs)
 *   4. MangaReaderActivity.T1()  -> 置空 (阻断每日重置清零 is_ad_free_today)
 *
 * 说明: l2/m, e2/b, c 等是混淆短名, 必须用 findAndHookMethod 按混淆名 +
 *       classLoader 定位, 目标 App 更新后若混淆改名会失效, 需同步调整。
 */
public class AdFreeModule implements IXposedHookLoadPackage {

    private static final String TAG = "QmlAdFree";
    private static final String TARGET_PKG = "com.qingmanlsland";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || lp.packageName == null || !lp.packageName.startsWith(TARGET_PKG)) {
            return;
        }
        Log.i(TAG, "loaded: " + lp.packageName);
        ClassLoader cl = lp.classLoader;
        hookSplashSource(cl);
        hookGlobalAdFreeGate(cl);
        hookReaderAdFree(cl);
        hookReaderDailyReset(cl);
    }

    /** 补丁 1: l2.m.g(Context)I -> 恒 0 (开屏广告源开关) */
    private void hookSplashSource(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("l2.m", cl, "g", Context.class,
                    XC_MethodReplacement.returnConstant(0));
            Log.i(TAG, "hook l2.m.g -> 0 ok");
        } catch (Throwable t) {
            Log.e(TAG, "hook l2.m.g failed: " + t);
        }
    }

    /** 补丁 2: c.c(Context)Z -> 恒 true (全局免广告门) */
    private void hookGlobalAdFreeGate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.qingmanlsland.app.c", cl, "c",
                    Context.class, XC_MethodReplacement.returnConstant(true));
            Log.i(TAG, "hook c.c -> true ok");
        } catch (Throwable t) {
            Log.e(TAG, "hook c.c failed: " + t);
        }
    }

    /** 补丁 3: MangaReaderActivity.C2() -> 强制 D0=true, 跳过原 prefs 读取 */
    private void hookReaderAdFree(ClassLoader cl) {
        try {
            String className = "com.qingmanlsland.app.ui.activity.MangaReaderActivity";
            XposedHelpers.findAndHookMethod(className, cl, "C2", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedHelpers.setBooleanField(param.thisObject, "D0", true);
                    param.setResult(null); // 跳过原方法体
                }
            });
            Log.i(TAG, "hook MangaReaderActivity.C2 ok");
        } catch (Throwable t) {
            Log.e(TAG, "hook MangaReaderActivity.C2 failed: " + t);
        }
    }

    /** 补丁 4: MangaReaderActivity.T1() -> 置空 (阻断每日重置清零) */
    private void hookReaderDailyReset(ClassLoader cl) {
        try {
            String className = "com.qingmanlsland.app.ui.activity.MangaReaderActivity";
            XposedHelpers.findAndHookMethod(className, cl, "T1",
                    XC_MethodReplacement.returnConstant(null));
            Log.i(TAG, "hook MangaReaderActivity.T1 ok");
        } catch (Throwable t) {
            Log.e(TAG, "hook MangaReaderActivity.T1 failed: " + t);
        }
    }
}