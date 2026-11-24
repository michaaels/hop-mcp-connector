package io.github.michaaels.hop.mcp;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Small authoring defaults and advisory checks; never rewrites executable code. */
final class HopAuthoringGuidance {
  static final String INSTRUCTIONS =
      " Prefer native Hop components and the smallest clear flow that preserves required behavior."
          + " Reuse a reader for files with the same format; avoid redundant checks and helper flows."
          + " Add destination fields only when requested or required by the business contract."
          + " Declare public inputs of reusable definitions as named parameters and preserve parent parameter passing."
          + " Preserve failed execution status; avoid ending failed pipeline or SQL branches at success-resetting dummy actions."
          + " Keep necessary scripts short, focused, and formatted with line breaks and indentation."
          + " Finish new flows with the auto_layout semantic operation: left-to-right stages, aligned branches and separated workflow failure lanes."
          + " For existing flows preview layout first; preserve routing and execution settings, review notes and remaining crossings, and never add dummy components merely to bend hops."
          + " If auto_layout reports review_required, inspect the remaining geometry before describing the canvas as finished; never change business logic just to improve a drawing."
          + " Before preparing each new pipeline or workflow, announce its destination in chat: the Hop Web URL, project and relative path, or the full physical path in Hop Desktop. Read hop_config for project_root and presentation_default. In Web, query hop_live_ui_status and mention confirmed work by other sessions on the same project. Distinguish a present session from an edit. If another session works on the same file or DEFINITION_IN_USE is returned, wait for coordination. Work on other files can continue."
          + " Respect presentation_default unless the user chooses a mode for this task. For guided presentation, start with operations=[] and append small blocks with previous_plan_id."
          + " Preparation returns without waiting for GUI playback: continue planning the next block, then apply the final immutable plan."
          + " For Result Only, use presentation=result_only. Apply auxiliary definitions with show_result=false, then apply the main definition with show_result=true. Use instant for legacy immediate presentation. End with a chat summary of what was created, how flows connect, and actual validation results."
          + " Formatting warnings are advisory; never rewrite or execute scripts automatically.";

  private static final Set<String> SCRIPT_PLUGINS = Set.of("ScriptValueMod", "EVAL", "JavaScript");

  private HopAuthoringGuidance() {}

  static boolean checkScriptStyle(Element component, List<String> warnings) {
    String plugin = HopXml.childText(component, "type");
    if (plugin == null || !SCRIPT_PLUGINS.contains(plugin)) return false;
    NodeList fields = component.getElementsByTagName("*");
    for (int index = 0; index < fields.getLength(); index++) {
      Element field = (Element) fields.item(index);
      String tag = field.getTagName().toLowerCase(Locale.ROOT);
      if (!tag.equals("jsscript_script") && !tag.equals("script")) continue;
      String script = field.getTextContent().strip();
      List<String> lines = script.lines().filter(line -> !line.isBlank()).toList();
      boolean compressed =
          lines.size() == 1
              && script.length() > 120
              && script.chars().filter(character -> character == ';').count() > 1;
      boolean longLine = lines.stream().anyMatch(line -> line.length() > 240);
      if (!compressed && !longLine) continue;
      if (warnings.size() >= ProjectFiles.MAX_RESULTS) return true;
      warnings.add(
          "Review script formatting in component '"
              + HopXml.childText(component, "name")
              + "': use short statements, line breaks and indentation.");
      break;
    }
    return false;
  }
}
