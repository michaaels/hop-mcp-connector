package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class HopWebAgentSessionTest {
  @Test
  void presenceAndWorkAreSeparateAndRevocationDoesNotDisableReconnect() throws Exception {
    var clock = new AtomicLong(1000);
    var session = new HopWebAgentSession(clock::get);
    assertFalse(session.activity().connected());
    String first = session.connect("Codex", "1");
    assertTrue(session.activity().connected());
    assertFalse(session.activity().working());
    assertEquals("Codex", session.activity().agent());
    assertNull(session.connect("Another agent", "2"));
    try (var work = session.begin("hop_inspect")) {
      clock.addAndGet(5000);
      assertEquals("Consultando flujo", session.activity().operation());
      assertEquals(5000, session.activity().elapsedMillis());
      assertTrue(session.activity().working());
    }
    clock.addAndGet(3000);
    assertFalse(session.activity().working());
    long lastActivity = session.activity().lastActivityMillis();
    assertTrue(session.touch(first));
    assertEquals(lastActivity, session.activity().lastActivityMillis());
    assertFalse(session.activity().working(), "Heartbeat is presence, not work");
    session.disconnect();
    assertFalse(session.accepts(first));
    assertTrue(session.owns(first));
    assertFalse(session.activity().connected());
    String second = session.connect("Codex", "1");
    assertNotEquals(first, second);
    assertFalse(session.touch(first));
    assertTrue(session.touch(second));
  }

  @Test
  void crashedAgentsExpireButLongRunningWorkAndNewConnectionsRemainIndependent() throws Exception {
    var clock = new AtomicLong(1000);
    var session = new HopWebAgentSession(clock::get);
    String oldBinding = session.connect("Codex", "1");
    var oldWork = session.begin("hop_apply_correction_plan");
    clock.addAndGet(60000);
    assertTrue(session.activity().connected(), "Accepted work does not expire in progress");
    session.disconnect();
    assertNull(session.connect("Other", "2"), "Finish an accepted operation before rebinding");
    oldWork.close();
    assertNotNull(session.connect("Other", "2"));
    assertThrows(SecurityException.class, () -> session.begin(oldBinding, "hop_inspect"));
    long freshActivity = session.activity().lastActivityMillis();
    oldWork.close();
    assertEquals(freshActivity, session.activity().lastActivityMillis());
    assertEquals("Other", session.activity().agent());
    clock.addAndGet(HopWebAgentSession.TTL.toMillis() + 1);
    assertFalse(session.activity().connected());
    assertNotNull(session.connect("Codex", "1"));
  }

  @Test
  void staleRequestCleanupCannotDisconnectAReplacementAgent() {
    var session = new HopWebAgentSession();
    String oldBinding = session.connect("First agent", "1");
    session.disconnect();
    String newBinding = session.connect("Replacement agent", "2");
    assertFalse(session.disconnect(oldBinding));
    assertFalse(session.disconnect(null));
    assertTrue(session.accepts(newBinding));
    assertEquals("Replacement agent", session.activity().agent());
    assertTrue(session.disconnect(newBinding));
    assertFalse(session.activity().connected());
  }
}
