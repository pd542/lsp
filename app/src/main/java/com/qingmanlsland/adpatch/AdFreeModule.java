package com.qingmanlsland.adpatch;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 清漫 LS 免广告模块 (LSPosed / LSPlant)
 *
 * 目标 App : com.qingmandao.app
 *           （壳 Application = com.dc7ea3, 真 Application = com.qingmanlsland.app.QingManApplication,
 *            壳用 native libbb615e.so 解密加载真实 dex, 真实类在另一个 ClassLoader 里）
 *
 * 加载策略（兼容加壳）:
 *   1. handleLoadPackage 时, 先 hook 壳 Application 的 attachBaseContext / onCreate
 *   2. 等壳把真 dex 解密加载、db6993.n = 真 Application 后, 取其 ClassLoader 挂钩
 *   3. 若 db6993.n 暂未就绪, 延迟重试, 避免错过时机
 *
 * 补丁点:
 *   1. l2.m.g(Context):I          -> 恒 0   (开屏广告源开关)
 *   2. c.c(Context):Z             -> 恒 true (全局免广告门)
 *   3. MangaReaderActivity.C2()   -> 强制 D0=true (阅读器永久免广告)
 *   4. MangaReaderActivity.T1()   -> 置空 (阻断每日重置清零)
 */
public class AdFreeModule implements IXposedHookLoadPackage {

    private static final String TAG = "QmlAdFree";
    private static final String TARGET_PKG = "com.qingmandao";
    private static final String SHELL_APP = "com.dc7ea3";           // 壳 Application
    private static final String SHELL_HELPER = "com.db6993";        // 壳 native 辅助类
    private static final String REAL_APP_FIELD = "n";               // 真 Application 静态字段

    private boolean hooksInstalled = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || lp.packageName == null || !lp.packageName.startsWith(TARGET_PKG)) {
            return;
        }
        Log.i(TAG, "handleLoadPackage pkg=" + lp.packageName + " process=" + lp.processName);

        // 先试: 当前 ClassLoader 如果有真实类 (未加壳/已解密), 直接挂钩
        if (tryInstallOnClassLoader(lp.classLoader, true)) {
            return;
        }

        // 加壳场景: hook 壳 Application, 等真 ClassLoader 就绪
        try {
            XposedHelpers.findAndHookMethod(SHELL_APP, lp.classLoader, "attachBaseContext", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "shell attachBaseContext done, scheduling hooks");
                            scheduleInstall(param.thisObject);
                        }
                    });
            XposedHelpers.findAndHookMethod(SHELL_APP, lp.classLoader, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Log.i(TAG, "shell onCreate done, scheduling hooks");
                            scheduleInstall(param.thisObject);
                        }
                    });
            Log.i(TAG, "shell hooks installed (attachBaseContext + onCreate)");
        } catch (Throwable t) {
            Log.e(TAG, "shell hooks install FAILED: " + t);
            scheduleInstall(null);
        }

        // 兜底: 无论何种情况, 延迟多探测几次
        scheduleProbe(null, 1000);
        scheduleProbe(null, 3000);
        scheduleProbe(null, 8000);
    }

    /** 延迟去拿真 ClassLoader 挂钩 */
    private void scheduleInstall(final Object shellApp) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                installRealHooks(shellApp);
            }
        }, 100);
    }

    /** 多次探测兜底 */
    private void scheduleProbe(final Object shellApp, long delay) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                installRealHooks(shellApp);
            }
        }, delay);
    }

    /** 从壳拿真 Application, 取 ClassLoader 挂钩 */
    private void installRealHooks(Object shellApp) {
        if (hooksInstalled) return;
        try {
            ClassLoader base = shellApp != null
                    ? shellApp.getClass().getClassLoader()
                    : AdFreeModule.class.getClassLoader();
            Class<?> helper = XposedHelpers.findClass(SHELL_HELPER, base);
            Object realApp = XposedHelpers.getStaticObjectField(helper, REAL_APP_FIELD);
            if (realApp != null) {
                ClassLoader realCl = realApp.getClass().getClassLoader();
                Log.i(TAG, "realApp=" + realApp.getClass().getName() + " classLoader=" + realCl);
                if (tryInstallOnClassLoader(realCl, false)) {
                    return;
                }
            } else {
                Log.w(TAG, "realApp still null (db6993.n), hooks pending");
            }
            // 再兜底一次: 直接尝试 (可能真实类已在 base 里)
            tryInstallOnClassLoader(base, false);
        } catch (Throwable t) {
            Log.w(TAG, "installRealHooks attempt: " + t);
        }
    }

    /** 在 cl 上尝试安装 4 个 hook, 成功返回 true */
    private synchronized boolean tryInstallOnClassLoader(ClassLoader cl, boolean verboseFail) {
        if (cl == null || hooksInstalled) return false;
        // 类可用性探测
        try {
            XposedHelpers.findClass("l2.m", cl);
        } catch (Throwable t) {
            if (verboseFail) Log.i(TAG, "l2.m not in this classLoader yet");
            return false;
        }
        boolean ok1 = hookSplashSource(cl);
        boolean ok2 = hookGlobalAdFreeGate(cl);
        boolean ok3 = hookReaderAdFree(cl);
        boolean ok4 = hookReaderDailyReset(cl);
        if (ok1 && ok2 && ok3 && ok4) {
            hooksInstalled = true;
            Log.i(TAG, "ALL 4 HOOKS INSTALLED @ " + cl);
            return true;
        }
        Log.w(TAG, "partial hooks: " + ok1 + "/" + ok2 + "/" + ok3 + "/" + ok4);
        return false;
    }

    /** 补丁 1: l2.m.g(Context)I -> 恒 0 (开屏广告源开关) */
    private boolean hookSplashSource(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("l2.m", cl, "g", Context.class,
                    XC_MethodReplacement.returnConstant(0));
            Log.i(TAG, "[1/4] l2.m.g -> 0 OK");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "[1/4] l2.m.g FAILED: " + t);
            return false;
        }
    }

    /** 补丁 2: c.c(Context)Z -> 恒 true (全局免广告门) */
    private boolean hookGlobalAdFreeGate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("com.qingmanlsland.app.c", cl, "c",
                    Context.class, XC_MethodReplacement.returnConstant(true));
            Log.i(TAG, "[2/4] c.c -> true OK");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "[2/4] c.c FAILED: " + t);
            return false;
        }
    }

    /** 补丁 3: MangaReaderActivity.C2() -> 强制 D0=true, 跳过原 prefs 读取 */
    private boolean hookReaderAdFree(ClassLoader cl) {
        try {
            String name = "com.qingmanlsland.app.ui.activity.MangaReaderActivity";
            XposedHelpers.findAndHookMethod(name, cl, "C2", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedHelpers.setBooleanField(param.thisObject, "D0", true);
                    param.setResult(null);
                }
            });
            Log.i(TAG, "[3/4] MangaReaderActivity.C2 OK");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "[3/4] MangaReaderActivity.C2 FAILED: " + t);
            return false;
        }
    }

    /** 补丁 4: MangaReaderActivity.T1() -> 置空 (阻断每日重置清零) */
    private boolean hookReaderDailyReset(ClassLoader cl) {
        try {
            String name = "com.qingmanlsland.app.ui.activity.MangaReaderActivity";
            XposedHelpers.findAndHookMethod(name, cl, "T1",
                    XC_MethodReplacement.returnConstant(null));
            Log.i(TAG, "[4/4] MangaReaderActivity.T1 OK");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "[4/4] MangaReaderActivity.T1 FAILED: " + t);
            return false;
        }
    }
}