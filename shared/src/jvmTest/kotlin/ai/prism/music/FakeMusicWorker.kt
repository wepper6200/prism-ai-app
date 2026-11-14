package ai.prism.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FakeMusicWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        val request = Json.parseToJsonElement(readln()).jsonObject
        val prompt = request.getValue("caption").jsonPrimitive.content
        println("{\"event\":\"progress\",\"stage\":\"composing\"}")
        System.out.flush()
        if (prompt == "wait") Thread.sleep(60_000)
        val bytes = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(52).put("WAVEfmt ".toByteArray()).putInt(16)
            putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16)
            put("data".toByteArray()).putInt(16).put(ByteArray(16))
        }.array()
        File(request.getValue("output").jsonPrimitive.content).writeBytes(if (prompt == "corrupt") byteArrayOf(1) else bytes)
        println("{\"event\":\"done\",\"seed\":1}")
    }
}
