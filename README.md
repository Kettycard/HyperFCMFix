# HyperOS FCM Fix (LSPosed Module)

专为小米 HyperOS 4 设计的 FCM 保活与穿透增强模块。

## 解决的问题
1. **Greezer 锁屏冻结 GMS**：解除锁屏 10 秒后对 `com.google.android.gms` 的强制冻结。
2. **PowerKeeper 神隐重写**：自动在 `MILLET_NO_RESTRICT_APP` 中补全 GMS，拦截省电策略为无限制。
3. **开机自启动与电池无限制**：开机自动下发 `cmd deviceidle` 与 `appops` 特权，内存中固化 `MODE_ALLOWED`。
4. **广播穿透**：解除 Stopped 应用不接收 FCM 广播的系统限制。

## 作用域 (Scope)
- 系统框架 (`android`)
- 电量和性能 (`com.miui.powerkeeper`)
- Google Play 服务 (`com.google.android.gms`)
