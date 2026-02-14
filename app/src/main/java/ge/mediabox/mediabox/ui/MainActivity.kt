package ge.mediabox.mediabox.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import ge.mediabox.mediabox.databinding.ActivityMainBinding
import ge.mediabox.mediabox.ui.player.PlayerActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMenu()
    }

    private fun setupMenu() {
        binding.btnTv.requestFocus()

        binding.btnTv.setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java)
            startActivity(intent)
        }

        binding.btnProfile.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }

        binding.btnSettings.setOnClickListener {
            // TODO: Implement Settings screen
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                currentFocus?.performClick()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}