plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "org.natsuri"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

/*
 * natsuri 自己的依赖要**尽量少**, 理由不是洁癖:
 *
 * 1. 它被打成一个 fat jar (natsuri.jar), 每多一个依赖就多一份要合并的 META-INF 与一份要跟着升级的东西;
 * 2. 用户拿到的是 jar + 两个脚本, 不该为了跑启动器再去装什么。
 *
 * 所以 HTTP 用 JDK 21 自带的 java.net.http, ZIP 用 java.util.zip, 命令行解析手写。
 * 只有 JSON 留了 kotlinx-serialization —— 清单与状态文件都是 JSON, 手写解析器不划算也不安全。
 *
 * 另: natsuri 的依赖**不会**进入 Console 的 classpath —— Console 跑在独立子进程里,
 * classpath 完全由发行清单决定 (见 launch/ConsoleLauncher.kt)。
 */
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll("-opt-in=kotlin.io.path.ExperimentalPathApi")
    }
}

// ---------------------------------------------------------------------------------------------------------
// 打包: 一个自带依赖的 natsuri.jar + 两个启动脚本。
// ---------------------------------------------------------------------------------------------------------

/**
 * fat jar。用 `application` 插件的 installDist 也能跑, 但那样用户目录里会多出一个 lib/ 目录和几个
 * kotlin jar —— 而 natsuri 的运行目录同时也是 natsu 的运行目录, 那几个 jar 摆在旁边只会让人以为
 * 它们和 Console 有关系。一个 natsuri.jar 更干净, 也更容易在 README 里说清楚"哪些文件是启动器的"。
 */
val natsuriJar = tasks.register<Jar>("natsuriJar") {
    group = "distribution"
    description = "Build the self-contained natsuri.jar"
    archiveFileName.set("natsuri.jar")
    destinationDirectory.set(layout.buildDirectory.dir("natsuri"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Main-Class" to "natsuri.MainKt",
            "Implementation-Title" to "natsuri",
            "Implementation-Version" to project.version,
            // 多版本 jar 的依赖 (kotlinx-serialization 没有, 但留着以防以后加)
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }

    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { cfg -> cfg.filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        // 签名文件必须去掉: 合并之后签名对不上, JVM 会拒绝加载整个 jar。
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
        // 各依赖自带的 module-info / 许可证目录合并没有意义, 反而会互相覆盖。
        exclude("module-info.class", "META-INF/versions/*/module-info.class")
        exclude("META-INF/maven/**", "META-INF/proguard/**")
    }
}

val natsuriDistDir = layout.buildDirectory.dir("natsuri-dist")

/** 发行目录: 用户解压之后看到的就是这几个文件。 */
val natsuriInstall = tasks.register<Sync>("natsuriInstall") {
    group = "distribution"
    description = "Assemble the natsuri distribution directory"
    into(natsuriDistDir)
    from(natsuriJar)
    from(layout.projectDirectory.dir("src/dist")) {
        // Unix 脚本要可执行位; Windows 上 Gradle 也会把它写进 zip 的条目属性。
        filePermissions { unix("0755") }
        include("natsuri")
    }
    from(layout.projectDirectory.dir("src/dist")) {
        include("natsuri.cmd", "natsuri.json")
    }
    from(layout.projectDirectory.file("README.md"))
    from(layout.projectDirectory.file("LICENSE"))
    from(layout.projectDirectory.dir("docs")) {
        into("docs")
    }
}

val natsuriDistZip = tasks.register<Zip>("natsuriDistZip") {
    group = "distribution"
    description = "Package the natsuri distribution as a zip"
    dependsOn(natsuriInstall)
    archiveFileName.set("natsuri-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(natsuriDistDir) {
        into("natsuri-${project.version}")
    }
}

tasks.named("assemble") {
    dependsOn(natsuriDistZip)
}
