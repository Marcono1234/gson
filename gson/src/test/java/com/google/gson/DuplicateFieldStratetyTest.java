package com.google.gson;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

// TODO: Also add Java17 specific tests for records
public class DuplicateFieldStratetyTest {
  @Test
  public void testUseLast() {
    Gson gson = new GsonBuilder().setDuplicateFieldStrategy(DuplicateFieldStrategy.USE_LAST).create();

    CustomClass deserialized = gson.fromJson("{}", CustomClass.class);
    assertThat(deserialized.i).isEqualTo(0);

    deserialized = gson.fromJson("{\"i\":1}", CustomClass.class);
    assertThat(deserialized.i).isEqualTo(1);

    deserialized = gson.fromJson("{\"i\":1,\"i\":2}", CustomClass.class);
    assertThat(deserialized.i).isEqualTo(2);

    deserialized = gson.fromJson("{\"i\":1,\"i\":null}", CustomClass.class);
    assertThat(deserialized.i).isNull();

    WithSerializedName deserialized2 = gson.fromJson("{\"i\":1,\"x\":2}", WithSerializedName.class);
    assertThat(deserialized2.i).isEqualTo(2);
  }

  @Test
  public void testThrowException() {
    Gson gson = new GsonBuilder().setDuplicateFieldStrategy(DuplicateFieldStrategy.THROW_EXCEPTION).create();

    // TODO: Adjust this once more specific exception type is thrown
    Exception e = assertThrows(Exception.class, () -> gson.fromJson("{\"i\":1,\"i\":1}", CustomClass.class));
    assertThat(e).hasMessageThat().isEqualTo("Field 'i' provides duplicate value for field com.google.gson.DuplicateFieldStratetyTest$CustomClass#i at path $.i");

    e = assertThrows(Exception.class, () -> gson.fromJson("{\"i\":1,\"i\":2}", CustomClass.class));
    assertThat(e).hasMessageThat().isEqualTo("Field 'i' provides duplicate value for field com.google.gson.DuplicateFieldStratetyTest$CustomClass#i at path $.i");

    e = assertThrows(Exception.class, () -> gson.fromJson("{\"i\":1,\"a\":2,\"i\":3}", CustomClass.class));
    assertThat(e).hasMessageThat().isEqualTo("Field 'i' provides duplicate value for field com.google.gson.DuplicateFieldStratetyTest$CustomClass#i at path $.i");

    e = assertThrows(Exception.class, () -> gson.fromJson("{\"i\":null,\"i\":1}", CustomClass.class));
    assertThat(e).hasMessageThat().isEqualTo("Field 'i' provides duplicate value for field com.google.gson.DuplicateFieldStratetyTest$CustomClass#i at path $.i");

    e = assertThrows(Exception.class, () -> gson.fromJson("{\"i\":null,\"i\":null}", CustomClass.class));
    assertThat(e).hasMessageThat().isEqualTo("Field 'i' provides duplicate value for field com.google.gson.DuplicateFieldStratetyTest$CustomClass#i at path $.i");

    e = assertThrows(Exception.class, () -> gson.fromJson("{\"i\":1,\"x\":2}", WithSerializedName.class));
    assertThat(e).hasMessageThat().isEqualTo("Field 'x' provides duplicate value for field com.google.gson.DuplicateFieldStratetyTest$WithSerializedName#i at path $.x");

    e = assertThrows(Exception.class, () -> gson.fromJson("{\"x\":1,\"x\":2}", WithSerializedName.class));
    assertThat(e).hasMessageThat().isEqualTo("Field 'x' provides duplicate value for field com.google.gson.DuplicateFieldStratetyTest$WithSerializedName#i at path $.x");
  }

  @Test
  public void testUnknownDuplicate() {
    Gson gson = new GsonBuilder().setDuplicateFieldStrategy(DuplicateFieldStrategy.THROW_EXCEPTION).create();
    CustomClass deserialized = gson.fromJson("{\"a\":1,\"a\":2}", CustomClass.class);
    assertThat(deserialized.i).isEqualTo(0);
  }

  @Test
  public void testCustom() {
    Gson gson = new GsonBuilder().setDuplicateFieldStrategy(new DuplicateFieldStrategy() {
      @Override
      public <T> T handleDuplicateField(TypeToken<?> declaringType, Object instance, Field field,
          TypeToken<T> resolvedFieldType, TypeAdapter<T> fieldTypeAdapter, Object existingFieldValue,
          String fieldName, JsonReader jsonReader) throws IOException {

        assertThat(declaringType).isEqualTo(TypeToken.get(WithStringField.class));
        assertThat(instance).isInstanceOf(WithStringField.class);
        Field f;
        try {
          f = WithStringField.class.getDeclaredField("s");
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
        assertThat(field).isEqualTo(f);
        assertThat(fieldTypeAdapter).isNotNull();

        @SuppressWarnings("unchecked")
        T t = (T) ("field:" + fieldName + ";existing:" + existingFieldValue + ";new:" + jsonReader.nextString());
        return t;
      }
    }).create();

    WithStringField deserialized = gson.fromJson("{}", WithStringField.class);
    assertThat(deserialized.s).isNull();

    deserialized = gson.fromJson("{\"s\":\"a\"}", WithStringField.class);
    assertThat(deserialized.s).isEqualTo("a");

    deserialized = gson.fromJson("{\"s\":\"a\",\"s\":\"b\"}", WithStringField.class);
    assertThat(deserialized.s).isEqualTo("field:s;existing:a;new:b");

    deserialized = gson.fromJson("{\"s\":\"a\",\"x\":\"b\"}", WithStringField.class);
    assertThat(deserialized.s).isEqualTo("field:x;existing:a;new:b");
  }

  @Test
  public void testCustomException() {
    Gson gson = new GsonBuilder().setDuplicateFieldStrategy(new DuplicateFieldStrategy() {
      @Override
      public <T> T handleDuplicateField(TypeToken<?> declaringType, Object instance, Field field,
          TypeToken<T> resolvedFieldType, TypeAdapter<T> fieldTypeAdapter, Object existingFieldValue,
          String fieldName, JsonReader jsonReader) throws IOException {

        throw new RuntimeException("custom");
      }
    }).create();

    // TODO: Adjust this once more specific exception type is thrown
    Exception e = assertThrows(Exception.class, () -> gson.fromJson("{\"i\":1,\"x\":2}", WithSerializedName.class));
    assertThat(e).hasMessageThat().isEqualTo("Failed handling duplicate field 'x' (com.google.gson.DuplicateFieldStratetyTest$WithSerializedName#i) at path $.x");
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("custom");
  }

  @Test
  public void testResolvedFieldType() {
    List<TypeToken<?>> declaringTypes = new ArrayList<>();
    List<TypeToken<?>> fieldTypes = new ArrayList<>();
    Gson gson = new GsonBuilder().setDuplicateFieldStrategy(new DuplicateFieldStrategy() {
      @Override
      public <T> T handleDuplicateField(TypeToken<?> declaringType, Object instance, Field field,
          TypeToken<T> resolvedFieldType, TypeAdapter<T> fieldTypeAdapter, Object existingFieldValue,
          String fieldName, JsonReader jsonReader) throws IOException {

        declaringTypes.add(declaringType);
        fieldTypes.add(resolvedFieldType);
        jsonReader.skipValue();
        return null;
      }
    }).create();

    TypeToken<WithTypeVariable<String>> typeToken = new TypeToken<WithTypeVariable<String>>() {};
    gson.fromJson("{\"a\":null,\"a\":null}", typeToken);
    assertThat(declaringTypes).containsExactly(typeToken);
    assertThat(fieldTypes).containsExactly(TypeToken.get(String.class));
  }

  @Test
  public void testBadNewFieldValue() {
    Gson gson = new GsonBuilder().setDuplicateFieldStrategy(new DuplicateFieldStrategy() {
      @Override
      public <T> T handleDuplicateField(TypeToken<?> declaringType, Object instance, Field field,
          TypeToken<T> resolvedFieldType, TypeAdapter<T> fieldTypeAdapter, Object existingFieldValue,
          String fieldName, JsonReader jsonReader) throws IOException {

        jsonReader.skipValue();
        @SuppressWarnings("unchecked")
        T t = (T) "a";
        return t;
      }

      @Override
      public String toString() {
        return "my-strategy";
      }
    }).create();

    // TODO: Adjust this once more specific exception type is thrown
    Exception e = assertThrows(Exception.class, () -> gson.fromJson("{\"i\":1,\"i\":2}", CustomClass.class));
    assertThat(e).hasMessageThat().isEqualTo("Failed storing java.lang.String provided by my-strategy into field 'com.google.gson.DuplicateFieldStratetyTest$CustomClass#i' at path $.i");
    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class); // thrown by Field.set
  }

  private static class CustomClass {
    Integer i = 0;
  }

  private static class WithSerializedName {
    @SerializedName(value = "i", alternate = "x")
    int i = 0;
  }

  private static class WithStringField {
    @SerializedName(value = "s", alternate = "x")
    String s;
  }

  private static class WithTypeVariable<T> {
    @SuppressWarnings("unused")
    T a;
  }
}
