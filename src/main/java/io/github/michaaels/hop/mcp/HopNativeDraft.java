package io.github.michaaels.hop.mcp;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Private native Hop snapshots; never included in MCP responses or visual event packets. */
record HopNativeDraft(String baselineXml, String resultXml) {
  static final int MAX_BYTES = 4 * 1024 * 1024;

  byte[] encode() throws IOException {
    byte[] before = baselineXml.getBytes(StandardCharsets.UTF_8);
    byte[] after = resultXml.getBytes(StandardCharsets.UTF_8);
    if ((long) before.length + after.length + 8 > MAX_BYTES)
      throw new IOException("Native draft exceeds 4 MiB");
    var bytes = new ByteArrayOutputStream();
    try (var out = new DataOutputStream(bytes)) {
      out.writeInt(before.length);
      out.write(before);
      out.writeInt(after.length);
      out.write(after);
    }
    return bytes.toByteArray();
  }

  static HopNativeDraft decode(byte[] bytes) throws IOException {
    if (bytes.length > MAX_BYTES) throw new IOException("Native draft exceeds 4 MiB");
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      String baseline = readXml(in), result = readXml(in);
      if (in.available() != 0) throw new IOException("Trailing native draft data");
      return new HopNativeDraft(baseline, result);
    }
  }

  private static String readXml(DataInputStream in) throws IOException {
    int size = in.readInt();
    if (size < 0 || size > in.available()) throw new IOException("Invalid native draft length");
    return new String(in.readNBytes(size), StandardCharsets.UTF_8);
  }
}
