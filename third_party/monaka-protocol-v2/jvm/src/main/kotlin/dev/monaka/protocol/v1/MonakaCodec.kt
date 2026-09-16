package dev.monaka.protocol.v1

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.*

enum class ErrorCode {
    MalformedJson, InvalidUtf8, DuplicateKey, TooLarge, UnsupportedVersion,
    UnsupportedMessage, UnsupportedValue, MissingField, InvalidType,
    OutOfRange, InvalidQuaternion, InconsistentValidity
}
sealed interface DecodeResult {
    data class Success(val value: Envelope) : DecodeResult
    data class Failure(val code: ErrorCode, val message: String) : DecodeResult
}
sealed interface EncodeResult {
    data class Success(val value: ByteArray) : EncodeResult
    data class Failure(val code: ErrorCode, val message: String) : EncodeResult
}
internal val gson: Gson = GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues().disableHtmlEscaping().create()
private class Invalid(val code: ErrorCode, override val message: String) : RuntimeException(message)
private fun need(ok: Boolean, code: ErrorCode, path: String) { if (!ok) throw Invalid(code,path) }
private fun fail(code: ErrorCode, path: String): Nothing = throw Invalid(code,path)
private fun JsonElement.number() = isJsonPrimitive && asJsonPrimitive.isNumber
private fun JsonElement.string() = isJsonPrimitive && asJsonPrimitive.isString
private fun JsonElement.bool() = isJsonPrimitive && asJsonPrimitive.isBoolean
private fun validString(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val c = s[i++]
        if (Character.isHighSurrogate(c)) {
            if (i == s.length || !Character.isLowSurrogate(s[i++])) return false
        } else if (Character.isLowSurrogate(c)) return false
    }
    return true
}
private fun parse(r: JsonReader, depth: Int = 0): JsonElement = when(r.peek()) {
    JsonToken.BEGIN_OBJECT -> {
        need(depth < 16,ErrorCode.MalformedJson,"depth exceeds 16")
        r.beginObject()
        val obj = JsonObject()
        while(r.hasNext()) {
            val key = r.nextName()
            need(validString(key),ErrorCode.MalformedJson,"unpaired surrogate")
            need(!obj.has(key),ErrorCode.DuplicateKey,"duplicate object key")
            obj.add(key,parse(r,depth+1))
        }
        r.endObject(); obj
    }
    JsonToken.BEGIN_ARRAY -> {
        need(depth < 16,ErrorCode.MalformedJson,"depth exceeds 16")
        r.beginArray(); val a = JsonArray()
        while(r.hasNext()) a.add(parse(r,depth+1))
        r.endArray(); a
    }
    JsonToken.STRING -> {
        val s = r.nextString(); need(validString(s),ErrorCode.MalformedJson,"unpaired surrogate"); JsonPrimitive(s)
    }
    JsonToken.NUMBER -> {
        val raw = r.nextString()
        need(raw.toDouble().isFinite(),ErrorCode.OutOfRange,"nonfinite number")
        // Preserve the source decimal instead of routing U63 or integer parsing via Double.
        JsonPrimitive(java.math.BigDecimal(raw))
    }
    JsonToken.BOOLEAN -> JsonPrimitive(r.nextBoolean())
    JsonToken.NULL -> { r.nextNull(); JsonNull.INSTANCE }
    else -> fail(ErrorCode.MalformedJson,"unexpected JSON token")
}
private fun validate(v: JsonElement, s: JsonObject, path: String) {
    if(s.has("\$ref")) {
        val name = s["\$ref"].asString.substring(8)
        validate(v,schema["\$defs"].asJsonObject[name].asJsonObject,path)
        if(name == "U63") need(v.asString.toLongOrNull()!=null,ErrorCode.OutOfRange,path)
        if(name == "Id") need(v.asString.toByteArray(Charsets.UTF_8).size<=96,ErrorCode.OutOfRange,path)
        return
    }
    if(s.has("anyOf")) { if(v.isJsonNull) return; validate(v,s["anyOf"].asJsonArray[0].asJsonObject,path); return }
    val type = s["type"].asString
    need(when(type) {
        "object" -> v.isJsonObject; "array" -> v.isJsonArray; "string" -> v.string()
        "boolean" -> v.bool(); "null" -> v.isJsonNull; else -> v.number()
    },ErrorCode.InvalidType,path)
    when(type) {
        "object" -> {
            for(key in s["required"].asJsonArray) need(v.asJsonObject.has(key.asString),ErrorCode.MissingField,"$path.${key.asString}")
            for((key,sub) in s["properties"].asJsonObject.entrySet())
                if(v.asJsonObject.has(key)) validate(v.asJsonObject[key],sub.asJsonObject,"$path.$key")
        }
        "array" -> {
            val a = v.asJsonArray
            if(s.has("minItems")) need(a.size()>=s["minItems"].asInt,ErrorCode.OutOfRange,path)
            if(s.has("maxItems")) need(a.size()<=s["maxItems"].asInt,ErrorCode.OutOfRange,path)
            for(x in a) validate(x,s["items"].asJsonObject,"$path[]")
            if(s.has("uniqueItems")) need(a.toList().distinct().size==a.size(),ErrorCode.InconsistentValidity,path)
        }
        "string" -> {
            val x = v.asString
            need(validString(x),ErrorCode.InvalidUtf8,path)
            val length = x.codePointCount(0,x.length)
            if(s.has("minLength")) need(length>=s["minLength"].asInt,ErrorCode.OutOfRange,path)
            if(s.has("maxLength")) need(length<=s["maxLength"].asInt,ErrorCode.OutOfRange,path)
            if(s.has("pattern")) need(Regex(s["pattern"].asString).matches(x),ErrorCode.OutOfRange,path)
        }
        "number", "integer" -> {
            val x = v.asDouble
            need(x.isFinite(),ErrorCode.OutOfRange,path)
            if(type=="integer") need(floor(x)==x,ErrorCode.InvalidType,path)
            if(s.has("minimum")) need(x>=s["minimum"].asDouble,ErrorCode.OutOfRange,path)
            if(s.has("maximum")) need(x<=s["maximum"].asDouble,ErrorCode.OutOfRange,path)
        }
    }
    if(s.has("enum")) need(s["enum"].asJsonArray.any { it==v },ErrorCode.UnsupportedValue,path)
    if(s.has("const")) need(s["const"]==v,ErrorCode.UnsupportedValue,path)
}
private fun dispatch(v: JsonElement): String {
    need(v.isJsonObject,ErrorCode.InvalidType,"envelope")
    val j = v.asJsonObject
    for(k in listOf("protocol","type","version")) need(j.has(k),ErrorCode.MissingField,k)
    need(j["protocol"].string() && j["type"].string() && j["version"].isJsonObject,ErrorCode.InvalidType,"dispatch")
    val ver = j["version"].asJsonObject
    need(ver.has("major"),ErrorCode.MissingField,"version.major")
    need(ver["major"].number(),ErrorCode.InvalidType,"version.major")
    val major = ver["major"].asDouble
    need(major.isFinite() && floor(major)==major,ErrorCode.InvalidType,"version.major")
    need(major>=0 && major<=65535,ErrorCode.OutOfRange,"version.major")
    need(major==1.0,ErrorCode.UnsupportedVersion,"version.major")
    return when(j["protocol"].asString to j["type"].asString) {
        "monaka.observation" to "pose" -> "TrackerObservation"
        "monaka.observation" to "device_state" -> "ObservationDeviceState"
        "monaka.tracking" to "pose" -> "MtpPose"
        "monaka.tracking" to "tracker_state" -> "MtpTrackerState"
        else -> fail(ErrorCode.UnsupportedMessage,"protocol/type")
    }
}
private fun semantics(j: JsonObject) {
    need(j["timestamp_ns"].asString.toLong()<=j["sent_at_ns"].asString.toLong(),ErrorCode.OutOfRange,"timestamp_ns > sent_at_ns")
    val caps = j["capabilities"].asJsonArray.map { it.asString }
    if(j.has("battery") && !j["battery"].isJsonNull) {
        val b = j["battery"].asJsonObject
        need(b["timestamp_ns"].asString.toLong()<=j["sent_at_ns"].asString.toLong(),ErrorCode.OutOfRange,"battery timestamp > sent_at_ns")
        for((f,c) in listOf("fraction" to "battery_fraction","charging" to "charging"))
            if(!b[f].isJsonNull) need(c in caps,ErrorCode.InconsistentValidity,c)
    }
    if(j["type"].asString!="pose") return
    val valid = j["validity"].asJsonObject
    val p = valid["position"].asBoolean; val o = valid["orientation"].asBoolean
    for((n,b) in listOf("position" to p,"orientation" to o))
        if(b) need(!j[n].isJsonNull && n in caps,ErrorCode.InconsistentValidity,n)
    for(n in listOf("linear_velocity","angular_velocity","linear_acceleration"))
        if(j.has(n) && !j[n].isJsonNull) need(n in caps,ErrorCode.InconsistentValidity,n)
    if(o) {
        val norm = j["orientation"].asJsonArray.fold(0.0) { a,x -> hypot(a,x.asDouble) }
        need(if(j["protocol"].asString=="monaka.tracking") abs(norm-1)<=1e-5 else norm>=0.5 && norm<=1.5,
            ErrorCode.InvalidQuaternion,"orientation norm")
    }
    if(j.has("orientation_evidence")) need(if(o) j["orientation_evidence"].asString!="none" else j["orientation_evidence"].asString=="none",ErrorCode.InconsistentValidity,"orientation_evidence")
    need(when(j["tracking_state"].asString) { "tracked" -> p&&o; "degraded" -> p||o; else -> !p&&!o },ErrorCode.InconsistentValidity,"tracking_state")
    if(j.has("confidence")) for((n,b) in listOf("position" to p,"orientation" to o)) {
        val c = j["confidence"].asJsonObject[n].asDouble
        need(if(b) c>0 else c==0.0,ErrorCode.InconsistentValidity,"confidence.$n")
    }
}
private fun finiteTree(j: JsonElement) {
    if(j.number()) need(j.asDouble.isFinite(),ErrorCode.OutOfRange,"nonfinite number")
    if(j.isJsonArray) j.asJsonArray.forEach { finiteTree(it) }
    if(j.isJsonObject) j.asJsonObject.entrySet().forEach { finiteTree(it.value) }
}
// Sorting is for reproducible encoding only; decoding is order independent.
private fun sorted(j: JsonElement): JsonElement = when {
    j.isJsonObject -> JsonObject().also { out -> j.asJsonObject.entrySet().sortedBy { it.key }.forEach { out.add(it.key,sorted(it.value)) } }
    j.isJsonArray -> JsonArray().also { out -> j.asJsonArray.forEach { out.add(sorted(it)) } }
    else -> j
}
object MonakaCodec {
    @JvmStatic fun decodeEnvelope(data: ByteArray): DecodeResult = try {
        need(data.size<=4096,ErrorCode.TooLarge,"datagram exceeds 4096 bytes")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        } catch(e: java.nio.charset.CharacterCodingException) { fail(ErrorCode.InvalidUtf8,"invalid UTF-8") }
        need(!text.startsWith('\uFEFF'),ErrorCode.MalformedJson,"BOM")
        val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
        val v = reader.use { val x=parse(it); need(it.peek()==JsonToken.END_DOCUMENT,ErrorCode.MalformedJson,"trailing input"); x }
        val name = dispatch(v); val j = v.asJsonObject
        validate(j,schema["\$defs"].asJsonObject[name].asJsonObject,"envelope"); semantics(j)
        DecodeResult.Success(when(name) {
            "TrackerObservation" -> readTrackerObservation(j)
            "ObservationDeviceState" -> readObservationDeviceState(j)
            "MtpPose" -> readMtpPose(j)
            else -> readMtpTrackerState(j)
        })
    } catch(e: Invalid) { DecodeResult.Failure(e.code,e.message) }
      catch(e: Exception) { DecodeResult.Failure(ErrorCode.MalformedJson,e.message ?: "malformed JSON") }

    @JvmStatic fun encodeEnvelope(value: Envelope): EncodeResult = try {
        val j = when(value) {
            is TrackerObservation -> value.toJson(); is ObservationDeviceState -> value.toJson()
            is MtpPose -> value.toJson(); is MtpTrackerState -> value.toJson()
        }
        finiteTree(j)
        val name = dispatch(j)
        validate(j,schema["\$defs"].asJsonObject[name].asJsonObject,"envelope"); semantics(j)
        j["version"].asJsonObject.addProperty("minor",0)
        val bytes = gson.toJson(sorted(j)).toByteArray(Charsets.UTF_8)
        when(val r = decodeEnvelope(bytes)) {
            is DecodeResult.Failure -> EncodeResult.Failure(r.code,r.message)
            is DecodeResult.Success -> EncodeResult.Success(bytes)
        }
    } catch(e: Invalid) { EncodeResult.Failure(e.code,e.message) }
      catch(e: Exception) { EncodeResult.Failure(ErrorCode.MalformedJson,e.message ?: "encode failed") }
}
