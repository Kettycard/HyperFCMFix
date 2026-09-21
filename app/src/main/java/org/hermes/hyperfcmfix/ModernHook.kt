package org.hermes.hyperfcmfix

import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class ModernHook : XposedModule() {

    companion object {
        private const val TAG = "HyperFCMFix"
        private const val GMS_PKG = "com.google.android.gms"
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
        hookSystemReady(classLoader)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.packageName
        val classLoader = param.defaultClassLoader

        if (packageName == "com.miui.powerkeeper") {
            hookPowerKeeper(classLoader)
        }
    }

    /**
     * 1. 拦截 DeviceIdleController，强制 GMS 进入白名单
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
     * 2. 拦截 AppOpsService，解除 GMS 自启动限制
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

                        if (pkgName == GMS_PKG && (code == OP_AUTO_START || code == 11)) {
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
     * 3. 拦截 BroadcastQueue / BroadcastController，解除 Stopped 限制
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
                            for (arg in chain.args) {
                                if (arg is Intent) {
                                    val action = arg.action
                                    if (action == "com.google.android.c2dm.intent.RECEIVE" ||
                                        action == "com.google.android.c2dm.intent.REGISTRATION"
                                    ) {
                                        var flags = arg.flags
                                        flags = flags or FLAG_RECEIVER_INCLUDE_STOPPED_PACKAGES
                                        flags = flags and FLAG_RECEIVER_EXCLUDE_STOPPED_PACKAGES.inv()
                                        arg.flags = flags
                                    }
                                    break
                                }
                            }
                            chain.proceed()
                        }
                        log(Log.INFO, TAG, "已安全 Hook $className.$methodName 广播穿透")
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * 4. 系统启动后执行特权命令
     */
    private fun hookSystemReady(classLoader: ClassLoader) {
        try {
            val amsClass = classLoader.loadClass("com.android.server.am.ActivityManagerService")
            for (m in amsClass.declaredMethods) {
                if (m.name == "systemReady") {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val res = chain.proceed()
                        Thread {
                            try {
                                Thread.sleep(8000)
                                executePrivilegedCommands()
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

    /**
     * 5. 拦截 PowerKeeper 防止 GMS 被剔除出无限制列表
     */
    private fun hookPowerKeeper(classLoader: ClassLoader) {
        try {
            val clazz = classLoader.loadClass("com.miui.powerkeeper.statemachine.ActiveStateController")
            for (m in clazz.declaredMethods) {
                if (m.name == "dealNoRestrictApp") {
                    hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val res = chain.proceed()
                        try {
                            for (field in clazz.declaredFields) {
                                if (field.name == "MILLET_NO_RESTRICT_APP") {
                                    field.isAccessible = true
                                    val list = field.get(chain.thisObject) as? MutableList<String>
                                    if (list != null && !list.contains(GMS_PKG)) {
                                        list.add(GMS_PKG)
                                    }
                                    break
                                }
                            }
                        } catch (t: Throwable) {
                            log(Log.WARN, TAG, "更新 MILLET_NO_RESTRICT_APP 异常: ${t.message}")
                        }
                        res
                    }
                    log(Log.INFO, TAG, "已安全 Hook ActiveStateController.dealNoRestrictApp")
                    break
                }
            }
        } catch (t: Throwable) {
            log(Log.WARN, TAG, "Hook PowerKeeper 失败: ${t.message}")
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
