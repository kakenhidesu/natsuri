package natsuri.launch

import natsuri.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * 让启动器在 Ctrl+C 时**不要抢在子进程前面死掉**。
 *
 * ## 为什么需要它
 *
 * 终端里按 Ctrl+C 时, Windows 把 `CTRL_C_EVENT` 发给控制台上所有进程, Linux 把 `SIGINT` 发给整个
 * 前台进程组 —— natsuri 和 natsu **同时**收到。JVM 默认的处理 (`java.lang.Terminator`) 是
 * `Shutdown.exit(signo + 128)`, 也就是启动器立刻开始退出并把退出码钉死成 130,
 * `main` 里那句"把子进程的退出码传出去"根本没机会跑。
 *
 * 结果是: natsu 明明干干净净地关掉了 (它自己收到了同一个 Ctrl+C, 跑完了关闭钩子, 退了个码),
 * 而调用方看到的却永远是 130, 分不清"用户中断"和"natsu 关闭时出了错"。
 *
 * ## 做法
 *
 * 把 **INT** 的处理接管过来 (只有 INT, 见 [install] 里为什么不碰 TERM), 什么也不做 —— 于是 `waitFor()` 正常返回,
 * 退出码是子进程真实的那个。子进程那一份信号没有被拦截, 它照常自己关。
 *
 * 接管信号也意味着**用户按 Ctrl+C 之后要靠子进程退出才能回到 shell**。所以留了退路:
 * 连按三次就放弃等待, 强制结束子进程并退出。
 *
 * ## 为什么用反射
 *
 * `sun.misc.Signal` 在 `jdk.unsupported` 模块里 —— 它在 JDK 21 上可用, 但它不是标准 API,
 * 未来某天可能被拿掉或需要额外的 `--add-exports`。反射调用让"拿不到就退回默认行为"变成一行代码,
 * 而不是一个编译期的硬依赖。退回默认行为的代价只是退出码变成 130, 不影响任何数据。
 */
object SignalShield {

    private val interrupts = AtomicInteger()

    /**
     * 接管中断信号。
     *
     * @param onGiveUp 连按三次之后调用 (用来强制结束子进程)
     * @return 是否接管成功
     */
    fun install(onGiveUp: () -> Unit): Boolean {
        /*
         * **只接管 INT, 绝不接管 TERM。**
         *
         * INT 是"用户按了 Ctrl+C", 终端会把它同时发给 natsuri 与 natsu, 所以接管它只是让启动器
         * 别抢在子进程前面死掉, 语义没有变。
         *
         * TERM 完全是另一回事: 它是 systemd / docker stop / `kill` 用来结束一个服务的手段,
         * 而且**只发给 natsuri 自己**。把它也吞掉, 结果就是 `systemctl stop` 停不掉、`kill` 没反应,
         * 最后只能 `kill -9` —— 那时 natsu 连关闭钩子都跑不了。
         * 所以 TERM 保持 JVM 默认行为: 关闭钩子照常跑, 那个钩子会等 natsu 退出、超时才强制结束。
         */
        val installed = handle("INT", onGiveUp)
        if (!installed) {
            Log.debug("无法接管中断信号, Ctrl+C 时 natsuri 会以 130 退出 (natsu 自己仍会正常关闭)")
        }
        return installed
    }

    private fun handle(name: String, onGiveUp: () -> Unit): Boolean = runCatching {
        val signalClass = Class.forName("sun.misc.Signal")
        val handlerClass = Class.forName("sun.misc.SignalHandler")
        val signal = signalClass.getConstructor(String::class.java).newInstance(name)
        val handler = Proxy.newProxyInstance(
            SignalShield::class.java.classLoader,
            arrayOf(handlerClass),
        ) { proxy, method, arguments ->
            if (method.name == "handle") {
                val count = interrupts.incrementAndGet()
                when {
                    count == 1 -> System.err.println("\n[INFO] 已收到中断, natsu 正在关闭 —— 再按两次可强制结束。")
                    count >= 3 -> {
                        System.err.println("[WARN] 收到第 $count 次中断, 强制结束 natsu。")
                        runCatching { onGiveUp() }
                    }
                }
                null
            } else {
                // equals / hashCode / toString 用身份语义。
                // JDK 内部会把 handler 放进 map (`Signal.handlers`), 一个 equals 恒返回 false 的代理
                // 会让"取消/替换处理器"这类操作找不到自己。
                when (method.name) {
                    "equals" -> arguments?.getOrNull(0) === proxy
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "natsuri-signal-shield"
                    else -> null
                }
            }
        }
        signalClass.getMethod("handle", signalClass, handlerClass).invoke(null, signal, handler)
        true
    }.getOrElse {
        Log.debug("接管 SIG$name 失败: ${it.message}")
        false
    }
}
