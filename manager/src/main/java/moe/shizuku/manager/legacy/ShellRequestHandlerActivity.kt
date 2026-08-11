package moe.shizuku.manager.legacy

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.shell.ShellBinderRequestHandler

class ShellRequestHandlerActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ShellBinderRequestHandler.handleRequest(this@ShellRequestHandlerActivity, intent)
            }
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }
    }
}
