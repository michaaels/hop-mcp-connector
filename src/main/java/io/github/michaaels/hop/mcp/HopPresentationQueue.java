package io.github.michaaels.hop.mcp;

import static io.github.michaaels.hop.mcp.HopGuidedPresentation.*;

import java.util.*;

/** Pure, bounded playback state. Time is supplied by the UI; no sleeps, I/O or worker threads. */
final class HopPresentationQueue {
  private HopGuidedPresentation plan;
  private Graph current = new Graph(List.of(), List.of());
  private Graph from = current;
  private Graph to = current;
  private int presented;
  private double elapsed;
  private long lastTick;
  private boolean paused;
  private boolean instant;
  private double speed; // 0 selects adaptive pacing.

  boolean accept(HopGuidedPresentation next) {
    if (plan != null) {
      if (!plan.id().equals(next.id())
          || !plan.path().equals(next.path())
          || !plan.kind().equals(next.kind())
          || next.revision() < plan.revision()) return false;
      if (!next.steps()
              .subList(0, Math.min(plan.steps().size(), next.steps().size()))
              .equals(plan.steps())
          || terminal(plan.state()) && !next.state().equals(plan.state())) return false;
    } else current = next.baseline();
    plan = next;
    if (next.mode().equals("instant")) instant = true;
    if (next.state().equals("cancelled") || next.state().equals("failed")) {
      elapsed = 0;
      from = current;
      to = current;
    } else if (instant) skip();
    return true;
  }

  void tick(long now) {
    long delta = lastTick == 0 ? 0 : Math.max(0, Math.min(100, now - lastTick));
    lastTick = now;
    if (plan == null || paused || pending() == 0) return;
    if (elapsed == 0) {
      from = current;
      to = apply(current, plan.steps().get(presented), plan.result());
    }
    elapsed += delta;
    if (elapsed >= duration()) {
      current = to;
      presented++;
      elapsed = 0;
      if (presented == plan.steps().size()) current = plan.result();
    }
  }

  void pause(boolean value) {
    paused = value;
  }

  boolean paused() {
    return paused;
  }

  void speed(double value) {
    if (value != 0 && value != .5 && value != 1 && value != 2)
      throw new IllegalArgumentException("Unsupported presentation speed");
    speed = value;
    instant = false;
  }

  void instantaneous() {
    instant = true;
    skip();
  }

  void skip() {
    if (plan == null) return;
    if (Set.of("failed", "cancelled").contains(plan.state())) return;
    current = plan.result();
    from = current;
    to = current;
    presented = plan.steps().size();
    elapsed = 0;
    paused = false;
  }

  Graph from() {
    return elapsed == 0 ? current : from;
  }

  Graph to() {
    return elapsed == 0 ? current : to;
  }

  Graph current() {
    return current;
  }

  double fraction() {
    double progress = Math.min(1, elapsed / Math.min(400, duration()));
    return 1 - Math.pow(1 - progress, 3);
  }

  int presented() {
    return presented;
  }

  int pending() {
    return plan == null || Set.of("failed", "cancelled").contains(plan.state())
        ? 0
        : plan.steps().size() - presented;
  }

  HopGuidedPresentation plan() {
    return plan;
  }

  Step active() {
    return pending() == 0 ? null : plan.steps().get(presented);
  }

  boolean done() {
    return plan != null && pending() == 0 && terminal(plan.state());
  }

  long duration() {
    if (speed != 0) return Math.round(1000 / speed);
    if (pending() >= 8) return 650;
    if (pending() <= 2) return 1800;
    return 1100;
  }

  String status() {
    if (plan == null) return "Preparando el flujo…";
    String saved = plan.state().equals("applied") ? "Guardado · " : "Borrador · ";
    if (plan.state().equals("failed")) return "Aplicación rechazada · revisar el error del MCP";
    if (plan.state().equals("cancelled")) return "Borrador cancelado · no aplicado";
    if (paused) return saved + "presentación pausada";
    if (pending() > 0)
      return saved + "mostrando paso " + (presented + 1) + " de " + plan.steps().size();
    if (done()) return "Cambios aplicados y presentación completa";
    return "Esperando el siguiente bloque del modelo…";
  }

  private static boolean terminal(String state) {
    return Set.of("applied", "failed", "cancelled").contains(state);
  }

  private static Graph apply(Graph graph, Step step, Graph result) {
    Map<String, Node> nodes = new LinkedHashMap<>();
    graph.nodes().forEach(node -> nodes.put(node.name(), node));
    List<Edge> edges = new ArrayList<>(graph.edges());
    switch (step.operation()) {
      case "add_component" ->
          nodes.put(
              step.component(), new Node(step.component(), step.plugin(), step.x(), step.y()));
      case "remove_component" -> {
        nodes.remove(step.component());
        edges.removeIf(
            edge -> edge.from().equals(step.component()) || edge.to().equals(step.component()));
      }
      case "rename_component" -> {
        Node old = nodes.remove(step.component());
        if (old != null) {
          nodes.put(step.target(), new Node(step.target(), old.plugin(), old.x(), old.y()));
          edges.replaceAll(
              edge ->
                  new Edge(
                      edge.from().equals(step.component()) ? step.target() : edge.from(),
                      edge.to().equals(step.component()) ? step.target() : edge.to(),
                      edge.enabled(),
                      edge.branch()));
        }
      }
      case "move_component" ->
          nodes.computeIfPresent(step.component(), (name, node) -> node.move(step.x(), step.y()));
      case "add_hop" -> {
        if (nodes.containsKey(step.component()) && nodes.containsKey(step.target()))
          edges.add(
              result.edges().stream()
                  .filter(
                      edge ->
                          edge.from().equals(step.component()) && edge.to().equals(step.target()))
                  .findFirst()
                  .orElse(new Edge(step.component(), step.target(), true, 0)));
      }
      case "remove_hop" ->
          edges.removeIf(
              edge -> edge.from().equals(step.component()) && edge.to().equals(step.target()));
      case "auto_layout" ->
          result
              .nodes()
              .forEach(
                  target ->
                      nodes.computeIfPresent(
                          target.name(), (name, node) -> node.move(target.x(), target.y())));
      default -> {
        /* Configuration changes are highlighted without transmitting their values. */
      }
    }
    return new Graph(new ArrayList<>(nodes.values()), edges);
  }
}
