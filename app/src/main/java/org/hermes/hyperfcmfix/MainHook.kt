package org.hermes.hyperfcmfix

import android.app.AppOpsManager
import android.content.Intent
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.concurrent.thread

class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "HyperFCMFix"
        const val GMS_PKG = "com.google.android.gms"
        const val ACTION_RECEIVE = "com.google.android.c2dm.intent.RECEIVE"
        const val FLAG_RECEIVER_INCLUDE_STOPPED_PACKAGES = 0x00000020
        const val FLAG_RECEIVER_EXCLUDE_STOPPED_PACKAGES = 0x00000010
        const val OP_AUTO_START = 10008
        const val OP_BOOT_COMPLETED = 48
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
     * 1. 自动执行开机配置 (cmd / dumpsys)
     */
    private fun registerAutoSetup(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val amsClass = XposedHelpers.findClassIfExists(
                "com.android.server.am.ActivityManagerService",
                lpparam.classLoader
            ) ?: return

            XposedBridge.hookAllMethods(amsClass, "systemReady", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    XposedBridge.log("[$TAG] system_server 已就绪，启动后台自动配置线程...")
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
                                    XposedBridge.log("[$TAG] 自动执行指令: [$cmd], 状态码: $exitCode")
                                } catch (cmdErr: Throwable) {
                                    XposedBridge.log("[$TAG] 指令执行失败: [$cmd], 原因: ${cmdErr.message}")
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[$TAG] AutoSetup 线程异常: ${t.message}")
                        }
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] 注册 AutoSetup 失败: ${t.message}")
        }
    }

    /**
     * 2. 安全的 AppOps 与 DeviceIdle Hook（严格匹配方法返回类型，杜绝类型不匹配导致的崩溃）
     */
    private fun hookDeviceIdleAndAppOps(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // 2.1 原生电池优化白名单 Hook
        try {
            val deviceIdleController = XposedHelpers.findClassIfExists(
                "com.android.server.DeviceIdleController",
                classLoader
            )
            if (deviceIdleController != null) {
                val whitelistMethods = arrayOf("isPowerSaveWhitelistApp", "isPowerSaveWhitelistExceptIdleApp")
                for (name in whitelistMethods) {
                    for (m in deviceIdleController.declaredMethods) {
                        if (m.name == name && m.returnType == java.lang.Boolean.TYPE) {
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (param.args.getOrNull(0) == GMS_PKG) {
                                        param.result = true
                                    }
                                }
                            })
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2.2 AppOpsService Hook：严格只 hook 返回 int 的 noteOperation / checkOperation 方法
        try {
            val appOpsService = XposedHelpers.findClassIfExists(
                "com.android.server.appop.AppOpsService",
                classLoader
            )
            if (appOpsService != null) {
                val opMethodNames = arrayOf("checkOperation", "noteOperation")
                for (name in opMethodNames) {
                    for (m in appOpsService.declaredMethods) {
                        if (m.name == name && m.returnType == java.lang.Integer.TYPE) {
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    try {
                                        val code = param.args.getOrNull(0) as? Int ?: return
                                        val pkg = param.args.getOrNull(2) as? String
                                        if (pkg == GMS_PKG) {
                                            if (code == OP_AUTO_START || code == OP_BOOT_COMPLETED) {
                                                // 必须是 int 类型的 MODE_ALLOWED (0)
                                                param.result = AppOpsManager.MODE_ALLOWED
                                            }
                                        }
                                    } catch (_: Throwable) {}
                                }
                            })
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 3. 拦截 Greezer 冻结与 Stopped 广播限制
     */
    private fun hookSystemServer(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // 3.1 拦截 Greezer 冻结
        try {
            val greezeServiceClass = XposedHelpers.findClassIfExists(
                "com.android.server.am.GreezeManagerService",
                classLoader
            ) ?: XposedHelpers.findClassIfExists(
                "miui.process.GreezeManagerInternal",
                classLoader
            )
            if (greezeServiceClass != null) {
                for (m in greezeServiceClass.declaredMethods) {
                    val name = m.name
                    if (name in arrayOf("freezeProcess", "freezeUids", "isPkgNeedGreeze", "needFreeze")) {
                        if (m.returnType == java.lang.Boolean.TYPE) {
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val arg0 = param.args.getOrNull(0)
                                    if ((arg0 is String && arg0 == GMS_PKG) || (arg0 is List<*> && arg0.contains(GMS_PKG))) {
                                        param.result = false
                                    }
                                }
                            })
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 3.2 穿透 Stopped 限制 (通过 hook broadcastIntentLocked，类似 fcmfix 官方成熟方案)
        try {
            val targetClass = XposedHelpers.findClassIfExists(
                "com.android.server.am.BroadcastController",
                classLoader
            ) ?: XposedHelpers.findClassIfExists(
                "com.android.server.am.ActivityManagerService",
                classLoader
            )

            if (targetClass != null) {
                for (m in targetClass.declaredMethods) {
                    if (m.name == "broadcastIntentLocked") {
                        val params = m.parameterTypes
                        // 寻找 Intent 类型的参数位置
                        var intentIndex = -1
                        for (i in params.indices) {
                            if (params[i] == Intent::class.java) {
                                intentIndex = i
                                break
                            }
                        }
                        if (intentIndex != -1) {
                            val finalIndex = intentIndex
                            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val intent = param.args.getOrNull(finalIndex) as? Intent ?: return
                                    if (intent.action == ACTION_RECEIVE) {
                                        intent.flags = (intent.flags or FLAG_RECEIVER_INCLUDE_STOPPED_PACKAGES) and FLAG_RECEIVER_EXCLUDE_STOPPED_PACKAGES.inv()
                                    }
                                }
                            })
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 4. PowerKeeper 动态白名单
     */
    private fun hookPowerKeeper(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        try {
            val activeStateController = XposedHelpers.findClassIfExists(
                "com.miui.powerkeeper.millet.ActiveStateController",
                classLoader
            )
            if (activeStateController != null) {
                for (m in activeStateController.declaredMethods) {
                    if (m.name == "dealNoRestrictApp") {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
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
                }
            }
        } catch (_: Throwable) {}
    }
}
