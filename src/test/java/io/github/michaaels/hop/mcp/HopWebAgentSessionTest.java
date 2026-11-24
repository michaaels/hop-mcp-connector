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
    String code = session.createPairing();
    var first = session.connect(code, "Codex", "1");
    assertEquals(first.sessionId(), session.sessionId());
    assertTrue(session.activity().connected());
    assertFalse(session.activity().working());
    assertEquals("Codex", session.activity().agent());
    assertNull(session.createPairing());
    assertNull(session.connect(code, "Another agent", "2"));
    try (var work = session.begin("hop_inspect")) {
      clock.addAndGet(5000);
      assertEquals("Consultando flujo", session.activity().operation());
      assertEquals(5000, session.activity().elapsedMillis());
      assertTrue(session.activity().working());
    }
    clock.addAndGet(3000);
    assertFalse(session.activity().working());
    long lastActivity = session.activity().lastActivityMillis();
    assertEquals(first, session.touch(first.binding(), first.sessionId()));
    assertEquals(lastActivity, session.activity().lastActivityMillis());
    assertFalse(session.activity().working(), "Heartbeat is presence, not work");
    session.disconnect();
    assertFalse(session.accepts(first.binding()));
    assertEquals("", session.sessionId());
    assertFalse(session.activity().connected());
    var second = session.connect(session.createPairing(), "Codex", "1");
    assertNotEquals(first.sessionId(), second.sessionId());
    assertNull(session.touch(first.binding(), null));
    assertNull(session.touch(second.binding(), first.sessionId()));
    assertEquals(second, session.touch(second.binding(), second.sessionId()));
  }

  @Test
  void crashedAgentsExpireButLongRunningWorkAndNewConnectionsRemainIndependent() throws Exception {
    var clock = new AtomicLong(1000);
    var session = new HopWebAgentSession(clock::get);
    String oldBinding = session.connect(session.createPairing(), "Codex", "1").binding();
    var oldWork = session.begin("hop_apply_correction_plan");
    clock.addAndGet(60000);
    assertTrue(session.activity().connected(), "Accepted work does not expire in progress");
    session.disconnect();
    assertNull(session.createPairing(), "Finish an accepted operation before rebinding");
    oldWork.close();
    assertNotNull(session.connect(session.createPairing(), "Other", "2"));
    assertThrows(SecurityException.class, () -> session.begin(oldBinding, "hop_inspect"));
    long freshActivity = session.activity().lastActivityMillis();
    oldWork.close();
    assertEquals(freshActivity, session.activity().lastActivityMillis());
    assertEquals("Other", session.activity().agent());
    clock.addAndGet(HopWebAgentSession.TTL.toMillis() + 1);
    assertFalse(session.activity().connected());
    assertEquals("", session.sessionId());
    assertNotNull(session.connect(session.createPairing(), "Codex", "1"));
  }

  @Test
  void staleRequestCleanupCannotDisconnectAReplacementAgent() {
    var session = new HopWebAgentSession();
    String oldBinding = session.connect(session.createPairing(), "First agent", "1").binding();
    session.disconnect();
    String newBinding =
        session.connect(session.createPairing(), "Replacement agent", "2").binding();
    assertFalse(session.disconnect(oldBinding, null));
    assertFalse(session.disconnect(null, null));
    assertTrue(session.accepts(newBinding));
    assertEquals("Replacement agent", session.activity().agent());
    assertTrue(session.disconnect(newBinding, null));
    assertFalse(session.activity().connected());
  }

  @Test
  void pairingExpiresAndReissuingOrDisconnectingRevokesPreviousLinks() {
    var clock = new AtomicLong(1000);
    var session = new HopWebAgentSession(clock::get);
    String first = session.createPairing();
    assertEquals("", session.sessionId(), "A pending link does not establish a session");
    String second = session.createPairing();
    assertNull(session.connect(first, "First agent", "1"));
    clock.addAndGet(HopWebAgentSession.PAIRING_TTL.toMillis());
    assertNull(session.connect(second, "Second agent", "1"));
    String revoked = session.createPairing();
    session.disconnect();
    assertNull(session.connect(revoked, "Revoked agent", "1"));
    assertNotNull(session.connect(session.createPairing(), "Approved agent", "1"));
  }
}
