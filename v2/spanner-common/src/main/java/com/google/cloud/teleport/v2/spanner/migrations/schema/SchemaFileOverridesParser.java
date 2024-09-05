package com.google.cloud.teleport.v2.spanner.migrations.schema;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchemaFileOverridesParser implements ISchemaOverridesParser, Serializable {

  private static final Logger LOG = LoggerFactory.getLogger(SchemaFileOverridesParser.class);
  private static Gson gson = new Gson();

  final SchemaFileOverride schemaFileOverride;
  public SchemaFileOverridesParser(String overridesFilePath) {
    try (InputStream stream =
        Channels.newInputStream(FileSystems.open(FileSystems.matchNewResource(overridesFilePath, false)))) {
      String result = IOUtils.toString(stream, StandardCharsets.UTF_8);
      schemaFileOverride = gson.fromJson(result, SchemaFileOverride.class);
      LOG.info("schemaFileOverride = " + schemaFileOverride.toString());
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Failed to read schema overrides file. Make sure it is ASCII or UTF-8 encoded and contains a"
              + " well-formed JSON string.",
          e);
    }
  }

  /**
   * Gets the spanner table name given the source table name, or source table name if no override is
   * configured.
   *
   * @param sourceTableName The source table name
   * @return The overridden spanner table name
   */
  @Override
  public String getTableOverrideOrDefault(String sourceTableName) {
     if (schemaFileOverride.getRenamedTables() == null) {
       return sourceTableName;
     }
    return schemaFileOverride.getRenamedTables().getOrDefault(sourceTableName, sourceTableName);
  }

  /**
   * Gets the spanner column name given the source table name, or the source column name if override
   * is configured.
   *
   * @param sourceTableName the source table name for which column name is overridden
   * @param sourceColumnName the source column name being overridden
   * @return A pair of spannerTableName and spannerColumnName
   */
  @Override
  public Pair<String, String> getColumnOverrideOrDefault(String sourceTableName,
      String sourceColumnName) {
    if (schemaFileOverride.getRenamedColumns() == null || schemaFileOverride.getRenamedColumns().get(sourceTableName) == null) {
      return new ImmutablePair<>(sourceTableName, sourceColumnName);
    }
    Map<String, String> tableOverridesMap =  schemaFileOverride.getRenamedColumns().get(sourceTableName);
    return new ImmutablePair<>(sourceTableName, tableOverridesMap.getOrDefault(sourceColumnName, sourceColumnName));
  }

  @Override
  public String toString() {
    return "SchemaFileOverridesParser{" +
        "schemaFileOverride=" + schemaFileOverride +
        '}';
  }
}
