package au.com.fuzn.beaver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import au.com.fuzn.beaver.ui.BeaverApp
import au.com.fuzn.beaver.ui.theme.BeaverTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BeaverTheme {
                BeaverApp()
            }
        }
    }
}
