package dev.monaka.protocol.v2
import java.io.File
object AuthorityModelTests {
    @JvmStatic fun main(args: Array<String>) {
        val model=(MonakaCodec.decodeEnvelope(File(args[0]).readBytes()) as DecodeResult.Success).value as TrustedHmdCommonPose
        for(x in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            for(p in listOf(model.copy(source_position=listOf(x,0.0,0.0)),model.copy(common_position=listOf(x,0.0,0.0)))) {
                val r=MonakaCodec.encodeEnvelope(p)
                check(r is EncodeResult.Failure && r.code==ErrorCode.OutOfRange)
            }
        }
        val negative=MonakaCodec.encodeEnvelope(model.copy(source=model.source.copy(observation_id=-1)))
        check(negative is EncodeResult.Failure && negative.code==ErrorCode.OutOfRange)
        val nonunit=MonakaCodec.encodeEnvelope(model.copy(common_orientation=listOf(0.0,0.0,0.0,2.0)))
        check(nonunit is EncodeResult.Failure && nonunit.code==ErrorCode.InvalidQuaternion)
        val invalidUtf16=MonakaCodec.encodeEnvelope(model.copy(publisher_id="\uD800"))
        check(invalidUtf16 is EncodeResult.Failure && invalidUtf16.code==ErrorCode.InvalidUtf8)
        val minor=MonakaCodec.encodeEnvelope(model.copy(version=Version(2,0)))
        check(minor is EncodeResult.Failure && minor.code==ErrorCode.UnsupportedVersion)
        val good=MonakaCodec.encodeEnvelope(model.copy(version=Version(2,42))) as EncodeResult.Success
        val result=(MonakaCodec.decodeEnvelope(good.value) as DecodeResult.Success).value as TrustedHmdCommonPose
        check(result.version.minor==1)
        println("PASS JVM authority typed model validation")
    }
}
