package dev.monaka.tracking.desktop

import java.io.File

/** Launch a child JVM from the same JDK as the running Gradle test process. */
internal fun testJvmExecutable(): String {
	val name = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
	val binary = File(System.getProperty("java.home"), "bin/$name")
	check(binary.isFile) { "Test JVM executable is missing: $binary" }
	return binary.absolutePath
}
