package org.hermes.hyperfcmfix

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class ModernHook : XposedModule() {

    companion object {
        private const val TAG = "HyperFCMFix"
        private const val GMS_PKG = "com.google.android.gms"
        private const val SETTING_MILLET = "MILLET_NO_RESTRICT_APP"
        private const val OP_AUTO_START = 10008
        private const val MODE_ALLOWED = 0

        private const val FLAG_RECEIVER_INCLUDE_STOPPED_PACKAGES = 0x00000020
        private const val FLAG_RECEIVER_EXCLUDE_STOPPED_PACKAGES = 0x00000010
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val classLoader = param.classLoader
        log(Log.INFO, TAG, "system_server 正在启动，载入 LibXposed API 102 现代架构...")

        hookDeviceIdleController(classLoader)
        hookAppOpsService(classLoader)
        hookBroadcastQueue(classLoader)
        hookXiaomiBroadcastStub(classLoader)
        hookSystemReady(classLoader)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
    }

    /**
     * 1. 拦截 DeviceIdleController，强制 GMS 进入电池优化白名单
     */
    private fun hookDeviceIdleController(classLoader: ClassLoader) {
        try {
            val clazz = classLoader.loadClass("com.android.server.DeviceIdleController")
            for (m in clazz.declaredMethods) {
                if (m.returnType == Boolean::class.javaPrimitiveType &&
                    (m.name == "isPowerSaveWhitelistApp" || m.name == "isPowerSaveWhitelistExceptIdleApp")
                ) {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val arg0 = chain.args.firstOrNull() as? String
                        if (arg0 == GMS_PKG) {
                            true
                        } else {
                            chain.proceed()
                        }
                    }
                    log(Log.INFO, TAG, "已安全 Hook DeviceIdleController.${m.name}")
                }
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "Hook DeviceIdleController 失败: ${t.message}")
        }
    }

    /**
     * 2. 拦截 AppOpsService，解除 GMS 及所有被 GMS 唤醒应用的自启动限制
     */
    private fun hookAppOpsService(classLoader: ClassLoader) {
        try {
            val clazz = classLoader.loadClass("com.android.server.appop.AppOpsService")
            for (m in clazz.declaredMethods) {
                if (m.returnType == Int::class.javaPrimitiveType &&
                    (m.name == "checkOperation" || m.name == "noteOperation")
                ) {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val args = chain.args
                        val code = args.getOrNull(0) as? Int
                        val pkgName = args.getOrNull(2) as? String

                        // 放行 GMS 自身的自启动
                        if (pkgName == GMS_PKG && (code == OP_AUTO_START || code == 11)) {
                            MODE_ALLOWED
                        } else if (code == OP_AUTO_START) {
                            // 当检查其他应用（如 Telegram）的自启动时，放行自启动
                            MODE_ALLOWED
                        } else {
                            chain.proceed()
                        }
                    }
                }
            }
            log(Log.INFO, TAG, "已安全 Hook AppOpsService 自启动放行逻辑")
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "Hook AppOpsService 失败: ${t.message}")
        }
    }

    /**
     * 3. 拦截 BroadcastQueue / BroadcastController，解除 Stopped 应用拦截
     */
    private fun hookBroadcastQueue(classLoader: ClassLoader) {
        val targets = listOf(
            "com.android.server.am.BroadcastQueueModernImpl" to "enqueueBroadcastLocked",
            "com.android.server.am.BroadcastController" to "broadcastIntentLocked"
        )

        for ((className, methodName) in targets) {
            try {
                val clazz = classLoader.loadClass(className)
                for (m in clazz.declaredMethods) {
                    if (m.name == methodName) {
                        hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                            var isFcm = false
                            for (arg in chain.args) {
                                if (arg is Intent) {
                                    val action = arg.action
                                    if (action == "com.google.android.c2dm.intent.RECEIVE" ||
                                        action == "com.google.android.c2dm.intent.REGISTRATION"
                                    ) {
                                        isFcm = true
                                        var flags = arg.flags
                                        flags = flags or FLAG_RECEIVER_INCLUDE_STOPPED_PACKAGES
                                        flags = flags and FLAG_RECEIVER_EXCLUDE_STOPPED_PACKAGES.inv()
                                        arg.flags = flags
                                        log(Log.INFO, TAG, "已为 FCM 广播注入穿透标记: $action")
                                    }
                                    break
                                }
                            }
                            if (isFcm) {
                                // 核心黑科技：当 appOp 为 -1 时，改写为 11 (OP_VIBRATE / 系统放行操作码)
                                for (i in chain.args.indices) {
                                    val v = chain.args[i]
                                    if (v is Int && v == -1) {
                                        chain.args[i] = 11
                                        log(Log.INFO, TAG, "改写 FCM broadcastIntentLocked appOp: -1 -> 11")
                                    }
                                }
                            }
                            chain.proceed()
                        }
                        log(Log.INFO, TAG, "已安全 Hook $className.$methodName 广播穿透与 appOp 改写")
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 4. 关键：拦截小米 BroadcastQueueModernStubImpl.shouldStopBroadcastDispatch
     * 彻底阻止小米框架在派发广播时掐断 FCM 消息！
     */
    private fun hookXiaomiBroadcastStub(classLoader: ClassLoader) {
        try {
            val stubClass = classLoader.loadClass("com.android.server.am.BroadcastQueueModernStubImpl")
            for (m in stubClass.declaredMethods) {
                if (m.name == "shouldStopBroadcastDispatch" && m.returnType == Boolean::class.javaPrimitiveType) {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        // 检查参数中是否包含 FCM 广播或者直接拦截
                        var isFcm = false
                        for (arg in chain.args) {
                            if (arg != null) {
                                val str = arg.toString()
                                if (str.contains("c2dm.intent.RECEIVE") ||
                                    str.contains("c2dm.intent.REGISTRATION") ||
                                    str.contains("com.google.android.gms")
                                ) {
                                    isFcm = true
                                    break
                                }
                            }
                        }
                        if (isFcm) {
                            log(Log.INFO, TAG, "阻止小米拦截 FCM 广播分发 -> 放行!")
                            false // 绝不停止分发！
                        } else {
                            chain.proceed()
                        }
                    }
                    log(Log.INFO, TAG, "成功 Hook BroadcastQueueModernStubImpl.shouldStopBroadcastDispatch")
                }
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "未找到 BroadcastQueueModernStubImpl: ${t.message}")
        }
    }

    /**
     * 5. 系统启动后执行特权命令，并监听/保持 MILLET_NO_RESTRICT_APP 白名单
     */
    private fun hookSystemReady(classLoader: ClassLoader) {
        try {
            val amsClass = classLoader.loadClass("com.android.server.am.ActivityManagerService")
            for (m in amsClass.declaredMethods) {
                if (m.name == "systemReady") {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val res = chain.proceed()
                        val amsInstance = chain.thisObject
                        Thread {
                            try {
                                Thread.sleep(6000)
                                executePrivilegedCommands()
                                registerMilletObserver(amsInstance)
                            } catch (e: Throwable) {
                                log(Log.WARN, TAG, "后台特权执行线程异常: ${e.message}")
                            }
                        }.apply {
                            name = "HyperFCMFix-Init"
                            isDaemon = true
                            start()
                        }
                        res
                    }
                    log(Log.INFO, TAG, "已安全 Hook ActivityManagerService.systemReady")
                    break
                }
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "Hook systemReady 失败: ${t.message}")
        }
    }

    private fun registerMilletObserver(amsInstance: Any?) {
        try {
            if (amsInstance == null) return
            val contextField = amsInstance.javaClass.getDeclaredField("mContext")
            contextField.isAccessible = true
            val context = contextField.get(amsInstance) as? Context ?: return
            val resolver = context.contentResolver

            ensureGmsInMillet(resolver)

            val uri = Settings.System.getUriFor(SETTING_MILLET)
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    ensureGmsInMillet(resolver)
                }
            }
            resolver.registerContentObserver(uri, false, observer)
            log(Log.INFO, TAG, "成功注册 MILLET_NO_RESTRICT_APP 动态保活监听器")
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "注册 MILLET 监听器失败: ${e.message}")
        }
    }

    private fun ensureGmsInMillet(resolver: ContentResolver) {
        try {
            val current = Settings.System.getString(resolver, SETTING_MILLET) ?: ""
            val list = current.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (!list.contains(GMS_PKG)) {
                list.add(GMS_PKG)
                val updated = list.joinToString(",")
                Settings.System.putString(resolver, SETTING_MILLET, updated)
                log(Log.INFO, TAG, "MILLET_NO_RESTRICT_APP 自动补齐 GMS -> $updated")
            }
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "ensureGmsInMillet 异常: ${e.message}")
        }
    }

    private fun executePrivilegedCommands() {
        val commands = listOf(
            "cmd deviceidle whitelist +$GMS_PKG",
            "cmd appops set $GMS_PKG AUTO_START allow",
            "cmd appops set $GMS_PKG BOOT_COMPLETED allow",
            "dumpsys greezer IM GMS disable",
            "dumpsys greezer LM add $GMS_PKG"
        )
        for (cmd in commands) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                val exitCode = process.waitFor()
                log(Log.INFO, TAG, "自动执行指令: [$cmd], 状态码: $exitCode")
            } catch (e: Throwable) {
                log(Log.WARN, TAG, "执行指令失败: [$cmd], 错误: ${e.message}")
            }
        }
    }
}
