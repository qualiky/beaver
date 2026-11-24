package au.com.fuzn.beaver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import au.com.fuzn.beaver.app.BeaverApplication
import au.com.fuzn.beaver.ui.BeaverRoot
import au.com.fuzn.beaver.ui.theme.BeaverTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as BeaverApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = container
        setContent {
            BeaverTheme {
                BeaverRoot(container)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.refreshToday()
    }
}
