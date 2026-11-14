package io.github.michaaels.hop.mcp;

import java.io.*;
import java.util.*;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.workflow.WorkflowMeta;

/**
 * Whitelisted visual data only: never carries SQL, scripts, connection values or executable XML.
 */
record HopGuidedPresentation(
    String id,
    int revision,
    long updatedAt,
    String path,
    String kind,
    String mode,
    String state,
    Graph baseline,
    Graph result,
    List<Step> steps,
    String snapshotSha256) {
  static final int MAX_BYTES = 128 * 1024;
  static final int MAX_NODES = 200;
  static final int MAX_EDGES = 1000;
  static final int MAX_STEPS = HopDefinitionMutator.MAX_OPERATIONS;

  HopGuidedPresentation(
      String id,
      int revision,
      long updatedAt,
      String path,
      String kind,
      String mode,
      String state,
      Graph baseline,
      Graph result,
      List<Step> steps) {
    this(id, revision, updatedAt, path, kind, mode, state, baseline, result, steps, "");
  }

  record Node(String name, String plugin, int x, int y) {
    Node {
      name = text(name);
      plugin = text(plugin);
      x = coordinate(x);
      y = coordinate(y);
    }

    Node move(int nextX, int nextY) {
      return new Node(name, plugin, nextX, nextY);
    }
  }

  record Edge(String from, String to, boolean enabled, int branch) {
    Edge {
      from = text(from);
      to = text(to);
      if (branch < -1 || branch > 1) throw new IllegalArgumentException("Invalid visual branch");
    }
  }

  record Graph(List<Node> nodes, List<Edge> edges) {
    Graph {
      nodes = List.copyOf(nodes);
      edges = List.copyOf(edges);
      bound(nodes.size(), MAX_NODES);
      bound(edges.size(), MAX_EDGES);
      Set<String> names = new HashSet<>();
      for (Node node : nodes)
        if (!names.add(node.name())) throw new IllegalArgumentException("Duplicate visual name");
      for (Edge edge : edges)
        if (!names.contains(edge.from()) || !names.contains(edge.to()))
          throw new IllegalArgumentException("Unknown visual endpoint");
    }
  }

  record Step(String operation, String component, String target, String plugin, int x, int y) {
    Step {
      if (!HopSemanticCapabilities.OPERATION_NAMES.contains(operation))
        throw new IllegalArgumentException("Invalid visual operation");
      component = text(component);
      target = text(target);
      plugin = text(plugin);
      x = coordinate(x);
      y = coordinate(y);
    }

    String label() {
      return switch (operation) {
        case "add_component" -> "Crear " + component;
        case "rename_component" -> "Renombrar " + component + " → " + target;
        case "move_component" -> "Mover " + component;
        case "add_hop" -> "Conectar " + component + " → " + target;
        case "remove_hop" -> "Retirar conexión " + component + " → " + target;
        case "remove_component" -> "Retirar " + component;
        case "update_component" -> "Configurar " + component;
        case "auto_layout" -> "Distribuir y alinear el flujo";
        case "add_parameter", "remove_parameter" -> "Actualizar parámetros";
        default -> "Actualizar información del flujo";
      };
    }
  }

  HopGuidedPresentation {
    if (!UUID.fromString(id).toString().equals(id))
      throw new IllegalArgumentException("Invalid presentation ID");
    if (revision < 1 || revision > 100)
      throw new IllegalArgumentException("Invalid presentation revision");
    if (path == null || path.length() > 4096 || !Set.of("pipeline", "workflow").contains(kind))
      throw new IllegalArgumentException("Invalid presentation definition");
    mode = mode(mode);
    if (!Set.of("prepared", "applying", "applied", "failed", "cancelled").contains(state))
      throw new IllegalArgumentException("Invalid presentation state");
    steps = List.copyOf(steps);
    bound(steps.size(), MAX_STEPS);
    Objects.requireNonNull(baseline);
    Objects.requireNonNull(result);
    if (snapshotSha256 == null
        || !snapshotSha256.isEmpty() && !snapshotSha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid native draft fingerprint");
  }

  static String mode(String mode) {
    if (!Set.of("guided", "instant").contains(mode == null ? "" : mode))
      throw new IllegalArgumentException("presentation must be guided or instant");
    return mode;
  }

  static List<Step> steps(List<Map<String, Object>> operations) {
    return operations.stream()
        .map(
            op -> {
              String operation = String.valueOf(op.get("operation"));
              String component =
                  String.valueOf(
                      op.getOrDefault(
                          "component", op.getOrDefault("name", op.getOrDefault("from", ""))));
              String target =
                  String.valueOf(op.getOrDefault("new_name", op.getOrDefault("to", "")));
              return new Step(
                  operation,
                  component,
                  target,
                  String.valueOf(op.getOrDefault("plugin_id", "")),
                  number(op.get("x")),
                  number(op.get("y")));
            })
        .toList();
  }

  static Graph graph(PipelineMeta meta) {
    List<Node> nodes =
        meta.getTransforms().stream()
            .map(t -> new Node(t.getName(), t.getPluginId(), t.getLocation().x, t.getLocation().y))
            .toList();
    List<Edge> edges =
        meta.getPipelineHops().stream()
            .map(
                h ->
                    new Edge(
                        h.getFromTransform().getName(),
                        h.getToTransform().getName(),
                        h.isEnabled(),
                        h.isErrorHop() ? -1 : 0))
            .toList();
    return new Graph(nodes, edges);
  }

  static Graph graph(WorkflowMeta meta) {
    List<Node> nodes =
        meta.getActions().stream()
            .map(
                a ->
                    new Node(
                        a.getName(),
                        a.getAction().getPluginId(),
                        a.getLocation().x,
                        a.getLocation().y))
            .toList();
    List<Edge> edges =
        meta.getWorkflowHops().stream()
            .map(
                h ->
                    new Edge(
                        h.getFromAction().getName(),
                        h.getToAction().getName(),
                        h.isEnabled(),
                        h.isUnconditional() ? 0 : h.isEvaluation() ? 1 : -1))
            .toList();
    return new Graph(nodes, edges);
  }

  byte[] encode() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeInt(2);
      out.writeUTF(id);
      out.writeInt(revision);
      out.writeLong(updatedAt);
      out.writeUTF(path);
      out.writeUTF(kind);
      out.writeUTF(mode);
      out.writeUTF(state);
      out.writeUTF(snapshotSha256);
      writeGraph(out, baseline);
      writeGraph(out, result);
      out.writeInt(steps.size());
      for (Step step : steps) {
        out.writeUTF(step.operation());
        out.writeUTF(step.component());
        out.writeUTF(step.target());
        out.writeUTF(step.plugin());
        out.writeInt(step.x());
        out.writeInt(step.y());
      }
    }
    if (bytes.size() > MAX_BYTES) throw new IOException("Visual preview exceeds 128 KiB");
    return bytes.toByteArray();
  }

  static HopGuidedPresentation decode(byte[] bytes) throws IOException {
    if (bytes.length > MAX_BYTES) throw new IOException("Visual preview exceeds 128 KiB");
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != 2) throw new IOException("Unsupported visual preview format");
      String id = in.readUTF();
      int revision = in.readInt();
      long time = in.readLong();
      String path = in.readUTF(), kind = in.readUTF(), mode = in.readUTF(), state = in.readUTF();
      String snapshotSha256 = in.readUTF();
      Graph baseline = readGraph(in), result = readGraph(in);
      int count = bound(in.readInt(), MAX_STEPS);
      List<Step> steps = new ArrayList<>();
      for (int i = 0; i < count; i++)
        steps.add(
            new Step(
                in.readUTF(),
                in.readUTF(),
                in.readUTF(),
                in.readUTF(),
                in.readInt(),
                in.readInt()));
      if (in.available() != 0) throw new IOException("Trailing visual preview data");
      return new HopGuidedPresentation(
          id, revision, time, path, kind, mode, state, baseline, result, steps, snapshotSha256);
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid visual preview", e);
    }
  }

  private static void writeGraph(DataOutputStream out, Graph graph) throws IOException {
    out.writeInt(graph.nodes().size());
    Map<String, Integer> indices = new HashMap<>();
    for (int i = 0; i < graph.nodes().size(); i++) {
      Node n = graph.nodes().get(i);
      indices.put(n.name(), i);
      out.writeUTF(n.name());
      out.writeUTF(n.plugin());
      out.writeInt(n.x());
      out.writeInt(n.y());
    }
    out.writeInt(graph.edges().size());
    for (Edge edge : graph.edges()) {
      out.writeInt(indices.get(edge.from()));
      out.writeInt(indices.get(edge.to()));
      out.writeBoolean(edge.enabled());
      out.writeInt(edge.branch());
    }
  }

  private static Graph readGraph(DataInputStream in) throws IOException {
    int count = bound(in.readInt(), MAX_NODES);
    List<Node> nodes = new ArrayList<>();
    for (int i = 0; i < count; i++)
      nodes.add(new Node(in.readUTF(), in.readUTF(), in.readInt(), in.readInt()));
    count = bound(in.readInt(), MAX_EDGES);
    List<Edge> edges = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      int from = in.readInt(), to = in.readInt();
      if (from < 0 || to < 0 || from >= nodes.size() || to >= nodes.size())
        throw new IOException("Invalid visual endpoint");
      edges.add(
          new Edge(nodes.get(from).name(), nodes.get(to).name(), in.readBoolean(), in.readInt()));
    }
    return new Graph(nodes, edges);
  }

  private static int number(Object value) {
    return value instanceof Number n ? n.intValue() : 50;
  }

  private static int coordinate(int value) {
    return Math.max(0, Math.min(1_000_000, value));
  }

  private static int bound(int value, int maximum) {
    if (value < 0 || value > maximum)
      throw new IllegalArgumentException("Visual preview count exceeds bound");
    return value;
  }

  private static String text(String value) {
    String clean = HopXml.redact(value == null ? "" : value).replace('\r', ' ').replace('\n', ' ');
    return clean.substring(0, Math.min(clean.length(), 160));
  }
}
