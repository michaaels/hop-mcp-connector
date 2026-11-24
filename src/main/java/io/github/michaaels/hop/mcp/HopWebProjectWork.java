package io.github.michaaels.hop.mcp;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Application-local coordination of MCP edits on canonical project files. */
final class HopWebProjectWork {
  private final List<Claim> claims = new ArrayList<>();

  Session register(Path project, Supplier<String> binding, BooleanSupplier closed) {
    return new Session(project, binding, closed);
  }

  private synchronized Claim acquire(Session session, Path target, Instant expires)
      throws McpException {
    prune();
    String binding = session.binding.get();
    if (binding == null || session.closed.getAsBoolean())
      throw McpException.security(
          "AGENT_DISCONNECTED", "Connect again before editing the project.");
    for (Claim claim : claims)
      if (claim.target.equals(target) && !claim.ownedBy(session, binding))
        throw session.reportConflict(binding, claim);
    if (claims.size() >= 8 * (HopCorrectionPlanManager.MAX_PLANS + 3))
      throw McpException.conflict("PROJECT_WORK_LIMIT", "Finish or cancel an existing MCP draft.");
    var claim = new Claim(session, binding, target, expires);
    claims.add(claim);
    session.clearConflictFor(target);
    return claim;
  }

  private void prune() {
    Instant now = Instant.now();
    claims.removeIf(
        claim ->
            claim.expires != null
                && (!now.isBefore(claim.expires)
                    || !claim.ownedBy(claim.session, claim.session.binding.get())));
  }

  private synchronized boolean otherEditInProgress(Session observer) {
    prune();
    return claims.stream()
        .anyMatch(
            claim -> claim.session != observer && claim.session.project.equals(observer.project));
  }

  record ConflictNotice(String path) {}

  private record PendingConflict(String binding, ConflictNotice notice) {}

  final class Session {
    private final Path project;
    private final Supplier<String> binding;
    private final BooleanSupplier closed;
    private PendingConflict pendingConflict;
    private PendingConflict lastShown;

    private Session(Path project, Supplier<String> binding, BooleanSupplier closed) {
      this.project = project;
      this.binding = binding;
      this.closed = closed;
    }

    Claim prepare(Path target, Instant expires) throws McpException {
      return acquire(this, target, Objects.requireNonNull(expires));
    }

    Claim write(Path target) throws McpException {
      return acquire(this, target, null);
    }

    boolean otherEditInProgress() {
      return HopWebProjectWork.this.otherEditInProgress(this);
    }

    ConflictNotice takeConflict() {
      synchronized (HopWebProjectWork.this) {
        var conflict = pendingConflict;
        pendingConflict = null;
        if (conflict == null
            || closed.getAsBoolean()
            || !conflict.binding().equals(binding.get())
            || conflict.equals(lastShown)) return null;
        lastShown = conflict;
        return conflict.notice();
      }
    }

    private McpException reportConflict(String currentBinding, Claim blocker) {
      String path = relative(blocker.target);
      pendingConflict = new PendingConflict(currentBinding, new ConflictNotice(path));
      return McpException.conflict(
          "DEFINITION_IN_USE", "Otra sesión está editando " + path + ". Tu cambio no se aplicó.");
    }

    private void clearConflictFor(Path target) {
      String path = relative(target);
      if (pendingConflict != null && pendingConflict.notice().path().equals(path))
        pendingConflict = null;
      if (lastShown != null && lastShown.notice().path().equals(path)) lastShown = null;
    }

    private String relative(Path target) {
      return project.relativize(target).toString().replace('\\', '/');
    }
  }

  final class Claim implements AutoCloseable {
    private final Session session;
    private final String binding;
    private final Path target;
    private final Instant expires;

    private Claim(Session session, String binding, Path target, Instant expires) {
      this.session = session;
      this.binding = binding;
      this.target = target;
      this.expires = expires;
    }

    private boolean ownedBy(Session owner, String currentBinding) {
      return session == owner && binding.equals(currentBinding);
    }

    void requireCurrentAgent() throws McpException {
      if (!ownedBy(session, session.binding.get()))
        throw McpException.security(
            "STALE_AGENT_PLAN", "Prepare a new plan for the current MCP connection.");
    }

    @Override
    public void close() {
      synchronized (HopWebProjectWork.this) {
        claims.remove(this);
      }
    }
  }
}
