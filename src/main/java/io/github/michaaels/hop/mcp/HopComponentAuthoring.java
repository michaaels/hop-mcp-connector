package io.github.michaaels.hop.mcp;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.hop.core.RowMetaAndData;
import org.apache.hop.core.injection.bean.BeanInjectionInfo;
import org.apache.hop.core.injection.bean.BeanInjector;
import org.apache.hop.core.injection.bean.BeanLevelInfo;
import org.apache.hop.core.plugins.ActionPluginType;
import org.apache.hop.core.plugins.IPlugin;
import org.apache.hop.core.plugins.IPluginType;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.plugins.TransformPluginType;
import org.apache.hop.core.row.value.ValueMetaString;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;
import org.apache.hop.workflow.action.IAction;

/** Discovers and creates Hop components using the native plugin and metadata-injection APIs. */
final class HopComponentAuthoring {
  static final int MAX_PROPERTIES = 50;
  static final int MAX_PROPERTY_GROUPS = 20;
  static final int MAX_ROWS_PER_GROUP = 100;
  static final int MAX_TOTAL_GROUP_CELLS = 1_000;
  static final int MAX_PROPERTY_VALUE_LENGTH = 8_192;
  static final int MAX_COMPONENT_NAME_LENGTH = 200;
  static final int MAX_PLUGIN_ID_LENGTH = 200;
  static final int MAX_PLUGIN_OFFSET = 100_000;
  static final int MAX_PLUGIN_QUERY_LENGTH = 1_024;
  static final int MAX_PLUGIN_IDS = 64;
  static final int MAX_PLUGIN_ID_OUTPUT_LENGTH = 512;
  static final int MAX_PLUGIN_ID_VALUE_LENGTH = 4_096;
  static final int MAX_PLUGIN_NAME_LENGTH = 2_048;
  static final int MAX_PLUGIN_DESCRIPTION_LENGTH = 8_192;
  static final int MAX_PLUGIN_CATEGORY_LENGTH = 1_024;

  private static final Pattern SENSITIVE_KEY =
      Pattern.compile(
          "(?i)(password|passwd|secret|token|credential|private[_.-]?key|access[_.-]?key|client[_.-]?secret|authorization|auth[_.-]?header)");
  private static final Set<Class<?>> SCALAR_TYPES =
      Set.of(
          String.class,
          Boolean.class,
          Byte.class,
          Short.class,
          Integer.class,
          Long.class,
          Float.class,
          Double.class,
          Character.class,
          BigDecimal.class,
          BigInteger.class);
  private static final Set<String> RESERVED_PROPERTIES =
      Set.of("name", "type", "pluginid", "plugin_id");

  private final PluginRegistry registry;
  private final IHopMetadataProvider metadataProvider;

  HopComponentAuthoring(IHopMetadataProvider metadataProvider) {
    this(PluginRegistry.getInstance(), metadataProvider);
  }

  HopComponentAuthoring(PluginRegistry registry, IHopMetadataProvider metadataProvider) {
    this.registry = registry;
    this.metadataProvider = metadataProvider;
  }

  Map<String, Object> types(String requestedKind, String query, int offset, int limit) {
    Kind kind = Kind.parse(requestedKind);
    if (offset < 0 || offset > MAX_PLUGIN_OFFSET)
      throw new IllegalArgumentException("offset must be between 0 and " + MAX_PLUGIN_OFFSET);
    if (query != null && query.length() > MAX_PLUGIN_QUERY_LENGTH)
      throw new IllegalArgumentException(
          "query cannot exceed " + MAX_PLUGIN_QUERY_LENGTH + " characters");
    if (limit < 1 || limit > 50)
      throw new IllegalArgumentException("limit must be between 1 and 50");
    String normalizedQuery = normalize(query);
    List<IPlugin> matches =
        registry.getPlugins(kind.pluginType).stream()
            .filter(plugin -> matches(plugin, normalizedQuery))
            .sorted(
                Comparator.comparing(
                        (IPlugin plugin) -> safe(plugin.getName()), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(HopComponentAuthoring::canonicalId))
            .toList();
    List<Map<String, Object>> components = new ArrayList<>();
    for (int i = offset; i < matches.size() && components.size() < limit; i++) {
      components.add(pluginRow(matches.get(i)));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", kind.value);
    result.put("matched_component_count", matches.size());
    result.put("returned_component_count", components.size());
    result.put("offset", offset);
    result.put("limit", limit);
    result.put("has_more", offset + components.size() < matches.size());
    if (normalizedQuery != null) result.put("query", query);
    result.put("components", components);
    return result;
  }

  Map<String, Object> schema(String requestedKind, String pluginId) throws Exception {
    Kind kind = Kind.parse(requestedKind);
    IPlugin plugin = requirePlugin(kind, pluginId);
    Object component = instantiate(kind, plugin);
    boolean nativeInjectionSupported = BeanInjectionInfo.isInjectionSupported(component.getClass());
    BeanInjectionInfo<?> info = nativeInjectionSupported ? injectionInfo(component) : null;
    List<Map<String, Object>> properties =
        info == null ? List.of() : injectableScalarProperties(info);
    List<Map<String, Object>> propertyGroups =
        info == null ? List.of() : injectablePropertyGroups(info);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("kind", kind.value);
    result.put("plugin", pluginRow(plugin));
    result.put("native_injection_supported", nativeInjectionSupported);
    result.put("scalar_injection_supported", !properties.isEmpty());
    result.put("tabular_injection_supported", !propertyGroups.isEmpty());
    result.put("property_count", properties.size());
    result.put("properties", properties);
    result.put("property_group_count", propertyGroups.size());
    result.put("property_groups", propertyGroups);
    result.put("sensitive_properties_excluded", true);
    result.put("collection_properties_excluded", false);
    result.put("nested_collection_properties_excluded", true);
    result.put("structural_properties_excluded", true);
    return result;
  }

  TransformMeta createTransform(
      String pluginId,
      String componentName,
      Object rawProperties,
      Object rawPropertyGroups,
      int x,
      int y)
      throws Exception {
    Kind kind = Kind.PIPELINE;
    IPlugin plugin = requirePlugin(kind, pluginId);
    ITransformMeta transform = (ITransformMeta) instantiate(kind, plugin);
    transform.setDefault();
    inject(transform, rawProperties, rawPropertyGroups, false);
    TransformMeta result =
        new TransformMeta(canonicalId(plugin), validName(componentName), transform);
    result.setLocation(x, y);
    return result;
  }

  ActionMeta createAction(
      WorkflowMeta workflowMeta,
      String pluginId,
      String componentName,
      Object rawProperties,
      Object rawPropertyGroups,
      int x,
      int y)
      throws Exception {
    Kind kind = Kind.WORKFLOW;
    IPlugin plugin = requirePlugin(kind, pluginId);
    IAction action = (IAction) instantiate(kind, plugin);
    action.setPluginId(canonicalId(plugin));
    action.setName(validName(componentName));
    action.setMetadataProvider(metadataProvider);
    action.setParentWorkflowMeta(workflowMeta);
    if (action.isStart() && workflowMeta.findStart() != null) {
      throw new IllegalArgumentException("A workflow can contain only one Start action");
    }
    inject(action, rawProperties, rawPropertyGroups, false);
    ActionMeta result = new ActionMeta(action);
    result.setLocation(x, y);
    result.setParentWorkflowMeta(workflowMeta);
    return result;
  }

  void updateComponent(Object component, Object rawProperties, Object rawPropertyGroups)
      throws Exception {
    if (component == null) throw new IllegalArgumentException("component is required");
    inject(component, rawProperties, rawPropertyGroups, true);
  }

  private Object instantiate(Kind kind, IPlugin plugin) throws Exception {
    Object component = registry.loadClass(plugin);
    if (!kind.componentType.isInstance(component)) {
      throw new IllegalStateException(
          "Plugin "
              + canonicalId(plugin)
              + " did not create a "
              + kind.componentType.getSimpleName());
    }
    return component;
  }

  private IPlugin requirePlugin(Kind kind, String pluginId) {
    String validId = validPluginId(pluginId);
    IPlugin plugin = registry.findPluginWithId(kind.pluginType, validId);
    if (plugin == null) {
      throw new IllegalArgumentException("Unknown " + kind.value + " component plugin: " + validId);
    }
    return plugin;
  }

  private List<Map<String, Object>> injectableScalarProperties(BeanInjectionInfo<?> info) {
    return info.getProperties().values().stream()
        .filter(this::isPublicScalarProperty)
        .sorted(Comparator.comparing(BeanInjectionInfo.Property::getKey))
        .map(this::propertyRow)
        .toList();
  }

  private List<Map<String, Object>> injectablePropertyGroups(BeanInjectionInfo<?> info) {
    List<Map<String, Object>> groups = new ArrayList<>();
    for (BeanInjectionInfo<?>.Group group : info.getGroups()) {
      if (group.getKey() == null || group.getKey().isBlank() || isSensitive(group.getKey())) {
        continue;
      }
      List<Map<String, Object>> properties =
          group.getProperties().stream()
              .filter(this::isPublicTabularProperty)
              .sorted(Comparator.comparing(BeanInjectionInfo.Property::getKey))
              .map(this::propertyRow)
              .toList();
      if (properties.isEmpty()) continue;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("key", group.getKey());
      row.put("description", safe(group.getTranslatedDescription()));
      row.put("max_rows", MAX_ROWS_PER_GROUP);
      row.put("properties", properties);
      groups.add(row);
    }
    groups.sort(
        Comparator.comparing(
            group -> String.valueOf(group.get("key")), String.CASE_INSENSITIVE_ORDER));
    return groups;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void inject(
      Object component,
      Object rawProperties,
      Object rawPropertyGroups,
      boolean replacePropertyGroups)
      throws Exception {
    Map<String, Object> properties = validatedProperties(rawProperties);
    Map<String, List<Map<String, Object>>> propertyGroups =
        validatedPropertyGroups(rawPropertyGroups);
    if (properties.isEmpty() && propertyGroups.isEmpty()) return;
    for (String key : properties.keySet()) {
      if (isSensitive(key))
        throw new SecurityException("Sensitive component properties are not accepted");
    }
    if (!BeanInjectionInfo.isInjectionSupported(component.getClass())) {
      throw new IllegalArgumentException(
          "Plugin does not expose injectable scalar properties: " + component.getClass().getName());
    }
    BeanInjectionInfo info = injectionInfo(component);
    BeanInjector injector = new BeanInjector(info, metadataProvider);
    for (Map.Entry<String, Object> entry : properties.entrySet()) {
      String key = entry.getKey();
      BeanInjectionInfo.Property property =
          (BeanInjectionInfo.Property) info.getProperties().get(key);
      if (property == null || !isPublicScalarProperty(property)) {
        throw new IllegalArgumentException("Unknown or non-scalar injectable property: " + key);
      }
      injector.setProperty(component, key, null, String.valueOf(entry.getValue()));
    }
    if (replacePropertyGroups) {
      resetPropertyGroups(component, info, propertyGroups.keySet());
    }
    injectPropertyGroups(component, info, injector, propertyGroups);
    injector.runPostInjectionProcessing(component);
  }

  @SuppressWarnings("rawtypes")
  private void resetPropertyGroups(
      Object component, BeanInjectionInfo info, Set<String> requestedGroupKeys) throws Exception {
    for (String groupKey : requestedGroupKeys) {
      List<BeanInjectionInfo.Property> properties = new ArrayList<>();
      for (Object value : info.getProperties().values()) {
        BeanInjectionInfo.Property property = (BeanInjectionInfo.Property) value;
        if (groupKey.equals(property.getGroupKey()) && isPublicTabularProperty(property)) {
          properties.add(property);
        }
      }
      if (properties.isEmpty()) {
        throw new IllegalArgumentException("Unknown or unsupported property group: " + groupKey);
      }
      for (BeanInjectionInfo.Property property : properties) {
        resetCollection(component, property);
      }
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void resetCollection(Object component, BeanInjectionInfo.Property property)
      throws Exception {
    Object owner = component;
    List<BeanLevelInfo> path = property.getPath();
    for (int i = 1; i < path.size(); i++) {
      BeanLevelInfo level = path.get(i);
      if (level.dim == BeanLevelInfo.DIMENSION.LIST) {
        Object value = level.field != null ? level.field.get(owner) : level.getter.invoke(owner);
        if (value instanceof List<?> list) list.clear();
        return;
      }
      if (level.dim == BeanLevelInfo.DIMENSION.ARRAY) {
        if (level.field == null) {
          throw new IllegalArgumentException(
              "Unsupported array-backed property group: " + property.getGroupKey());
        }
        level.field.set(owner, Array.newInstance(level.leafClass, 0));
        return;
      }
      if (i < path.size() - 1) {
        owner = level.field != null ? level.field.get(owner) : level.getter.invoke(owner);
        if (owner == null) return;
      }
    }
    throw new IllegalArgumentException(
        "Property group does not expose a replaceable collection: " + property.getGroupKey());
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void injectPropertyGroups(
      Object component,
      BeanInjectionInfo info,
      BeanInjector injector,
      Map<String, List<Map<String, Object>>> requestedGroups)
      throws Exception {
    int totalCells = 0;
    for (Map.Entry<String, List<Map<String, Object>>> requestedGroup : requestedGroups.entrySet()) {
      String groupKey = requestedGroup.getKey();
      List<Map<String, Object>> requestedRows = requestedGroup.getValue();
      Map<String, BeanInjectionInfo.Property> allowed = new LinkedHashMap<>();
      for (Object value : info.getProperties().values()) {
        BeanInjectionInfo.Property property = (BeanInjectionInfo.Property) value;
        if (groupKey.equals(property.getGroupKey()) && isPublicTabularProperty(property)) {
          allowed.put(property.getKey(), property);
        }
      }
      if (allowed.isEmpty()) {
        throw new IllegalArgumentException("Unknown or unsupported property group: " + groupKey);
      }

      LinkedHashSet<String> requestedKeys = new LinkedHashSet<>();
      for (Map<String, Object> requestedRow : requestedRows) {
        for (String key : requestedRow.keySet()) {
          if (!allowed.containsKey(key)) {
            throw new IllegalArgumentException(
                "Unknown or unsupported property in group " + groupKey + ": " + key);
          }
          requestedKeys.add(key);
        }
      }
      totalCells += requestedRows.size() * requestedKeys.size();
      if (totalCells > MAX_TOTAL_GROUP_CELLS) {
        throw new IllegalArgumentException(
            "property_groups cannot exceed " + MAX_TOTAL_GROUP_CELLS + " cells");
      }

      List<RowMetaAndData> rows = new ArrayList<>();
      for (Map<String, Object> requestedRow : requestedRows) {
        RowMetaAndData row = new RowMetaAndData();
        for (String key : requestedKeys) {
          Object value = requestedRow.get(key);
          row.addValue(new ValueMetaString(key), value == null ? null : String.valueOf(value));
        }
        rows.add(row);
      }
      for (String key : requestedKeys) {
        injector.setProperty(component, key, rows, key);
      }
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static BeanInjectionInfo<?> injectionInfo(Object component) {
    return new BeanInjectionInfo((Class) component.getClass());
  }

  private boolean isPublicScalarProperty(BeanInjectionInfo<?>.Property property) {
    return property.pathArraysCount == 0
        && (property.getGroupKey() == null || property.getGroupKey().isBlank())
        && !property.isExcludedFromInjection()
        && !isReserved(property.getKey())
        && !isSensitive(property.getKey())
        && isScalar(property.getPropertyClass());
  }

  private boolean isPublicTabularProperty(BeanInjectionInfo<?>.Property property) {
    return property.pathArraysCount == 1
        && property.getGroupKey() != null
        && !property.getGroupKey().isBlank()
        && !property.isExcludedFromInjection()
        && !isSensitive(property.getKey())
        && isScalar(property.getPropertyClass());
  }

  private Map<String, Object> propertyRow(BeanInjectionInfo<?>.Property property) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("key", property.getKey());
    row.put("description", safe(property.getTranslatedDescription()));
    row.put("java_type", property.getPropertyClass().getName());
    row.put("value_type", valueType(property.getPropertyClass()));
    if (property.getPropertyClass().isEnum()) {
      List<String> values = new ArrayList<>();
      for (Object value : property.getPropertyClass().getEnumConstants()) {
        values.add(String.valueOf(value));
      }
      row.put("allowed_values", values);
    }
    return row;
  }

  private static Map<String, Object> validatedProperties(Object value) {
    if (value == null) return Map.of();
    if (!(value instanceof Map<?, ?> map))
      throw new IllegalArgumentException("properties must be an object");
    if (map.size() > MAX_PROPERTIES)
      throw new IllegalArgumentException("properties cannot exceed " + MAX_PROPERTIES + " entries");
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String key = validatePropertyKey(entry.getKey(), "property names");
      result.put(key, validatePropertyValue(entry.getValue()));
    }
    return result;
  }

  private static Map<String, List<Map<String, Object>>> validatedPropertyGroups(Object value) {
    if (value == null) return Map.of();
    if (!(value instanceof Map<?, ?> groups)) {
      throw new IllegalArgumentException("property_groups must be an object");
    }
    if (groups.size() > MAX_PROPERTY_GROUPS) {
      throw new IllegalArgumentException(
          "property_groups cannot exceed " + MAX_PROPERTY_GROUPS + " entries");
    }
    Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> groupEntry : groups.entrySet()) {
      String groupKey = validatePropertyKey(groupEntry.getKey(), "property group names");
      if (isSensitive(groupKey)) {
        throw new SecurityException("Sensitive property groups are not accepted");
      }
      if (!(groupEntry.getValue() instanceof List<?> rows)) {
        throw new IllegalArgumentException("property group " + groupKey + " must be an array");
      }
      if (rows.isEmpty() || rows.size() > MAX_ROWS_PER_GROUP) {
        throw new IllegalArgumentException(
            "property group "
                + groupKey
                + " must contain between 1 and "
                + MAX_ROWS_PER_GROUP
                + " rows");
      }
      List<Map<String, Object>> validatedRows = new ArrayList<>();
      for (Object rowValue : rows) {
        if (!(rowValue instanceof Map<?, ?> row)) {
          throw new IllegalArgumentException("property group rows must be objects");
        }
        if (row.isEmpty() || row.size() > MAX_PROPERTIES) {
          throw new IllegalArgumentException(
              "property group rows must contain between 1 and " + MAX_PROPERTIES + " properties");
        }
        Map<String, Object> validatedRow = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : row.entrySet()) {
          String key = validatePropertyKey(entry.getKey(), "property names");
          if (isSensitive(key)) {
            throw new SecurityException("Sensitive component properties are not accepted");
          }
          validatedRow.put(key, validatePropertyValue(entry.getValue()));
        }
        validatedRows.add(validatedRow);
      }
      result.put(groupKey, List.copyOf(validatedRows));
    }
    return result;
  }

  private static String validatePropertyKey(Object value, String label) {
    String key = value == null ? "" : String.valueOf(value);
    if (key.isBlank() || key.length() > MAX_PLUGIN_ID_LENGTH) {
      throw new IllegalArgumentException(label + " must contain 1 to 200 characters");
    }
    return key;
  }

  private static Object validatePropertyValue(Object value) {
    if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
      throw new IllegalArgumentException("property values must be strings, numbers or booleans");
    }
    if (String.valueOf(value).length() > MAX_PROPERTY_VALUE_LENGTH) {
      throw new IllegalArgumentException(
          "property values cannot exceed " + MAX_PROPERTY_VALUE_LENGTH + " characters");
    }
    if (value instanceof Number number) {
      double numericValue = number.doubleValue();
      if (!Double.isFinite(numericValue) || Math.abs(numericValue) > 1_000_000_000D)
        throw new IllegalArgumentException(
            "numeric property values must be finite and within ±1,000,000,000");
    }
    return value;
  }

  private static boolean isScalar(Class<?> type) {
    return type.isPrimitive() || type.isEnum() || SCALAR_TYPES.contains(type);
  }

  private static String valueType(Class<?> type) {
    if (type == boolean.class || type == Boolean.class) return "boolean";
    if ((type.isPrimitive() && type != char.class) || Number.class.isAssignableFrom(type))
      return "number";
    return "string";
  }

  private static boolean matches(IPlugin plugin, String query) {
    if (query == null) return true;
    if (contains(plugin.getName(), query)
        || contains(plugin.getDescription(), query)
        || contains(plugin.getCategory(), query)) return true;
    if (plugin.getIds() != null) {
      for (String id : plugin.getIds()) if (contains(id, query)) return true;
    }
    return false;
  }

  private static boolean contains(Object value, String query) {
    return value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(query);
  }

  private static Map<String, Object> pluginRow(IPlugin plugin) {
    String[] rawIds = plugin.getIds();
    List<String> ids =
        rawIds == null
            ? List.of()
            : Arrays.stream(rawIds)
                .limit(MAX_PLUGIN_IDS)
                .map(id -> boundedText(safe(id), MAX_PLUGIN_ID_VALUE_LENGTH))
                .toList();
    return Map.of(
        "id", boundedText(canonicalId(plugin), MAX_PLUGIN_ID_OUTPUT_LENGTH),
        "ids", ids,
        "name", boundedText(safe(plugin.getName()), MAX_PLUGIN_NAME_LENGTH),
        "description", boundedText(safe(plugin.getDescription()), MAX_PLUGIN_DESCRIPTION_LENGTH),
        "category", boundedText(safe(plugin.getCategory()), MAX_PLUGIN_CATEGORY_LENGTH));
  }

  private static String boundedText(String value, int maxLength) {
    if (value.length() <= maxLength) return value;
    int end = maxLength;
    if (Character.isHighSurrogate(value.charAt(end - 1))
        && Character.isLowSurrogate(value.charAt(end))) end--;
    return value.substring(0, end);
  }

  private static String canonicalId(IPlugin plugin) {
    if (plugin.getIds() == null || plugin.getIds().length == 0 || plugin.getIds()[0].isBlank()) {
      throw new IllegalStateException("Hop plugin has no canonical ID");
    }
    return plugin.getIds()[0];
  }

  private static String validPluginId(String value) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("plugin_id is required");
    if (value.length() > MAX_PLUGIN_ID_LENGTH)
      throw new IllegalArgumentException(
          "plugin_id cannot exceed " + MAX_PLUGIN_ID_LENGTH + " characters");
    return value;
  }

  private static String validName(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("name is required");
    if (value.length() > MAX_COMPONENT_NAME_LENGTH)
      throw new IllegalArgumentException(
          "name cannot exceed " + MAX_COMPONENT_NAME_LENGTH + " characters");
    return value;
  }

  private static boolean isSensitive(String key) {
    return key != null && SENSITIVE_KEY.matcher(key).find();
  }

  private static boolean isReserved(String key) {
    return key != null && RESERVED_PROPERTIES.contains(key.toLowerCase(Locale.ROOT));
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value.toLowerCase(Locale.ROOT);
  }

  private static String safe(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private enum Kind {
    PIPELINE("pipeline", TransformPluginType.class, ITransformMeta.class),
    WORKFLOW("workflow", ActionPluginType.class, IAction.class);

    private final String value;
    private final Class<? extends IPluginType> pluginType;
    private final Class<?> componentType;

    Kind(String value, Class<? extends IPluginType> pluginType, Class<?> componentType) {
      this.value = value;
      this.pluginType = pluginType;
      this.componentType = componentType;
    }

    private static Kind parse(String value) {
      if (value == null || value.isBlank()) throw new IllegalArgumentException("kind is required");
      return switch (value.toLowerCase(Locale.ROOT)) {
        case "pipeline" -> PIPELINE;
        case "workflow" -> WORKFLOW;
        default -> throw new IllegalArgumentException("kind must be pipeline or workflow");
      };
    }
  }
}
