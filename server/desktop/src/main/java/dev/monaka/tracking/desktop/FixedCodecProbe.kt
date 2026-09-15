package dev.monaka.tracking.desktop

import dev.monaka.protocol.v1.*
import java.io.File

/** Standalone software verification entry point; loads no VRServer or native receiver. */
object FixedCodecProbe {
	@JvmStatic fun main(args: Array<String>) {
		require(args.size == 1)
		when (val decoded = MonakaCodec.decodeEnvelope(File(args[0]).readBytes())) {
			is DecodeResult.Failure -> println("ERROR:${decoded.code}")
			is DecodeResult.Success -> when (val encoded = MonakaCodec.encodeEnvelope(decoded.value)) {
				is EncodeResult.Success -> System.out.write(encoded.value)
				is EncodeResult.Failure -> error("Validated envelope cannot encode: ${encoded.code}")
			}
		}
	}
}
