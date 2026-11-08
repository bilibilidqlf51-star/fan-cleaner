# 推到 GitHub 自动构建 APK —— 操作指引

目标：把工程推到 GitHub，让 Actions 自动编译出 APK，然后从 Actions 下载安装。

---

## 前置：把工程拿到电脑上

手机上已打包好：`/sdcard/Download/douyin-fan-cleaner.tar.gz`

传到电脑后解压（tar.gz）：

```bash
tar xzf douyin-fan-cleaner.tar.gz
cd douyin-fan-cleaner
```

---

## 方式 A：有 git 的电脑（推荐）

1. 在 GitHub 新建仓库（**不要勾选** Add README / .gitignore，保持空仓库）。
2. 在解压后的 `douyin-fan-cleaner` 目录里执行：

```bash
git init
git add .
git commit -m "init: fan cleaner"
git branch -M main
git remote add origin https://github.com/你的用户名/你的仓库名.git
git push -u origin main
```

3. 推送后打开仓库 → **Actions** 标签 → 等待 `Build APK` 跑完（约 3~6 分钟）。
4. 点进那次运行 → 页面底部 **Artifacts** → 下载 `app-debug`。
5. 解压得到 `app-debug.apk`，传到手机安装。

> 如果推送时提示需要认证：GitHub 已不允许密码推送，用 Personal Access Token 当密码，
> 或者用 GitHub Desktop 图形客户端。

---

## 方式 B：没有 git（网页上传）

1. 手机上或电脑上打开 GitHub，新建空仓库。
2. 进入仓库 → **Add file → Upload files**。
3. 把解压后的**所有文件和文件夹**拖进去（包括 `.github`、`app`、`gradle` 等隐藏目录）。
   - 网页拖拽对嵌套目录和隐藏文件夹支持不好，**推荐方式 A**。
4. Commit 后同样去 Actions 看构建结果。

---

## 构建产物

- 文件：`app/build/outputs/apk/debug/app-debug.apk`
- 这是 **debug 包**，可以直接安装，无需签名配置。

---

## 常见问题

| 现象 | 处理 |
|---|---|
| Actions 报 `Gradle version 8.2` 不匹配或其他版本错误 | 改 `.github/workflows/build-apk.yml` 里的 `gradle-version` 和根 `build.gradle` 里的 AGP 版本，保持兼容（AGP 8.2.x ↔ Gradle 8.2~8.5） |
| 报找不到 `gradlew` | 不用管，workflow 用的是系统 `gradle` 命令，不依赖 wrapper jar |
| 依赖下载失败（ML Kit / TFLite） | 重跑一次 Actions；确认 `settings.gradle` 里有 `google()` 和 `mavenCentral()` |
| 构建报 SDK 版本 | `app/build.gradle` 里 `compileSdk 34`，Actions 环境自带 Android SDK 34 |
| 安装时提示「未知来源」 | 在手机设置里允许该来源安装 |
