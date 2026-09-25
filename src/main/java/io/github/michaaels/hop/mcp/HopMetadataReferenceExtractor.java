package io.github.michaaels.hop.mcp;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.hop.core.variables.IVariables;
import org.apache.hop.core.variables.Variables;
import org.apache.hop.metadata.api.HopMetadata;
import org.apache.hop.metadata.api.HopMetadataProperty;
import org.apache.hop.metadata.api.HopMetadataPropertyType;
import org.apache.hop.metadata.api.IHopMetadata;
import org.apache.hop.metadata.api.IHopMetadataProvider;
import org.apache.hop.pipeline.PipelineMeta;
import org.apache.hop.pipeline.transform.ITransformMeta;
import org.apache.hop.pipeline.transform.TransformMeta;
import org.apache.hop.workflow.WorkflowMeta;
import org.apache.hop.workflow.action.ActionMeta;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Extracts compact, typed metadata references while a definition enters the project index. */
final class HopMetadataReferenceExtractor {
  private static final int MAX_REFERENCES = 200;
  private static final int MAX_VALUES_PER_PROPERTY = 100;
  private static final int MAX_VALUE_DEPTH = 4;
  private static final int MAX_NAME_LENGTH = 256;

  record Result(List<HopProjectDefinitionIndex.MetadataReference> references, boolean truncated) {}

  record ReferenceKey(String type, String name, String component) {}

  private final IHopMetadataProvider metadataProvider;
  private final IVariables variables;
  private volatile Map<HopMetadataPropertyType, List<String>> metadataKeysByType;
  private volatile boolean metadataKeyMappingTruncated;

  HopMetadataReferenceExtractor(IHopMetadataProvider metadataProvider, IVariables variables) {
    this.metadataProvider = metadataProvider;
    this.variables = variables;
  }

  Result extract(String path, Document document) {
    Map<ReferenceKey, HopProjectDefinitionIndex.MetadataReference> references =
        new LinkedHashMap<>();
    Map<HopMetadataPropertyType, List<String>> typeKeys = metadataKeysByType();
    boolean[] truncated = {metadataKeyMappingTruncated};

    if (metadataProvider != null) {
      try {
        if (path.toLowerCase(Locale.ROOT).endsWith(".hpl")) {
          PipelineMeta pipeline = new PipelineMeta(document.getDocumentElement(), metadataProvider);
          for (TransformMeta transform : pipeline.getTransforms()) {
            String component = safeComponent(transform.getName());
            ITransformMeta meta = transform.getTransform();
            if (meta == null) continue;
            addNative(meta.getResourceMetaDataDependencies(), component, references, truncated);
            addAnnotatedProperties(meta, component, typeKeys, references, truncated);
          }
        } else if (path.toLowerCase(Locale.ROOT).endsWith(".hwf")) {
          IVariables scopedVariables = variables == null ? new Variables() : variables;
          WorkflowMeta workflow =
              new WorkflowMeta(document.getDocumentElement(), metadataProvider, scopedVariables);
          for (ActionMeta actionMeta : workflow.getActions()) {
            if (actionMeta.getAction() == null) continue;
            addAnnotatedProperties(
                actionMeta.getAction(),
                safeComponent(actionMeta.getName()),
                typeKeys,
                references,
                truncated);
          }
        }
      } catch (Exception | LinkageError unavailable) {
        truncated[0] = true;
      }
    }

    addTextFallback(document, typeKeys, references, truncated);
    return new Result(List.copyOf(references.values()), truncated[0]);
  }

  private Map<HopMetadataPropertyType, List<String>> metadataKeysByType() {
    Map<HopMetadataPropertyType, List<String>> cached = metadataKeysByType;
    if (cached != null) return cached;
    synchronized (this) {
      cached = metadataKeysByType;
      if (cached != null) return cached;
      Map<HopMetadataPropertyType, Set<String>> collected =
          new EnumMap<>(HopMetadataPropertyType.class);
      if (metadataProvider != null) {
        try {
          List<Class<IHopMetadata>> metadataClasses = metadataProvider.getMetadataClasses();
          if (metadataClasses != null) {
            if (metadataClasses.size() > 1_000) metadataKeyMappingTruncated = true;
            for (int i = 0; i < metadataClasses.size() && i < 1_000; i++) {
              Class<IHopMetadata> metadataClass = metadataClasses.get(i);
              HopMetadata annotation = metadataClass.getAnnotation(HopMetadata.class);
              if (annotation == null
                  || annotation.key().isBlank()
                  || annotation.hopMetadataPropertyType() == HopMetadataPropertyType.NONE) continue;
              collected
                  .computeIfAbsent(
                      annotation.hopMetadataPropertyType(), ignored -> new LinkedHashSet<>())
                  .add(annotation.key());
            }
          }
        } catch (Exception ignored) {
          // The XML fallback remains available when a third-party provider cannot enumerate types.
        }
      }
      Map<HopMetadataPropertyType, List<String>> immutable =
          new EnumMap<>(HopMetadataPropertyType.class);
      collected.forEach(
          (type, keys) -> {
            if (keys.size() == 1) immutable.put(type, List.copyOf(keys));
            else metadataKeyMappingTruncated = true;
          });
      metadataKeysByType = Map.copyOf(immutable);
      return metadataKeysByType;
    }
  }

  static void addNative(
      Map<Class<? extends IHopMetadata>, List<String>> dependencies,
      String component,
      Map<ReferenceKey, HopProjectDefinitionIndex.MetadataReference> references,
      boolean[] truncated) {
    if (dependencies == null) return;
    for (Map.Entry<Class<? extends IHopMetadata>, List<String>> dependency :
        dependencies.entrySet()) {
      Class<? extends IHopMetadata> metadataClass = dependency.getKey();
      HopMetadata annotation =
          metadataClass == null ? null : metadataClass.getAnnotation(HopMetadata.class);
      if (annotation == null || annotation.key().isBlank()) {
        truncated[0] = true;
        continue;
      }
      addNames(
          annotation.key(),
          dependency.getValue(),
          component,
          HopProjectDefinitionIndex.ReferenceSource.NATIVE,
          references,
          truncated);
    }
  }

  static void addAnnotatedProperties(
      Object object,
      String component,
      Map<HopMetadataPropertyType, List<String>> typeKeys,
      Map<ReferenceKey, HopProjectDefinitionIndex.MetadataReference> references,
      boolean[] truncated) {
    IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
    for (Class<?> type = object.getClass();
        type != null && type != Object.class;
        type = type.getSuperclass()) {
      for (Field field : type.getDeclaredFields()) {
        HopMetadataProperty annotation = field.getAnnotation(HopMetadataProperty.class);
        if (annotation == null
            || annotation.hopMetadataPropertyType() == HopMetadataPropertyType.NONE) continue;
        try {
          if (!field.trySetAccessible()) {
            truncated[0] = true;
            continue;
          }
          addPropertyValue(
              annotation.hopMetadataPropertyType(),
              field.get(object),
              component,
              typeKeys,
              references,
              truncated,
              visited,
              0);
        } catch (IllegalAccessException | RuntimeException ignored) {
          truncated[0] = true;
        }
      }
      for (Method method : type.getDeclaredMethods()) {
        HopMetadataProperty annotation = method.getAnnotation(HopMetadataProperty.class);
        if (annotation == null
            || annotation.hopMetadataPropertyType() == HopMetadataPropertyType.NONE
            || method.getParameterCount() != 0) continue;
        try {
          if (!method.trySetAccessible()) {
            truncated[0] = true;
            continue;
          }
          addPropertyValue(
              annotation.hopMetadataPropertyType(),
              method.invoke(object),
              component,
              typeKeys,
              references,
              truncated,
              visited,
              0);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
          truncated[0] = true;
        }
      }
    }
  }

  private static void addPropertyValue(
      HopMetadataPropertyType propertyType,
      Object value,
      String component,
      Map<HopMetadataPropertyType, List<String>> typeKeys,
      Map<ReferenceKey, HopProjectDefinitionIndex.MetadataReference> references,
      boolean[] truncated,
      IdentityHashMap<Object, Boolean> visited,
      int depth) {
    List<String> keys = typeKeys.get(propertyType);
    if (keys == null || keys.isEmpty()) return;
    List<String> names = new ArrayList<>();
    collectNames(value, names, truncated, visited, depth);
    for (String key : keys) {
      addNames(
          key,
          names,
          component,
          HopProjectDefinitionIndex.ReferenceSource.METADATA_PROPERTY,
          references,
          truncated);
    }
  }

  private static void collectNames(
      Object value,
      List<String> names,
      boolean[] truncated,
      IdentityHashMap<Object, Boolean> visited,
      int depth) {
    if (value == null) return;
    if (value instanceof CharSequence text) {
      String name = text.toString().trim();
      if (!name.isEmpty() && name.length() <= MAX_NAME_LENGTH) names.add(name);
      else if (name.length() > MAX_NAME_LENGTH) truncated[0] = true;
      return;
    }
    if (value instanceof IHopMetadata metadata) {
      collectNames(metadata.getName(), names, truncated, visited, depth + 1);
      return;
    }
    if (depth >= MAX_VALUE_DEPTH || visited.put(value, Boolean.TRUE) != null) {
      truncated[0] = true;
      return;
    }
    if (value instanceof Iterable<?> values) {
      int count = 0;
      for (Object item : values) {
        if (count++ >= MAX_VALUES_PER_PROPERTY) {
          truncated[0] = true;
          break;
        }
        collectNames(item, names, truncated, visited, depth + 1);
      }
    } else if (value instanceof Map<?, ?> values) {
      int count = 0;
      for (Object item : values.values()) {
        if (count++ >= MAX_VALUES_PER_PROPERTY) {
          truncated[0] = true;
          break;
        }
        collectNames(item, names, truncated, visited, depth + 1);
      }
    } else if (value.getClass().isArray()) {
      int count = Math.min(Array.getLength(value), MAX_VALUES_PER_PROPERTY);
      if (Array.getLength(value) > count) truncated[0] = true;
      for (int index = 0; index < count; index++) {
        collectNames(Array.get(value, index), names, truncated, visited, depth + 1);
      }
    }
  }

  private static void addTextFallback(
      Document document,
      Map<HopMetadataPropertyType, List<String>> typeKeys,
      Map<ReferenceKey, HopProjectDefinitionIndex.MetadataReference> references,
      boolean[] truncated) {
    Element root = document.getDocumentElement();
    if (root == null) return;
    for (String componentTag : List.of("transform", "action")) {
      NodeList components = root.getElementsByTagName(componentTag);
      for (int index = 0; index < components.getLength(); index++) {
        Node node = components.item(index);
        if (!(node instanceof Element componentElement)) continue;
        String component = safeComponent(directChildText(componentElement, "name"));
        NodeList children = componentElement.getChildNodes();
        for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
          Node child = children.item(childIndex);
          if (!(child instanceof Element property)) continue;
          HopMetadataPropertyType propertyType = fallbackType(property.getTagName());
          if (propertyType == HopMetadataPropertyType.NONE) continue;
          if (containsElementChild(property)) continue;
          String value = property.getTextContent();
          if (value == null || value.isBlank()) continue;
          for (String key : typeKeys.getOrDefault(propertyType, List.of())) {
            addReference(
                key,
                value.trim(),
                component,
                HopProjectDefinitionIndex.ReferenceSource.TEXT_FALLBACK,
                references,
                truncated);
          }
        }
      }
    }
  }

  private static HopMetadataPropertyType fallbackType(String xmlProperty) {
    String normalized = xmlProperty.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "connection", "connectionname", "databaseconnection", "rdbmsconnection" ->
          HopMetadataPropertyType.RDBMS_CONNECTION;
      default -> HopMetadataPropertyType.NONE;
    };
  }

  private static String directChildText(Element element, String tag) {
    NodeList children = element.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child instanceof Element property && tag.equals(property.getTagName())) {
        return property.getTextContent();
      }
    }
    return null;
  }

  private static boolean containsElementChild(Element element) {
    NodeList children = element.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      if (children.item(index) instanceof Element) return true;
    }
    return false;
  }

  private static void addNames(
      String type,
      List<String> names,
      String component,
      HopProjectDefinitionIndex.ReferenceSource source,
      Map<ReferenceKey, HopProjectDefinitionIndex.MetadataReference> references,
      boolean[] truncated) {
    if (names == null) return;
    int count = 0;
    for (String name : names) {
      if (count++ >= MAX_VALUES_PER_PROPERTY) {
        truncated[0] = true;
        break;
      }
      addReference(type, name, component, source, references, truncated);
    }
  }

  private static void addReference(
      String type,
      String name,
      String component,
      HopProjectDefinitionIndex.ReferenceSource source,
      Map<ReferenceKey, HopProjectDefinitionIndex.MetadataReference> references,
      boolean[] truncated) {
    if (type == null || type.isBlank() || name == null || name.isBlank()) return;
    String normalizedType = type.trim();
    String normalizedName = name.trim();
    String normalizedComponent = safeComponent(component);
    if (normalizedType.length() > MAX_NAME_LENGTH
        || normalizedName.length() > MAX_NAME_LENGTH
        || normalizedComponent.length() > MAX_NAME_LENGTH) {
      truncated[0] = true;
      return;
    }
    ReferenceKey key =
        new ReferenceKey(
            normalizedType.toLowerCase(Locale.ROOT),
            normalizedName.toLowerCase(Locale.ROOT),
            normalizedComponent.toLowerCase(Locale.ROOT));
    HopProjectDefinitionIndex.MetadataReference existing = references.get(key);
    if (existing == null || source.ordinal() < existing.source().ordinal()) {
      if (existing == null && references.size() >= MAX_REFERENCES) {
        truncated[0] = true;
        return;
      }
      references.put(
          key,
          new HopProjectDefinitionIndex.MetadataReference(
              normalizedType, normalizedName, normalizedComponent, source));
    }
  }

  private static String safeComponent(String value) {
    return value == null || value.isBlank() ? "unknown" : value.trim();
  }
}
