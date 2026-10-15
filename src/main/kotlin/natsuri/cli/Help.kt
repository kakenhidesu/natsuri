package natsuri.cli

object Help {

    const val TAGLINE = "natsuri — A launcher and distribution manager for natsu."

    fun version(): String = buildString {
        val v = Help::class.java.`package`?.implementationVersion ?: "(开发版本)"
        appendLine("natsuri $v")
        appendLine(TAGLINE)
        appendLine("运行于 Java ${System.getProperty("java.version")} @ ${System.getProperty("java.home")}")
        append("平台 ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    }

    fun usage(): String = """
        $TAGLINE

        用法:
          natsuri [全局选项] [命令] [命令选项] [-- 传给 natsu 的参数]

        不带命令时 = natsuri run。

        命令:
          run                        启动当前选定的 natsu 版本 (默认命令)
          install                    安装一份发行
          versions                   列出已安装的版本 (加 --remote 一并列出源上的)
          update [--check]           检查 / 执行更新
          use <版本>                  切换到某个已安装版本
          pin <版本>                  锁定版本, 普通更新不再越过它
          unpin                      解除锁定
          rollback                   回滚到上一个版本 (只回滚程序与 classpath)
          doctor [--deep]            体检; 只诊断, 不改配置、不登录
          plugins list               列出 plugins/ 里的插件
          plugins install <jar|URL>  装一个插件文件
          plugins remove <标识>       删掉一个插件 jar (保留它的 data/ 与 config/)
          config show                打印当前生效的 natsuri.json
          config set <键> <值>        改一项配置
          manifest generate ...      [维护者] 从真实发行产物生成清单
          manifest index ...         [维护者] 生成 / 更新版本索引

        全局选项:
          --dir <路径>     指定 natsuri 的运行目录 (默认: natsuri.jar 所在目录)
          --verbose, -v   打印调试信息
          --quiet         只打印警告与错误
          --help, -h      显示帮助
          --version       显示版本

        run:
          natsuri run [-- <natsu 参数…>]
            `--` 之后的内容**原样**交给 natsu.console.MainKt, natsuri 不作任何解释。
            例: natsuri run -- --no-console

        install:
          natsuri install --manifest <本地路径或 https 地址>
          natsuri install --index <索引地址> [--channel stable|preview]
          natsuri install <已安装版本>            # 等价于 use
          选项:
            --no-use     只安装, 不切换成当前版本
            --force      即使版本被 pin 住也照装 (仍然只影响这一次)

        update:
          natsuri update --check     只查, 不改任何状态
          natsuri update             下载、校验、安装并切换; 失败时原版本不受影响
          选项:
            --channel <c>  临时改用别的频道
            --force        越过 pin (需要显式给出)

        plugins install:
          natsuri plugins install <本地 jar 或 https 地址> [--sha256 <摘要>] [--force]
            支持 .jar 与 .mirai2.jar。远程安装建议给 --sha256。

        目录布局 (运行目录):
          natsuri.jar / natsuri / natsuri.cmd   启动器自己
          natsuri.json                          启动器配置
          .natsuri/                             已安装版本、下载缓存、状态与锁
          natsu.yml                             natsu 的配置 —— 由 Console 生成与管理, natsuri 不动它
          plugins/ config/ data/ bots/ logs/    natsu 的业务目录 —— 由 Console 按需创建, natsuri 不动它们

        第一次使用:
          1. 装好 Java 21 或更高版本
          2. natsuri install --manifest <清单地址>
          3. natsuri            # 首次启动由 Console 生成 natsu.yml 模板后正常结束
          4. 编辑 natsu.yml, 把插件放进 plugins/
          5. natsuri            # 正式运行
    """.trimIndent()
}
