package io.github.daranna.sqlitebrowser.adb

import java.io.File
import java.util.concurrent.TimeUnit

/** Calls the `adb` binary. Later we may switch to Android Studio's own device APIs. */
object Adb {

    data class Device(val serial: String, val description: String) {
        override fun toString() = description
    }

    fun findAdb(): File? {
        val sdkDirs = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            System.getProperty("user.home") + "/Library/Android/sdk",
            System.getProperty("user.home") + "/Android/Sdk",
            System.getenv("LOCALAPPDATA")?.let { "$it/Android/Sdk" },
        )
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb"
        sdkDirs.map { File(it, "platform-tools/$exe") }.firstOrNull { it.canExecute() }?.let { return it }
        return System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, exe) }
            .firstOrNull { it.canExecute() }
    }

    fun devices(): List<Device> = run("devices", "-l").lines()
        .drop(1)
        .filter { it.isNotBlank() && " device " in "$it " }
        .map { line ->
            val serial = line.substringBefore(' ')
            val model = Regex("model:(\\S+)").find(line)?.groupValues?.get(1)
            Device(serial, if (model != null) "$model ($serial)" else serial)
        }

    /** Forwards [port] on this machine to the same port on the device. */
    fun forward(serial: String, port: Int) {
        run("-s", serial, "forward", "tcp:$port", "tcp:$port")
    }

    private fun run(vararg args: String): String {
        val adb = findAdb() ?: throw AdbException("adb not found. Set ANDROID_HOME or put adb on PATH.")
        val process = ProcessBuilder(adb.path, *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroy()
            throw AdbException("adb ${args.joinToString(" ")} timed out")
        }
        if (process.exitValue() != 0) throw AdbException(output.trim())
        return output
    }
}

class AdbException(message: String) : Exception(message)
