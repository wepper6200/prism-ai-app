package ai.prism.android

import ai.prism.presentation.root.RootComponentFactory
import ai.prism.ui.PrismApp
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arkivanov.decompose.defaultComponentContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val koin = (application as PrismApplication).koin
        val root = koin.get<RootComponentFactory>().create(defaultComponentContext())

        setContent {
            PrismApp(root)
        }
    }
}
