package io.github.michaaels.hop.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/** Local routing declarations; does not initialize transforms or access external systems. */
final class HopRouting {
  private HopRouting() {}

  record Target(String field, String name, String branch, boolean legacy) {}

  record Routes(String field, Map<String, List<String>> branches) {}

  static Map<String, Routes> routes(Element root) {
    Map<String, Routes> result = new LinkedHashMap<>();
    for (var entry : components(root).entrySet()) {
      Map<String, List<String>> byTarget = new LinkedHashMap<>();
      for (Target target : targets(entry.getValue())) {
        if (!target.legacy()) {
          List<String> branches =
              byTarget.computeIfAbsent(target.name(), ignored -> new ArrayList<>());
          if (!branches.contains(target.branch())) branches.add(target.branch());
        }
      }
      result.put(
          entry.getKey(), new Routes(HopXml.childText(entry.getValue(), "fieldname"), byTarget));
    }
    return result;
  }

  static Map<String, Element> components(Element root) {
    Map<String, Element> result = new LinkedHashMap<>();
    String tag = "workflow".equals(root.getTagName()) ? "action" : "transform";
    var nodes = root.getElementsByTagName(tag);
    for (int index = 0; index < nodes.getLength(); index++) {
      Element element = (Element) nodes.item(index);
      String name = HopXml.childText(element, "name");
      if (name != null && !name.isBlank()) result.putIfAbsent(name, element);
    }
    return result;
  }

  static void describeHop(Element edge, Routes source, Map<String, Object> output) {
    Set<String> branches = new LinkedHashSet<>();
    String evaluation = HopXml.childText(edge, "evaluation");
    String unconditional = HopXml.childText(edge, "unconditional");
    if (evaluation != null) output.put("evaluation", truth(evaluation));
    if (unconditional != null) output.put("unconditional", truth(unconditional));
    if (truth(unconditional)) branches.add("unconditional");
    else if (evaluation != null) branches.add(truth(evaluation) ? "success" : "failure");
    if (source != null && evaluation == null && unconditional == null) {
      String to = HopXml.childText(edge, "to");
      branches.addAll(source.branches().getOrDefault(to, List.of()));
      String field = source.field();
      if (field != null && !field.isBlank() && !branches.isEmpty())
        output.put("routing_field", bounded(field));
    }
    if (!branches.isEmpty()) output.put("branches", List.copyOf(branches));
  }

  static boolean truth(String value) {
    return value != null && ("Y".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value));
  }

  static List<Target> targets(Element source) {
    List<Target> targets = new ArrayList<>();
    String type = HopXml.childText(source, "type");
    if ("FilterRows".equals(type)) {
      add(targets, source, "send_true_to", "true", false);
      add(targets, source, "send_false_to", "false", false);
    } else if ("SwitchCase".equals(type)) {
      add(targets, source, "default_target_transform", "default", false);
      var cases = source.getElementsByTagName("case");
      for (int index = 0; index < cases.getLength(); index++) {
        add(targets, (Element) cases.item(index), "target_transform", "case", false);
      }
    } else if ("WorkflowExecutor".equals(type) || "PipelineExecutor".equals(type)) {
      for (String group : List.of("execution_result", "result_rows", "result_files")) {
        add(targets, source, group + "_target_transform", group, false);
        add(targets, source, group + "_target_step", group, true);
      }
    }
    return targets;
  }

  private static void add(
      List<Target> targets, Element source, String field, String branch, boolean legacy) {
    String name = HopXml.childText(source, field);
    if (name != null && !name.isBlank()) targets.add(new Target(field, name, branch, legacy));
  }

  static boolean validate(
      Element root, List<Map<String, Object>> hops, List<String> errors, List<String> warnings) {
    Map<String, Element> components = components(root);
    Set<String> enabled = new HashSet<>();
    for (var hop : hops) {
      if (!Boolean.FALSE.equals(hop.get("enabled")))
        enabled.add(hop.get("from") + "\u0000" + hop.get("to"));
    }
    boolean truncated = false;
    for (var entry : components.entrySet()) {
      if ("Repeat".equals(HopXml.childText(entry.getValue(), "type"))) {
        truncated |= validateRepeat(entry.getKey(), entry.getValue(), errors, warnings);
      }
      Set<String> reported = new HashSet<>();
      for (Target target : targets(entry.getValue())) {
        if (!reported.add(target.field() + "\u0000" + target.name())) continue;
        String where = "Component " + bounded(entry.getKey()) + " field " + target.field();
        if (target.legacy()) {
          truncated |=
              diagnostic(
                  warnings,
                  where
                      + " uses a legacy *_target_step property; convert it to *_target_transform.");
        }
        if (!components.containsKey(target.name())) {
          truncated |=
              diagnostic(errors, where + " points to missing transform: " + bounded(target.name()));
        } else if (!enabled.contains(entry.getKey() + "\u0000" + target.name())) {
          truncated |=
              diagnostic(
                  warnings, where + " has no enabled outgoing hop to: " + bounded(target.name()));
        }
      }
    }
    truncated |= validateTerminalFailureRecovery(root, components, hops, warnings);
    for (String reference : HopDefinitionReferences.extract(root, ProjectFiles.MAX_RESULTS + 1)) {
      if (reference.contains("${Internal.Pipeline.Filename.Folder}")) {
        truncated |=
            diagnostic(
                warnings,
                "Internal.Pipeline.Filename.Folder is not a native pipeline folder variable; use ${Internal.Pipeline.Filename.Directory}.");
      }
    }
    return truncated;
  }

  private static boolean validateTerminalFailureRecovery(
      Element root,
      Map<String, Element> components,
      List<Map<String, Object>> hops,
      List<String> warnings) {
    if (!"workflow".equals(root.getTagName())) return false;
    Set<String> outgoing = new HashSet<>();
    Set<String> failedActionTargets = new HashSet<>();
    for (var hop : hops) {
      if (Boolean.FALSE.equals(hop.get("enabled"))) continue;
      String from = String.valueOf(hop.get("from"));
      outgoing.add(from);
      Element source = components.get(from);
      String type = source == null ? null : HopXml.childText(source, "type");
      boolean executable = "PIPELINE".equals(type) || "WORKFLOW".equals(type) || "SQL".equals(type);
      if (executable
          && Boolean.FALSE.equals(hop.get("evaluation"))
          && !Boolean.TRUE.equals(hop.get("unconditional"))) {
        failedActionTargets.add(String.valueOf(hop.get("to")));
      }
    }
    boolean truncated = false;
    for (String target : failedActionTargets) {
      Element action = components.get(target);
      if (action == null
          || outgoing.contains(target)
          || !"DUMMY".equals(HopXml.childText(action, "type"))) continue;
      truncated |=
          diagnostic(
              warnings,
              "Failure route ends at terminal DUMMY action "
                  + bounded(target)
                  + "; this can reset a failed action to success. Verify recovery is intentional or preserve failure with an Abort action.");
    }
    return truncated;
  }

  private static boolean validateRepeat(
      String name, Element action, List<String> errors, List<String> warnings) {
    boolean truncated = false;
    String where = "Repeat action " + bounded(name);
    for (String field : List.of("filename", "run_configuration")) {
      String value = HopXml.childText(action, field);
      if (value == null || value.isBlank()) {
        truncated |= diagnostic(errors, where + " requires a non-empty " + field + ".");
      }
    }
    String delay = HopXml.childText(action, "delay");
    if (delay != null && !delay.isBlank() && !delay.contains("${") && !delay.contains("%%")) {
      try {
        long seconds = Long.parseLong(delay.trim());
        if (seconds < 0 || seconds > Long.MAX_VALUE / 1000) throw new NumberFormatException();
      } catch (NumberFormatException invalidDelay) {
        truncated |=
            diagnostic(errors, where + " delay must be a non-negative integer in seconds.");
      }
    }
    String stopVariable = HopXml.childText(action, "variable_name");
    if (stopVariable == null || stopVariable.isBlank()) {
      truncated |=
          diagnostic(
              warnings,
              where
                  + " has no stop variable; verify that the repeated child workflow reaches End repeat.");
    }
    return truncated;
  }

  private static boolean diagnostic(List<String> output, String message) {
    if (output.size() >= ProjectFiles.MAX_RESULTS) return true;
    output.add(HopXml.redact(message));
    return false;
  }

  private static String bounded(String text) {
    return text.length() > 256 ? text.substring(0, 256) : text;
  }
}
