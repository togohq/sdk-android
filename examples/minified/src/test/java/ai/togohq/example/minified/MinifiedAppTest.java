package ai.togohq.example.minified;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import ai.togohq.example.ExampleApp;
import ai.togohq.example.MainActivity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * The example app's release build, minified by R8 with the rules the build
 * used and none from the SDK, runs, tracks and flushes: the app sets the SDK up
 * at launch, the button tracks {@code button_tapped} and flushes, and the batch
 * arrives at the local edge with the event in it.
 *
 * <p>Only the app's own entry points can be called: R8 renamed every SDK class
 * the app does not name in its manifest, which is exactly what a customer's
 * release build does. What this cannot show is the dex: R8 wrote class files
 * here so the JVM can run them. A release build on a real device covers that,
 * before the first release.
 */
@RunWith(LocalEdgeRunner.class)
@Config(application = ExampleApp.class)
public class MinifiedAppTest {
  @Test
  @SuppressWarnings("unchecked")
  public void theMinifiedAppTracksAndFlushes() throws InterruptedException {
    BlockingQueue<String> received = (BlockingQueue<String>) System.getProperties().get(LocalEdgeRunner.RECEIVED);
    assertNotNull("the local edge is not running", received);

    ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
    shadowOf(Looper.getMainLooper()).idle();
    Button button = findButton(controller.get().getWindow().getDecorView());
    assertNotNull("the example app's screen has no button", button);
    button.performClick();
    shadowOf(Looper.getMainLooper()).idle();

    String batch = null;
    StringBuilder seen = new StringBuilder();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      String request = received.poll(200, TimeUnit.MILLISECONDS);
      shadowOf(Looper.getMainLooper()).idle();
      if (request == null) continue;
      seen.append(request).append("\n---\n");
      if (request.contains("\"button_tapped\"")) {
        batch = request;
        break;
      }
    }
    assertNotNull("no batch with button_tapped reached the local edge; it received:\n" + seen, batch);
    assertEquals("/v1/events", batch.substring(0, batch.indexOf('\n')));
    assertTrue(batch, batch.contains("\"name\":\"sdk-android\""));
    assertTrue(batch, batch.contains("\"screen\":\"main\""));
  }

  private static Button findButton(View view) {
    if (view instanceof Button) return (Button) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        Button found = findButton(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
  }
}
