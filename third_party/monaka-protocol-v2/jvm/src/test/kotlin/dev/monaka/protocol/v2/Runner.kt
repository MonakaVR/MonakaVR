package dev.monaka.protocol.v2
import java.io.File
import java.lang.management.ManagementFactory
object Runner {
    @JvmStatic fun main(args: Array<String>) {
        val bytes = File(args[0]).readBytes()
        val decoded = MonakaCodec.decodeEnvelope(bytes)
        if(decoded is DecodeResult.Failure) { println("ERROR:${decoded.code}"); return }
        val model = (decoded as DecodeResult.Success).value
        if(args.getOrNull(1)=="--version-minor") {
            println((model as TrackerObservation).version.minor); return
        }
        if(args.getOrNull(1)=="--self-test") {
            val pose = model as TrackerObservation
            for(x in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
                val r = MonakaCodec.encodeEnvelope(pose.copy(position=listOf(x,0.0,0.0)))
                check(r is EncodeResult.Failure && r.code==ErrorCode.OutOfRange)
            }
            val bad = MonakaCodec.encodeEnvelope(pose.copy(source_id="\uD800"))
            check(bad is EncodeResult.Failure && bad.code==ErrorCode.InvalidUtf8)
            check(MonakaCodec.encodeEnvelope(pose.copy(sequence=-1)) is EncodeResult.Failure)
            println("PASS JVM nonfinite model, invalid UTF-16, negative U63 checks"); return
        }
        if(args.getOrNull(1)=="--bench") {
            val bean=ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            bean.isThreadAllocatedMemoryEnabled=true
            val id=Thread.currentThread().id
            repeat(1000) { MonakaCodec.decodeEnvelope(bytes); MonakaCodec.encodeEnvelope(model) }
            for(encode in listOf(false,true)) {
                val before=bean.getThreadAllocatedBytes(id)
                val start=System.nanoTime()
                val cpuStart=bean.currentThreadCpuTime
                repeat(1000) { if(encode) check(MonakaCodec.encodeEnvelope(model) is EncodeResult.Success) else check(MonakaCodec.decodeEnvelope(bytes) is DecodeResult.Success) }
                val elapsed=System.nanoTime()-start
                val cpu=bean.currentThreadCpuTime-cpuStart
                val allocated=bean.getThreadAllocatedBytes(id)-before
                val payload=(MonakaCodec.encodeEnvelope(model) as EncodeResult.Success).value.size
                println("${if(encode) "encode" else "decode"} ns/op=${elapsed/1000.0} thread_cpu_ns/op=${cpu/1000.0} allocated_bytes/op=${allocated/1000.0} payload_bytes=$payload")
            }
            return
        }
        when(val r=MonakaCodec.encodeEnvelope(model)) {
            is EncodeResult.Success -> println(r.value.toString(Charsets.UTF_8))
            is EncodeResult.Failure -> println("ERROR:${r.code}")
        }
    }
}
