/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.jvm.java.intellij;

import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Ascii;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Represents a graph of IjModules and the dependencies between them.
 */
public class IjModuleGraph {

  enum DependencyType {
    /**
     * The current {@link IjModule} depends on the other element from test code only. This
     * only happens if a particular module contains both test and production code and only code in
     * the test folders needs to reference the other element.
     */
    TEST,
    /**
     * The current {@link IjModule} depends on the other element from production (non-test)
     * code.
     */
    PROD,
    /**
     * This dependency means that the other element contains a compiled counterpart to this element.
     * This is used when the current element uses BUCK features which cannot be expressed in
     * IntelliJ.
     */
    COMPILED_SHADOW,
    ;

    public static DependencyType merge(DependencyType left, DependencyType right) {
      if (left.equals(right)) {
        return left;
      }
      Preconditions.checkArgument(
          !left.equals(COMPILED_SHADOW) && !right.equals(COMPILED_SHADOW),
          "The COMPILED_SHADOW type cannot be merged with other types.");
      return DependencyType.PROD;
    }

    public static <T> void putWithMerge(Map<T, DependencyType> map, T key, DependencyType value) {
      DependencyType oldValue = map.get(key);
      if (oldValue != null) {
        value = merge(oldValue, value);
      }
      map.put(key, value);
    }
  }

  /**
   * Indicates how to aggregate {@link TargetNode}s into {@link IjModule}s.
   */
  public static class AggregationMode {
    private static final int MIN_SHALLOW_GRAPH_SIZE = 500;
    private static final int SHALLOW_MAX_PATH_LENGTH = 3;

    public static final AggregationMode AUTO = new AggregationMode();
    public static final AggregationMode NONE = new AggregationMode(Integer.MAX_VALUE);
    public static final AggregationMode SHALLOW = new AggregationMode(SHALLOW_MAX_PATH_LENGTH);

    public static final Function<String, AggregationMode> FROM_STRING_FUNCTION =
        new Function<String, AggregationMode>() {
          @Override
          public AggregationMode apply(String input) {
            return fromString(input);
          }
        };


    private Optional<Integer> minimumDepth;

    AggregationMode() {
      minimumDepth = Optional.absent();
    }

    AggregationMode(int minimumDepth) {
      if (minimumDepth <= 0) {
        throw new HumanReadableException(
            "Aggregation level must be a positive integer (got " +
            minimumDepth +
            ")");
      }

      this.minimumDepth = Optional.of(minimumDepth);
    }

    public int getGraphMinimumDepth(int graphSize) {
      return
          minimumDepth
              .or(graphSize < MIN_SHALLOW_GRAPH_SIZE ? Integer.MAX_VALUE : SHALLOW_MAX_PATH_LENGTH);
    }

    public static AggregationMode fromString(String aggregationModeString) {
      switch (Ascii.toLowerCase(aggregationModeString)) {
        case "shallow":
          return SHALLOW;
        case "none":
          return NONE;
        case "auto":
          return AUTO;
        default:
          try {
            // See if a number was passed.
            return new AggregationMode(Integer.parseInt(aggregationModeString));
          } catch (NumberFormatException e) {
            throw new HumanReadableException(
                "Invalid aggregation mode value %s.",
                aggregationModeString);
          }
      }
    }
  }

  /**
   * Create all the modules we are capable of representing in IntelliJ from the supplied graph.
   *
   * @param targetGraph graph whose nodes will be converted to {@link IjModule}s.
   * @return map which for every BuildTarget points to the corresponding IjModule. Multiple
   * BuildTarget can point to one IjModule (many:one mapping), the BuildTargets which
   * can't be prepresented in IntelliJ are missing from this mapping.
   */
  private static ImmutableMap<BuildTarget, IjModule> createModules(
      IjProjectConfig projectConfig,
      TargetGraph targetGraph,
      IjModuleFactory moduleFactory,
      final int minimumPathDepth) {

    final BlockedPathNode blockedPathTree = createAggregationHaltPoints(projectConfig, targetGraph);

    ImmutableListMultimap<Path, TargetNode<?>> baseTargetPathMultimap =
        FluentIterable
          .from(targetGraph.getNodes())
          .filter(IjModuleFactory.SUPPORTED_MODULE_TYPES_PREDICATE)
          .index(
            new Function<TargetNode<?>, Path>() {
              @Override
              public Path apply(TargetNode<?> input) {
                Path basePath = input.getBuildTarget().getBasePath();

                if (input.getConstructorArg() instanceof AndroidResourceDescription.Arg) {
                  return basePath;
                }

                return simplifyPath(basePath, minimumPathDepth, blockedPathTree);
              }
            });

    ImmutableMap.Builder<BuildTarget, IjModule> moduleMapBuilder = new ImmutableMap.Builder<>();

    for (Path baseTargetPath : baseTargetPathMultimap.keySet()) {
      ImmutableSet<TargetNode<?>> targets =
          FluentIterable.from(baseTargetPathMultimap.get(baseTargetPath)).toSet();

      IjModule module = moduleFactory.createModule(baseTargetPath, targets);

      for (TargetNode<?> target : targets) {
        moduleMapBuilder.put(target.getBuildTarget(), module);
      }
    }

    return moduleMapBuilder.build();
  }

  static Path simplifyPath(
      Path basePath,
      int minimumPathDepth,
      BlockedPathNode blockedPathTree) {
    int depthForPath = calculatePathDepth(basePath, minimumPathDepth, blockedPathTree);
    return basePath.subpath(0, depthForPath);
  }

  static int calculatePathDepth(
      Path basePath,
      int minimumPathDepth,
      BlockedPathNode blockedPathTree) {
    int maxDepth = basePath.getNameCount();
    if (minimumPathDepth >= maxDepth) {
      return maxDepth;
    }

    int depthForPath =
        blockedPathTree.findLowestPotentialBlockedOnPath(basePath, 0, maxDepth);

    return depthForPath < minimumPathDepth ? minimumPathDepth : depthForPath;
  }

  /**
   * Create the set of paths which should terminate aggregation.
   */
  private static BlockedPathNode createAggregationHaltPoints(
      IjProjectConfig projectConfig,
      TargetGraph targetGraph) {
    BlockedPathNode blockRoot = new BlockedPathNode();

    for (TargetNode<?> node : targetGraph.getNodes()) {
      if (node.getConstructorArg() instanceof AndroidResourceDescription.Arg ||
          isNonDefaultJava(node, projectConfig.getJavaBuckConfig().getDefaultJavacOptions())) {
        Path blockedPath = node.getBuildTarget().getBasePath();
        blockRoot.markAsBlocked(blockedPath, 0, blockedPath.getNameCount());
      }
    }

    return blockRoot;
  }

  private static boolean isNonDefaultJava(TargetNode<?> node, JavacOptions defaultJavacOptions) {
    BuildRuleType type = node.getType();
    if (!type.equals(JavaLibraryDescription.TYPE)) {
      return false;
    }

    String defaultSourceLevel = defaultJavacOptions.getSourceLevel();
    String defaultTargetLevel = defaultJavacOptions.getTargetLevel();
    JavaLibraryDescription.Arg arg = (JavaLibraryDescription.Arg) node.getConstructorArg();
    return !defaultSourceLevel.equals(arg.source.or(defaultSourceLevel)) ||
        !defaultTargetLevel.equals(arg.target.or(defaultTargetLevel));
  }

  /**
   * @param projectConfig the project config used
   * @param targetGraph input graph.
   * @param libraryFactory library factory.
   * @param moduleFactory module factory.
   * @param aggregationMode module aggregation mode.
   * @return module graph corresponding to the supplied {@link TargetGraph}. Multiple targets from
   * the same base path are mapped to a single module, therefore an IjModuleGraph edge
   * exists between two modules (Ma, Mb) if a TargetGraph edge existed between a pair of
   * nodes (Ta, Tb) and Ma contains Ta and Mb contains Tb.
   */
  public static IjModuleGraph from(
      final IjProjectConfig projectConfig,
      final TargetGraph targetGraph,
      final IjLibraryFactory libraryFactory,
      final IjModuleFactory moduleFactory,
      AggregationMode aggregationMode) {
    final ImmutableMap<BuildTarget, IjModule> rulesToModules =
        createModules(
            projectConfig,
            targetGraph,
            moduleFactory,
            aggregationMode.getGraphMinimumDepth(targetGraph.getNodes().size()));
    final ExportedDepsClosureResolver exportedDepsClosureResolver =
        new ExportedDepsClosureResolver(targetGraph);
    ImmutableMap.Builder<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>>
        depsBuilder = ImmutableMap.builder();
    final Set<IjLibrary> referencedLibraries = new HashSet<>();

    for (final IjModule module : FluentIterable.from(rulesToModules.values()).toSet()) {
      Map<IjProjectElement, DependencyType> moduleDeps = new HashMap<>();

      for (Map.Entry<BuildTarget, DependencyType> entry : module.getDependencies().entrySet()) {
        BuildTarget depBuildTarget = entry.getKey();
        DependencyType depType = entry.getValue();
        ImmutableSet<IjProjectElement> depElements;

        if (depType.equals(DependencyType.COMPILED_SHADOW)) {
          TargetNode<?> targetNode = targetGraph.get(depBuildTarget);
          Optional<IjLibrary> library = libraryFactory.getLibrary(targetNode);
          if (library.isPresent()) {
            depElements = ImmutableSet.<IjProjectElement>of(library.get());
          } else {
            depElements = ImmutableSet.of();
          }
        } else {
          depElements = FluentIterable.from(
              exportedDepsClosureResolver.getExportedDepsClosure(depBuildTarget))
              .append(depBuildTarget)
              .filter(
                  new Predicate<BuildTarget>() {
                    @Override
                    public boolean apply(BuildTarget input) {
                      // The exported deps closure can contain references back to targets contained
                      // in the module, so filter those out.
                      TargetNode<?> targetNode = targetGraph.get(input);
                      return !module.getTargets().contains(targetNode);
                    }
                  })
              .transform(
                  new Function<BuildTarget, IjProjectElement>() {
                    @Nullable
                    @Override
                    public IjProjectElement apply(BuildTarget depTarget) {
                      IjModule depModule = rulesToModules.get(depTarget);
                      if (depModule != null) {
                        return depModule;
                      }
                      TargetNode<?> targetNode = targetGraph.get(depTarget);
                      return libraryFactory.getLibrary(targetNode).orNull();
                    }
                  })
              .filter(Predicates.notNull())
              .toSet();
        }

        for (IjProjectElement depElement : depElements) {
          Preconditions.checkState(!depElement.equals(module));
          DependencyType.putWithMerge(moduleDeps, depElement, depType);
        }
      }

      if (!module.getExtraClassPathDependencies().isEmpty()) {
        IjLibrary extraClassPathLibrary = IjLibrary.builder()
            .setClassPaths(module.getExtraClassPathDependencies())
            .setTargets(ImmutableSet.<TargetNode<?>>of())
            .setName("library_" + module.getName() + "_extra_classpath")
            .build();
        moduleDeps.put(extraClassPathLibrary, DependencyType.PROD);
      }

      referencedLibraries.addAll(
          FluentIterable.from(moduleDeps.keySet())
              .filter(IjLibrary.class)
              .toSet());

      depsBuilder.put(module, ImmutableMap.copyOf(moduleDeps));
    }

    for (IjLibrary library : referencedLibraries) {
      depsBuilder.put(library, ImmutableMap.<IjProjectElement, DependencyType>of());
    }

    return new IjModuleGraph(depsBuilder.build());
  }

  public ImmutableSet<IjProjectElement> getNodes() {
    return deps.keySet();
  }

  public ImmutableSet<IjModule> getModuleNodes() {
    return FluentIterable.from(deps.keySet()).filter(IjModule.class).toSet();
  }

  public ImmutableMap<IjProjectElement, DependencyType> getDepsFor(IjProjectElement source) {
    return Optional.fromNullable(deps.get(source))
        .or(ImmutableMap.<IjProjectElement, DependencyType>of());
  }

  public ImmutableMap<IjModule, DependencyType> getDependentModulesFor(IjModule source) {
    final ImmutableMap<IjProjectElement, DependencyType> deps = getDepsFor(source);
    return FluentIterable.from(deps.keySet()).filter(IjModule.class)
        .toMap(
            new Function<IjModule, DependencyType>() {
              @Override
              public DependencyType apply(IjModule input) {
                return Preconditions.checkNotNull(deps.get(input));
              }
            });
  }

  public ImmutableMap<IjLibrary, DependencyType> getDependentLibrariesFor(IjModule source) {
    final ImmutableMap<IjProjectElement, DependencyType> deps = getDepsFor(source);
    return FluentIterable.from(deps.keySet()).filter(IjLibrary.class)
        .toMap(
            new Function<IjLibrary, DependencyType>() {
              @Override
              public DependencyType apply(IjLibrary input) {
                return Preconditions.checkNotNull(deps.get(input));
              }
            });
  }

  private static void checkNamesAreUnique(
      ImmutableMap<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>> deps) {
    Set<String> names = new HashSet<>();
    for (IjProjectElement element : deps.keySet()) {
      String name = element.getName();
      Preconditions.checkArgument(!names.contains(name));
      names.add(name);
    }
  }

  private ImmutableMap<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>> deps;

  public IjModuleGraph(
      ImmutableMap<IjProjectElement, ImmutableMap<IjProjectElement, DependencyType>> deps) {
    this.deps = deps;
    checkNamesAreUnique(deps);
  }


  static class BlockedPathNode {
    private static final Optional<BlockedPathNode> EMPTY_CHILD = Optional.absent();

    private boolean isBlocked;

    // The key is a path component to allow traversing down a hierarchy
    // to find blocks rather than doing simple path comparison.
    @Nullable
    private Map<Path, BlockedPathNode> children;

    BlockedPathNode() {
      this.isBlocked = false;
    }

    void putChild(Path path, BlockedPathNode node) {
      if (children == null) {
        children = new HashMap<Path, BlockedPathNode>();
      }
      children.put(path, node);
    }

    private Optional<BlockedPathNode> getChild(Path path) {
      return children == null ? EMPTY_CHILD : Optional.fromNullable(children.get(path));
    }

    private void clearAllChildren() {
      children = null;
    }

    void markAsBlocked(Path path, int currentIdx, int pathNameCount) {
      if (currentIdx == pathNameCount) {
        isBlocked = true;
        clearAllChildren();
        return;
      }

      Path component = path.getName(currentIdx);
      Optional<BlockedPathNode> blockedPathNodeOptional = getChild(component);
      BlockedPathNode blockedPathNode;

      if (blockedPathNodeOptional.isPresent()) {
        blockedPathNode = blockedPathNodeOptional.get();
        if (blockedPathNode.isBlocked) {
          return;
        }
      } else {
        blockedPathNode = new BlockedPathNode();
        putChild(component, blockedPathNode);
      }

      blockedPathNode.markAsBlocked(path, ++currentIdx, pathNameCount);
    }

    int findLowestPotentialBlockedOnPath(Path path, int currentIdx, int pathNameCount) {
      if (isBlocked || currentIdx == pathNameCount) {
        return currentIdx;
      }

      Path thisComponent = path.getName(currentIdx);
      Optional<BlockedPathNode> nextNode = getChild(thisComponent);
      if (nextNode.isPresent()) {
        return nextNode.get().findLowestPotentialBlockedOnPath(path, ++currentIdx, pathNameCount);
      }

      return currentIdx;
    }
  }
}
