package io.github.michaaels.hop.mcp;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.swt.internal.widgets.MarkupValidator;
import org.junit.jupiter.api.Test;

class HopWebIndicatorTooltipTest {
  @Test
  void nativeRapAcceptsTooltipMarkupAndProjectNamesRemainPlainText() {
    String markup =
        HopWebIndicatorTooltip.markup("Proyecto: <script>alert('x')</script> & pruebas\nActivo");
    // Exercise RAP 4.8's actual markup contract, including its restricted span attributes.
    assertDoesNotThrow(() -> new MarkupValidator().validate(markup));
    assertTrue(markup.contains("&lt;script&gt;"));
    assertTrue(markup.contains("&amp; pruebas<br/>Activo"));
    assertFalse(markup.contains("<script>"));
  }
}
