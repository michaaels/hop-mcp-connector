package io.github.michaaels.hop.mcp;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** One revocable agent lease per Web editor. Heartbeats renew presence without signalling work. */
final class HopWebAgentSession {
  static final Duration TTL = Duration.ofSeconds(45);
  private final LongSupplier clock;
  private final Map<String, Work> active = new LinkedHashMap<>();
  private final java.util.Set<String> revoked = new java.util.LinkedHashSet<>();
  private String binding;
  private String agent = "No informado", version = "";
  private long contacted, connectedAt, lastActivity, lastDuration, pulseUntil;

  HopWebAgentSession() {
    this(System::currentTimeMillis);
  }

  HopWebAgentSession(LongSupplier clock) {
    this.clock = clock;
  }

  synchronized String connect(String name, String reportedVersion) {
    expire();
    if (binding != null || !active.isEmpty()) return null;
    binding = UUID.randomUUID().toString();
    agent = label(name, "No informado");
    version = label(reportedVersion, "");
    contacted = connectedAt = clock.getAsLong();
    lastActivity = lastDuration = pulseUntil = 0;
    return binding;
  }

  synchronized boolean accepts(String id) {
    expire();
    return id != null && id.equals(binding);
  }

  synchronized boolean touch(String id) {
    if (!accepts(id)) return false;
    contacted = clock.getAsLong();
    return true;
  }

  synchronized void disconnect() {
    if (binding != null) revoked.add(binding);
    while (revoked.size() > 16) revoked.remove(revoked.iterator().next());
    binding = null;
    pulseUntil = 0;
  }

  synchronized boolean disconnect(String expectedBinding) {
    if (expectedBinding == null || !expectedBinding.equals(binding)) return false;
    disconnect();
    return true;
  }

  synchronized boolean owns(String id) {
    return accepts(id) || revoked.contains(id);
  }

  synchronized long connectedSince() {
    return binding == null ? 0 : connectedAt;
  }

  synchronized AutoCloseable begin(String name) {
    return begin(binding, name);
  }

  synchronized AutoCloseable begin(String requestedBinding, String name) {
    if (!accepts(requestedBinding)) throw new SecurityException("The Web agent was disconnected");
    String generation = binding;
    String id = UUID.randomUUID().toString();
    long now = clock.getAsLong();
    active.put(id, new Work(operation(name), now));
    lastActivity = now;
    pulseUntil = now + 2400;
    return () -> finish(generation, id);
  }

  private synchronized void finish(String generation, String id) {
    Work work = active.remove(id);
    if (generation == null || !generation.equals(binding)) return;
    if (work == null) return;
    contacted = lastActivity = clock.getAsLong();
    lastDuration = Math.max(0, lastActivity - work.started());
    pulseUntil = lastActivity + 2400;
  }

  synchronized HopAgentActivity activity() {
    expire();
    if (binding == null)
      return new HopAgentActivity(
          false, false, "No conectado", "", "En espera", lastDuration, lastActivity);
    long now = clock.getAsLong();
    Work work = active.values().stream().reduce((first, next) -> next).orElse(null);
    return new HopAgentActivity(
        true,
        work != null || now < pulseUntil,
        agent,
        version,
        work == null ? "En espera" : work.operation(),
        work == null ? lastDuration : Math.max(0, now - work.started()),
        lastActivity);
  }

  private void expire() {
    if (binding != null && active.isEmpty() && clock.getAsLong() - contacted > TTL.toMillis())
      disconnect();
  }

  private static String label(String text, String fallback) {
    if (text == null || text.isBlank()) return fallback;
    String clean = text.replaceAll("[\\p{Cntrl}]", " ").strip();
    return clean.substring(0, Math.min(clean.length(), 100));
  }

  private static String operation(String tool) {
    return switch (tool) {
      case "hop_prepare_correction_plan" -> "Preparando cambios";
      case "hop_apply_correction_plan", "hop_mutate_definition" -> "Aplicando cambios";
      case "hop_rollback_mutation" -> "Revirtiendo cambios";
      case "hop_inspect", "hop_read_text" -> "Consultando flujo";
      case "hop_catalog", "hop_search" -> "Buscando flujos";
      case "hop_check", "hop_validate" -> "Validando diseño";
      default -> "Atendiendo solicitud MCP";
    };
  }

  private record Work(String operation, long started) {}
}
