package dev.jasonpearson.motiondots

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.jasonpearson.motiondots.ui.MainScreen
import dev.jasonpearson.motiondots.ui.MainViewModel
import dev.jasonpearson.motiondots.ui.MotionDotsAppTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { MotionDotsAppTheme { MainScreen(viewModel) } }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onVisible()
    }

    override fun onStop() {
        viewModel.onHidden()
        super.onStop()
    }
}
