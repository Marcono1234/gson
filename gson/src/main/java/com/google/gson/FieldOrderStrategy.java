package com.google.gson;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.Objects;

public interface FieldOrderStrategy extends Comparator<FieldOrderStrategy.SerializedField> {
    final class SerializedField {
        private final String name;
        private final Field field;

        public SerializedField(String name, Field field) {
            this.name = Objects.requireNonNull(name);
            this.field = Objects.requireNonNull(field);
        }

        // Serialized name; might differ from Field.getName() when @SerializedName or custom FieldNamingStrategy is used
        public String getName() {
            return name;
        }

        // Important: Name of field should normally not be used for ordering; instead `getName()` should be used
        public Field getField() {
            return field;
        }
    }

    FieldOrderStrategy STRING_COMPARE = new FieldOrderStrategy() {
        @Override
        public int compare(SerializedField o1, SerializedField o2) {
            return o1.name.compareTo(o2.name);
        }
    };

    // First fields from superclass, then ordered with String.compareTo
    FieldOrderStrategy SUPER_THEN_STRING_COMPARE = new FieldOrderStrategy() {
        @Override
        public int compare(SerializedField o1, SerializedField o2) {
            Class<?> c1 = o1.field.getDeclaringClass();
            Class<?> c2 = o2.field.getDeclaringClass();

            if (c1 == c2) {
                return o1.name.compareTo(o2.name);
            } else if (c1.isAssignableFrom(c2)) {
                return -1;
            } else {
                return 1;
            }
        }
    };
}
