package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HopFailureRoutingTest {
  private static final String WORKFLOW =
      """
      <workflow><actions>
      <action><name>Load</name><type>PIPELINE</type></action>
      <action><name>Recover</name><type>DUMMY</type></action>
      <action><name>Notify</name><type>MAIL</type></action>
      <action><name>Fail</name><type>ABORT</type></action>
      </actions><hops>
      <hop><from>Load</from><to>Recover</to><enabled>Y</enabled><evaluation>N</evaluation><unconditional>N</unconditional></hop>
      <hop><from>Recover</from><to>Notify</to><enabled>N</enabled><evaluation>Y</evaluation><unconditional>Y</unconditional></hop>
      </hops></workflow>
      """;

  @Test
  void warnsWhenDisabledNotificationLeavesFailedPipelineAtSuccessResettingDummy() throws Exception {
    var result = HopXml.validate("load.hwf", WORKFLOW);
    assertEquals(true, result.get("valid"));
    List<?> warnings = (List<?>) result.get("warnings");
    assertEquals(1, warnings.size());
    assertTrue(warnings.getFirst().toString().contains("terminal DUMMY action Recover"));
  }

  @Test
  void acceptsFailurePreservationAndDoesNotConfuseFalseConditionsWithFailedExecution()
      throws Exception {
    String abort =
        WORKFLOW.replace(
            "</hops>",
            "<hop><from>Recover</from><to>Fail</to><enabled>Y</enabled><unconditional>Y</unconditional></hop></hops>");
    assertTrue(((List<?>) HopXml.validate("load.hwf", abort).get("warnings")).isEmpty());
    String condition = WORKFLOW.replace("<type>PIPELINE</type>", "<type>EVAL</type>");
    assertTrue(((List<?>) HopXml.validate("condition.hwf", condition).get("warnings")).isEmpty());
    String disabled =
        WORKFLOW.replace(
            "<to>Recover</to><enabled>Y</enabled>", "<to>Recover</to><enabled>N</enabled>");
    assertTrue(((List<?>) HopXml.validate("disabled.hwf", disabled).get("warnings")).isEmpty());
  }
}
