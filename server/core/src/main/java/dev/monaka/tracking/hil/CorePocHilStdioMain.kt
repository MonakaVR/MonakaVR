package dev.monaka.tracking.hil

import com.fasterxml.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Opt-in pipe transport for the existing diagnostic control. No source/policy logic. */
object CorePocHilStdioMain {
	fun process(input: InputStream, output: OutputStream, execute: (JsonNode) -> Map<String, Any?>) {
		val partial = ByteArrayOutputStream()
		while (true) {
			val value = input.read()
			if (value < 0) { require(partial.size() == 0) { "Unterminated local IPC request" }; return }
			if (value != 10) {
				require(partial.size() < 1_048_576) { "Local IPC request exceeds 1MiB" }
				partial.write(value); continue
			}
			val request = CorePocHilJson.mapper.readTree(partial.toByteArray()); partial.reset()
			val id = request.required("id").textValue(); require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
			val result = execute(request)
			output.write(CorePocHilJson.mapper.writeValueAsBytes(mapOf("id" to id, "response" to result)))
			output.write(10); output.flush()
			if (request.required("op").textValue() == "shutdown") return
		}
	}
	@JvmStatic fun main(args: Array<String>) {
		val arguments = args.toList()
		fun option(name: String) = arguments.indexOf(name).let { require(it >= 0 && it + 1 < arguments.size); arguments[it + 1] }
		val directory = Path.of(option("--session")).toAbsolutePath().normalize(); Files.createDirectories(directory)
		val bytes = Files.readAllBytes(Path.of(option("--config")))
		val config = CorePocHilJson.config(CorePocHilJson.mapper.readTree(bytes))
		CorePocHilSession(config, "--hil" in arguments).use { session ->
			val control = CorePocHilControl(session, directory)
			Files.createFile(directory.resolve("requests.jsonl")) // Same reused-session refusal as file IPC.
			val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
			val temporary = directory.resolve("ready.json.tmp")
			Files.writeString(temporary, CorePocHilJson.mapper.writeValueAsString(mapOf("status" to session.status(), "configSha256" to hash, "transport" to "stdio")), StandardOpenOption.CREATE_NEW)
			Files.move(temporary, directory.resolve("ready.json"), StandardCopyOption.ATOMIC_MOVE)
			process(System.`in`.buffered(), System.out, control::execute)
		}
	}
}
