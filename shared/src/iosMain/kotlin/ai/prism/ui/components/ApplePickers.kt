@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package ai.prism.ui.components

import ai.prism.ios.appleHost
import ai.prism.ios.toBytes
import ai.prism.ios.toNativeData
import ai.prism.presentation.chat.PickedDocument
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@Composable
actual fun rememberImagePicker(onPicked: (List<ByteArray>) -> Unit, onFailed: () -> Unit): ImagePicker {
    val picked = rememberUpdatedState(onPicked)
    val failed = rememberUpdatedState(onFailed)
    return remember {
        object : ImagePicker {
            override val canTakePhoto: Boolean get() = appleHost.cameraAvailable()
            override fun pickImages() = appleHost.pickImages({ list -> picked.value(list.map { it.toBytes() }) }, { failed.value() })
            override fun takePhoto() = appleHost.takePhoto({ data -> data?.let { picked.value(listOf(it.toBytes())) } }, { failed.value() })
            override fun hasClipboardImage(): Boolean = appleHost.clipboardImageAvailable()
            override fun pasteImages() { appleHost.clipboardImage()?.let { picked.value(listOf(it.toBytes())) } }
        }
    }
}

@Composable
actual fun rememberDocumentPicker(onPicked: (List<PickedDocument>) -> Unit, onFailed: () -> Unit): DocumentPicker {
    val picked = rememberUpdatedState(onPicked)
    val failed = rememberUpdatedState(onFailed)
    return remember { DocumentPicker { appleHost.pickDocuments({ files -> picked.value(files.map { PickedDocument(it.name, it.data.toBytes()) }) }, { failed.value() }) } }
}

@Composable
actual fun rememberFolderPicker(onPicked: (String) -> Unit): FolderPicker {
    val picked = rememberUpdatedState(onPicked)
    return remember { FolderPicker { appleHost.pickFolder { picked.value(it) } } }
}

@Composable
actual fun rememberMicrophonePermission(): MicrophonePermission = remember {
    MicrophonePermission { granted, denied -> appleHost.microphonePermission { if (it) granted() else denied() } }
}

@Composable
actual fun rememberImageExporter(mimeType: String, onResult: (Boolean) -> Unit): ImageExporter {
    val result = rememberUpdatedState(onResult)
    return remember { ImageExporter { bytes, name -> appleHost.exportFile(bytes.toNativeData(), name) { result.value(it) } } }
}
