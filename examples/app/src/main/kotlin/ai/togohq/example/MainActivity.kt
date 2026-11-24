package ai.togohq.example

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout

class MainActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val button = Button(this).apply {
      text = "Send an event"
      setOnClickListener { Analytics.buttonTapped() }
    }
    setContentView(LinearLayout(this).apply { addView(button) })
  }
}
