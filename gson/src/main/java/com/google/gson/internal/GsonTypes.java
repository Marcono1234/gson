/*
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gson.internal;

import static java.util.Objects.requireNonNull;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Properties;

/**
 * Static methods for working with types.
 *
 * @author Bob Lee
 * @author Jesse Wilson
 */
@SuppressWarnings("MemberName") // legacy class name
public final class GsonTypes {
  static final Type[] EMPTY_TYPE_ARRAY = new Type[] {};

  private GsonTypes() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns a new parameterized type, applying {@code typeArguments} to {@code rawType} and
   * enclosed by {@code ownerType}.
   *
   * @return a {@link java.io.Serializable serializable} parameterized type.
   */
  public static ParameterizedType newParameterizedTypeWithOwner(
      Type ownerType, Class<?> rawType, Type... typeArguments) {
    return new ParameterizedTypeImpl(ownerType, rawType, typeArguments);
  }

  /**
   * Returns an array type whose elements are all instances of {@code componentType}.
   *
   * @return a {@link java.io.Serializable serializable} generic array type.
   */
  public static GenericArrayType arrayOf(Type componentType) {
    return new GenericArrayTypeImpl(componentType);
  }

  /**
   * Returns a type that represents an unknown type that extends {@code bound}. For example, if
   * {@code bound} is {@code CharSequence.class}, this returns {@code ? extends CharSequence}. If
   * {@code bound} is {@code Object.class}, this returns {@code ?}, which is shorthand for {@code ?
   * extends Object}.
   */
  public static WildcardType subtypeOf(Type bound) {
    Type[] upperBounds;
    if (bound instanceof WildcardType) {
      upperBounds = ((WildcardType) bound).getUpperBounds();
    } else {
      upperBounds = new Type[] {bound};
    }
    return new WildcardTypeImpl(upperBounds, EMPTY_TYPE_ARRAY);
  }

  /**
   * Returns a type that represents an unknown supertype of {@code bound}. For example, if {@code
   * bound} is {@code String.class}, this returns {@code ? super String}.
   */
  public static WildcardType supertypeOf(Type bound) {
    Type[] lowerBounds;
    if (bound instanceof WildcardType) {
      lowerBounds = ((WildcardType) bound).getLowerBounds();
    } else {
      lowerBounds = new Type[] {bound};
    }
    return new WildcardTypeImpl(new Type[] {Object.class}, lowerBounds);
  }

  /**
   * Returns a type that is functionally equal but not necessarily equal according to {@link
   * Object#equals(Object) Object.equals()}. The returned type is {@link java.io.Serializable}.
   */
  public static Type canonicalize(Type type) {
    if (type instanceof Class) {
      Class<?> c = (Class<?>) type;
      return c.isArray() ? new GenericArrayTypeImpl(canonicalize(c.getComponentType())) : c;

    } else if (type instanceof ParameterizedType) {
      ParameterizedType p = (ParameterizedType) type;
      return new ParameterizedTypeImpl(
          p.getOwnerType(), (Class<?>) p.getRawType(), p.getActualTypeArguments());

    } else if (type instanceof GenericArrayType) {
      GenericArrayType g = (GenericArrayType) type;
      return new GenericArrayTypeImpl(g.getGenericComponentType());

    } else if (type instanceof WildcardType) {
      WildcardType w = (WildcardType) type;
      return new WildcardTypeImpl(w.getUpperBounds(), w.getLowerBounds());

    } else {
      // type is either serializable as-is or unsupported
      return type;
    }
  }

  public static Class<?> getRawType(Type type) {
    if (type instanceof Class<?>) {
      // type is a normal class.
      return (Class<?>) type;

    } else if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;

      // getRawType() returns Type instead of Class; that seems to be an API mistake,
      // see https://bugs.openjdk.org/browse/JDK-8250659
      Type rawType = parameterizedType.getRawType();
      return (Class<?>) rawType;

    } else if (type instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) type).getGenericComponentType();
      return Array.newInstance(getRawType(componentType), 0).getClass();

    } else if (type instanceof TypeVariable) {
      // we could use the variable's bounds, but that won't work if there are multiple.
      // having a raw type that's more general than necessary is okay
      return Object.class;

    } else if (type instanceof WildcardType) {
      Type[] bounds = ((WildcardType) type).getUpperBounds();
      // Currently the JLS only permits one bound for wildcards so using first bound is safe
      assert bounds.length == 1;
      return getRawType(bounds[0]);

    } else {
      String className = type == null ? "null" : type.getClass().getName();
      throw new IllegalArgumentException(
          "Expected a Class, ParameterizedType, or GenericArrayType, but <"
              + type
              + "> is of type "
              + className);
    }
  }

  private static boolean equal(Object a, Object b) {
    return Objects.equals(a, b);
  }

  /** Returns true if {@code a} and {@code b} are equal. */
  public static boolean equals(Type a, Type b) {
    if (a == b) {
      // also handles (a == null && b == null)
      return true;

    } else if (a instanceof Class) {
      // Class already specifies equals().
      return a.equals(b);

    } else if (a instanceof ParameterizedType) {
      if (!(b instanceof ParameterizedType)) {
        return false;
      }

      // TODO: save a .clone() call
      ParameterizedType pa = (ParameterizedType) a;
      ParameterizedType pb = (ParameterizedType) b;
      return equal(pa.getOwnerType(), pb.getOwnerType())
          && pa.getRawType().equals(pb.getRawType())
          && Arrays.equals(pa.getActualTypeArguments(), pb.getActualTypeArguments());

    } else if (a instanceof GenericArrayType) {
      if (!(b instanceof GenericArrayType)) {
        return false;
      }

      GenericArrayType ga = (GenericArrayType) a;
      GenericArrayType gb = (GenericArrayType) b;
      return equals(ga.getGenericComponentType(), gb.getGenericComponentType());

    } else if (a instanceof WildcardType) {
      if (!(b instanceof WildcardType)) {
        return false;
      }

      WildcardType wa = (WildcardType) a;
      WildcardType wb = (WildcardType) b;
      return Arrays.equals(wa.getUpperBounds(), wb.getUpperBounds())
          && Arrays.equals(wa.getLowerBounds(), wb.getLowerBounds());

    } else if (a instanceof TypeVariable) {
      if (!(b instanceof TypeVariable)) {
        return false;
      }
      TypeVariable<?> va = (TypeVariable<?>) a;
      TypeVariable<?> vb = (TypeVariable<?>) b;
      return Objects.equals(va.getGenericDeclaration(), vb.getGenericDeclaration())
          && va.getName().equals(vb.getName());

    } else {
      // This isn't a type we support. Could be a generic array type, wildcard type, etc.
      return false;
    }
  }

  public static String typeToString(Type type) {
    return type instanceof Class ? ((Class<?>) type).getName() : type.toString();
  }

  /**
   * Returns the generic supertype for {@code supertype}. For example, given a class {@code
   * IntegerSet}, the result for when supertype is {@code Set.class} is {@code Set<Integer>} and the
   * result when the supertype is {@code Collection.class} is {@code Collection<Integer>}.
   */
  private static Type getGenericSupertype(Type context, Class<?> rawType, Class<?> supertype) {
    if (supertype == rawType) {
      return context;
    }

    // we skip searching through interfaces if unknown is an interface
    if (supertype.isInterface()) {
      Class<?>[] interfaces = rawType.getInterfaces();
      for (int i = 0, length = interfaces.length; i < length; i++) {
        if (interfaces[i] == supertype) {
          return rawType.getGenericInterfaces()[i];
        } else if (supertype.isAssignableFrom(interfaces[i])) {
          return getGenericSupertype(rawType.getGenericInterfaces()[i], interfaces[i], supertype);
        }
      }
    }

    // check our supertypes
    if (!rawType.isInterface()) {
      while (rawType != Object.class) {
        Class<?> rawSupertype = rawType.getSuperclass();
        if (rawSupertype == supertype) {
          return rawType.getGenericSuperclass();
        } else if (supertype.isAssignableFrom(rawSupertype)) {
          return getGenericSupertype(rawType.getGenericSuperclass(), rawSupertype, supertype);
        }
        rawType = rawSupertype;
      }
    }

    // we can't resolve this further
    return supertype;
  }

  /**
   * Returns the generic form of {@code supertype}. For example, if this is {@code
   * ArrayList<String>}, this returns {@code Iterable<String>} given the input {@code
   * Iterable.class}.
   *
   * @param supertype a superclass of, or interface implemented by, this.
   */
  private static Type getSupertype(Type context, Class<?> contextRawType, Class<?> supertype) {
    if (context instanceof WildcardType) {
      // Wildcards are useless for resolving supertypes. As the upper bound has the same raw type,
      // use it instead
      Type[] bounds = ((WildcardType) context).getUpperBounds();
      // Currently the JLS only permits one bound for wildcards so using first bound is safe
      assert bounds.length == 1;
      context = bounds[0];
    }
    if (!supertype.isAssignableFrom(contextRawType)) {
      throw new IllegalArgumentException(
          contextRawType + " is not the same as or a subtype of " + supertype);
    }
    return resolve(
        context, contextRawType, GsonTypes.getGenericSupertype(context, contextRawType, supertype));
  }

  /**
   * Returns the component type of this array type.
   *
   * @throws ClassCastException if this type is not an array.
   */
  public static Type getArrayComponentType(Type array) {
    return array instanceof GenericArrayType
        ? ((GenericArrayType) array).getGenericComponentType()
        : ((Class<?>) array).getComponentType();
  }

  /**
   * Returns the element type of this collection type.
   *
   * @throws IllegalArgumentException if this type is not a collection.
   */
  public static Type getCollectionElementType(Type context, Class<?> contextRawType) {
    Type collectionType = getSupertype(context, contextRawType, Collection.class);

    if (collectionType instanceof ParameterizedType) {
      return ((ParameterizedType) collectionType).getActualTypeArguments()[0];
    }
    return Object.class;
  }

  /**
   * Returns a two element array containing this map's key and value types in positions 0 and 1
   * respectively.
   */
  public static Type[] getMapKeyAndValueTypes(Type context, Class<?> contextRawType) {
    /*
     * Work around a problem with the declaration of java.util.Properties. That
     * class should extend Hashtable<String, String>, but it's declared to
     * extend Hashtable<Object, Object>.
     */
    if (Properties.class.isAssignableFrom(contextRawType)) {
      return new Type[] {String.class, String.class};
    }

    Type mapType = getSupertype(context, contextRawType, Map.class);
    // TODO: strip wildcards?
    if (mapType instanceof ParameterizedType) {
      ParameterizedType mapParameterizedType = (ParameterizedType) mapType;
      return mapParameterizedType.getActualTypeArguments();
    }
    return new Type[] {Object.class, Object.class};
  }

  public static Type resolve(Type context, Class<?> contextRawType, Type toResolve) {

    return resolve(context, contextRawType, toResolve, new HashMap<TypeVariable<?>, Type>());
  }

  private static Type resolve(
      Type context,
      Class<?> contextRawType,
      Type toResolve,
      Map<TypeVariable<?>, Type> visitedTypeVariables) {
    // this implementation is made a little more complicated in an attempt to avoid object-creation
    TypeVariable<?> resolving = null;
    while (true) {
      if (toResolve instanceof TypeVariable) {
        TypeVariable<?> typeVariable = (TypeVariable<?>) toResolve;
        Type previouslyResolved = visitedTypeVariables.get(typeVariable);
        if (previouslyResolved != null) {
          // cannot reduce due to infinite recursion
          return (previouslyResolved == Void.TYPE) ? toResolve : previouslyResolved;
        }

        // Insert a placeholder to mark the fact that we are in the process of resolving this type
        visitedTypeVariables.put(typeVariable, Void.TYPE);
        if (resolving == null) {
          resolving = typeVariable;
        }

        toResolve = resolveTypeVariable(context, contextRawType, typeVariable);
        if (toResolve == typeVariable) {
          break;
        }

      } else if (toResolve instanceof Class && ((Class<?>) toResolve).isArray()) {
        Class<?> original = (Class<?>) toResolve;
        Type componentType = original.getComponentType();
        Type newComponentType =
            resolve(context, contextRawType, componentType, visitedTypeVariables);
        toResolve = equal(componentType, newComponentType) ? original : arrayOf(newComponentType);
        break;

      } else if (toResolve instanceof GenericArrayType) {
        GenericArrayType original = (GenericArrayType) toResolve;
        Type componentType = original.getGenericComponentType();
        Type newComponentType =
            resolve(context, contextRawType, componentType, visitedTypeVariables);
        toResolve = equal(componentType, newComponentType) ? original : arrayOf(newComponentType);
        break;

      } else if (toResolve instanceof ParameterizedType) {
        ParameterizedType original = (ParameterizedType) toResolve;
        Type ownerType = original.getOwnerType();
        Type newOwnerType = resolve(context, contextRawType, ownerType, visitedTypeVariables);
        boolean ownerChanged = !equal(newOwnerType, ownerType);

        Type[] args = original.getActualTypeArguments();
        boolean argsChanged = false;
        for (int t = 0, length = args.length; t < length; t++) {
          Type resolvedTypeArgument =
              resolve(context, contextRawType, args[t], visitedTypeVariables);
          if (!equal(resolvedTypeArgument, args[t])) {
            if (!argsChanged) {
              args = args.clone();
              argsChanged = true;
            }
            args[t] = resolvedTypeArgument;
          }
        }

        toResolve =
            ownerChanged || argsChanged
                ? newParameterizedTypeWithOwner(
                    newOwnerType, (Class<?>) original.getRawType(), args)
                : original;
        break;

      } else if (toResolve instanceof WildcardType) {
        WildcardType original = (WildcardType) toResolve;
        Type[] originalLowerBound = original.getLowerBounds();
        Type[] originalUpperBound = original.getUpperBounds();

        if (originalLowerBound.length == 1) {
          Type lowerBound =
              resolve(context, contextRawType, originalLowerBound[0], visitedTypeVariables);
          if (lowerBound != originalLowerBound[0]) {
            toResolve = supertypeOf(lowerBound);
            break;
          }
        } else if (originalUpperBound.length == 1) {
          Type upperBound =
              resolve(context, contextRawType, originalUpperBound[0], visitedTypeVariables);
          if (upperBound != originalUpperBound[0]) {
            toResolve = subtypeOf(upperBound);
            break;
          }
        }
        toResolve = original;
        break;

      } else {
        break;
      }
    }
    // ensure that any in-process resolution gets updated with the final result
    if (resolving != null) {
      visitedTypeVariables.put(resolving, toResolve);
    }
    return toResolve;
  }

  private static Type resolveTypeVariable(
      Type context, Class<?> contextRawType, TypeVariable<?> unknown) {
    Class<?> declaredByRaw = declaringClassOf(unknown);

    // we can't reduce this further
    if (declaredByRaw == null) {
      return unknown;
    }

    Type declaredBy = getGenericSupertype(context, contextRawType, declaredByRaw);
    if (declaredBy instanceof ParameterizedType) {
      int index = indexOf(declaredByRaw.getTypeParameters(), unknown);
      return ((ParameterizedType) declaredBy).getActualTypeArguments()[index];
    }

    return unknown;
  }

  private static int indexOf(Object[] array, Object toFind) {
    for (int i = 0, length = array.length; i < length; i++) {
      if (toFind.equals(array[i])) {
        return i;
      }
    }
    throw new NoSuchElementException();
  }

  /**
   * Returns the declaring class of {@code typeVariable}, or {@code null} if it was not declared by
   * a class.
   */
  private static Class<?> declaringClassOf(TypeVariable<?> typeVariable) {
    GenericDeclaration genericDeclaration = typeVariable.getGenericDeclaration();
    return genericDeclaration instanceof Class ? (Class<?>) genericDeclaration : null;
  }

  static void checkNotPrimitive(Type type) {
    if (type instanceof Class<?> && ((Class<?>) type).isPrimitive()) {
      throw new IllegalArgumentException("Primitive type is not allowed");
    }
  }

  /**
   * Whether an {@linkplain ParameterizedType#getOwnerType() owner type} must be specified when
   * constructing a {@link ParameterizedType} for {@code rawType}.
   *
   * <p>Note that this method might not require an owner type for all cases where Java reflection
   * would create parameterized types with owner type.
   */
  public static boolean requiresOwnerType(Type rawType) {
    if (rawType instanceof Class<?>) {
      Class<?> rawTypeAsClass = (Class<?>) rawType;
      return !Modifier.isStatic(rawTypeAsClass.getModifiers())
          && rawTypeAsClass.getDeclaringClass() != null;
    }
    return false;
  }

  // Here and below we put @SuppressWarnings("serial") on fields of type `Type`. Recent Java
  // compilers complain that the declared type is not Serializable. But in this context we go out of
  // our way to ensure that the Type in question is either Class (which is serializable) or one of
  // the nested Type implementations here (which are also serializable).
  private static final class ParameterizedTypeImpl implements ParameterizedType, Serializable {
    @SuppressWarnings("serial")
    private final Type ownerType;

    @SuppressWarnings("serial")
    private final Type rawType;

    @SuppressWarnings("serial")
    private final Type[] typeArguments;

    public ParameterizedTypeImpl(Type ownerType, Class<?> rawType, Type... typeArguments) {
      requireNonNull(rawType);

      if (ownerType == null && requiresOwnerType(rawType)) {
        throw new IllegalArgumentException("Must specify owner type for " + rawType);
      }

      this.ownerType = ownerType == null ? null : canonicalize(ownerType);
      this.rawType = canonicalize(rawType);
      this.typeArguments = typeArguments.clone();
      for (int t = 0, length = this.typeArguments.length; t < length; t++) {
        requireNonNull(this.typeArguments[t]);
        checkNotPrimitive(this.typeArguments[t]);
        this.typeArguments[t] = canonicalize(this.typeArguments[t]);
      }
    }

    @Override
    public Type[] getActualTypeArguments() {
      return typeArguments.clone();
    }

    @Override
    public Type getRawType() {
      return rawType;
    }

    @Override
    public Type getOwnerType() {
      return ownerType;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof ParameterizedType
          && GsonTypes.equals(this, (ParameterizedType) other);
    }

    private static int hashCodeOrZero(Object o) {
      return o != null ? o.hashCode() : 0;
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(typeArguments) ^ rawType.hashCode() ^ hashCodeOrZero(ownerType);
    }

    @Override
    public String toString() {
      int length = typeArguments.length;
      if (length == 0) {
        return typeToString(rawType);
      }

      StringBuilder stringBuilder = new StringBuilder(30 * (length + 1));
      stringBuilder
          .append(typeToString(rawType))
          .append("<")
          .append(typeToString(typeArguments[0]));
      for (int i = 1; i < length; i++) {
        stringBuilder.append(", ").append(typeToString(typeArguments[i]));
      }
      return stringBuilder.append(">").toString();
    }

    private static final long serialVersionUID = 0;
  }

  private static final class GenericArrayTypeImpl implements GenericArrayType, Serializable {
    @SuppressWarnings("serial")
    private final Type componentType;

    public GenericArrayTypeImpl(Type componentType) {
      requireNonNull(componentType);
      this.componentType = canonicalize(componentType);
    }

    @Override
    public Type getGenericComponentType() {
      return componentType;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof GenericArrayType && GsonTypes.equals(this, (GenericArrayType) o);
    }

    @Override
    public int hashCode() {
      return componentType.hashCode();
    }

    @Override
    public String toString() {
      return typeToString(componentType) + "[]";
    }

    private static final long serialVersionUID = 0;
  }

  /**
   * The WildcardType interface supports multiple upper bounds and multiple lower bounds. We only
   * support what the target Java version supports - at most one bound, see also
   * https://bugs.openjdk.java.net/browse/JDK-8250660. If a lower bound is set, the upper bound must
   * be Object.class.
   */
  private static final class WildcardTypeImpl implements WildcardType, Serializable {
    @SuppressWarnings("serial")
    private final Type upperBound;

    @SuppressWarnings("serial")
    private final Type lowerBound;

    public WildcardTypeImpl(Type[] upperBounds, Type[] lowerBounds) {
      if (lowerBounds.length > 1) {
        throw new IllegalArgumentException("At most one lower bound is supported");
      }
      if (upperBounds.length != 1) {
        throw new IllegalArgumentException("Exactly one upper bound must be specified");
      }

      if (lowerBounds.length == 1) {
        requireNonNull(lowerBounds[0]);
        checkNotPrimitive(lowerBounds[0]);
        if (upperBounds[0] != Object.class) {
          throw new IllegalArgumentException(
              "When lower bound is specified, upper bound must be Object");
        }
        this.lowerBound = canonicalize(lowerBounds[0]);
        this.upperBound = Object.class;

      } else {
        requireNonNull(upperBounds[0]);
        checkNotPrimitive(upperBounds[0]);
        this.lowerBound = null;
        this.upperBound = canonicalize(upperBounds[0]);
      }
    }

    @Override
    public Type[] getUpperBounds() {
      return new Type[] {upperBound};
    }

    @Override
    public Type[] getLowerBounds() {
      return lowerBound != null ? new Type[] {lowerBound} : EMPTY_TYPE_ARRAY;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof WildcardType && GsonTypes.equals(this, (WildcardType) other);
    }

    @Override
    public int hashCode() {
      // this equals Arrays.hashCode(getLowerBounds()) ^ Arrays.hashCode(getUpperBounds());
      return (lowerBound != null ? 31 + lowerBound.hashCode() : 1) ^ (31 + upperBound.hashCode());
    }

    @Override
    public String toString() {
      if (lowerBound != null) {
        return "? super " + typeToString(lowerBound);
      } else if (upperBound == Object.class) {
        return "?";
      } else {
        return "? extends " + typeToString(upperBound);
      }
    }

    private static final long serialVersionUID = 0;
  }
}
