package ai.togohq;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import org.robolectric.RuntimeEnvironment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The public API from Java, as a Java app and the Solar2D plugin call it: the
 * static methods without `.INSTANCE`, the builder, a plain HashMap, and a
 * lambda for the callbacks. That this file compiles is most of the test.
 */
@RunWith(RobolectricTestRunner.class)
public class JavaInteropTest {
  @After
  public void after() {
    TestSupport.reset();
  }

  /**
   * Java can pass null to every parameter, and no call throws: a null name or
   * id is refused as an empty one, through onError, and a null config or
   * builder value is ignored. A non-null Kotlin parameter would have thrown a
   * NullPointerException on this thread instead.
   */
  @Test
  public void nullsFromJavaAreRefusedNeverThrown() throws InterruptedException {
    Context context = RuntimeEnvironment.getApplication();
    Togo.setup(null, null);
    Togo.setup(context, null);
    List<TogoErrorCode> codes = Collections.synchronizedList(new ArrayList<>());
    Togo.setup(
        context,
        new TogoConfig.Builder("togo_pkt_java000000000")
            .endpoint(null)
            .consent(null)
            .onDenied(null)
            .defaultProperties(null)
            .appVersion(null)
            .appBuild(null)
            .onError(error -> codes.add(error.getCode()))
            .build());
    Map<String, Object> withNullKey = new HashMap<>();
    withNullKey.put(null, "x");
    Togo.track(null);
    Togo.track(null, null);
    Togo.identify(null);
    Togo.setPersonProperties(null, null, null);
    Togo.setPersonProperties((TogoPersonProperties) null);
    Togo.setPersonProperties(
        new TogoPersonProperties.Builder()
            .email(null)
            .name(null)
            .set(null)
            .setOnce(null)
            .unset(Arrays.asList("legacy", null))
            .build());
    Togo.setConsent(null);
    Togo.track("ok", withNullKey);
    Togo.flush(null);
    // onError and a flush callback run on one callback thread, in order: once
    // this one has run, every error reported before it has been delivered.
    CountDownLatch done = new CountDownLatch(1);
    Togo.flush(done::countDown);
    assertTrue(done.await(10, TimeUnit.SECONDS));
    assertTrue(codes.toString(), codes.contains(TogoErrorCode.EVENT_NAME_EMPTY));
    assertTrue(codes.toString(), codes.contains(TogoErrorCode.USER_ID_INVALID));
    assertTrue(codes.toString(), codes.contains(TogoErrorCode.PROPERTY_VALUE_INVALID));
  }

  /** A null write key disables the client with one error, like any invalid one. */
  @Test
  public void aNullWriteKeyIsAnInvalidOne() throws InterruptedException {
    List<TogoErrorCode> codes = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch reported = new CountDownLatch(1);
    Togo.setup(
        RuntimeEnvironment.getApplication(),
        new TogoConfig.Builder(null)
            .onError(
                error -> {
                  codes.add(error.getCode());
                  reported.countDown();
                })
            .build());
    assertTrue(reported.await(10, TimeUnit.SECONDS));
    assertEquals(Collections.singletonList(TogoErrorCode.WRITE_KEY_INVALID), codes);
  }

  @Test
  public void javaCallsSetupTrackIdentifyAndFlush() throws InterruptedException {
    Context context = RuntimeEnvironment.getApplication();
    TogoConfig config =
        new TogoConfig.Builder("togo_pkt_java000000000")
            .endpoint("http://127.0.0.1:9")
            .consent(TogoConsent.PENDING)
            .sessionTimeoutSeconds(900)
            .onError(error -> {})
            .build();
    Togo.setup(context, config);

    Map<String, Object> properties = new HashMap<>();
    properties.put("plan_id", "pro");
    properties.put("seats", 5);
    Togo.track("plan_selected", properties);
    Togo.track("app_ready");
    Togo.identify("user_1");
    Togo.setPersonProperties(properties, "ada@example.com", null);
    Togo.setPersonProperties(new TogoPersonProperties.Builder().removeName().build());
    Togo.setConsent(TogoConsent.GRANTED);
    Togo.reset();

    CountDownLatch done = new CountDownLatch(1);
    Togo.flush(done::countDown);
    Togo.flush();
    assertTrue(done.await(10, TimeUnit.SECONDS));
    assertEquals(TogoConsent.GRANTED, Togo.getConsent());
    String unused = Togo.getAnonymousId() + Togo.getDeviceId() + Togo.getSessionId();
    assertTrue(unused.length() > 0);
  }
}
