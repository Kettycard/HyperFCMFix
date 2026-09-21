# HyperFCMFix

针对小米 HyperOS 4 (Android 15) 深度优化的 FCM 推送唤醒与锁屏防冻结 LSPosed 模块（基于现代 LibXposed API 102 架构）。

---

## 🌟 核心特性与解决痛点

在小米 HyperOS 4 下，由于 MIUI / HyperOS 激进的后台治理、Greezer 冻结机制以及 Aurogon 策略，传统的 FCMFix 或常规设置无法保证即时通讯软件（Telegram、Discord、Twitter、Nextcloud Talk 等）在锁屏后及时接收通知。

本模块通过 LSPosed 在 `system_server` 特权层挂载，彻底攻破以下三大拦截层：

1. **突破 Greezer 广播拦截（核心突破）**：
   - HyperOS 4 在 `BroadcastQueue` 中魔改了 `Greezer Denial: ... need cached broadcast` 机制，非微信/QQ 等国内白名单应用在锁屏冷冻后，FCM 广播会被系统强行暂存，导致 Google Play 服务（GMS）在 1ms 内判定 `No response to broadcast`。
   - 本模块在开机时**直接反射注入 `GreezeManagerService.mBroadcastTargetWhiteList` 核心白名单表**，将 FCM 广播（`c2dm.intent.RECEIVE` / `REGISTRATION`）与目标应用强行加入豁免名单，使系统将其视同微信/QQ 等对待！
2. **重写 `appOp == 11` 解决 Stopped 状态拦截**：
   - 深入拦截 `BroadcastController.broadcastIntentLocked`，当 GMS 派发 FCM 广播时，将默认的 `appOp = -1` 强制重写为系统预留的合法操作码 `11`，并自动注入 `FLAG_RECEIVER_INCLUDE_STOPPED_PACKAGES`，即使应用在多任务中被划掉也能被拉起唤醒。
3. **解除 GMS 与应用自启动/电量无限制枷锁**：
   - 拦截 `DeviceIdleController`，强制将 GMS 保持在电池优化白名单中；
   - 拦截 `AppOpsService` 对 `OP_AUTO_START` (10008) 的权限检查，统一返回 `MODE_ALLOWED`。
4. **现代 LibXposed API 102 架构**：
   - 采用现代类型约束与异常沙盒，彻底杜绝传统 Xposed 因类型转换失败拉崩 `system_server` 导致系统进入安全模式（Safe Mode）的问题。

---

## 📲 安装与使用说明

1. **下载与安装**：
   - 在 [Releases](https://github.com/Kettycard/HyperFCMFix/releases) 页面下载最新的 Release APK 并安装。
2. **LSPosed Manager 配置**：
   - 打开 LSPosed Manager，激活 **HyperOS FCM Fix** 模块；
   - 作用域仅需勾选：**系统框架 (`android`)** 即可。
3. **重启手机**：
   - 重启手机后，模块将在开机 8 秒内自动完成底层白名单注入。
4. **状态排查与验证**：
   - 在拨号盘输入 `*#*#426#*#*` 打开 Google Play 服务的 **FCM Diagnostics** 界面；
   - 观察推送事件，状态应转变为正常的 **`Successful broadcast (time=8~18ms)`**。

---

## 🙏 参考与致谢项目 (Credits & Acknowledgments)

本项目在逆向分析小米底层机制与设计穿透方案时，深入参考并借鉴了开源社区以下优秀项目的研究成果与实现，特此致谢：

- **[kooritea/fcmfix](https://github.com/kooritea/fcmfix)**：
  - 启发了关于 Android 广播队列中 `appOp` 参数改写（`-1` -> `11`）以及 `FLAG_RECEIVER_INCLUDE_STOPPED_PACKAGES` 广播标记注入的经典思路。
- **[dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix)**：
  - 提供了详尽的小米 HyperOS 底层 `GreezeManagerService`、`ActiveStateController` 状态机、`MILLET_NO_RESTRICT_APP` 与 `Aurogon` 拦截机制的技术逆向调查报告。
- **[libxposed/api](https://github.com/libxposed/api)**：
  - 提供了下一代现代、类型安全且高稳定性的 Xposed API 102 标准接口支持。

---

## 📄 开源许可证

本项目基于 Apache License 2.0 开源。
