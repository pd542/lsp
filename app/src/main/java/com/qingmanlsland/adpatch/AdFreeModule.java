package com.qingmanlsland.adpatch;

import android.app.Application;
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
 * 目标 App : com.qingmandao.app (壳 Application = com.dc7ea3,
 *            真 Application = com.qingmanlsland.app.QingManApplication,
 *            壳通过 native libbb615e.so 解密并加载真实 dex)
 *
 * 补丁点 (全部为"上游方法 hook"):
 *   1. l2.m.g(Context):I        -> 恒 0  (开屏广告源开关)
 *   2. c.c(Context):Z           -> 恒 true (全局免广告门)
 *   3. MangaReaderActivity.C2()  -> 强制 D0=true (阅读器永久免广告)
 *   4. MangaReaderActivity.T1()  -> 置空 (阻断每日重置清零)
 *
 * 加壳适配: 壳 onCreate → db6993.n(真 Application) → getClassLoader →
 *           在真 ClassLoader 上挂钩 (不在壳的 ClassLoader 上瞎找).
 */
public class AdFreeModule implements IXposedHookLoadPackage {

    private static final String TAG = "QmlAdFree";
    private static final String TARGET_PKG = "com.qingmandao";

    private boolean hooksInstalled = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || lp.packageName == null || !lp.packageName.startsWith(TARGET_PKG)) {
            return;
        }
        Log.i(TAG, "process loaded: " + lp.packageName + " | process=" + lp.processName);

        // hook 壳 Application 的 onCreate, 等真 ClassLoader 就绪后再挂目标钩子
        try {
            XposedHelpers.findAndHookMethod(
                    "com.dc7ea3",                     // 壳 Application
                    lp.classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            installRealHooks(param.thisObject);
                        }
                    });
            Log.i(TAG, "shell onCreate hook installed");
        } catch (Throwable t) {
            Log.e(TAG, "shell onCreate hook FAILED: " + t);
            // 兜底: 直接在当前 ClassLoader 上尝试 (非加壳版本可用)
            installHooks(lp.classLoader);
        }
    }

    /** 壳 onCreate 完成后: 从 db6993.n 拿真 Application, 取其 ClassLoader 挂钩 */
    private void installRealHooks(Object shellApp) {
        if (hooksInstalled) return;
        try {
            Class<?> dbCls = XposedHelpers.findClass("com.db6993", shellApp.getClass().getClassLoader());
            Object realApp = XposedHelpers.getStaticObjectField(dbCls, "n");
            if (realApp == null) {
                Log.w(TAG, "db6993.n is null, retrying on shell classLoader");
                installHooks(shellApp.getClass().getClassLoader());
                return;
            }
            ClassLoader realCl = realApp.getClass().getClassLoader();
            Log.i(TAG, "realApp=" + realApp.getClass().getName() + " | realClassLoader=" + realCl);
            installHooks(realCl);
        } catch (Throwable t) {
            Log.e(TAG, "installRealHooks failed: " + t);
            // last resort
            installHooks(shellApp.getClass().getClassLoader());
        }
    }

    /** 在指定 ClassLoader 上挂四个目标 hook */
    private synchronized void installHooks(ClassLoader cl) {
        if (hooksInstalled) return;
        try {
            hookSplashSource(cl);
            hookGlobalAdFreeGate(cl);
            hookReaderAdFree(cl);
            hookReaderDailyReset(cl);
            hooksInstalled = true;
            Log.i(TAG, "all 4 hooks installed on classLoader=" + cl);
        } catch (Throwable t) {
            Log.e(TAG, "installHooks failed: " + t);
        }
    }

    /** 补丁 1: l2.m.g(Context)I -> 恒 0 (开屏广告源开关) */
    private void hookSplashSource(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("l2.m", cl, "g", Context.class,
                    XC_MethodReplacement.returnConstant(0));
            Log.i(TAG, "[1/4] l2.m.g -> 0  OK");
        } catch (Throwable t) {
            Log.e(TAG, "[1/4] l2.m.g FAILED: " + t);
        }
    }

    /** 补丁 2: c.c(Context)Z -> 恒 true (全局免广告门) */
    private void hookGlobalAdFreeGate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.qingmanlsland.app.c", cl, "c",
                    Context.class, XC_MethodReplacement.returnConstant(true));
            Log.i(TAG, "[2/4] c.c -> true  OK");
        } catch (Throwable t) {
            Log.e(TAG, "[2/4] c.c FAILED: " + t);
        }
    }

    /** 补丁 3: MangaReaderActivity.C2() -> 强制 D0=true, 跳过原 prefs 读取 */
    private void hookReaderAdFree(ClassLoader cl) {
        try {
            String name = "com.qingmanlsland.app.ui.activity.MangaReaderActivity";
            XposedHelpers.findAndHookMethod(name, cl, "C2", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedHelpers.setBooleanField(param.thisObject, "D0", true);
                    param.setResult(null);
                }
            });
            Log.i(TAG, "[3/4] MangaReaderActivity.C2  OK");
        } catch (Throwable t) {
            Log.e(TAG, "[3/4] MangaReaderActivity.C2 FAILED: " + t);
        }
    }

    /** 补丁 4: MangaReaderActivity.T1() -> 置空 (阻断每日重置清零) */
    private void hookReaderDailyReset(ClassLoader cl) {
        try {
            String name = "com.qingmanlsland.app.ui.activity.MangaReaderActivity";
            XposedHelpers.findAndHookMethod(name, cl, "T1",
                    XC_MethodReplacement.returnConstant(null));
            Log.i(TAG, "[4/4] MangaReaderActivity.T1  OK");
        } catch (Throwable t) {
            Log.e(TAG, "[4/4] MangaReaderActivity.T1 FAILED: " + t);
        }
    }
}