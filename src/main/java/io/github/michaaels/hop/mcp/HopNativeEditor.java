package io.github.michaaels.hop.mcp;

import static io.github.michaaels.hop.mcp.HopGuidedPresentation.*;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.hop.core.exception.HopException;
import org.apache.hop.core.gui.plugin.GuiRegistry;
import org.apache.hop.pipeline.*;
import org.apache.hop.pipeline.debug.PipelineDebugMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.ui.hopgui.HopGui;
import org.apache.hop.ui.hopgui.file.IHopFileTypeHandler;
import org.apache.hop.ui.hopgui.file.pipeline.*;
import org.apache.hop.ui.hopgui.file.pipeline.context.HopGuiPipelineTransformContext;
import org.apache.hop.ui.hopgui.file.workflow.*;
import org.apache.hop.ui.hopgui.file.workflow.context.HopGuiWorkflowActionContext;
import org.apache.hop.ui.hopgui.perspective.explorer.ExplorerPerspective;
import org.apache.hop.workflow.*;
import org.apache.hop.workflow.action.ActionMeta;
import org.eclipse.swt.widgets.Composite;

/** Animates real Hop metadata in Hop's own editors. The temporary draft cannot save or execute. */
final class HopNativeEditor {
  private final HopGui hopGui;
  private final PipelineGraph pipelineGraph;
  private final WorkflowGraph workflowGraph;
  private final Map<String, TransformMeta> transforms = new LinkedHashMap<>();
  private final Map<String, ActionMeta> actions = new LinkedHashMap<>();

  HopNativeEditor(
      Composite parent,
      HopGui hopGui,
      HopGuidedPresentation plan,
      HopNativeDraft draft,
      Runnable blocked)
      throws Exception {
    this.hopGui = hopGui;
    var perspective = ExplorerPerspective.getInstance();
    if (plan.kind().equals("pipeline")) {
      var meta = pipeline(draft.baselineXml());
      pipelineGraph =
          nativeCallbacks(
              hopGui, () -> new PipelineGraph(parent, hopGui, perspective, meta, blocked));
      workflowGraph = null;
    } else {
      var meta = workflow(draft.baselineXml());
      workflowGraph =
          nativeCallbacks(
              hopGui, () -> new WorkflowGraph(parent, hopGui, perspective, meta, blocked));
      pipelineGraph = null;
    }
    control().setEnabled(false);
    update(draft);
  }

  IHopFileTypeHandler handler() {
    return pipelineGraph != null ? pipelineGraph : workflowGraph;
  }

  Composite control() {
    return pipelineGraph != null ? pipelineGraph : workflowGraph;
  }

  void update(HopNativeDraft draft) throws Exception {
    if (pipelineGraph != null) {
      var baseline = pipeline(draft.baselineXml());
      var result = pipeline(draft.resultXml());
      transforms.clear();
      baseline.getTransforms().forEach(t -> transforms.put(t.getName(), t));
      result.getTransforms().forEach(t -> transforms.put(t.getName(), t));
      pipelineGraph.setPipelineMeta(result);
    } else {
      var baseline = workflow(draft.baselineXml());
      var result = workflow(draft.resultXml());
      actions.clear();
      baseline.getActions().forEach(a -> actions.put(a.getName(), a));
      result.getActions().forEach(a -> actions.put(a.getName(), a));
      workflowGraph.setWorkflowMeta(result);
    }
  }

  void render(HopPresentationQueue queue) {
    var before = new LinkedHashMap<String, Node>();
    queue.from().nodes().forEach(n -> before.put(n.name(), n));
    var after = new LinkedHashMap<String, Node>();
    queue.to().nodes().forEach(n -> after.put(n.name(), n));
    double fraction = queue.fraction();
    var visible = fraction < .5 ? before : after;
    String active = queue.active() == null ? "" : queue.active().component();
    List<Node> positions = new ArrayList<>();
    for (Node node : visible.values()) {
      Node start = before.getOrDefault(node.name(), node),
          end = after.getOrDefault(node.name(), node);
      positions.add(
          node.move(
              (int) Math.round(start.x() + (end.x() - start.x()) * fraction),
              (int) Math.round(start.y() + (end.y() - start.y()) * fraction)));
    }
    List<Edge> edges = fraction < .5 ? queue.from().edges() : queue.to().edges();
    if (pipelineGraph != null) renderPipeline(positions, edges, active);
    else renderWorkflow(positions, edges, active);
  }

  private void renderPipeline(List<Node> nodes, List<Edge> edges, String active) {
    var meta = pipelineGraph.getPipelineMeta();
    populate(meta, nodes, edges, active, transforms);
    pipelineGraph.redraw();
  }

  static void populate(
      PipelineMeta meta,
      List<Node> nodes,
      List<Edge> edges,
      String active,
      Map<String, TransformMeta> transforms) {
    meta.setTransforms(new ArrayList<>());
    while (!meta.getPipelineHops().isEmpty()) meta.removePipelineHop(0);
    for (Node node : nodes) {
      TransformMeta transform = transforms.get(node.name());
      if (transform == null) continue;
      transform.setLocation(node.x(), node.y());
      transform.setSelected(node.name().equals(active));
      meta.addTransform(transform);
    }
    for (Edge edge : edges) {
      var from = meta.findTransform(edge.from());
      var to = meta.findTransform(edge.to());
      if (from == null || to == null) continue;
      var hop = new PipelineHopMeta(from, to);
      hop.setEnabled(edge.enabled());
      hop.setErrorHop(edge.branch() < 0);
      meta.addPipelineHop(hop);
    }
    meta.clearChanged();
  }

  private void renderWorkflow(List<Node> nodes, List<Edge> edges, String active) {
    var meta = workflowGraph.getWorkflowMeta();
    populate(meta, nodes, edges, active, actions);
    workflowGraph.redraw();
  }

  static void populate(
      WorkflowMeta meta,
      List<Node> nodes,
      List<Edge> edges,
      String active,
      Map<String, ActionMeta> actions) {
    while (!meta.getActions().isEmpty()) meta.removeAction(0);
    meta.setWorkflowHops(new ArrayList<>());
    for (Node node : nodes) {
      ActionMeta action = actions.get(node.name());
      if (action == null) continue;
      action.setLocation(node.x(), node.y());
      action.setSelected(node.name().equals(active));
      meta.addAction(action);
    }
    for (Edge edge : edges) {
      var from = meta.findAction(edge.from());
      var to = meta.findAction(edge.to());
      if (from == null || to == null) continue;
      var hop = new WorkflowHopMeta(from, to);
      hop.setEnabled(edge.enabled());
      hop.setUnconditional(edge.branch() == 0);
      hop.setEvaluation(edge.branch() > 0);
      meta.addWorkflowHop(hop);
    }
    meta.clearChanged();
  }

  private PipelineMeta pipeline(String xml) throws Exception {
    var document = HopXml.parse(xml);
    var meta = new PipelineMeta(document.getDocumentElement(), hopGui.getMetadataProvider());
    meta.setMetadataProvider(hopGui.getMetadataProvider());
    meta.clearChanged();
    return meta;
  }

  private WorkflowMeta workflow(String xml) throws Exception {
    var document = HopXml.parse(xml);
    var meta =
        new WorkflowMeta(
            document.getDocumentElement(), hopGui.getMetadataProvider(), hopGui.getVariables());
    meta.clearChanged();
    return meta;
  }

  private static HopException readOnly() {
    return new HopException("Borrador MCP: aplicar el plan antes de guardar o ejecutar.");
  }

  /**
   * Hop's private toolbar constructor registers getClass(), but its callbacks name the base graph.
   * Add those aliases at registration time, before Hop resolves combo values. Scope the adapter to
   * this GUI and this constructor, and retain all registrations using ordinary maps afterwards.
   */
  private static <T> T nativeCallbacks(HopGui gui, Supplier<T> create) {
    var registry = GuiRegistry.getInstance().getGuiPluginObjectsMap();
    var previous = registry.get(gui.getId());
    Map<String, Map<String, Object>> scoped =
        new HashMap<>() {
          @Override
          public Map<String, Object> computeIfAbsent(
              String id, Function<? super String, ? extends Map<String, Object>> mapping) {
            return super.computeIfAbsent(
                id,
                ignored ->
                    new HashMap<>() {
                      @Override
                      public Object put(String name, Object object) {
                        if (object instanceof PipelineGraph)
                          super.put(HopGuiPipelineGraph.class.getName(), object);
                        if (object instanceof WorkflowGraph)
                          super.put(HopGuiWorkflowGraph.class.getName(), object);
                        return super.put(name, object);
                      }
                    });
          }
        };
    if (previous != null) scoped.putAll(previous);
    registry.put(gui.getId(), scoped);
    try {
      return create.get();
    } finally {
      Map<String, Map<String, Object>> restored = previous == null ? new HashMap<>() : previous;
      scoped.forEach((id, objects) -> restored.put(id, new HashMap<>(objects)));
      registry.put(gui.getId(), restored);
    }
  }

  private static final class PipelineGraph extends HopGuiPipelineGraph {
    private final Runnable blocked;

    PipelineGraph(
        Composite parent,
        HopGui gui,
        ExplorerPerspective perspective,
        PipelineMeta meta,
        Runnable blocked) {
      super(parent, gui, perspective, meta, perspective.getPipelineFileType());
      this.blocked = blocked;
    }

    @Override
    public String getFilename() {
      // Native session persistence must not reopen a temporary presentation as a real file.
      return null;
    }

    @Override
    public void start() {
      blocked.run();
    }

    @Override
    public synchronized void start(PipelineExecutionConfiguration configuration)
        throws HopException {
      throw readOnly();
    }

    @Override
    public void preview() {
      blocked.run();
    }

    @Override
    public void preview(HopGuiPipelineTransformContext context) {
      blocked.run();
    }

    @Override
    public void debug() {
      blocked.run();
    }

    @Override
    public void debug(HopGuiPipelineTransformContext context) {
      blocked.run();
    }

    @Override
    public synchronized void debug(PipelineExecutionConfiguration config, PipelineDebugMeta debug) {
      blocked.run();
    }

    @Override
    public synchronized void save() throws HopException {
      throw readOnly();
    }

    @Override
    public void saveAs(String filename) throws HopException {
      throw readOnly();
    }

    @Override
    public void reload() {
      blocked.run();
    }
  }

  private static final class WorkflowGraph extends HopGuiWorkflowGraph {
    private final Runnable blocked;

    WorkflowGraph(
        Composite parent,
        HopGui gui,
        ExplorerPerspective perspective,
        WorkflowMeta meta,
        Runnable blocked) {
      super(parent, gui, perspective, meta, perspective.getWorkflowFileType());
      this.blocked = blocked;
    }

    @Override
    public String getFilename() {
      return null;
    }

    @Override
    public void start() {
      blocked.run();
    }

    @Override
    public synchronized void start(WorkflowExecutionConfiguration configuration)
        throws HopException {
      throw readOnly();
    }

    @Override
    public void startWorkflowHere(HopGuiWorkflowActionContext context) {
      blocked.run();
    }

    @Override
    public void preview() {
      blocked.run();
    }

    @Override
    public void debug() {
      blocked.run();
    }

    @Override
    public synchronized void save() throws HopException {
      throw readOnly();
    }

    @Override
    public void saveAs(String filename) throws HopException {
      throw readOnly();
    }

    @Override
    public void reload() {
      blocked.run();
    }
  }
}
