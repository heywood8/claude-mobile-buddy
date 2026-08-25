package dev.heywood8.claudebuddy

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Decodes QR codes out of the camera preview.
 *
 * ZXing's decoder only, with no Play Services and no bundled scanner UI: pairing is the one
 * moment the whole thing depends on, and it should not also depend on Google Play being
 * present and current.
 */
private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            // The luminance plane is padded to rowStride, which is not the same as the image
            // width. Passing width here instead is the classic way to get a scanner that
            // only reads codes on some devices.
            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            val text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            onText(text)
        } catch (_: Exception) {
            // Most frames contain no code at all. Not finding one is the normal case.
        } finally {
            reader.reset()
            image.close()
        }
    }
}

/**
 * Scans the code printed by `cmbridge pair`.
 *
 * Anything that is not a well-formed pairing payload is ignored rather than reported: a
 * camera pointed at the world sees a great many barcodes, and none of the others are errors.
 */
@Composable
fun PairingScreen(
    modifier: Modifier = Modifier,
    onPaired: (PairedHost) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var handled by remember { mutableStateOf(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            ProcessCameraProvider.getInstance(context).get().unbindAll()
            executor.shutdown()
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Scan the code from cmbridge pair", style = MaterialTheme.typography.titleMedium)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    PreviewView(viewContext).also { view ->
                        bindCamera(viewContext, view, lifecycleOwner, executor) { text ->
                            if (handled) return@bindCamera
                            val host = PairingCode.parse(text) ?: return@bindCamera
                            handled = true
                            Keyring.add(viewContext, host)
                            onPaired(host)
                        }
                    }
                },
            )
        }

        Text(
            "The code carries the key for one bridge. Scanning it again from the same machine " +
                "replaces that entry rather than adding a second one.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onCancel, modifier = Modifier.padding(bottom = 8.dp)) {
            Text("Cancel")
        }
    }
}

private fun bindCamera(
    context: Context,
    view: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    executor: ExecutorService,
    onText: (String) -> Unit,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = future.get()
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            // The analyzer runs on its own thread; the callback touches Compose state and
            // the keyring, so it hops back to main before doing either.
            .also {
                it.setAnalyzer(executor, QrAnalyzer { text ->
                    context.mainExecutor.execute { onText(text) }
                })
            }

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    }, context.mainExecutor)
}
