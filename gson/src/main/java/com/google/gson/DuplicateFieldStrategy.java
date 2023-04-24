package com.google.gson;

import com.google.gson.internal.reflect.ReflectionHelper;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.lang.reflect.Field;

// TODO: Doc not called for duplicate unknown fields
public interface DuplicateFieldStrategy {
  static DuplicateFieldStrategy USE_LAST = new DuplicateFieldStrategy() {
    @Override
    public <T> T handleDuplicateField(TypeToken<?> declaringType, Object instance, Field field,
        TypeToken<T> resolvedFieldType, TypeAdapter<T> fieldTypeAdapter, Object existingFieldValue,
        String fieldName, JsonReader jsonReader) throws IOException {
      // Ignore existing value and simply read and return duplicate value
      return fieldTypeAdapter.read(jsonReader);
    }

    @Override
    public String toString() {
      return "USE_LAST";
    }
  };

  static DuplicateFieldStrategy THROW_EXCEPTION = new DuplicateFieldStrategy() {
    @Override
    public <T> T handleDuplicateField(TypeToken<?> declaringType, Object instance, Field field,
        TypeToken<T> resolvedFieldType, TypeAdapter<T> fieldTypeAdapter, Object existingFieldValue,
        String fieldName, JsonReader jsonReader) {
      // Include both field name and Field since they might differ for custom field naming strategies
      // TODO Proper exception type
      throw new RuntimeException("Field '" + fieldName + "' provides duplicate value for field " + ReflectionHelper.fieldToString(field) + " at path " + jsonReader.getPath());
    }

    @Override
    public String toString() {
      return "THROW_EXCEPTION";
    }
  };

  // TODO: Is access to `instance` (and `declaringType`) really needed here? Only to look up values of other fields
  <T> T handleDuplicateField(TypeToken<?> declaringType, Object instance, Field field, TypeToken<T> resolvedFieldType, TypeAdapter<T> fieldTypeAdapter, Object existingFieldValue, String fieldName, JsonReader jsonReader) throws IOException;
}
