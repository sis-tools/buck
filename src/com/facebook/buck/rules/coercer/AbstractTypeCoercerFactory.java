/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules.coercer;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Pair;
import com.facebook.buck.python.NeededCoverageSpec;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.primitives.Primitives;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Create {@link TypeCoercer}s that can convert incoming java structures (from json) into particular
 * types.
 */
public class AbstractTypeCoercerFactory implements TypeCoercerFactory {

  private final TypeCoercer<Pattern> patternTypeCoercer = new PatternTypeCoercer();

  private final TypeCoercer<?>[] nonParameterizedTypeCoercers;
  private final ObjectMapper jacksonObjectMapper;

  public AbstractTypeCoercerFactory(ObjectMapper mapper, PathTypeCoercer pathTypeCoercer) {
    // Cached instance for any type coercers that utilize Jackson
    jacksonObjectMapper = mapper.copy()
        .setPropertyNamingStrategy(PropertyNamingStrategy.CAMEL_CASE_TO_LOWER_CASE_WITH_UNDERSCORES)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    TypeCoercer<String> stringTypeCoercer = new IdentityTypeCoercer<>(String.class);
    TypeCoercer<Flavor> flavorTypeCoercer = new FlavorTypeCoercer();
    TypeCoercer<Label> labelTypeCoercer = new LabelTypeCoercer();
    // This has no implementation, but is here so that constructor succeeds so that it can be
    // queried. This is only used for the visibility field, which is not actually handled by the
    // coercer.
    TypeCoercer<BuildTargetPattern> buildTargetPatternTypeCoercer =
        new IdentityTypeCoercer<BuildTargetPattern>(BuildTargetPattern.class) {
          @Override
          public BuildTargetPattern coerce(
              CellPathResolver cellRoots,
              ProjectFilesystem filesystem,
              Path pathRelativeToProjectRoot,
              Object object)
              throws CoerceFailedException {
            throw new UnsupportedOperationException();
          }
        };
    TypeCoercer<BuildTarget> buildTargetTypeCoercer = new BuildTargetTypeCoercer();
    TypeCoercer<SourcePath> sourcePathTypeCoercer = new SourcePathTypeCoercer(
        buildTargetTypeCoercer,
        pathTypeCoercer);
    TypeCoercer<SourceWithFlags> sourceWithFlagsTypeCoercer = new SourceWithFlagsTypeCoercer(
        sourcePathTypeCoercer,
        new ListTypeCoercer<>(stringTypeCoercer));
    TypeCoercer<OCamlSource> ocamlSourceTypeCoercer = new OCamlSourceTypeCoercer(
        sourcePathTypeCoercer);
    TypeCoercer<Float> floatTypeCoercer = new NumberTypeCoercer<>(Float.class);
    TypeCoercer<NeededCoverageSpec> neededCoverageSpecTypeCoercer =
        new NeededCoverageSpecTypeCoercer(
            floatTypeCoercer,
            buildTargetTypeCoercer,
            stringTypeCoercer);
    nonParameterizedTypeCoercers = new TypeCoercer<?>[] {
        // special classes
        labelTypeCoercer,
        pathTypeCoercer,
        flavorTypeCoercer,
        sourcePathTypeCoercer,
        buildTargetTypeCoercer,
        buildTargetPatternTypeCoercer,

        // identity
        stringTypeCoercer,
        new IdentityTypeCoercer<>(Boolean.class),

        // numeric
        new NumberTypeCoercer<>(Integer.class),
        new NumberTypeCoercer<>(Double.class),
        floatTypeCoercer,
        new NumberTypeCoercer<>(Long.class),
        new NumberTypeCoercer<>(Short.class),
        new NumberTypeCoercer<>(Byte.class),

        // other simple
        sourceWithFlagsTypeCoercer,
        ocamlSourceTypeCoercer,
        new BuildConfigFieldsTypeCoercer(),
        new UriTypeCoercer(),
        new FrameworkPathTypeCoercer(sourcePathTypeCoercer),
        new SourceWithFlagsListTypeCoercer(stringTypeCoercer, sourceWithFlagsTypeCoercer),
        new SourceListTypeCoercer(stringTypeCoercer, sourcePathTypeCoercer),
        new LogLevelTypeCoercer(),
        new ManifestEntriesTypeCoercer(jacksonObjectMapper),
        patternTypeCoercer,
        neededCoverageSpecTypeCoercer,
        new ConstraintTypeCoercer(),
        new VersionTypeCoercer(),
    };
  }

  @Override
  public TypeCoercer<?> typeCoercerForType(Type type) {
    if (type instanceof TypeVariable) {
      type = ((TypeVariable<?>) type).getBounds()[0];
      if (Object.class.equals(type)) {
        throw new IllegalArgumentException("Generic types must be specific: " + type);
      }
    }

    if (type instanceof WildcardType) {
      type = ((WildcardType) type).getUpperBounds()[0];
      if (Object.class.equals(type)) {
        throw new IllegalArgumentException("Generic types must be specific: " + type);
      }
    }

    if (type instanceof Class) {
      Class<?> rawClass = Primitives.wrap((Class<?>) type);

      if (rawClass.isEnum()) {
        return new EnumTypeCoercer<>(rawClass);
      }

      TypeCoercer<?> selectedTypeCoercer = null;
      for (TypeCoercer<?> typeCoercer : nonParameterizedTypeCoercers) {
        if (rawClass.isAssignableFrom(typeCoercer.getOutputClass())) {
          if (selectedTypeCoercer == null) {
            selectedTypeCoercer = typeCoercer;
          } else {
            throw new IllegalArgumentException("multiple coercers matched for type: " + type);
          }
        }
      }
      if (selectedTypeCoercer != null) {
        return selectedTypeCoercer;
      } else {
        throw new IllegalArgumentException("no type coercer for type: " + type);
      }
    } else if (type instanceof ParameterizedType) {
      ParameterizedType parameterizedType = (ParameterizedType) type;

      Type rawType = parameterizedType.getRawType();
      if (!(rawType instanceof Class<?>)) {
        throw new RuntimeException(
            "expected getRawType() to return a class for type: " + parameterizedType);
      }

      Class<?> rawClass = (Class<?>) rawType;
      if (rawClass.equals(Either.class)) {
        Preconditions.checkState(parameterizedType.getActualTypeArguments().length == 2,
            "expected type '%s' to have two parameters", parameterizedType);
        return new EitherTypeCoercer<>(
            typeCoercerForType(parameterizedType.getActualTypeArguments()[0]),
            typeCoercerForType(parameterizedType.getActualTypeArguments()[1]));
      } else if (rawClass.equals(Pair.class)) {
        Preconditions.checkState(parameterizedType.getActualTypeArguments().length == 2,
            "expected type '%s' to have two parameters", parameterizedType);
        return new PairTypeCoercer<>(
            typeCoercerForType(parameterizedType.getActualTypeArguments()[0]),
            typeCoercerForType(parameterizedType.getActualTypeArguments()[1]));
      } else if (rawClass.isAssignableFrom(ImmutableList.class)) {
        return new ListTypeCoercer<>(
            typeCoercerForType(getSingletonTypeParameter(parameterizedType)));
      } else if (rawClass.isAssignableFrom(ImmutableSet.class)) {
        return new SetTypeCoercer<>(
            typeCoercerForType(getSingletonTypeParameter(parameterizedType)));
      } else if (rawClass.isAssignableFrom(ImmutableSortedSet.class)) {
        // SortedSet is tested second because it is a subclass of Set, and therefore can
        // be assigned to something of type Set, but not vice versa.
        Type elementType = getSingletonTypeParameter(parameterizedType);
        @SuppressWarnings({"rawtypes", "unchecked"})
        SortedSetTypeCoercer<?> sortedSetTypeCoercer = new SortedSetTypeCoercer(
            typeCoercerForComparableType(elementType));
        return sortedSetTypeCoercer;
      } else if (rawClass.isAssignableFrom(ImmutableMap.class)) {
        Preconditions.checkState(parameterizedType.getActualTypeArguments().length == 2,
            "expected type '%s' to have two parameters", parameterizedType);
        return new MapTypeCoercer<>(
            typeCoercerForType(parameterizedType.getActualTypeArguments()[0]),
            typeCoercerForType(parameterizedType.getActualTypeArguments()[1]));
      } else if (rawClass.isAssignableFrom(ImmutableSortedMap.class)) {
        Preconditions.checkState(parameterizedType.getActualTypeArguments().length == 2,
            "expected type '%s' to have two parameters", parameterizedType);
        @SuppressWarnings({"rawtypes", "unchecked"})
        SortedMapTypeCoercer<?, ?> sortedMapTypeCoercer = new SortedMapTypeCoercer(
            typeCoercerForComparableType(parameterizedType.getActualTypeArguments()[0]),
            typeCoercerForType(parameterizedType.getActualTypeArguments()[1]));
        return sortedMapTypeCoercer;
      } else if (rawClass.isAssignableFrom(PatternMatchedCollection.class)) {
        return new PatternMatchedCollectionTypeCoercer<>(
            patternTypeCoercer,
            typeCoercerForType(getSingletonTypeParameter(parameterizedType)));
      } else if (rawClass.isAssignableFrom(Optional.class)) {
        return new OptionalTypeCoercer<>(
            typeCoercerForType(getSingletonTypeParameter(parameterizedType)));
      } else {
        throw new IllegalArgumentException("Unhandled type: " + type);
      }
    } else {
      throw new IllegalArgumentException("Cannot create type coercer for type: " + type);
    }
  }

  private <T extends Comparable<T>> TypeCoercer<T> typeCoercerForComparableType(Type type) {
    Preconditions.checkState(
        type instanceof Class && Comparable.class.isAssignableFrom((Class<?>) type),
        "type '%s' should be a class implementing Comparable",
        type);

    @SuppressWarnings("unchecked")
    TypeCoercer<T> typeCoercer = (TypeCoercer<T>) typeCoercerForType(type);
    return typeCoercer;
  }

  private static Type getSingletonTypeParameter(ParameterizedType type) {
    Preconditions.checkState(type.getActualTypeArguments().length == 1,
        "expected type '%s' to have one parameter", type);
    return type.getActualTypeArguments()[0];
  }
}
