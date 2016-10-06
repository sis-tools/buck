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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.CellConfig;
import com.facebook.buck.config.Config;
import com.facebook.buck.config.Configs;
import com.facebook.buck.config.RawConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.WatchmanDiagnosticCache;
import com.facebook.buck.json.DefaultProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.json.ProjectBuildFileParserFactory;
import com.facebook.buck.json.ProjectBuildFileParserOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.python.PythonBuckConfig;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.UncheckedExecutionException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Represents a single checkout of a code base. Two cells model the same code base if their
 * underlying {@link ProjectFilesystem}s are equal.
 */
public class Cell {

  private final ImmutableSet<Path> knownRoots;
  private final ProjectFilesystem filesystem;
  private final Watchman watchman;
  private final BuckConfig config;
  private final KnownBuildRuleTypes knownBuildRuleTypes;
  private final KnownBuildRuleTypesFactory knownBuildRuleTypesFactory;
  private final String pythonInterpreter;
  private final String buildFileName;
  private final boolean enforceBuckPackageBoundaries;
  private final ImmutableSet<Pattern> tempFilePatterns;
  private final LoadingCache<Path, Cell> cellLoader;
  private final WatchmanDiagnosticCache watchmanDiagnosticCache;

  private final Supplier<Integer> hashCodeSupplier = Suppliers.memoize(
      new Supplier<Integer>() {
        @Override
        public Integer get() {
          return Objects.hash(filesystem, config);
        }
      });

  private Cell(
      final ImmutableSet<Path> knownRoots,
      final ProjectFilesystem filesystem,
      final Watchman watchman,
      final BuckConfig config,
      final KnownBuildRuleTypesFactory knownBuildRuleTypesFactory,
      final LoadingCache<Path, Cell> cellLoader,
      WatchmanDiagnosticCache watchmanDiagnosticCache) throws IOException, InterruptedException {

    this.knownRoots = knownRoots;
    this.filesystem = filesystem;
    this.watchman = watchman;
    this.config = config;

    ParserConfig parserConfig = new ParserConfig(config);
    this.buildFileName = parserConfig.getBuildFileName();
    this.enforceBuckPackageBoundaries = parserConfig.getEnforceBuckPackageBoundary();
    this.tempFilePatterns = parserConfig.getTempFilePatterns();

    PythonBuckConfig pythonConfig = new PythonBuckConfig(config, new ExecutableFinder());
    this.pythonInterpreter = pythonConfig.getPythonInterpreter();

    this.knownBuildRuleTypesFactory = knownBuildRuleTypesFactory;
    this.knownBuildRuleTypes = knownBuildRuleTypesFactory.create(config);
    this.cellLoader = cellLoader;
    this.watchmanDiagnosticCache = watchmanDiagnosticCache;
  }

  public static Cell createRootCell(
      ProjectFilesystem filesystem,
      final Console console,
      final Watchman watchman,
      final BuckConfig rootConfig,
      CellConfig rootCellConfigOverrides,
      final KnownBuildRuleTypesFactory knownBuildRuleTypesFactory,
      final Clock clock,
      WatchmanDiagnosticCache watchmanDiagnosticCache) throws IOException, InterruptedException {

    DefaultCellPathResolver rootCellCellPathResolver = new DefaultCellPathResolver(
        filesystem.getRootPath(),
        rootConfig.getEntriesForSection(DefaultCellPathResolver.REPOSITORIES_SECTION));

    ImmutableMap<RelativeCellName, Path> transitiveCellPathMapping =
        rootCellCellPathResolver.getTransitivePathMapping();
    ImmutableMap<Path, RawConfig> pathToConfigOverrides;
    try {
      pathToConfigOverrides =
          rootCellConfigOverrides.getOverridesByPath(transitiveCellPathMapping);
    } catch (CellConfig.MalformedOverridesException e) {
      throw new HumanReadableException(e.getMessage());
    }

    LoadingCache<Path, Cell> cellLoader = createCellLoader(
        console,
        watchman,
        rootConfig,
        knownBuildRuleTypesFactory,
        clock,
        transitiveCellPathMapping,
        pathToConfigOverrides,
        watchmanDiagnosticCache);

    // We would like to go through the cellLoader, however that would mean recreating the Filesystem
    // and BuckConfig. These are being provided from Main.java, so using different values in the
    // Cell could result in inconsistencies.
    Cell rootCell = new Cell(
        rootCellCellPathResolver.getKnownRoots(),
        filesystem,
        watchman,
        rootConfig,
        knownBuildRuleTypesFactory,
        cellLoader,
        watchmanDiagnosticCache);
    cellLoader.put(filesystem.getRootPath(), rootCell);
    return rootCell;
  }

  private static LoadingCache<Path, Cell> createCellLoader(
      final Console console,
      final Watchman watchman,
      final BuckConfig rootConfig,
      final KnownBuildRuleTypesFactory knownBuildRuleTypesFactory,
      final Clock clock,
      final ImmutableMap<RelativeCellName, Path> transitiveCellPathMapping,
      final ImmutableMap<Path, RawConfig> pathToConfigOverrides,
      final WatchmanDiagnosticCache watchmanDiagnosticCache) {

    final ImmutableSet<Path> allPossibleRoots =
        ImmutableSet.copyOf(transitiveCellPathMapping.values());

    final AtomicReference<LoadingCache<Path, Cell>> loaderReference = new AtomicReference<>();
    CacheLoader<Path, Cell> loader = new CacheLoader<Path, Cell>() {
      @Override
      public Cell load(Path cellPath) throws Exception {
        cellPath = cellPath.toRealPath().normalize();

        Preconditions.checkState(
            allPossibleRoots.contains(cellPath),
            "Cell %s outside of transitive closure of root cell (%s).",
            cellPath,
            allPossibleRoots);

        RawConfig configOverrides = Optional.fromNullable(pathToConfigOverrides.get(cellPath))
            .or(RawConfig.of(ImmutableMap.<String, ImmutableMap<String, String>>of()));
        Config config = Configs.createDefaultConfig(
            cellPath,
            configOverrides);
        DefaultCellPathResolver cellPathResolver =
            new DefaultCellPathResolver(cellPath, config);

        ProjectFilesystem cellFilesystem = new ProjectFilesystem(cellPath, config);

        BuckConfig buckConfig = new BuckConfig(
            config,
            cellFilesystem,
            rootConfig.getArchitecture(),
            rootConfig.getPlatform(),
            rootConfig.getEnvironment(),
            cellPathResolver);

        ParserConfig parserConfig = new ParserConfig(buckConfig);

        Watchman.build(
            cellPath,
            rootConfig.getEnvironment(),
            console,
            clock,
            parserConfig.getWatchmanQueryTimeoutMs()
        ).close();

        return new Cell(
            cellPathResolver.getKnownRoots(),
            cellFilesystem,
            watchman,
            buckConfig,
            knownBuildRuleTypesFactory,
            loaderReference.get(),
            watchmanDiagnosticCache);
      }
    };

    loaderReference.set(CacheBuilder.newBuilder().build(loader));
    return loaderReference.get();
  }

  public LoadingCache<Path, Cell> createCellLoaderForDistributedBuild(
      final ImmutableMap<Path, BuckConfig> cellConfigs,
      final ImmutableMap<Path, ProjectFilesystem> cellFilesystems,
      final WatchmanDiagnosticCache watchmanDiagnosticCache
  ) throws InterruptedException, IOException {

    final AtomicReference<LoadingCache<Path, Cell>> cacheReference = new AtomicReference<>();
    CacheLoader<Path, Cell> loader = new CacheLoader<Path, Cell>() {
      @Override
      public Cell load(Path cellPath) throws Exception {
        ProjectFilesystem cellFilesystem =
            Preconditions.checkNotNull(cellFilesystems.get(cellPath));
        BuckConfig buckConfig = Preconditions.checkNotNull(cellConfigs.get(cellPath));

        return new Cell(
            cellConfigs.keySet(),
            cellFilesystem,
            Watchman.NULL_WATCHMAN,
            buckConfig,
            knownBuildRuleTypesFactory,
            cacheReference.get(),
            watchmanDiagnosticCache
        );
      }
    };

    LoadingCache<Path, Cell> cache = CacheBuilder.newBuilder().build(loader);
    cacheReference.set(cache);
    return cache;
  }

  public ProjectFilesystem getFilesystem() {
    return filesystem;
  }

  public Path getRoot() {
    return getFilesystem().getRootPath();
  }

  public KnownBuildRuleTypes getKnownBuildRuleTypes() {
    return knownBuildRuleTypes;
  }

  public BuckConfig getBuckConfig() {
    return config;
  }

  public String getBuildFileName() {
    return buildFileName;
  }

  public boolean isEnforcingBuckPackageBoundaries() {
    return enforceBuckPackageBoundaries;
  }

  public Cell getCellIgnoringVisibilityCheck(Path cellPath) {
    try {
      return cellLoader.get(cellPath);
    } catch (ExecutionException | UncheckedExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), HumanReadableException.class);
      throw Throwables.propagate(e);
    }
  }

  public Cell getCell(Path cellPath) {
    if (!knownRoots.contains(cellPath)) {
      throw new HumanReadableException(
          "Unable to find repository rooted at %s. Known roots are:\n  %s",
          cellPath,
          Joiner.on(",\n  ").join(knownRoots));
    }
    return getCellIgnoringVisibilityCheck(cellPath);
  }

  public Cell getCell(BuildTarget target) {
    return getCell(target.getCellPath());
  }

  public Optional<Cell> getCellIfKnown(BuildTarget target) {
    if (knownRoots.contains(target.getCellPath())) {
      return Optional.of(getCell(target));
    }
    return Optional.absent();
  }

  /**
   * @return all loaded {@link Cell}s that are children of this {@link Cell}.
   */
  public ImmutableMap<Path, Cell> getLoadedCells() {
    return ImmutableMap.copyOf(cellLoader.asMap());
  }

  public Description<?> getDescription(BuildRuleType type) {
    return getKnownBuildRuleTypes().getDescription(type);
  }

  public BuildRuleType getBuildRuleType(String rawType) {
    return getKnownBuildRuleTypes().getBuildRuleType(rawType);
  }

  public ImmutableSet<Description<?>> getAllDescriptions() {
    return getKnownBuildRuleTypes().getAllDescriptions();
  }

  /**
   * For use in performance-sensitive code or if you don't care if the build file actually exists,
   * otherwise prefer {@link #getAbsolutePathToBuildFile(BuildTarget)}.
   *
   * @param target target to look up
   * @return path which may or may not exist.
   */
  public Path getAbsolutePathToBuildFileUnsafe(BuildTarget target) {
    Cell targetCell = getCell(target);

    ProjectFilesystem targetFilesystem = targetCell.getFilesystem();

    Path buildFile = targetFilesystem
        .resolve(target.getBasePath())
        .resolve(targetCell.getBuildFileName());
    return buildFile;
  }

  public Path getAbsolutePathToBuildFile(BuildTarget target)
      throws MissingBuildFileException {
    Path buildFile = getAbsolutePathToBuildFileUnsafe(target);
    Cell cell = getCell(target);
    if (!cell.getFilesystem().isFile(buildFile)) {
      throw new MissingBuildFileException(target, cell.getBuckConfig());
    }
    return buildFile;
  }

  public Watchman getWatchman() {
    return watchman;
  }

  public WatchmanDiagnosticCache getWatchmanDiagnosticCache() {
    return watchmanDiagnosticCache;
  }

  /**
   * Callers are responsible for managing the life-cycle of the created {@link
   * ProjectBuildFileParser}.
   */
  public ProjectBuildFileParser createBuildFileParser(
      ConstructorArgMarshaller marshaller,
      Console console,
      BuckEventBus eventBus,
      boolean ignoreBuckAutodepsFiles) {
    ProjectBuildFileParserFactory factory = createBuildFileParserFactory();
    return factory.createParser(
        marshaller,
        console,
        config.getEnvironment(),
        eventBus,
        ignoreBuckAutodepsFiles,
        watchmanDiagnosticCache);
  }

  private ProjectBuildFileParserFactory createBuildFileParserFactory() {
    ParserConfig parserConfig = new ParserConfig(getBuckConfig());

    boolean useWatchmanGlob =
        parserConfig.getGlobHandler() == ParserConfig.GlobHandler.WATCHMAN &&
            watchman.hasWildmatchGlob();
    boolean watchmanGlobStatResults =
        parserConfig.getWatchmanGlobSanityCheck() == ParserConfig.WatchmanGlobSanityCheck.STAT;
    boolean watchmanUseGlobGenerator = watchman.getCapabilities().contains(
        Watchman.Capability.GLOB_GENERATOR);

    return new DefaultProjectBuildFileParserFactory(
        ProjectBuildFileParserOptions.builder()
            .setProjectRoot(getFilesystem().getRootPath())
            .setCellRoots(getCellPathResolver().getCellPaths())
            .setPythonInterpreter(pythonInterpreter)
            .setAllowEmptyGlobs(parserConfig.getAllowEmptyGlobs())
            .setIgnorePaths(filesystem.getIgnorePaths())
            .setBuildFileName(getBuildFileName())
            .setDefaultIncludes(parserConfig.getDefaultIncludes())
            .setDescriptions(getAllDescriptions())
            .setUseWatchmanGlob(useWatchmanGlob)
            .setWatchmanGlobStatResults(watchmanGlobStatResults)
            .setWatchmanUseGlobGenerator(watchmanUseGlobGenerator)
            .setWatchman(watchman)
            .setWatchmanQueryTimeoutMs(parserConfig.getWatchmanQueryTimeoutMs())
            .setRawConfig(getBuckConfig().getRawConfigForParser())
            .setEnableBuildFileSandboxing(parserConfig.getEnableBuildFileSandboxing())
            .setBuildFileImportWhitelist(parserConfig.getBuildFileImportWhitelist())
            .build());
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Cell that = (Cell) o;
    return Objects.equals(filesystem, that.filesystem) &&
        config.equalsForDaemonRestart(that.config);
  }

  @Override
  public String toString() {
    return String.format(
        "%s filesystem=%s config=%s",
        super.toString(),
        filesystem,
        config);
  }

  @Override
  public int hashCode() {
    return hashCodeSupplier.get();
  }

  public Iterable<Pattern> getTempFilePatterns() {
    return tempFilePatterns;
  }

  public CellPathResolver getCellPathResolver() {
    return config.getCellPathResolver();
  }

  public ImmutableSet<Path> getKnownRoots() {
    return knownRoots;
  }

  @SuppressWarnings("serial")
  public static class MissingBuildFileException extends BuildTargetException {
    public MissingBuildFileException(BuildTarget buildTarget, BuckConfig buckConfig) {
      super(String.format("No build file at %s when resolving target %s.",
          buildTarget.getBasePathWithSlash() + new ParserConfig(buckConfig).getBuildFileName(),
          buildTarget.getFullyQualifiedName()));
    }

    @Override
    public String getHumanReadableErrorMessage() {
      return getMessage();
    }
  }
}
