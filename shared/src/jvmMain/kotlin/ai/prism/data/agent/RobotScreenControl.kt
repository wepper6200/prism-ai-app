package ai.prism.data.agent

import ai.prism.domain.agent.MouseButton
import ai.prism.domain.agent.ScreenCapture
import ai.prism.domain.agent.ScreenControl
import ai.prism.domain.agent.WorkspaceException
import java.awt.AWTException
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Экран, мышь и клавиатура через java.awt.Robot (основной монитор). На время действия окна Prism AI сворачиваются,
 * чтобы не закрывать экран, и после снимка возвращаются — пользователь подтверждает следующее действие.
 */
class RobotScreenControl(private val guardDesktopInput: Boolean = false) : ScreenControl {
    private var desktopBeforeInput: BufferedImage? = null

    override fun forDesktopTask(): ScreenControl = RobotScreenControl(guardDesktopInput = true)
    private val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")

    private val robot: Robot by lazy {
        try {
            Robot().apply { autoDelay = ROBOT_DELAY_MS }
        } catch (e: AWTException) {
            throw WorkspaceException("screen control is not available: ${e.message}", e)
        } catch (e: HeadlessException) {
            throw WorkspaceException("screen control is not available: no screen", e)
        } catch (e: SecurityException) {
            throw WorkspaceException("screen control is not allowed: ${e.message}", e)
        }
    }

    override suspend fun capture(): ScreenCapture = withPrismWindowsHidden { shot() }

    override suspend fun click(x: Int, y: Int, button: MouseButton, double: Boolean): ScreenCapture = withPrismWindowsHidden {
        val bounds = bounds()
        verifyDesktop(bounds, x, y)
        robot.mouseMove(bounds.x + x.coerceIn(0, bounds.width - 1), bounds.y + y.coerceIn(0, bounds.height - 1))
        val mask = when (button) {
            MouseButton.LEFT -> InputEvent.BUTTON1_DOWN_MASK
            MouseButton.MIDDLE -> InputEvent.BUTTON2_DOWN_MASK
            MouseButton.RIGHT -> InputEvent.BUTTON3_DOWN_MASK
        }
        repeat(if (double) 2 else 1) {
            robot.mousePress(mask)
            robot.mouseRelease(mask)
        }
        settle()
        shot()
    }

    override suspend fun scroll(x: Int, y: Int, amount: Int): ScreenCapture = withPrismWindowsHidden {
        val bounds = bounds()
        verifyDesktop(bounds, x, y)
        robot.mouseMove(bounds.x + x.coerceIn(0, bounds.width - 1), bounds.y + y.coerceIn(0, bounds.height - 1))
        robot.mouseWheel(amount.coerceIn(-MAX_SCROLL, MAX_SCROLL))
        settle()
        shot()
    }

    override suspend fun type(text: String): ScreenCapture = withPrismWindowsHidden {
        verifyDesktop(bounds())
        // Через буфер обмена: Robot печатает только клавиши текущей раскладки, а кириллица и символы — нет.
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val previous = runCatching {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as? String else null
        }.getOrNull()
        clipboard.setContents(StringSelection(text), null)
        chord(listOf(if (mac) KeyEvent.VK_META else KeyEvent.VK_CONTROL, KeyEvent.VK_V))
        settle()
        // Прежний текст пользователя возвращается в буфер, когда программа уже вставила наш.
        previous?.let { clipboard.setContents(StringSelection(it), null) }
        shot()
    }

    override suspend fun press(keys: String): ScreenCapture {
        val codes = keys.split('+', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map { name ->
            keyCode(name) ?: throw WorkspaceException("unknown key \"$name\" in \"$keys\" (examples: ctrl+s, alt+tab, enter, win+r, f5)")
        }
        if (codes.isEmpty()) throw WorkspaceException("no keys in \"$keys\"")
        return withPrismWindowsHidden {
            verifyDesktop(bounds())
            chord(codes)
            settle()
            shot()
        }
    }

    /** Нажимает клавиши по порядку и отпускает в обратном (сочетание). */
    private fun chord(codes: List<Int>) {
        codes.forEach { robot.keyPress(it) }
        codes.asReversed().forEach { robot.keyRelease(it) }
    }

    private fun keyCode(name: String): Int? = when (name) {
        "ctrl", "control", "ctl" -> KeyEvent.VK_CONTROL
        "shift" -> KeyEvent.VK_SHIFT
        "alt", "option" -> KeyEvent.VK_ALT
        "win", "windows", "meta", "super", "cmd", "command" -> if (mac) KeyEvent.VK_META else KeyEvent.VK_WINDOWS
        "enter", "return" -> KeyEvent.VK_ENTER
        "tab" -> KeyEvent.VK_TAB
        "esc", "escape" -> KeyEvent.VK_ESCAPE
        "space", "spacebar" -> KeyEvent.VK_SPACE
        "backspace" -> KeyEvent.VK_BACK_SPACE
        "delete", "del" -> KeyEvent.VK_DELETE
        "insert", "ins" -> KeyEvent.VK_INSERT
        "home" -> KeyEvent.VK_HOME
        "end" -> KeyEvent.VK_END
        "pageup", "pgup" -> KeyEvent.VK_PAGE_UP
        "pagedown", "pgdn" -> KeyEvent.VK_PAGE_DOWN
        "up" -> KeyEvent.VK_UP
        "down" -> KeyEvent.VK_DOWN
        "left" -> KeyEvent.VK_LEFT
        "right" -> KeyEvent.VK_RIGHT
        "printscreen", "prtsc" -> KeyEvent.VK_PRINTSCREEN
        else -> when {
            name.length == 1 && name[0] in 'a'..'z' -> KeyEvent.VK_A + (name[0] - 'a')
            name.length == 1 && name[0] in '0'..'9' -> KeyEvent.VK_0 + (name[0] - '0')
            name.matches(Regex("f([1-9]|1[0-9]|2[0-4])")) -> FUNCTION_KEYS[name.drop(1).toInt() - 1]
            name.length == 1 -> KeyEvent.getExtendedKeyCodeForChar(name[0].code).takeIf { it != KeyEvent.VK_UNDEFINED }
            else -> null
        }
    }

    private fun bounds(): Rectangle =
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds

    /** Снимок основного монитора в точках экрана (на мониторах с масштабом — в логическом размере). */
    private fun shot(): ScreenCapture {
        val bounds = bounds()
        val image = robot.createScreenCapture(bounds)
        if (guardDesktopInput) desktopBeforeInput = image
        val bytes = ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
        return ScreenCapture(bytes, bounds.width, bounds.height)
    }

    /** Check the desktop again after hiding Prism and before sending an approved input. */
    private fun verifyDesktop(bounds: Rectangle, x: Int? = null, y: Int? = null) {
        if (!guardDesktopInput) return
        val previous = desktopBeforeInput ?: throw WorkspaceException("Take a desktop screenshot before sending input.")
        val now = robot.createScreenCapture(bounds)
        val sameSize = now.width == previous.width && now.height == previous.height
        val full = Rectangle(0, 0, now.width, now.height)
        val point = if (x != null && y != null) Rectangle(x - 22, y - 16, 45, 33).intersection(full) else null
        if (!sameSize || changedPixels(previous, now, full, 22) > 0.08 ||
            (point != null && changedPixels(previous, now, point, 3) > 0.15)) {
            desktopBeforeInput = null
            throw WorkspaceException("The desktop changed during approval. Take a fresh screenshot before retrying.")
        }
    }

    private fun changedPixels(before: BufferedImage, after: BufferedImage, area: Rectangle, stride: Int): Double {
        var samples = 0
        var changed = 0
        for (y in area.y until area.y + area.height step stride) {
            for (x in area.x until area.x + area.width step stride) {
                val first = before.getRGB(x, y)
                val second = after.getRGB(x, y)
                val difference = kotlin.math.abs((first and 255) - (second and 255)) +
                    kotlin.math.abs((first shr 8 and 255) - (second shr 8 and 255)) +
                    kotlin.math.abs((first shr 16 and 255) - (second shr 16 and 255))
                if (difference > 60) changed++
                samples++
            }
        }
        return changed.toDouble() / samples.coerceAtLeast(1)
    }

    /** Время программе ответить на действие перед снимком. */
    private fun settle() = robot.delay(SETTLE_MS)

    private companion object {
        const val ROBOT_DELAY_MS = 30
        const val SETTLE_MS = 700
        const val MAX_SCROLL = 50
        val FUNCTION_KEYS = intArrayOf(
            KeyEvent.VK_F1, KeyEvent.VK_F2, KeyEvent.VK_F3, KeyEvent.VK_F4, KeyEvent.VK_F5, KeyEvent.VK_F6,
            KeyEvent.VK_F7, KeyEvent.VK_F8, KeyEvent.VK_F9, KeyEvent.VK_F10, KeyEvent.VK_F11, KeyEvent.VK_F12,
            KeyEvent.VK_F13, KeyEvent.VK_F14, KeyEvent.VK_F15, KeyEvent.VK_F16, KeyEvent.VK_F17, KeyEvent.VK_F18,
            KeyEvent.VK_F19, KeyEvent.VK_F20, KeyEvent.VK_F21, KeyEvent.VK_F22, KeyEvent.VK_F23, KeyEvent.VK_F24,
        )
    }
}
