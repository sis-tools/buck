/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ObjectMappers;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import java.lang.reflect.Field;

import javax.annotation.Nullable;

/**
 * Support class for writing builders for nodes of a {@link TargetGraph}
 * and {@link ActionGraph} ({@link TargetNode} and {@link BuildRule} respectively) mirroring the
 * behavior seen when running the actual parser as closely as possible.
 */
public abstract class AbstractNodeBuilder<A> {
  private static final DefaultTypeCoercerFactory TYPE_COERCER_FACTORY =
      new DefaultTypeCoercerFactory(ObjectMappers.newDefaultInstance());
  private static final VisibilityPatternParser VISIBILITY_PATTERN_PARSER =
      new VisibilityPatternParser();

  protected final Description<A> description;
  protected final BuildRuleFactoryParams factoryParams;
  protected final BuildTarget target;
  protected final A arg;
  private final CellPathResolver cellRoots;
  @Nullable
  private final HashCode rawHashCode;

  protected AbstractNodeBuilder(
      Description<A> description,
      BuildTarget target) {
    this(description, target, new FakeProjectFilesystem(), null);
  }

  protected AbstractNodeBuilder(
      Description<A> description,
      BuildTarget target,
      ProjectFilesystem projectFilesystem) {
    this(description, target, projectFilesystem, null);
  }

  protected AbstractNodeBuilder(
      Description<A> description,
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      HashCode hashCode) {
    this.description = description;
    this.factoryParams = new BuildRuleFactoryParams(projectFilesystem, target);
    this.target = target;
    this.rawHashCode = hashCode;

    this.cellRoots = new FakeCellPathResolver(factoryParams.getProjectFilesystem());

    this.arg = description.createUnpopulatedConstructorArg();
    populateWithDefaultValues(this.arg);
  }

  public final BuildRule build(BuildRuleResolver resolver) throws NoSuchBuildTargetException {
    return build(resolver, new FakeProjectFilesystem(), TargetGraph.EMPTY);
  }

  public final BuildRule build(BuildRuleResolver resolver, TargetGraph targetGraph)
      throws NoSuchBuildTargetException {
    return build(resolver, new FakeProjectFilesystem(), targetGraph);
  }

  public final BuildRule build(BuildRuleResolver resolver, ProjectFilesystem filesystem)
      throws NoSuchBuildTargetException {
    return build(resolver, filesystem, TargetGraph.EMPTY);
  }

  public final BuildRule build(
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      TargetGraph targetGraph) throws NoSuchBuildTargetException {

    // The BuildRule determines its deps by extracting them from the rule parameters.
    BuildRuleParams params = createBuildRuleParams(resolver, filesystem);

    BuildRule rule = description.createBuildRule(targetGraph, params, resolver, arg);
    resolver.addToIndex(rule);
    return rule;
  }

  public TargetNode<A> build() {
    try {
      HashCode hash = rawHashCode == null ?
          Hashing.sha1().hashString(factoryParams.target.getFullyQualifiedName(), UTF_8) :
          rawHashCode;

      return new TargetNodeFactory(TYPE_COERCER_FACTORY).create(
          // This hash will do in a pinch.
          hash,
          description,
          arg,
          factoryParams,
          getDepsFromArg(),
          ImmutableSet.of(VISIBILITY_PATTERN_PARSER.parse(null, "PUBLIC")),
          cellRoots);
    } catch (NoSuchBuildTargetException e) {
      throw Throwables.propagate(e);
    }
  }

  public BuildRuleParams createBuildRuleParams(
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem) {
    TargetNode<?> node = build();
    return new FakeBuildRuleParamsBuilder(target)
        .setProjectFilesystem(filesystem)
        .setDeclaredDeps(resolver.getAllRules(node.getDeclaredDeps()))
        .setExtraDeps(resolver.getAllRules(node.getExtraDeps()))
        .build();
  }

  @SuppressWarnings("unchecked")
  private ImmutableSortedSet<BuildTarget> getDepsFromArg() {
    // Not all rules have deps, but all rules call them deps. When they do, they're always optional.
    // Grab them in the unsafest way I know.
    try {
      Field depsField = arg.getClass().getField("deps");
      Object optional = depsField.get(arg);

      if (optional == null) {
        return ImmutableSortedSet.of();
      }
      // Here's a whole series of assumptions in one lump of a Bad Idea.
      return (ImmutableSortedSet<BuildTarget>) ((Optional<?>) optional).get();
    } catch (ReflectiveOperationException ignored) {
      // Field doesn't exist: no deps.
      return ImmutableSortedSet.of();
    }
  }

  protected <C extends Comparable<?>> Optional<ImmutableSortedSet<C>> amend(
      Optional<ImmutableSortedSet<C>> existing,
      C instance) {
    ImmutableSortedSet.Builder<C> toReturn = ImmutableSortedSet.naturalOrder();
    if (existing != null && existing.isPresent()) {
      toReturn.addAll(existing.get());
    }
    toReturn.add(instance);
    return Optional.of(toReturn.build());
  }

  // Thanks to type erasure, this needs a unique name.
  protected <C extends Comparable<?>> Optional<ImmutableSet<C>> amendSet(
      Optional<ImmutableSet<C>> existing,
      C instance) {
    ImmutableSet.Builder<C> toReturn = ImmutableSet.builder();
    if (existing.isPresent()) {
      toReturn.addAll(existing.get());
    }
    toReturn.add(instance);
    return Optional.of(toReturn.build());
  }

  /**
   * Populate optional fields of this constructor arg with their default values.
   */
  private void populateWithDefaultValues(A arg) {
    try {
      new ConstructorArgMarshaller(
          TYPE_COERCER_FACTORY).populateDefaults(
          cellRoots,
          factoryParams.getProjectFilesystem(),
          factoryParams,
          arg);
    } catch (ConstructorArgMarshalException error) {
      throw Throwables.propagate(error);
    }
  }

  @SuppressWarnings("unchecked")
  public Iterable<BuildTarget> findImplicitDeps() {
    ImplicitDepsInferringDescription<A> desc = (ImplicitDepsInferringDescription<A>) description;
    return desc.findDepsForTargetFromConstructorArgs(target, cellRoots, arg);
  }

  public BuildTarget getTarget() {
    return target;
  }

}
