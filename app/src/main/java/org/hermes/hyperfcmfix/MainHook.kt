package org.hermes.hyperfcmfix

import android.app.AppOpsManager
import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.concurrent.thread

class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "HyperFCMFix"
        const val GMS_PKG = "com.google.android.gms"
        const val ACTION_RECEIVE = "com.google.android.c2dm.intent.RECEIVE"
        const val FLAG_RECEIVER_INCLUDE_STOPPED_PACKAGES = 0x00000020
        const val FLAG_RECEIVER_EXCLUDE_STOPPED_PACKAGES = 0x00000010
        const val OP_AUTO_START = 10008 // HyperOS 特有自启动 Op Code
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> {
                hookSystemServer(lpparam)
                hookDeviceIdleAndAppOps(lpparam)
                registerAutoSetup(lpparam)
            }
            "com.miui.powerkeeper" -> {
                hookPowerKeeper(lpparam)
            }
        }
    }

    /**
     * 在 system_server 就绪后，自动执行底层特权配置
     */
    private fun registerAutoSetup(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val amsClass = XposedHelpers.findClassIfExists(
                "com.android.server.am.ActivityManagerService",
                lpparam.classLoader
            ) ?: return

            XposedBridge.hookAllMethods(amsClass, "systemReady", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    XposedBridge.log("[$TAG] system_server 就绪，启动后台自动配置线程...")
                    thread {
                        try {
                            Thread.sleep(8000)

                            val autoCommands = arrayOf(
                                "cmd deviceidle whitelist +$GMS_PKG",
                                "cmd appops set $GMS_PKG AUTO_START allow",
                                "cmd appops set $GMS_PKG BOOT_COMPLETED allow",
                                "dumpsys greezer IM GMS disable",
                                "dumpsys greezer LM add $GMS_PKG"
                            )

                            for (cmd in autoCommands) {
                                try {
                                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                                    val exitCode = process.waitFor()
                                    XposedBridge.log("[$TAG] 自动执行: [$cmd], 状态码: $exitCode")
                                } catch (cmdErr: Throwable) {
                                    XposedBridge.log("[$TAG] 执行失败: [$cmd], 原因: ${cmdErr.message}")
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] AutoSetup 异常: ${t.message}")
                        }
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 注册 AutoSetup 失败: ${t.message}")
        }
    }

    /**
     * 运行时拦截：DeviceIdle 与 AppOps（防止命令被系统动态覆盖）
     */
    private fun hookDeviceIdleAndAppOps(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // 原生电池白名单
        try {
            val deviceIdleController = XposedHelpers.findClassIfExists(
                "com.android.server.DeviceIdleController",
                classLoader
            )
            if (deviceIdleController != null) {
                val whitelistMethods = arrayOf("isPowerSaveWhitelistApp", "isPowerSaveWhitelistExceptIdleApp")
                for (method in whitelistMethods) {
                    XposedBridge.hookAllMethods(deviceIdleController, method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (param.args.getOrNull(0) == GMS_PKG) {
                                param.result = true
                            }
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 自启动与唤醒鉴权
        try {
            val appOpsService = XposedHelpers.findClassIfExists(
                "com.android.server.appop.AppOpsService",
                classLoader
            )
            if (appOpsService != null) {
                val opMethods = arrayOf("checkOperation", "noteOperation")
                for (method in opMethods) {
                    XposedBridge.hookAllMethods(appOpsService, method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val code = param.args.getOrNull(0) as? Int ?: return
                            val pkg = param.args.getOrNull(2) as? String
                            if (pkg == GMS_PKG) {
                                if (code == OP_AUTO_START || code == AppOpsManager.OP_BOOT_COMPLETED) {
                                    param.result = AppOpsManager.MODE_ALLOWED
                                }
                            }
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 运行时拦截：Greezer 冻结与 Stopped 广播限制
     */
    private fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // 拦截 Greezer 快速冻结 GMS
        try {
            val greezeServiceClass = XposedHelpers.findClassIfExists(
                "com.android.server.am.GreezeManagerService",
                classLoader
            ) ?: XposedHelpers.findClassIfExists(
                "miui.process.GreezeManagerInternal",
                classLoader
            )
            if (greezeServiceClass != null) {
                val freezeMethods = arrayOf("freezeProcess", "freezeUids", "isPkgNeedGreeze", "needFreeze")
                for (methodName in freezeMethods) {
                    XposedBridge.hookAllMethods(greezeServiceClass, methodName, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val arg0 = param.args.getOrNull(0)
                            if ((arg0 is String && arg0 == GMS_PKG) || (arg0 is List<*> && arg0.contains(GMS_PKG))) {
                                param.result = false
                            }
                        }
                    })
                }
            }
        } catch (_: Throwable) {}

        // 解除 Stopped 状态广播限制
        try {
            val broadcastQueueClass = XposedHelpers.findClassIfExists(
                "com.android.server.am.BroadcastQueueModernImpl",
                classLoader
            ) ?: XposedHelpers.findClassIfExists(
                "com.android.server.am.BroadcastQueue",
                classLoader
            )
            if (broadcastQueueClass != null) {
                val enqueueMethods = arrayOf("enqueueBroadcastLocked", "enqueueBroadcast")
                for (method in enqueueMethods) {
                    XposedBridge.hookAllMethods(broadcastQueueClass, method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            for (arg in param.args) {
                                if (arg is Intent && arg.action == ACTION_RECEIVE) {
                                    arg.flags = (arg.flags or FLAG_RECEIVER_INCLUDE_STOPPED_PACKAGES) and FLAG_RECEIVER_EXCLUDE_STOPPED_PACKAGES.inv()
                                    break
                                }
                            }
                        }
                    })
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * PowerKeeper 拦截：强制省电策略无限制 + 动态维护 MILLET_NO_RESTRICT_APP
     */
    private fun hookPowerKeeper(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        try {
            val policyClasses = arrayOf(
                "com.miui.powerkeeper.millet.ActiveStateController",
                "com.miui.powerkeeper.statemachine.PowerStateMachine",
                "com.miui.powerkeeper.utils.RuleUtils"
            )
            for (clsName in policyClasses) {
                val clazz = XposedHelpers.findClassIfExists(clsName, classLoader) ?: continue
                val checkMethods = arrayOf("getAppBgControl", "getAppStrategy", "getPolicy")
                for (m in checkMethods) {
                    try {
                        XposedBridge.hookAllMethods(clazz, m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val pkg = param.args.firstOrNull { it is String && it == GMS_PKG }
                                if (pkg != null) {
                                    param.result = if (param.method.toString().contains("String")) "noRestrict" else 0
                                }
                            }
                        })
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}

        try {
            val activeStateController = XposedHelpers.findClassIfExists(
                "com.miui.powerkeeper.millet.ActiveStateController",
                classLoader
            )
            if (activeStateController != null) {
                XposedBridge.hookAllMethods(activeStateController, "dealNoRestrictApp", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val context = XposedHelpers.callStaticMethod(
                                XposedHelpers.findClass("android.app.ActivityThread", classLoader),
                                "currentApplication"
                            ) as? android.content.Context ?: return

                            val cr = context.contentResolver
                            val currentList = android.provider.Settings.System.getString(cr, "MILLET_NO_RESTRICT_APP") ?: ""
                            if (!currentList.contains(GMS_PKG)) {
                                val newList = if (currentList.isEmpty()) GMS_PKG else "$currentList,$GMS_PKG"
                                android.provider.Settings.System.putString(cr, "MILLET_NO_RESTRICT_APP", newList)
                                XposedBridge.log("[$TAG] 已在 MILLET_NO_RESTRICT_APP 补全 GMS")
                            }
                        } catch (_: Throwable) {}
                    }
                })
            }
        } catch (_: Throwable) {}
    }
}
