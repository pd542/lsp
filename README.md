# QmlAdFree — 清漫 LS 免广告模块 (LSPosed / LSPlant)

针对 `com.qingmandao.app`（内部类命名空间 `com.qingmanlsland.*`）的开屏/插屏/阅读器广告移除模块。

## 补丁点（全部为「上游方法 hook」，不碰 `if-nez` 指令级 patch）

| # | 方法 | 改法 | 作用 |
|---|------|------|------|
| 1 | `l2.m.g(Context):I` | 恒 `return 0` | 服务端开屏开关永远关；`SplashActivity.A()` 中 `if-eqz v3,:cond_75` 直接走无广告分支 |
| 2 | `com.qingmanlsland.app.c.c(Context):Z` | 恒 `return true` | 全局免广告门常开，涵盖 `c.J` 插屏、`b$d` 章节间插屏、`AdFreeActivity` 判定 |
| 3 | `MangaReaderActivity.C2()` | 强制 `D0=true` 并跳过方法体 | 阅读器永久免广告，不依赖 prefs |
| 4 | `MangaReaderActivity.T1()` | 置空（`return null`） | 阻断每日重置清零 `is_ad_free_today` |

## 关键说明

- `l2/m`、`e2/b`、`c` 是混淆短名，模块用 `XposedHelpers.findAndHookMethod(name, classLoader, ...)` 按混淆名定位；目标 App 更新改名后会失效，需同步调整。
- `SplashActivity.A()` 里 `if-nez v1,:cond_7c` 的语义：`v1=1` 表示「在免广告期」。若把它改成 `nop`，开屏反而**永远进广告分支**。本模块改用上游 `l2.m.g -> 0` 从源头关掉开屏广告，比指令级 patch 更稳。

## 构建

推送到 `main` 即触发 GitHub Actions（`.github/workflows/android.yml`），产物在 Actions 的 Artifact：`QmlAdFree-release-apk`。

## 安装

1. 安装构建出的 APK。
2. 在 LSPosed/LSPlant 中启用本模块，作用域勾选目标 App。
3. 重启目标 App。