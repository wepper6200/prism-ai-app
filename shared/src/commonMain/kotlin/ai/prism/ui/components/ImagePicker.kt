package ai.prism.ui.components

import androidx.compose.runtime.Composable

/** Выбор изображений средствами платформы. */
interface ImagePicker {
    /** Можно сделать снимок камерой (Android). */
    val canTakePhoto: Boolean

    /** Открывает выбор изображений (на Android — системный Photo Picker, без доступа ко всей галерее). */
    fun pickImages()

    fun takePhoto()

    /** В буфере обмена есть изображение (скопированная картинка, снимок экрана или файл изображения). */
    fun hasClipboardImage(): Boolean

    /** Прикладывает изображения из буфера обмена — как выбранные ([onPicked] / [onFailed]). */
    fun pasteImages()
}

/**
 * [onPicked] получает байты выбранных файлов (чтение — вне главного потока),
 * [onFailed] вызывается, если какой-то файл прочитать не удалось.
 */
@Composable
expect fun rememberImagePicker(onPicked: (List<ByteArray>) -> Unit, onFailed: () -> Unit): ImagePicker
