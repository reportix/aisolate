package com.reportix.aisolate.backend.domain.deploy;

import com.reportix.aisolate.plugins.testing.HandlerJars;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A Python project's artifact, editable per test. */
public final class PythonArtifacts {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The HR tools' manifest: one plugin, two tools, one entity, two handlers, one rule file. */
  public static ObjectNode manifest() {
    return (ObjectNode)
        JSON.readTree(
            """
            {"schema": 1, "language": "python", "sdk": "0.1.0", "python": "3.13", "arch": "aarch64",
             "package": "acme_hr",
             "plugins": [{"name": "hr-tools", "class": "acme_hr.tools.hr_tools.HrTools",
                          "data_source": "acme_hr", "identity_attribute": "employee_id",
                          "entities": ["hr.compensation"],
                          "tools": ["aggregate_records", "read_records"]}],
             "tools": [
               {"name": "aggregate_records", "description": "Aggregate", "plugin": "hr-tools",
                "data_source": "acme_hr", "input_schema": {"type": "object"}},
               {"name": "read_records", "description": "Read", "plugin": "hr-tools",
                "data_source": "acme_hr", "input_schema": {"type": "object"}}],
             "entities": [
               {"name": "hr.compensation", "plugin": "hr-tools", "data_source": "acme_hr",
                "from": "hr.compensation c JOIN hr.employees e ON e.id = c.employee_id",
                "row_key": "employee_id", "subject": "employee_id", "subject_name": "full_name",
                "period": null,
                "columns": [
                  {"name": "employee_id", "expression": "c.employee_id", "type": "number",
                   "unit": null, "unit_column": null, "decimals": null},
                  {"name": "full_name", "expression": "e.full_name", "type": "text",
                   "unit": null, "unit_column": null, "decimals": null},
                  {"name": "gross_salary", "expression": "c.gross_salary", "type": "number",
                   "unit": "EUR", "unit_column": null, "decimals": 2}]}],
             "handlers": [
               {"name": "SalaryVerifier", "class": "acme_hr.post_llm.salary.SalaryVerifier",
                "stage": "answerVerifier", "order": 0, "description": null},
               {"name": "HandbookPreparator",
                "class": "acme_hr.pre_llm.handbook.HandbookPreparator",
                "stage": "contextPreparator", "order": 1, "description": "Reads the handbook",
                "data_sources": ["hr"]}],
             "rules": ["rules/hr.compensation.json"],
             "vendored": []}
            """);
  }

  /** The zip's files beside the manifest: the package and the rule file it lists. */
  public static Map<String, byte[]> files() {
    Map<String, byte[]> files = new LinkedHashMap<>();
    files.put("src/acme_hr/__init__.py", new byte[0]);
    files.put("rules/hr.compensation.json", HandlerJars.rulesFile("hr.compensation"));
    return files;
  }

  /** The bytes as the deploy reads an upload: a file, deleted when the JVM exits. */
  public static Path write(byte[] bytes) {
    try {
      Path file = Files.createTempFile("artifact-", ".zip");
      file.toFile().deleteOnExit();
      return Files.write(file, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static byte[] zip() {
    return zip(manifest(), files());
  }

  public static byte[] zip(ObjectNode manifest, Map<String, byte[]> files) {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    if (manifest != null) {
      entries.put(
          ManifestReader.MANIFEST,
          JSON.writeValueAsString(manifest).getBytes(StandardCharsets.UTF_8));
    }
    entries.putAll(files);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /**
   * The zip with {@code name} marked a Unix symlink in its central directory, as {@code zip
   * --symlinks} writes one; Java's ZipOutputStream cannot.
   */
  public static byte[] withSymlink(byte[] zip, String name) {
    ByteBuffer buffer = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
    byte[] wanted = name.getBytes(StandardCharsets.UTF_8);
    for (int at = 0; at + 46 < zip.length; at++) {
      if (buffer.getInt(at) == 0x02014b50
          && Short.toUnsignedInt(buffer.getShort(at + 28)) == wanted.length
          && new String(zip, at + 46, wanted.length, StandardCharsets.UTF_8).equals(name)) {
        buffer.putShort(at + 4, (short) (3 << 8 | 20));
        buffer.putInt(at + 38, 0xA1FF << 16);
        return zip;
      }
    }
    throw new IllegalArgumentException(name + " is not in the zip");
  }

  private PythonArtifacts() {}
}
