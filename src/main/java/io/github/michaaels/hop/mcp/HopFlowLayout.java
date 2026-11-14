package io.github.michaaels.hop.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hop.core.gui.IGuiPosition;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.workflow.WorkflowMeta;

/** Bounded canvas layout. Changes native component positions only, never routing or execution. */
final class HopFlowLayout {
  static final int MAX_COMPONENTS = 200;
  static final int MAX_HOPS = 1000;
  private static final int ROW_GAP = 140;

  record Node(String name, IGuiPosition position, boolean abort) {}

  record Edge(int from, int to, boolean failure) {}

  record Position(int x, int y) {}

  private HopFlowLayout() {}

  static Map<String, Object> arrange(PipelineMeta meta, int x, int y) {
    requireBounds(meta.nrTransforms(), meta.nrPipelineHops());
    List<Node> nodes =
        meta.getTransforms().stream()
            .map(t -> new Node(t.getName(), t, "Abort".equals(t.getPluginId())))
            .toList();
    Map<String, Integer> indices = indices(nodes);
    List<Edge> edges = new ArrayList<>();
    for (int i = 0; i < meta.nrPipelineHops(); i++) {
      var hop = meta.getPipelineHop(i);
      if (hop.isEnabled())
        edges.add(
            new Edge(
                indices.get(hop.getFromTransform().getName()),
                indices.get(hop.getToTransform().getName()),
                hop.isErrorHop()));
    }
    return arrange(nodes, edges, x, y);
  }

  static Map<String, Object> arrange(WorkflowMeta meta, int x, int y) {
    requireBounds(meta.nrActions(), meta.nrWorkflowHops());
    List<Node> nodes =
        meta.getActions().stream()
            .map(a -> new Node(a.getName(), a, "ABORT".equals(a.getAction().getPluginId())))
            .toList();
    Map<String, Integer> indices = indices(nodes);
    List<Edge> edges = new ArrayList<>();
    for (int i = 0; i < meta.nrWorkflowHops(); i++) {
      var hop = meta.getWorkflowHop(i);
      if (hop.isEnabled())
        edges.add(
            new Edge(
                indices.get(hop.getFromAction().getName()),
                indices.get(hop.getToAction().getName()),
                !hop.isUnconditional() && !hop.isEvaluation()));
    }
    return arrange(nodes, edges, x, y);
  }

  private static Map<String, Integer> indices(List<Node> nodes) {
    Map<String, Integer> indices = new HashMap<>();
    for (int i = 0; i < nodes.size(); i++) {
      if (indices.put(nodes.get(i).name(), i) != null)
        throw new IllegalArgumentException("auto_layout requires unique component names");
    }
    return indices;
  }

  private static void requireBounds(int nodes, int edges) {
    if (nodes > MAX_COMPONENTS || edges > MAX_HOPS)
      throw new IllegalArgumentException(
          "auto_layout supports at most 200 components and 1000 hops");
  }

  static void validateOptions(Map<String, Object> operation) {
    if (!Set.of("operation", "x", "y").containsAll(operation.keySet()))
      throw new IllegalArgumentException("auto_layout accepts only operation, x and y");
  }

  static Map<String, Object> arrange(List<Node> nodes, List<Edge> edges, int x, int y) {
    requireBounds(nodes.size(), edges.size());
    Position[] before =
        nodes.stream()
            .map(n -> new Position(n.position().getLocation().x, n.position().getLocation().y))
            .toArray(Position[]::new);
    Position[] after = plan(nodes, edges, x, y);
    // Validate the complete plan before changing any native object.
    for (Position point : after) {
      if (point.x() < 0 || point.y() < 0 || point.x() > 1_000_000 || point.y() > 1_000_000)
        throw new IllegalArgumentException("auto_layout exceeds canvas coordinate bounds");
    }
    int moved = 0;
    for (int i = 0; i < nodes.size(); i++) {
      if (!before[i].equals(after[i])) moved++;
      nodes.get(i).position().setLocation(after[i].x(), after[i].y());
    }
    Map<String, Integer> afterMetrics = metrics(after, edges);
    return Map.of(
        "operation",
        "auto_layout",
        "component_count",
        nodes.size(),
        "moved_components",
        moved,
        "direction",
        "left_to_right",
        "notes_moved",
        false,
        "before",
        metrics(before, edges),
        "after",
        afterMetrics,
        "review_required",
        afterMetrics.values().stream().anyMatch(count -> count > 0));
  }

  private static Position[] plan(List<Node> nodes, List<Edge> edges, int x, int y) {
    List<Edge> forward = forwardEdges(nodes.size(), edges);
    int[] ranks = ranks(nodes.size(), forward);
    boolean[] failure = failureLanes(nodes, edges);
    List<List<Integer>> layers = new ArrayList<>();
    for (int i = 0; i < nodes.size(); i++) {
      while (layers.size() <= ranks[i]) layers.add(new ArrayList<>());
      layers.get(ranks[i]).add(i);
    }
    int columnGap =
        nodes.stream().mapToInt(n -> Math.min(320, 80 + n.name().length() * 6)).max().orElse(180);
    columnGap = Math.max(180, columnGap);
    Position[] best = positions(layers, failure, columnGap, x, y);
    long bestScore = score(best, edges);
    // Four deterministic barycenter sweeps; no factorial search or external layout dependency.
    for (int pass = 0; pass < 4; pass++) {
      for (int step = 0; step < layers.size(); step++) {
        int layer = pass % 2 == 0 ? step : layers.size() - 1 - step;
        Position[] current = positions(layers, failure, columnGap, x, y);
        boolean predecessors = pass % 2 == 0;
        layers
            .get(layer)
            .sort(
                Comparator.<Integer>comparingInt(i -> failure[i] ? 1 : 0)
                    .thenComparingDouble(i -> barycenter(i, forward, current, predecessors)));
      }
      Position[] candidate = positions(layers, failure, columnGap, x, y);
      long candidateScore = score(candidate, edges);
      if (candidateScore < bestScore) {
        best = candidate;
        bestScore = candidateScore;
      }
    }
    best = refineRows(best, edges, failure, y);
    return avoidStraightBypasses(best, edges, failure);
  }

  private static Position[] refineRows(
      Position[] initial, List<Edge> edges, boolean[] failure, int originY) {
    Position[] best = initial;
    long bestScore = score(best, edges);
    int attempts = 0;
    for (int pass = 0; pass < 2; pass++) {
      for (int node = 0; node < best.length && attempts < 128; node++) {
        for (int delta : new int[] {-ROW_GAP, ROW_GAP}) {
          if (best[node].y() + delta < originY) continue;
          Position[] candidate = best.clone();
          candidate[node] = new Position(best[node].x(), best[node].y() + delta);
          if (!lanesSeparated(candidate, failure) || occupied(candidate, node)) continue;
          attempts++;
          long candidateScore = score(candidate, edges);
          if (candidateScore < bestScore) {
            best = candidate;
            bestScore = candidateScore;
          }
        }
        for (int other = node + 1; other < best.length && attempts < 128; other++) {
          if (best[node].x() != best[other].x() || failure[node] != failure[other]) continue;
          Position[] candidate = best.clone();
          candidate[node] = best[other];
          candidate[other] = best[node];
          attempts++;
          long candidateScore = score(candidate, edges);
          if (candidateScore < bestScore) {
            best = candidate;
            bestScore = candidateScore;
          }
        }
      }
    }
    return best;
  }

  private static boolean occupied(Position[] points, int node) {
    for (int i = 0; i < points.length; i++) {
      if (i != node
          && points[i].x() == points[node].x()
          && Math.abs(points[i].y() - points[node].y()) < 80) return true;
    }
    return false;
  }

  private static boolean lanesSeparated(Position[] points, boolean[] failure) {
    int normalBottom = Integer.MIN_VALUE;
    int failureTop = Integer.MAX_VALUE;
    for (int i = 0; i < points.length; i++) {
      if (failure[i]) failureTop = Math.min(failureTop, points[i].y());
      else normalBottom = Math.max(normalBottom, points[i].y());
    }
    return (long) normalBottom + ROW_GAP <= failureTop;
  }

  private static Position[] avoidStraightBypasses(
      Position[] initial, List<Edge> edges, boolean[] failure) {
    Position[] best = initial;
    long bestScore = score(best, edges);
    int attempts = 0;
    for (int node = 0; node < best.length && attempts < 24; node++) {
      boolean obstructs = false;
      for (Edge edge : edges) {
        if (node != edge.from()
            && node != edge.to()
            && onSegment(best[edge.from()], best[edge.to()], best[node])) obstructs = true;
      }
      if (!obstructs) continue;
      attempts++;
      Position[] candidate = best.clone();
      int nextY = best[node].y();
      boolean occupied;
      do {
        nextY += ROW_GAP;
        occupied = false;
        for (Position point : candidate) {
          if (point.x() == best[node].x() && point.y() == nextY) occupied = true;
        }
      } while (occupied);
      candidate[node] = new Position(best[node].x(), nextY);
      if (!failure[node]) {
        int firstFailureY = Integer.MAX_VALUE;
        for (int i = 0; i < candidate.length; i++)
          if (failure[i]) firstFailureY = Math.min(firstFailureY, candidate[i].y());
        int shift = Math.max(0, nextY + ROW_GAP - firstFailureY);
        for (int i = 0; i < candidate.length; i++)
          if (failure[i]) candidate[i] = new Position(candidate[i].x(), candidate[i].y() + shift);
      }
      long candidateScore = score(candidate, edges);
      if (candidateScore < bestScore) {
        best = candidate;
        bestScore = candidateScore;
      }
    }
    return best;
  }

  private static List<Edge> forwardEdges(int count, List<Edge> edges) {
    List<List<Edge>> outgoing = new ArrayList<>();
    int[] incoming = new int[count];
    for (int i = 0; i < count; i++) outgoing.add(new ArrayList<>());
    for (Edge edge : edges) {
      outgoing.get(edge.from()).add(edge);
      incoming[edge.to()]++;
    }
    for (var list : outgoing) list.sort(Comparator.comparing(Edge::failure));
    int[] state = new int[count];
    List<Edge> forward = new ArrayList<>();
    for (int i = 0; i < count; i++) if (incoming[i] == 0) visit(i, outgoing, state, forward);
    for (int i = 0; i < count; i++) visit(i, outgoing, state, forward);
    return forward;
  }

  private static void visit(int node, List<List<Edge>> outgoing, int[] state, List<Edge> forward) {
    if (state[node] != 0) return;
    state[node] = 1;
    for (Edge edge : outgoing.get(node)) {
      // Return edges are omitted from ranking only; the original Hop hops remain intact.
      if (state[edge.to()] == 1) continue;
      forward.add(edge);
      visit(edge.to(), outgoing, state, forward);
    }
    state[node] = 2;
  }

  private static int[] ranks(int count, List<Edge> edges) {
    int[] ranks = new int[count];
    // A DAG's longest path is at most count-1. This bounded relaxation also handles merges.
    for (int pass = 0; pass < count; pass++) {
      boolean changed = false;
      for (Edge edge : edges) {
        if (ranks[edge.to()] > ranks[edge.from()]) continue;
        ranks[edge.to()] = ranks[edge.from()] + 1;
        changed = true;
      }
      if (!changed) break;
    }
    return ranks;
  }

  private static boolean[] failureLanes(List<Node> nodes, List<Edge> edges) {
    boolean[] normal = new boolean[nodes.size()];
    boolean[] failure = new boolean[nodes.size()];
    int[] incoming = new int[nodes.size()];
    for (Edge edge : edges) incoming[edge.to()]++;
    for (int i = 0; i < nodes.size(); i++) {
      normal[i] = incoming[i] == 0;
      failure[i] = nodes.get(i).abort();
    }
    for (Edge edge : edges) if (edge.failure()) failure[edge.to()] = true;
    for (int pass = 0; pass < nodes.size(); pass++) {
      for (Edge edge : edges) {
        if (!edge.failure() && normal[edge.from()] && !nodes.get(edge.to()).abort())
          normal[edge.to()] = true;
        if (failure[edge.from()]) failure[edge.to()] = true;
      }
    }
    for (int i = 0; i < nodes.size(); i++) failure[i] &= !normal[i] || nodes.get(i).abort();
    return failure;
  }

  private static Position[] positions(
      List<List<Integer>> layers, boolean[] failure, int gap, int x, int y) {
    int normalRows =
        layers.stream()
            .mapToInt(layer -> (int) layer.stream().filter(i -> !failure[i]).count())
            .max()
            .orElse(0);
    Position[] points = new Position[failure.length];
    for (int column = 0; column < layers.size(); column++) {
      int normalRow = 0;
      int failureRow = normalRows + 1;
      for (int node : layers.get(column)) {
        int row = failure[node] ? failureRow++ : normalRow++;
        points[node] = new Position(x + column * gap, y + row * ROW_GAP);
      }
    }
    return points;
  }

  private static double barycenter(
      int node, List<Edge> edges, Position[] points, boolean predecessors) {
    long total = 0;
    int count = 0;
    for (Edge edge : edges) {
      if ((predecessors ? edge.to() : edge.from()) != node) continue;
      total += points[predecessors ? edge.from() : edge.to()].y();
      count++;
    }
    return count == 0 ? points[node].y() : (double) total / count;
  }

  static Map<String, Integer> metrics(Position[] points, List<Edge> edges) {
    int overlaps = 0;
    int crossings = 0;
    int throughNodes = 0;
    int backward = 0;
    for (int i = 0; i < points.length; i++) {
      for (int j = i + 1; j < points.length; j++) {
        if (Math.abs((long) points[i].x() - points[j].x()) < 64
            && Math.abs((long) points[i].y() - points[j].y()) < 80) overlaps++;
      }
    }
    for (int i = 0; i < edges.size(); i++) {
      Edge a = edges.get(i);
      if (points[a.to()].x() <= points[a.from()].x()) backward++;
      for (int node = 0; node < points.length; node++) {
        if (node != a.from()
            && node != a.to()
            && onSegment(points[a.from()], points[a.to()], points[node])) throughNodes++;
      }
      for (int j = i + 1; j < edges.size(); j++) {
        Edge b = edges.get(j);
        if (a.from() == b.from() || a.from() == b.to() || a.to() == b.from() || a.to() == b.to())
          continue;
        if (intersects(points[a.from()], points[a.to()], points[b.from()], points[b.to()]))
          crossings++;
      }
    }
    return Map.of(
        "crossings",
        crossings,
        "component_overlaps",
        overlaps,
        "edges_through_components",
        throughNodes,
        "backward_hops",
        backward);
  }

  private static long score(Position[] points, List<Edge> edges) {
    Map<String, Integer> metrics = metrics(points, edges);
    long verticalTravel = 0;
    for (Edge edge : edges)
      verticalTravel += Math.abs((long) points[edge.from()].y() - points[edge.to()].y());
    return (metrics.get("crossings") + 4L * metrics.get("edges_through_components"))
            * 1_000_000_000L
        + verticalTravel;
  }

  private static boolean intersects(Position a, Position b, Position c, Position d) {
    return opposite(cross(a, b, c), cross(a, b, d)) && opposite(cross(c, d, a), cross(c, d, b));
  }

  private static boolean opposite(long a, long b) {
    return a < 0 && b > 0 || a > 0 && b < 0;
  }

  private static boolean onSegment(Position a, Position b, Position p) {
    return cross(a, b, p) == 0
        && p.x() >= Math.min(a.x(), b.x())
        && p.x() <= Math.max(a.x(), b.x())
        && p.y() >= Math.min(a.y(), b.y())
        && p.y() <= Math.max(a.y(), b.y());
  }

  private static long cross(Position a, Position b, Position c) {
    return ((long) b.x() - a.x()) * ((long) c.y() - a.y())
        - ((long) b.y() - a.y()) * ((long) c.x() - a.x());
  }
}
