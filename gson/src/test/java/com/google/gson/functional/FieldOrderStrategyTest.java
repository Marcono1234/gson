package com.google.gson.functional;

import com.google.gson.FieldOrderStrategy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static com.google.common.truth.Truth.assertThat;

public class FieldOrderStrategyTest {
    static class Base {
        int z = 1;
        int y = 2;
    }
    static class Sub extends Base {
        int w = 3;
        int x = 4;
    }

    @Test
    public void testStringCompare() {
        Gson gson = new Gson();
        assertThat(gson.toJson(new Sub())).isEqualTo("{\"w\":3,\"x\":4,\"z\":1,\"y\":2}");

        gson = new GsonBuilder().setFieldOrderStrategy(FieldOrderStrategy.STRING_COMPARE).create();
        assertThat(gson.toJson(new Sub())).isEqualTo("{\"w\":3,\"x\":4,\"y\":2,\"z\":1}");

        gson = new GsonBuilder().setFieldOrderStrategy(FieldOrderStrategy.SUPER_THEN_STRING_COMPARE).create();
        assertThat(gson.toJson(new Sub())).isEqualTo("{\"y\":2,\"z\":1,\"w\":3,\"x\":4}");
    }

    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface FieldIndex {
        int value();
    }

    static class Annotated {
        @FieldIndex(1)
        int x = 1;

        @FieldIndex(3)
        int y = 2;

        @FieldIndex(2)
        int z = 3;
    }

    @Test
    public void testCustom() {
        Gson gson = new Gson();
        assertThat(gson.toJson(new Annotated())).isEqualTo("{\"x\":1,\"y\":2,\"z\":3}");

        gson = new GsonBuilder().setFieldOrderStrategy(new FieldOrderStrategy() {
            @Override
            public int compare(SerializedField o1, SerializedField o2) {
                FieldIndex fi1 = o1.getField().getAnnotation(FieldIndex.class);
                FieldIndex fi2 = o2.getField().getAnnotation(FieldIndex.class);

                return Integer.compare(fi1.value(), fi2.value());
            }
        }).create();
        assertThat(gson.toJson(new Annotated())).isEqualTo("{\"x\":1,\"z\":3,\"y\":2}");
    }

    // TODO: Test with custom @SerializedName and FieldNamingStrategy
    // TODO: Test field order has no effect on deserialization
    // TODO: Test for record class
}
