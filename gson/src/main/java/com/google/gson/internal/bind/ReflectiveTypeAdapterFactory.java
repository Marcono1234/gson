/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gson.internal.bind;

import com.google.gson.DuplicateFieldStrategy;
import com.google.gson.FieldNamingStrategy;
import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.MissingFieldValueStrategy;
import com.google.gson.ReflectionAccessFilter;
import com.google.gson.ReflectionAccessFilter.FilterResult;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.UnknownFieldStrategy;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.internal.$Gson$Types;
import com.google.gson.internal.ConstructorConstructor;
import com.google.gson.internal.Excluder;
import com.google.gson.internal.ObjectConstructor;
import com.google.gson.internal.Primitives;
import com.google.gson.internal.ReflectionAccessFilterHelper;
import com.google.gson.internal.reflect.ReflectionHelper;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Type adapter that reflects over the fields and methods of a class.
 */
public final class ReflectiveTypeAdapterFactory implements TypeAdapterFactory {
  private final ConstructorConstructor constructorConstructor;
  private final FieldNamingStrategy fieldNamingPolicy;
  private final Excluder excluder;
  private final JsonAdapterAnnotationTypeAdapterFactory jsonAdapterFactory;
  private final MissingFieldValueStrategy missingFieldValueStrategy;
  private final UnknownFieldStrategy unknownFieldStrategy;
  private final DuplicateFieldStrategy duplicateFieldStrategy;
  private final List<ReflectionAccessFilter> reflectionFilters;

  public ReflectiveTypeAdapterFactory(ConstructorConstructor constructorConstructor,
      FieldNamingStrategy fieldNamingPolicy, Excluder excluder,
      JsonAdapterAnnotationTypeAdapterFactory jsonAdapterFactory,
      MissingFieldValueStrategy missingFieldValueStrategy, UnknownFieldStrategy unknownFieldStrategy,
      DuplicateFieldStrategy duplicateFieldStrategy, List<ReflectionAccessFilter> reflectionFilters) {
    this.constructorConstructor = constructorConstructor;
    this.fieldNamingPolicy = fieldNamingPolicy;
    this.excluder = excluder;
    this.jsonAdapterFactory = jsonAdapterFactory;
    this.missingFieldValueStrategy = missingFieldValueStrategy;
    this.unknownFieldStrategy = unknownFieldStrategy;
    this.duplicateFieldStrategy = duplicateFieldStrategy;
    this.reflectionFilters = reflectionFilters;
  }

  private boolean includeField(Field f, boolean serialize) {
    return !excluder.excludeClass(f.getType(), serialize) && !excluder.excludeField(f, serialize);
  }

  /** first element holds the default name */
  @SuppressWarnings("MixedMutabilityReturnType")
  private List<String> getFieldNames(Field f) {
    SerializedName annotation = f.getAnnotation(SerializedName.class);
    if (annotation == null) {
      String name = fieldNamingPolicy.translateName(f);
      return Collections.singletonList(name);
    }

    String serializedName = annotation.value();
    String[] alternates = annotation.alternate();
    if (alternates.length == 0) {
      return Collections.singletonList(serializedName);
    }

    List<String> fieldNames = new ArrayList<>(alternates.length + 1);
    fieldNames.add(serializedName);
    Collections.addAll(fieldNames, alternates);
    return fieldNames;
  }

  @Override
  public <T> TypeAdapter<T> create(Gson gson, final TypeToken<T> type) {
    Class<? super T> raw = type.getRawType();

    if (!Object.class.isAssignableFrom(raw)) {
      return null; // it's a primitive!
    }

    FilterResult filterResult =
        ReflectionAccessFilterHelper.getFilterResult(reflectionFilters, raw);
    if (filterResult == FilterResult.BLOCK_ALL) {
      throw new JsonIOException(
          "ReflectionAccessFilter does not permit using reflection for " + raw
              + ". Register a TypeAdapter for this type or adjust the access filter.");
    }
    boolean blockInaccessible = filterResult == FilterResult.BLOCK_INACCESSIBLE;

    // If the type is actually a Java Record, we need to use the RecordAdapter instead. This will always be false
    // on JVMs that do not support records.
    if (ReflectionHelper.isRecord(raw)) {
      @SuppressWarnings("unchecked")
      TypeAdapter<T> adapter = new RecordAdapter<>(gson,
          missingFieldValueStrategy, unknownFieldStrategy, duplicateFieldStrategy, type, (Class<T>) raw,
          getBoundFields(gson, type, raw, blockInaccessible, true), blockInaccessible);
      return adapter;
    }

    ObjectConstructor<T> constructor = constructorConstructor.get(type);
    return new FieldReflectionAdapter<>(gson, missingFieldValueStrategy, unknownFieldStrategy,
        duplicateFieldStrategy, constructor, type, getBoundFields(gson, type, raw, blockInaccessible, false));
  }

  private static <M extends AccessibleObject & Member> void checkAccessible(Object object, M member) {
    if (!ReflectionAccessFilterHelper.canAccess(member, Modifier.isStatic(member.getModifiers()) ? null : object)) {
      String memberDescription = ReflectionHelper.getAccessibleObjectDescription(member, true);
      throw new JsonIOException(memberDescription + " is not accessible and ReflectionAccessFilter does not"
          + " permit making it accessible. Register a TypeAdapter for the declaring type, adjust the"
          + " access filter or increase the visibility of the element and its declaring type.");
    }
  }

  private BoundField createBoundField(
      final Gson context, final Field field, final Method accessor, final String name,
      final TypeToken<?> fieldType, boolean serialize, boolean deserialize,
      final boolean blockInaccessible) {

    final boolean isPrimitive = Primitives.isPrimitive(fieldType.getRawType());

    int modifiers = field.getModifiers();
    final boolean isStaticFinalField = Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers);

    JsonAdapter annotation = field.getAnnotation(JsonAdapter.class);
    TypeAdapter<?> mapped = null;
    if (annotation != null) {
      // This is not safe; requires that user has specified correct adapter class for @JsonAdapter
      mapped = jsonAdapterFactory.getTypeAdapter(
          constructorConstructor, context, fieldType, annotation);
    }
    final boolean jsonAdapterPresent = mapped != null;
    if (mapped == null) mapped = context.getAdapter(fieldType);

    @SuppressWarnings("unchecked")
    final TypeAdapter<Object> readTypeAdapter = (TypeAdapter<Object>) mapped;
    final TypeAdapter<Object> writeTypeAdapter;
    if (serialize) {
      writeTypeAdapter = jsonAdapterPresent ? readTypeAdapter
          : new TypeAdapterRuntimeTypeWrapper<>(context, readTypeAdapter, fieldType.getType());
    } else {
      // Will never actually be used, but we set it to avoid confusing nullness-analysis tools
      writeTypeAdapter = readTypeAdapter;
    }
    return new BoundField(name, field, fieldType, readTypeAdapter, serialize, deserialize) {
      @Override void write(JsonWriter writer, Object source)
          throws IOException, IllegalAccessException {
        if (!serialized) return;
        if (blockInaccessible) {
          if (accessor == null) {
            checkAccessible(source, field);
          } else {
            // Note: This check might actually be redundant because access check for canonical
            // constructor should have failed already
            checkAccessible(source, accessor);
          }
        }

        Object fieldValue;
        if (accessor != null) {
          try {
            fieldValue = accessor.invoke(source);
          } catch (InvocationTargetException e) {
            String accessorDescription = ReflectionHelper.getAccessibleObjectDescription(accessor, false);
            throw new JsonIOException("Accessor " + accessorDescription + " threw exception", e.getCause());
          }
        } else {
          fieldValue = field.get(source);
        }
        if (fieldValue == source) {
          // avoid direct recursion
          return;
        }
        writer.name(name);
        writeTypeAdapter.write(writer, fieldValue);
      }

      @Override
      void storeIntoArray(Object fieldValue, JsonReader reader, int index, Object[] target) throws IOException, JsonParseException {
        if (fieldValue == null && isPrimitive) {
          throw new JsonParseException("null is not allowed as value for record component '" + fieldName + "'"
              + " of primitive type; at path " + reader.getPath());
        }
        target[index] = fieldValue;
      }

      @Override
      void storeIntoField(Object fieldValue, Object target) throws IllegalAccessException {
        if (fieldValue != null || !isPrimitive) {
          if (blockInaccessible) {
            checkAccessible(target, field);
          } else if (isStaticFinalField) {
            // Reflection does not permit setting value of `static final` field, even after calling `setAccessible`
            // Handle this here to avoid causing IllegalAccessException when calling `Field.set`
            String fieldDescription = ReflectionHelper.getAccessibleObjectDescription(field, false);
            throw new JsonIOException("Cannot set value of 'static final' " + fieldDescription);
          }
          field.set(target, fieldValue);
        }
      }
    };
  }

  private Map<String, BoundField> getBoundFields(Gson context, TypeToken<?> type, Class<?> raw,
                                                 boolean blockInaccessible, boolean isRecord) {
    Map<String, BoundField> result = new LinkedHashMap<>();
    if (raw.isInterface()) {
      return result;
    }

    Class<?> originalRaw = raw;
    while (raw != Object.class) {
      Field[] fields = raw.getDeclaredFields();

      // For inherited fields, check if access to their declaring class is allowed
      if (raw != originalRaw && fields.length > 0) {
        FilterResult filterResult = ReflectionAccessFilterHelper.getFilterResult(reflectionFilters, raw);
        if (filterResult == FilterResult.BLOCK_ALL) {
          throw new JsonIOException("ReflectionAccessFilter does not permit using reflection for " + raw
              + " (supertype of " + originalRaw + "). Register a TypeAdapter for this type"
              + " or adjust the access filter.");
        }
        blockInaccessible = filterResult == FilterResult.BLOCK_INACCESSIBLE;
      }

      for (Field field : fields) {
        boolean serialize = includeField(field, true);
        boolean deserialize = includeField(field, false);
        if (!serialize && !deserialize) {
          continue;
        }
        // The accessor method is only used for records. If the type is a record, we will read out values
        // via its accessor method instead of via reflection. This way we will bypass the accessible restrictions
        Method accessor = null;
        if (isRecord) {
          // If there is a static field on a record, there will not be an accessor. Instead we will use the default
          // field serialization logic, but for deserialization the field is excluded for simplicity. Note that Gson
          // ignores static fields by default, but GsonBuilder.excludeFieldsWithModifiers can overwrite this.
          if (Modifier.isStatic(field.getModifiers())) {
            deserialize = false;
          } else {
            accessor = ReflectionHelper.getAccessor(raw, field);
            // If blockInaccessible, skip and perform access check later
            if (!blockInaccessible) {
              ReflectionHelper.makeAccessible(accessor);
            }

            // @SerializedName can be placed on accessor method, but it is not supported there
            // If field and method have annotation it is not easily possible to determine if accessor method
            // is implicit and has inherited annotation, or if it is explicitly declared with custom annotation
            if (accessor.getAnnotation(SerializedName.class) != null
                && field.getAnnotation(SerializedName.class) == null) {
              String methodDescription = ReflectionHelper.getAccessibleObjectDescription(accessor, false);
              throw new JsonIOException("@SerializedName on " + methodDescription + " is not supported");
            }
          }
        }

        // If blockInaccessible, skip and perform access check later
        // For Records if the accessor method is used the field does not have to be made accessible
        if (!blockInaccessible && accessor == null) {
          ReflectionHelper.makeAccessible(field);
        }
        Type fieldType = $Gson$Types.resolve(type.getType(), raw, field.getGenericType());
        List<String> fieldNames = getFieldNames(field);
        BoundField previous = null;
        for (int i = 0, size = fieldNames.size(); i < size; ++i) {
          String name = fieldNames.get(i);
          if (i != 0) serialize = false; // only serialize the default name
          BoundField boundField = createBoundField(context, field, accessor, name,
              TypeToken.get(fieldType), serialize, deserialize, blockInaccessible);
          BoundField replaced = result.put(name, boundField);
          if (previous == null) previous = replaced;
        }
        if (previous != null) {
          throw new IllegalArgumentException("Class " + originalRaw.getName()
              + " declares multiple JSON fields named '" + previous.name + "'; conflict is caused"
              + " by fields " + ReflectionHelper.fieldToString(previous.field) + " and " + ReflectionHelper.fieldToString(field));
        }
      }
      type = TypeToken.get($Gson$Types.resolve(type.getType(), raw, raw.getGenericSuperclass()));
      raw = type.getRawType();
    }
    return result;
  }

  static abstract class BoundField {
    final String name;
    final Field field;
    /** Name of the underlying field */
    final String fieldName;
    final TypeToken<?> resolvedType;
    final TypeAdapter<?> readTypeAdapter;
    final boolean serialized;
    final boolean deserialized;

    protected BoundField(String name, Field field, TypeToken<?> resolvedType, TypeAdapter<?> readTypeAdapter,
        boolean serialized, boolean deserialized) {
      this.name = name;
      this.field = field;
      this.fieldName = field.getName();
      this.resolvedType = resolvedType;
      this.readTypeAdapter = readTypeAdapter;
      this.serialized = serialized;
      this.deserialized = deserialized;
    }

    /** Reads this field value from the source, and append its JSON value to the writer */
    abstract void write(JsonWriter writer, Object source) throws IOException, IllegalAccessException;

    /**
     * Stores the value into the target array, used to provide constructor arguments for records
     *
     * @param reader should only be used to call {@link JsonReader#getPreviousPath()} for exceptions,
     *      since value has already been read
     */
    abstract void storeIntoArray(Object fieldValue, JsonReader reader, int index, Object[] target) throws IOException, JsonParseException;

    /** Stores the field value in the corresponding field on target via reflection */
    abstract void storeIntoField(Object fieldValue, Object target) throws IllegalAccessException;
  }

  /**
   * Base class for Adapters produced by this factory.
   *
   * <p>The {@link RecordAdapter} is a special case to handle records for JVMs that support it, for
   * all other types we use the {@link FieldReflectionAdapter}. This class encapsulates the common
   * logic for serialization and deserialization. During deserialization, we construct an
   * accumulator A, which we use to accumulate values from the source JSON. After the object has been read in
   * full, the {@link #finalize(Object)} method is used to convert the accumulator to an instance
   * of T.
   *
   * @param <T> type of objects that this Adapter creates.
   * @param <A> type of accumulator used to build the deserialization result.
   */
  // This class is public because external projects check for this class with `instanceof` (even though it is internal)
  public static abstract class Adapter<T, A> extends TypeAdapter<T> {
    final Gson gson;
    final MissingFieldValueStrategy missingFieldValueStrategy;
    final UnknownFieldStrategy unknownFieldStrategy;
    final DuplicateFieldStrategy duplicateFieldStrategy;
    final TypeToken<T> type;
    final Map<String, BoundField> boundFields;
    /** Fields to consider for missing field handling; {@code null} if missing fields should be ignored */
    final Map<Field, BoundField> missingFieldsToCheck;

    Adapter(Gson gson, MissingFieldValueStrategy missingFieldValueStrategy, UnknownFieldStrategy unknownFieldStrategy,
        DuplicateFieldStrategy duplicateFieldStrategy, TypeToken<T> type, Map<String, BoundField> boundFields) {
      this.gson = gson;
      this.missingFieldValueStrategy = missingFieldValueStrategy;
      this.unknownFieldStrategy = unknownFieldStrategy;
      this.duplicateFieldStrategy = duplicateFieldStrategy;
      this.type = type;
      this.boundFields = boundFields;

      if (missingFieldValueStrategy == MissingFieldValueStrategy.DO_NOTHING) {
        missingFieldsToCheck = null;
      } else {
        // Track the underlying Field because there might be multiple BoundField entries when using @SerializedName
        missingFieldsToCheck = new LinkedHashMap<>(boundFields.size());
        for (BoundField boundField : this.boundFields.values()) {
          if (boundField.deserialized) {
            missingFieldsToCheck.put(boundField.field, boundField);
          }
        }
      }
    }

    @Override
    public void write(JsonWriter out, T value) throws IOException {
      if (value == null) {
        out.nullValue();
        return;
      }

      out.beginObject();
      try {
        for (BoundField boundField : boundFields.values()) {
          boundField.write(out, value);
        }
      } catch (IllegalAccessException e) {
        throw ReflectionHelper.createExceptionForUnexpectedIllegalAccess(e);
      }
      out.endObject();
    }

    @Override
    public T read(JsonReader in) throws IOException {
      if (in.peek() == JsonToken.NULL) {
        in.nextNull();
        return null;
      }

      A accumulator = createAccumulator();
      Map<Field, BoundField> missingFields = missingFieldsToCheck == null ? null : new LinkedHashMap<>(missingFieldsToCheck);
      // For USE_LAST there is no need to check for duplicate fields, simply let field values overwrite each other
      Map<Field, Object> fieldValues = duplicateFieldStrategy == DuplicateFieldStrategy.USE_LAST ? null : new HashMap<>();

      try {
        in.beginObject();
        while (in.hasNext()) {
          String name = in.nextName();
          BoundField field = boundFields.get(name);
          if (field == null || !field.deserialized) {
            try {
              unknownFieldStrategy.handleUnknownField(type, createObjectForFieldStrategy(accumulator), name, in, gson);
            } catch (IOException e) {
              // Don't wrap IOException; it is most likely unrelated to unknownFieldStrategy, but instead caused by JSON data
              throw e;
            } catch (Exception e) {
              // UnknownFieldStrategy.THROW_EXCEPTION provides enough context, can directly rethrow
              if (unknownFieldStrategy == UnknownFieldStrategy.THROW_EXCEPTION) {
                throw e;
              }
              // TODO Proper exception type
              throw new RuntimeException("Failed handling unknown field '" + name + "' for " + type.getRawType() + " at path " + in.getPath(), e);
            }
          } else {
            Object fieldValue;
            Field refField = field.field;
            // Uses containsKey in case field has `null` value
            boolean wasHandledByStrategy = fieldValues != null && fieldValues.containsKey(refField);

            if (wasHandledByStrategy) {
              Object existingValue = fieldValues.get(refField);
              try {
                @SuppressWarnings("unchecked")
                Object v = duplicateFieldStrategy.handleDuplicateField(type, createObjectForFieldStrategy(accumulator), refField, (TypeToken<Object>) field.resolvedType, (TypeAdapter<Object>) field.readTypeAdapter, existingValue, name, in);
                fieldValue = v;
              } catch (Exception e) {
                // DuplicateFieldStrategy.THROW_EXCEPTION provides enough context, can directly rethrow
                if (duplicateFieldStrategy == DuplicateFieldStrategy.THROW_EXCEPTION) {
                  throw e;
                }
                // TODO Proper exception type
                throw new RuntimeException("Failed handling duplicate field '" + name + "' (" + ReflectionHelper.fieldToString(refField) + ")"
                    + " at path " + in.getPath(), e);
              }
            } else {
              fieldValue = field.readTypeAdapter.read(in);
            }

            if (fieldValues != null) {
              fieldValues.put(refField, fieldValue);
            }
            try {
              storeFieldValue(accumulator, fieldValue, in, field);
            } catch (Exception e) {
              if (wasHandledByStrategy) {
                String valueType = fieldValue == null ? "null" : fieldValue.getClass().getName();
                // TODO Proper exception type
                throw new RuntimeException("Failed storing " + valueType + " provided by " + duplicateFieldStrategy + " into field '" + ReflectionHelper.fieldToString(refField) + "' at path " + in.getPreviousPath(), e);
              }
              throw e;
            }

            if (missingFields != null) {
              missingFields.remove(field.field);
            }
          }
        }
      } catch (IllegalStateException e) {
        throw new JsonSyntaxException(e);
      } catch (IllegalAccessException e) {
        throw ReflectionHelper.createExceptionForUnexpectedIllegalAccess(e);
      }
      in.endObject();

      if (missingFields != null && !missingFields.isEmpty()) {
        for (Map.Entry<Field, BoundField> fieldEntry : missingFields.entrySet()) {
          Field field = fieldEntry.getKey();
          BoundField boundField = fieldEntry.getValue();
          Object newValue;
          try {
            newValue = missingFieldValueStrategy.handleMissingField(type, createObjectForFieldStrategy(accumulator), field, boundField.resolvedType);
          } catch (Exception e) {
            // TODO Proper exception type
            throw new RuntimeException("Failed handling missing field '" + ReflectionHelper.fieldToString(field) + "' at path " + in.getPath(), e);
          }

          // For null values keep the existing initial value
          if (newValue != null) {
            try {
              addMissingFieldValue(accumulator, boundField, newValue);
            } catch (Exception e) {
              // TODO Proper exception type
              throw new RuntimeException("Failed storing " + newValue.getClass().getName() + " provided by " + missingFieldValueStrategy + " into field '" + ReflectionHelper.fieldToString(field) + "' at path " + in.getPreviousPath(), e);
            }
          }
        }
      }

      return finalize(accumulator);
    }

    /** Creates the Object that will be used to collect each field value */
    abstract A createAccumulator();

    /**
     * Creates the Object based on the accumulator that will be passed as {@code instance}
     * to the {@link MissingFieldValueStrategy} and {@link UnknownFieldStrategy}.
     *
     * @return the object for missing and unknown field strategies, can be {@code null}
     */
    abstract Object createObjectForFieldStrategy(A accumulator);

    /**
     * Stores a field value into the accumulator.
     *
     * @param in should only be used to call {@link JsonReader#getPreviousPath()} for exceptions,
     *      since value has already been read
     */
    abstract void storeFieldValue(A accumulator, Object fieldValue, JsonReader in, BoundField field)
        throws IllegalAccessException, IOException;

    /**
     * Called for the {@link MissingFieldValueStrategy} to add {@code value} as value
     * for {@code field}.
     *
     * @param value the field value, must not be {@code null}
     */
    abstract void addMissingFieldValue(A accumulator, BoundField field, Object value);

    /** Converts the accumulator to a final instance of T. */
    abstract T finalize(A accumulator);
  }

  private static final class FieldReflectionAdapter<T> extends Adapter<T, T> {
    private final ObjectConstructor<T> constructor;

    FieldReflectionAdapter(Gson gson, MissingFieldValueStrategy missingFieldValueStrategy, UnknownFieldStrategy unknownFieldStrategy,
        DuplicateFieldStrategy duplicateFieldStrategy, ObjectConstructor<T> constructor, TypeToken<T> type, Map<String, BoundField> boundFields) {
      super(gson, missingFieldValueStrategy, unknownFieldStrategy, duplicateFieldStrategy, type, boundFields);
      this.constructor = constructor;
    }

    @Override
    T createAccumulator() {
      return constructor.construct();
    }

    @Override
    Object createObjectForFieldStrategy(T accumulator) {
      // Let missing and unknown field strategies directly access constructed object
      return accumulator;
    }

    @Override
    void storeFieldValue(T accumulator, Object fieldValue, JsonReader in, BoundField field)
        throws IllegalAccessException, IOException {
      field.storeIntoField(fieldValue, accumulator);
    }

    @Override
    void addMissingFieldValue(T accumulator, BoundField field, Object value) {
      try {
        field.storeIntoField(value, accumulator);
      } catch (IllegalAccessException e) {
        throw ReflectionHelper.createExceptionForUnexpectedIllegalAccess(e);
      }
    }

    @Override
    T finalize(T accumulator) {
      return accumulator;
    }
  }

  private static final class RecordAdapter<T> extends Adapter<T, Object[]> {
    static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = primitiveDefaults();

    // The canonical constructor of the record
    private final Constructor<T> constructor;
    // Array of arguments to the constructor, initialized with default values for primitives
    private final Object[] constructorArgsDefaults;
    // Map from component names to index into the constructors arguments.
    private final Map<String, Integer> componentIndices = new HashMap<>();

    RecordAdapter(Gson gson, MissingFieldValueStrategy missingFieldValueStrategy, UnknownFieldStrategy unknownFieldStrategy,
        DuplicateFieldStrategy duplicateFieldStrategy, TypeToken<T> type, Class<T> raw, Map<String, BoundField> boundFields, boolean blockInaccessible) {
      super(gson, missingFieldValueStrategy, unknownFieldStrategy, duplicateFieldStrategy, type, boundFields);
      constructor = ReflectionHelper.getCanonicalRecordConstructor(raw);

      if (blockInaccessible) {
        checkAccessible(null, constructor);
      } else {
        // Ensure the constructor is accessible
        ReflectionHelper.makeAccessible(constructor);
      }

      String[] componentNames = ReflectionHelper.getRecordComponentNames(raw);
      for (int i = 0; i < componentNames.length; i++) {
        componentIndices.put(componentNames[i], i);
      }
      Class<?>[] parameterTypes = constructor.getParameterTypes();

      // We need to ensure that we are passing non-null values to primitive fields in the constructor. To do this,
      // we create an Object[] where all primitives are initialized to non-null values.
      constructorArgsDefaults = new Object[parameterTypes.length];
      for (int i = 0; i < parameterTypes.length; i++) {
        // This will correctly be null for non-primitive types:
        constructorArgsDefaults[i] = PRIMITIVE_DEFAULTS.get(parameterTypes[i]);
      }
    }

    private static Map<Class<?>, Object> primitiveDefaults() {
      Map<Class<?>, Object> zeroes = new HashMap<>();
      zeroes.put(byte.class, (byte) 0);
      zeroes.put(short.class, (short) 0);
      zeroes.put(int.class, 0);
      zeroes.put(long.class, 0L);
      zeroes.put(float.class, 0F);
      zeroes.put(double.class, 0D);
      zeroes.put(char.class, '\0');
      zeroes.put(boolean.class, false);
      return zeroes;
    }

    @Override
    Object[] createAccumulator() {
      return constructorArgsDefaults.clone();
    }

    @Override
    Object createObjectForFieldStrategy(Object[] accumulator) {
      // Don't let missing and unknown field strategies directly access internal accumulator object
      // TODO: In the future maybe provide a Map-like object which encapsulates accumulator,
      //   but restricts operations only to valid component names / property names?
      return null;
    }

    private int getComponentIndex(String fieldName) {
      // Obtain the component index from the name of the field backing it
      Integer componentIndex = componentIndices.get(fieldName);
      if (componentIndex == null) {
        throw new IllegalStateException(
            "Could not find the index in the constructor '" + ReflectionHelper.constructorToString(constructor) + "'"
                + " for field with name '" + fieldName + "',"
                + " unable to determine which argument in the constructor the field corresponds"
                + " to. This is unexpected behavior, as we expect the RecordComponents to have the"
                + " same names as the fields in the Java class, and that the order of the"
                + " RecordComponents is the same as the order of the canonical constructor parameters.");
      }
      return componentIndex;
    }

    @Override
    void storeFieldValue(Object[] accumulator, Object fieldValue, JsonReader in, BoundField field) throws IOException {
      field.storeIntoArray(fieldValue, in, getComponentIndex(field.fieldName), accumulator);
    }

    @Override
    void addMissingFieldValue(Object[] accumulator, BoundField field, Object value) {
      assert(value != null);
      accumulator[getComponentIndex(field.fieldName)] = value;
    }

    @Override
    T finalize(Object[] accumulator) {
      try {
        return constructor.newInstance(accumulator);
      } catch (IllegalAccessException e) {
        throw ReflectionHelper.createExceptionForUnexpectedIllegalAccess(e);
      }
      // Note: InstantiationException should be impossible because record class is not abstract;
      //  IllegalArgumentException should not be possible unless a bad adapter returns objects of the wrong type
      catch (InstantiationException | IllegalArgumentException e) {
        throw new RuntimeException(
            "Failed to invoke constructor '" + ReflectionHelper.constructorToString(constructor) + "'"
            + " with args " + Arrays.toString(accumulator), e);
      }
      catch (InvocationTargetException e) {
        // TODO: JsonParseException ?
        throw new RuntimeException(
            "Failed to invoke constructor '" + ReflectionHelper.constructorToString(constructor) + "'"
            + " with args " + Arrays.toString(accumulator), e.getCause());
      }
    }
  }
}
