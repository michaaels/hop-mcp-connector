package io.github.michaaels.hop.mcp;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.hop.metadata.api.HopMetadata;
import org.apache.hop.metadata.api.HopMetadataProperty;
import org.apache.hop.metadata.api.IHopMetadata;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.metadata.api.IHopMetadataSerializer;

/** Read-only, bounded access to native Apache Hop metadata. */
final class HopMetadataService {
  static final int MAX_METADATA_TYPES = 200;
  static final int MAX_METADATA_TYPES_SCAN = 1_000;
  static final int MAX_METADATA_RESULTS = 200;
  static final int MAX_METADATA_OBJECTS_SCAN = 5_000;
  static final int MAX_METADATA_FIELDS = 128;
  static final int MAX_METADATA_COLLECTION_ITEMS = 100;
  static final int MAX_METADATA_DEPTH = 8;
  static final int MAX_METADATA_NAME_LENGTH = 512;
  static final int MAX_METADATA_QUERY_LENGTH = 256;
  static final int MAX_DEPENDENCY_RESULTS = 200;

  private static final String TRUNCATED = "<truncated>";

  private final IHopMetadataProvider metadataProvider;
  private final HopProjectDefinitionIndex definitionIndex;

  HopMetadataService(ProjectFiles files, IHopMetadataProvider metadataProvider) {
    this(files, metadataProvider, new HopProjectDefinitionIndex(files));
  }

  HopMetadataService(
      ProjectFiles files,
      IHopMetadataProvider metadataProvider,
      HopProjectDefinitionIndex definitionIndex) {
    this.metadataProvider = metadataProvider;
    this.definitionIndex = definitionIndex;
  }

  Map<String, Object> types(int offset, int limit) throws Exception {
    validatePage(offset, limit, MAX_METADATA_TYPES);
    List<Class<IHopMetadata>> classes = metadataClasses();
    List<Map<String, Object>> all = new ArrayList<>();
    boolean scanTruncated = classes.size() > MAX_METADATA_TYPES_SCAN;
    for (int i = 0; i < classes.size() && i < MAX_METADATA_TYPES_SCAN; i++) {
      Map<String, Object> type = typeRow(classes.get(i));
      if (type != null) all.add(type);
    }
    all.sort(
        Comparator.comparing(row -> String.valueOf(row.get("key")), String.CASE_INSENSITIVE_ORDER));
    int end = Math.min(all.size(), offset + limit);
    List<Map<String, Object>> page =
        offset >= all.size() ? List.of() : new ArrayList<>(all.subList(offset, end));
    boolean hasMore = scanTruncated || end < all.size();
    return new LinkedHashMap<>(
        Map.of(
            "offset", offset,
            "limit", limit,
            "count", all.size(),
            "count_complete", !scanTruncated,
            "returned", page.size(),
            "has_more", hasMore,
            "types", page));
  }

  Map<String, Object> list(String type, String query, int offset, int limit) throws Exception {
    validatePage(offset, limit, MAX_METADATA_RESULTS);
    String normalizedQuery = boundedQuery(query);
    Class<IHopMetadata> managedClass = resolveType(type);
    IHopMetadataSerializer<IHopMetadata> serializer = serializer(managedClass);
    List<String> names = serializer.listObjectNames();
    if (names == null) names = List.of();

    List<Map<String, Object>> page = new ArrayList<>();
    int matched = 0;
    boolean scanTruncated = names.size() > MAX_METADATA_OBJECTS_SCAN;
    int scanSize = Math.min(names.size(), MAX_METADATA_OBJECTS_SCAN);
    for (int i = 0; i < scanSize; i++) {
      String name = names.get(i);
      if (name == null || !matchesQuery(name, normalizedQuery)) continue;
      matched++;
      if (matched > offset && page.size() < limit) {
        String virtualPath = "";
        try {
          virtualPath = serializer.readVirtualPath(name);
        } catch (Exception ignored) {
          // A provider may not support cheap virtual-path reads. The name remains useful.
        }
        page.add(
            new LinkedHashMap<>(
                Map.of("name", name, "virtual_path", virtualPath == null ? "" : virtualPath)));
      }
    }
    boolean resultLimitReached = matched > offset + limit;
    boolean hasMore = resultLimitReached || scanTruncated;
    return new LinkedHashMap<>(
        Map.of(
            "type", metadataKey(managedClass),
            "query", normalizedQuery,
            "offset", offset,
            "limit", limit,
            "count", matched,
            "count_complete", !scanTruncated,
            "returned", page.size(),
            "has_more", hasMore,
            "objects", page));
  }

  Map<String, Object> get(String type, String name) throws Exception {
    requireName(name, "name");
    Class<IHopMetadata> managedClass = resolveType(type);
    IHopMetadataSerializer<IHopMetadata> serializer = serializer(managedClass);
    if (!serializer.exists(name)) {
      throw McpException.validation(
          "METADATA_NOT_FOUND", "Metadata object was not found for the requested type and name.");
    }
    IHopMetadata metadata = serializer.load(name);
    if (metadata == null) {
      throw McpException.validation(
          "METADATA_NOT_FOUND", "Metadata object was not found for the requested type and name.");
    }
    Map<String, Object> projected = projectMetadata(metadata);
    return new LinkedHashMap<>(
        Map.of(
            "type",
            metadataKey(managedClass),
            "name",
            name,
            "metadata",
            projected,
            "redaction_applied",
            true));
  }

  Map<String, Object> dependencies(String type, String name, int offset, int limit)
      throws Exception {
    validatePage(offset, limit, MAX_DEPENDENCY_RESULTS);
    requireName(name, "name");
    Class<IHopMetadata> managedClass = resolveType(type);
    IHopMetadataSerializer<IHopMetadata> serializer = serializer(managedClass);
    if (!serializer.exists(name)) {
      throw McpException.validation(
          "METADATA_NOT_FOUND", "Metadata object was not found for the requested type and name.");
    }

    HopProjectDefinitionIndex.Snapshot snapshot = definitionIndex.snapshot();
    List<Map<String, Object>> usedBy = new ArrayList<>();
    boolean resultTruncated = false;
    dependencyScan:
    for (HopProjectDefinitionIndex.Entry definition : snapshot.definitions().values()) {
      if (!definition.containsText(name)) continue;
      List<String> components = definition.componentsContaining(name, MAX_DEPENDENCY_RESULTS + 1);
      if (components.isEmpty()) components = List.of("unknown");
      for (String component : components) {
        if (usedBy.size() >= MAX_DEPENDENCY_RESULTS) {
          resultTruncated = true;
          break dependencyScan;
        }
        usedBy.add(
            new LinkedHashMap<>(
                Map.of("path", definition.path(), "component", safeString(component))));
      }
      if (definition.componentsTruncated()) resultTruncated = true;
    }

    int end = Math.min(usedBy.size(), offset + limit);
    List<Map<String, Object>> page =
        offset >= usedBy.size() ? List.of() : new ArrayList<>(usedBy.subList(offset, end));
    boolean scanTruncated = snapshot.truncated() || resultTruncated;
    boolean hasMore = scanTruncated || end < usedBy.size();
    return new LinkedHashMap<>(
        Map.of(
            "type", metadataKey(managedClass),
            "name", name,
            "offset", offset,
            "limit", limit,
            "count", usedBy.size(),
            "count_complete", !scanTruncated,
            "returned", page.size(),
            "has_more", hasMore,
            "used_by", page));
  }

  private Map<String, Object> projectMetadata(IHopMetadata metadata) {
    IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("name", safeString(metadata.getName()));
    result.put("virtual_path", safeString(metadata.getVirtualPath()));
    result.put("metadata_provider", safeString(metadata.getMetadataProviderName()));
    projectProperties(metadata, result, 0, seen);
    return castMap(SensitiveData.redactMap(result));
  }

  private void projectProperties(
      Object object, Map<String, Object> result, int depth, IdentityHashMap<Object, Boolean> seen) {
    if (object == null || depth > MAX_METADATA_DEPTH || seen.put(object, Boolean.TRUE) != null) {
      return;
    }
    try {
      int fields = 0;
      for (Class<?> current = object.getClass();
          current != null && fields < MAX_METADATA_FIELDS;
          current = current.getSuperclass()) {
        for (Field field : current.getDeclaredFields()) {
          HopMetadataProperty property = field.getAnnotation(HopMetadataProperty.class);
          if (property == null
              || property.isExcludedFromSerialization()
              || Modifier.isStatic(field.getModifiers())
              || field.isSynthetic()
              || fields >= MAX_METADATA_FIELDS) continue;
          String key = propertyKey(field.getName(), property);
          Object value =
              property.password() ? SensitiveData.redactedMarker() : readField(field, object);
          result.put(key, property.password() ? value : projectValue(value, key, depth + 1, seen));
          fields++;
        }
      }
      if (fields < MAX_METADATA_FIELDS) {
        for (Class<?> current = object.getClass();
            current != null && fields < MAX_METADATA_FIELDS;
            current = current.getSuperclass()) {
          for (Method method : current.getDeclaredMethods()) {
            HopMetadataProperty property = method.getAnnotation(HopMetadataProperty.class);
            if (property == null
                || property.isExcludedFromSerialization()
                || method.getParameterCount() != 0
                || method.getReturnType() == Void.TYPE
                || method.isSynthetic()
                || Modifier.isStatic(method.getModifiers())
                || fields >= MAX_METADATA_FIELDS) continue;
            String key = propertyKey(method.getName(), property);
            if (result.containsKey(key)) continue;
            Object value =
                property.password() ? SensitiveData.redactedMarker() : invoke(method, object);
            result.put(
                key, property.password() ? value : projectValue(value, key, depth + 1, seen));
            fields++;
          }
        }
      }
    } finally {
      seen.remove(object);
    }
  }

  private Object projectValue(
      Object value, String key, int depth, IdentityHashMap<Object, Boolean> seen) {
    if (value == null) return null;
    if (SensitiveData.isSensitiveKey(key)) return SensitiveData.redactedMarker();
    if (value instanceof String text) return SensitiveData.redactSensitiveText(text);
    if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>)
      return value;
    if (value instanceof Character || value instanceof java.time.temporal.TemporalAccessor) {
      return SensitiveData.redactSensitiveText(String.valueOf(value));
    }
    if (depth > MAX_METADATA_DEPTH) return TRUNCATED;
    if (value.getClass().isArray()) {
      if (value instanceof byte[]) return "<binary>";
      List<Object> result = new ArrayList<>();
      int size = Math.min(Array.getLength(value), MAX_METADATA_COLLECTION_ITEMS);
      for (int i = 0; i < size; i++)
        result.add(projectValue(Array.get(value, i), key, depth + 1, seen));
      if (Array.getLength(value) > size) result.add(TRUNCATED);
      return result;
    }
    if (value instanceof Iterable<?> iterable) {
      List<Object> result = new ArrayList<>();
      int count = 0;
      for (Object item : iterable) {
        if (count++ >= MAX_METADATA_COLLECTION_ITEMS) {
          result.add(TRUNCATED);
          break;
        }
        result.add(projectValue(item, key, depth + 1, seen));
      }
      return result;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new LinkedHashMap<>();
      int count = 0;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (count++ >= MAX_METADATA_COLLECTION_ITEMS) {
          result.put(TRUNCATED, TRUNCATED);
          break;
        }
        String childKey = String.valueOf(entry.getKey());
        result.put(childKey, projectValue(entry.getValue(), childKey, depth + 1, seen));
      }
      return result;
    }
    Map<String, Object> nested = new LinkedHashMap<>();
    projectProperties(value, nested, depth, seen);
    return nested.isEmpty() ? Map.of("type", value.getClass().getSimpleName()) : nested;
  }

  private List<Class<IHopMetadata>> metadataClasses() {
    List<Class<IHopMetadata>> classes = metadataProvider.getMetadataClasses();
    return classes == null ? List.of() : classes;
  }

  private Class<IHopMetadata> resolveType(String type) throws Exception {
    requireName(type, "type");
    String requested = type.trim();
    for (Class<IHopMetadata> managedClass : metadataClasses()) {
      HopMetadata annotation = managedClass.getAnnotation(HopMetadata.class);
      if (annotation == null) continue;
      if (requested.equalsIgnoreCase(annotation.key())
          || requested.equalsIgnoreCase(annotation.name())
          || requested.equalsIgnoreCase(managedClass.getSimpleName())
          || requested.equalsIgnoreCase(managedClass.getName())) return managedClass;
    }
    try {
      return metadataProvider.getMetadataClassForKey(requested);
    } catch (Exception ignored) {
      throw McpException.validation("METADATA_NOT_FOUND", "Metadata type was not found.");
    }
  }

  @SuppressWarnings("unchecked")
  private IHopMetadataSerializer<IHopMetadata> serializer(Class<IHopMetadata> managedClass)
      throws Exception {
    return (IHopMetadataSerializer<IHopMetadata>)
        metadataProvider.getSerializer((Class<? extends IHopMetadata>) managedClass);
  }

  private Map<String, Object> typeRow(Class<IHopMetadata> managedClass) {
    HopMetadata annotation = managedClass.getAnnotation(HopMetadata.class);
    if (annotation == null || annotation.key().isBlank()) return null;
    return new LinkedHashMap<>(
        Map.of(
            "key", annotation.key(),
            "name", annotation.name(),
            "description", annotation.description(),
            "category", annotation.category()));
  }

  private String metadataKey(Class<IHopMetadata> managedClass) {
    HopMetadata annotation = managedClass.getAnnotation(HopMetadata.class);
    return annotation == null ? managedClass.getSimpleName() : annotation.key();
  }

  private static String propertyKey(String fallback, HopMetadataProperty property) {
    return property.key().isBlank() ? fallback : property.key();
  }

  private static Object readField(Field field, Object object) {
    try {
      if (!field.trySetAccessible()) return null;
      return field.get(object);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Object invoke(Method method, Object object) {
    try {
      if (!method.trySetAccessible()) return null;
      return method.invoke(object);
    } catch (Exception ignored) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }

  private static String safeString(String value) {
    return value == null ? "" : SensitiveData.redactSensitiveText(value);
  }

  private static boolean matchesQuery(String name, String query) {
    return query.isBlank()
        || name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
  }

  private static String boundedQuery(String query) {
    if (query == null) return "";
    if (query.length() > MAX_METADATA_QUERY_LENGTH) {
      throw new IllegalArgumentException(
          "query exceeds " + MAX_METADATA_QUERY_LENGTH + " characters");
    }
    return query;
  }

  private static void requireName(String value, String field) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(field + " is required");
    if (value.length() > MAX_METADATA_NAME_LENGTH) {
      throw new IllegalArgumentException(
          field + " exceeds " + MAX_METADATA_NAME_LENGTH + " characters");
    }
  }

  private static void validatePage(int offset, int limit, int maxLimit) {
    if (offset < 0 || offset > ProjectFiles.MAX_SCAN_FILES)
      throw new IllegalArgumentException(
          "offset must be between 0 and " + ProjectFiles.MAX_SCAN_FILES);
    if (limit < 1 || limit > maxLimit)
      throw new IllegalArgumentException("limit must be between 1 and " + maxLimit);
  }
}
