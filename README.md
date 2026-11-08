# 清粉助手（抖音粉丝批量清理）

无障碍服务 + 头像过滤 + 年龄/性别判断的 Android 工程，**可直接用 Android Studio 打开编译**。

## 直接编译

1. Android Studio → **Open** → 选择 `douyin-fan-cleaner` 目录。
2. 等待 Gradle 同步（需要网络下载依赖；工程已带 `gradle-wrapper.properties`，Gradle 8.2）。
   - 若提示缺少 Gradle wrapper jar，按提示让 AS 自动补全，或选「Use local Gradle distribution」。
3. 连接手机 → Run。

> 本工程由脚本生成，**未经编译验证**。首次同步若报版本问题，通常是把 `com.android.application` 版本（`build.gradle` 里 8.2.2）调整成与你 AS 匹配的版本即可。

## 工程结构

```
douyin-fan-cleaner/
├── settings.gradle
├── build.gradle                       # AGP 8.2.2
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle                   # minSdk 30 / compileSdk 34 / ML Kit + TFLite
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml        # 启动页 + 无障碍服务注册
        ├── assets/
        │   ├── model_age_nonq.tflite       # 年龄模型（约 4.3 MB）
        │   └── model_gender_nonq.tflite    # 性别模型（约 2.7 MB）
        ├── java/com/example/fancleaner/
        │   ├── MainActivity.java          # 引导页：检查服务是否开启、跳转设置
        │   ├── FanCleanerService.java     # 无障碍服务：扫描列表、截图、点击、限速
        │   ├── AvatarClassifier.java      # 头像判定规则（无人脸/年龄/性别）
        │   └── AgeGenderEstimator.java    # TFLite 年龄+性别推理
        └── res/
            ├── layout/activity_main.xml
            ├── values/strings.xml
            └── xml/accessibility_config.xml
```

## 工作流程

```
仅在「粉丝指数」页（检测到「关注我的人」标题）动作
  ↓
扫描当前屏所有粉丝行（节点树：每行头像 ImageView + 「更多(···)」按钮）
  ↓
takeScreenshot() 截全屏 → 按每行头像 bounds 裁剪
  ↓
ML Kit 人脸检测
  ├─ 没人脸 → 清理（风景照 / 书法 / 作品图 / 图标 / 默认灰头像）
  └─ 有人脸 → 裁剪人脸 → TFLite 年龄/性别模型
        ├─ 女性 且 年龄 ≥ 40 → 清理（40多岁妈妈自拍）
        ├─ 年龄 ≤ 14         → 清理（小孩自拍）
        └─ 其他              → 保留
  ↓
取最上面一个「可清理」行 → 点「更多」→ 点「移除粉丝」→ 等二次确认
  ↓
计数 +1 → 随机延迟 1.2~2.2s → 重新扫描 → 扫描不到可清理的行就向下滚动
  ↓
达到 TARGET（默认 20）→ 停止并提示
```

页面识别依据：页面上出现「关注我的人」。离开该页自动暂停，**不会误点抖音视频流里的「···」**。

## 使用步骤

1. 安装 App（或直接 Run 到手机）。
2. 打开「清粉助手」→ 点「去系统设置开启无障碍服务」→ 找到「清粉助手」并开启。
3. 打开抖音 → 我 → 点「粉丝」数字 → 进入「粉丝指数」列表页。
4. 服务自动开始，Toast 显示「已移除 x/20」。
5. 想停：关掉无障碍服务，或退出粉丝列表页。

## 可调参数

`FanCleanerService.java` 顶部：

| 参数 | 默认 | 含义 |
|---|---|---|
| `TARGET` | 20 | 本次最多移除数量 |
| `DELAY_MIN` / `DELAY_MAX` | 1200 / 2200 | 每步随机延迟（防风控） |
| `CONFIRM_WAIT` | 1800 | 点「移除粉丝」后等确认框的时间 |
| `ENABLE_AVATAR_FILTER` | true | 是否开启头像过滤；false = 按列表顺序逐个清理 |

`AvatarClassifier.java` 顶部：

| 参数 | 默认 | 含义 |
|---|---|---|
| `MOM_AGE_MIN` | 40 | 女性且年龄 ≥ 此值 → 判定「妈妈自拍」 |
| `CHILD_AGE_MAX` | 14 | 年龄 ≤ 此值 → 判定「小孩自拍」 |

## 模型来源与规格

来自 [shubham0204/Age-Gender_Estimation_TF-Android](https://github.com/shubham0204/age-gender_estimation_tf-android)（Apache-2.0）：

| 模型 | 输入 | 输出 | 说明 |
|---|---|---|---|
| `model_age_nonq.tflite` | `[1,200,200,3]` float32（/255） | `[1,1]` | 年龄 = 输出 × 116 |
| `model_gender_nonq.tflite` | `[1,128,128,3]` float32（/255） | `[1,2]` | `[0]`=男性概率，`[1]`=女性概率 |

## 重要限制（必须先看）

1. **年龄判断有误差。** 模型是回归估计，对侧脸、滤镜、口罩、低分辨率头像都可能估偏；阈值已放宽（妈妈≥40、小孩≤14）以降低误删。**正式使用前请把 `TARGET` 改成 3~5 先实测。**
2. **移除不可逆。** 抖音移除粉丝后对方不再关注你，无法撤销。
3. **风控风险。** 随机延迟 + 上限能降低但不能消除被限流/封号风险；不要一天内大量运行。
4. **抖音 UI 会变。** 节点文案（「更多」「移除粉丝」「确认」）随版本变化；点了没反应时看日志，按实际节点调整 `findMoreInRow` / `handleWaitMenu` 里的关键词。
5. 截图失败或模型加载失败时会自动降级（不过滤按顺序清理 / 有人脸一律保留），不会乱删。

## 调试

```bash
adb logcat -s FanCleaner AvatarClassifier AgeGenderEstimator
```

日志会打印：每步动作、点击结果、人脸检测结果、估算年龄/性别、判定原因、已移除数量。
