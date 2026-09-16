plugins { kotlin("jvm") version "2.3.10" }
repositories { mavenCentral() }
dependencies { implementation(files("libs/gson-2.11.0.jar")) }
kotlin { jvmToolchain(17) }
tasks.jar {
    archiveFileName.set("monaka-protocol-jvm-0.1.0.jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
