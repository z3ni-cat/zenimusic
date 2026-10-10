import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

class A : ComponentActivity() {
    fun x() {
        setContent {
            G()
        }
    }
}

@Composable
fun G() {}
