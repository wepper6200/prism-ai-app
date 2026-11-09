package ai.prism.imagegen

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Поддельная программа генерации для тестов протокола: отвечает как native/imagegen/prism_imagegen.cpp.
 * Аргумент — файл журнала полученных команд.
 * Модель с «broken» в пути не загружается, описание «crash» завершает процесс как при нехватке памяти,
 * описание «slow» рисует медленно, чтобы его можно было отменить.
 */
object FakeImageWorker {
    private val cancelled = AtomicBoolean(false)

    @JvmStatic
    fun main(args: Array<String>) {
        val log = File(args[0])
        emit("""{"event":"started"}""")
        var generation: Thread? = null
        while (true) {
            val line = readlnOrNull() ?: break
            log.appendText(line + "\n")
            when {
                "\"load\"" in line -> when {
                    "broken" in line -> emit("""{"event":"error","code":"load_failed","message":"bad file"}""")
                    else -> emit("""{"event":"loaded","device":"CPU"}""")
                }
                "\"generate\"" in line -> {
                    cancelled.set(false)
                    generation = Thread { generate(line) }.apply { start() }
                }
                "\"cancel\"" in line -> cancelled.set(true)
                "\"quit\"" in line -> break
            }
        }
        generation?.join()
    }

    private fun generate(line: String) {
        val output = Regex("\"output\":\"([^\"]+)\"").find(line)!!.groupValues[1]
        val steps = Regex("\"steps\":(\\d+)").find(line)!!.groupValues[1].toInt()
        if ("\"prompt\":\"crash\"" in line) exitProcess(137)
        val slow = "\"prompt\":\"slow\"" in line
        for (step in 1..steps) {
            if (slow) Thread.sleep(300)
            if (cancelled.get()) {
                emit("""{"event":"error","code":"cancelled","message":"cancelled"}""")
                return
            }
            emit("""{"event":"progress","step":$step,"steps":$steps}""")
        }
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, 8, 8)
        g.dispose()
        ImageIO.write(image, "png", File(output))
        emit("""{"event":"done","output":"$output","width":8,"height":8,"seed":7}""")
    }

    @Synchronized
    private fun emit(line: String) {
        println(line)
        System.out.flush()
    }
}
