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

package com.facebook.buck.android;

import static com.facebook.buck.android.AndroidBinaryGraphEnhancer.PACKAGE_STRING_ASSETS_FLAVOR;

import com.facebook.buck.android.AndroidBinary.ExopackageMode;
import com.facebook.buck.android.AndroidBinary.PackageType;
import com.facebook.buck.android.AndroidBinary.RelinkerMode;
import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.NdkCxxPlatforms.TargetCpuType;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.dalvik.ZipSplitter.DexSplitStrategy;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AndroidBinaryDescription
    implements Description<AndroidBinaryDescription.Arg>, Flavored {

  public static final BuildRuleType TYPE = BuildRuleType.of("android_binary");
  private static final Logger LOG = Logger.get(AndroidBinaryDescription.class);

  /**
   * By default, assume we have 5MB of linear alloc,
   * 1MB of which is taken up by the framework, so that leaves 4MB.
   */
  private static final long DEFAULT_LINEAR_ALLOC_HARD_LIMIT = 4 * 1024 * 1024;

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.<String, MacroExpander>of(
              "exe", new ExecutableMacroExpander(),
              "location", new LocationMacroExpander()));

  private static final Pattern COUNTRY_LOCALE_PATTERN = Pattern.compile("([a-z]{2})-[A-Z]{2}");

  private static final ImmutableSet<Flavor> FLAVORS = ImmutableSet.of(
      PACKAGE_STRING_ASSETS_FLAVOR);

  private final JavaOptions javaOptions;
  private final JavacOptions javacOptions;
  private final ProGuardConfig proGuardConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms;
  private final ListeningExecutorService dxExecutorService;

  public AndroidBinaryDescription(
      JavaOptions javaOptions,
      JavacOptions javacOptions,
      ProGuardConfig proGuardConfig,
      ImmutableMap<TargetCpuType, NdkCxxPlatform> nativePlatforms,
      ListeningExecutorService dxExecutorService,
      CxxBuckConfig cxxBuckConfig) {
    this.javaOptions = javaOptions;
    this.javacOptions = javacOptions;
    this.proGuardConfig = proGuardConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.nativePlatforms = nativePlatforms;
    this.dxExecutorService = dxExecutorService;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    ResourceCompressionMode compressionMode = getCompressionMode(args);

    BuildTarget target = params.getBuildTarget();
    boolean isFlavored = target.isFlavored();
    if (isFlavored) {
      if (target.getFlavors().contains(PACKAGE_STRING_ASSETS_FLAVOR) &&
          !compressionMode.isStoreStringsAsAssets()) {
        throw new HumanReadableException(
            "'package_string_assets' flavor does not exist for %s.",
            target.getUnflavoredBuildTarget());
      }
      params = params.copyWithBuildTarget(BuildTarget.of(target.getUnflavoredBuildTarget()));
    }

    BuildRule keystore = resolver.getRule(args.keystore);
    if (!(keystore instanceof Keystore)) {
      throw new HumanReadableException(
          "In %s, keystore='%s' must be a keystore() but was %s().",
          params.getBuildTarget(),
          keystore.getFullyQualifiedName(),
          keystore.getType());
    }

    APKModuleGraph apkModuleGraph = new APKModuleGraph(
        targetGraph,
        target,
        args.applicationModuleTargets);

    ProGuardObfuscateStep.SdkProguardType androidSdkProguardConfig =
        args.androidSdkProguardConfig.or(ProGuardObfuscateStep.SdkProguardType.DEFAULT);

    // If the old boolean version of this argument was specified, make sure the new form
    // was not specified, and allow the old form to override the default.
    if (args.useAndroidProguardConfigWithOptimizations.isPresent()) {
      Preconditions.checkArgument(
          !args.androidSdkProguardConfig.isPresent(),
          "The deprecated use_android_proguard_config_with_optimizations parameter" +
              " cannot be used with android_sdk_proguard_config.");
      androidSdkProguardConfig = args.useAndroidProguardConfigWithOptimizations.or(false)
          ? ProGuardObfuscateStep.SdkProguardType.OPTIMIZED
          : ProGuardObfuscateStep.SdkProguardType.DEFAULT;
    }

    EnumSet<ExopackageMode> exopackageModes = EnumSet.noneOf(ExopackageMode.class);
    if (args.exopackageModes.isPresent() && !args.exopackageModes.get().isEmpty()) {
      exopackageModes = EnumSet.copyOf(args.exopackageModes.get());
    } else if (args.exopackage.or(false)) {
      exopackageModes = EnumSet.of(ExopackageMode.SECONDARY_DEX);
    }

    DexSplitMode dexSplitMode = createDexSplitMode(args, exopackageModes);

    PackageType packageType = getPackageType(args);
    boolean shouldPreDex = !args.disablePreDex.or(false) &&
        PackageType.DEBUG.equals(packageType) &&
        !args.preprocessJavaClassesBash.isPresent();

    ResourceFilter resourceFilter =
        new ResourceFilter(args.resourceFilter.or(ImmutableList.<String>of()));

    Optional<Set<RType>> bannedDuplicateTypesArgs = args.bannedDuplicateResourceTypes;
    EnumSet<RType> bannedDuplicateResourceTypes;
    if (bannedDuplicateTypesArgs.isPresent() && !bannedDuplicateTypesArgs.get().isEmpty()) {
      bannedDuplicateResourceTypes = EnumSet.copyOf(bannedDuplicateTypesArgs.get());
    } else {
      bannedDuplicateResourceTypes = EnumSet.noneOf(RType.class);
    }

    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        params,
        resolver,
        compressionMode,
        resourceFilter,
        bannedDuplicateResourceTypes,
        args.resourceUnionPackage,
        addFallbackLocales(args.locales.or(ImmutableSet.<String>of())),
        args.manifest,
        packageType,
        ImmutableSet.copyOf(args.cpuFilters.get()),
        args.buildStringSourceMap.or(false),
        shouldPreDex,
        AndroidBinary.getPrimaryDexPath(params.getBuildTarget(), params.getProjectFilesystem()),
        dexSplitMode,
        ImmutableSet.copyOf(args.noDx.or(ImmutableSet.<BuildTarget>of())),
        /* resourcesToExclude */ ImmutableSet.<BuildTarget>of(),
        args.skipCrunchPngs.or(false),
        args.includesVectorDrawables.or(false),
        javacOptions,
        exopackageModes,
        (Keystore) keystore,
        args.buildConfigValues.get(),
        args.buildConfigValuesFile,
        Optional.<Integer>absent(),
        args.trimResourceIds.or(false),
        args.keepResourcePattern,
        nativePlatforms,
        args.nativeLibraryMergeMap,
        args.nativeLibraryMergeGlue,
        args.nativeLibraryMergeCodeGenerator,
        args.enableRelinker.or(false) ? RelinkerMode.ENABLED : RelinkerMode.DISABLED,
        dxExecutorService,
        args.manifestEntries.get(),
        cxxBuckConfig,
        apkModuleGraph);
    AndroidGraphEnhancementResult result = graphEnhancer.createAdditionalBuildables();

    if (target.getFlavors().contains(PACKAGE_STRING_ASSETS_FLAVOR)) {
      Optional<PackageStringAssets> packageStringAssets = result.getPackageStringAssets();
      Preconditions.checkState(packageStringAssets.isPresent());
      return packageStringAssets.get();
    }

    // Build rules added to "no_dx" are only hints, not hard dependencies. Therefore, although a
    // target may be mentioned in that parameter, it may not be present as a build rule.
    ImmutableSortedSet.Builder<BuildRule> builder = ImmutableSortedSet.naturalOrder();
    for (BuildTarget noDxTarget : args.noDx.or(ImmutableSet.<BuildTarget>of())) {
      Optional<BuildRule> ruleOptional = resolver.getRuleOptional(noDxTarget);
      if (ruleOptional.isPresent()) {
        builder.add(ruleOptional.get());
      } else {
        LOG.info("%s: no_dx target not a dependency: %s", target, noDxTarget);
      }
    }

    ImmutableSortedSet<BuildRule> buildRulesToExcludeFromDex = builder.build();
    ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex =
        FluentIterable.from(buildRulesToExcludeFromDex)
            .filter(JavaLibrary.class)
            .toSortedSet(HasBuildTarget.BUILD_TARGET_COMPARATOR);

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return new AndroidBinary(
        params
            .copyWithExtraDeps(Suppliers.ofInstance(result.getFinalDeps()))
            .appendExtraDeps(
                pathResolver.filterBuildRuleInputs(
                    result.getPackageableCollection().getProguardConfigs()))
            .appendExtraDeps(rulesToExcludeFromDex),
        pathResolver,
        proGuardConfig.getProguardJarOverride(),
        proGuardConfig.getProguardMaxHeapSize(),
        proGuardConfig.getProguardAgentPath(),
        (Keystore) keystore,
        packageType,
        dexSplitMode,
        args.noDx.or(ImmutableSet.<BuildTarget>of()),
        androidSdkProguardConfig,
        args.optimizationPasses,
        args.proguardConfig,
        compressionMode,
        args.cpuFilters.get(),
        resourceFilter,
        exopackageModes,
        MACRO_HANDLER.getExpander(
            params.getBuildTarget(),
            params.getCellRoots(),
            resolver),
        args.preprocessJavaClassesBash,
        rulesToExcludeFromDex,
        result,
        args.reorderClassesIntraDex,
        args.dexReorderToolFile,
        args.dexReorderDataDumpFile,
        args.xzCompressionLevel,
        dxExecutorService,
        args.packageAssetLibraries,
        args.compressAssetLibraries,
        args.manifestEntries.or(ManifestEntries.empty()),
        javaOptions.getJavaRuntimeLauncher());
  }

  private DexSplitMode createDexSplitMode(Arg args, EnumSet<ExopackageMode> exopackageModes) {
    // Exopackage builds default to JAR, otherwise, default to RAW.
    DexStore defaultDexStore = ExopackageMode.enabledForSecondaryDexes(exopackageModes)
        ? DexStore.JAR
        : DexStore.RAW;
    DexSplitStrategy dexSplitStrategy = args.minimizePrimaryDexSize.or(false)
        ? DexSplitStrategy.MINIMIZE_PRIMARY_DEX_SIZE
        : DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE;
    return new DexSplitMode(
        args.useSplitDex.or(false),
        dexSplitStrategy,
        args.dexCompression.or(defaultDexStore),
        args.useLinearAllocSplitDex.or(false),
        args.linearAllocHardLimit.or(DEFAULT_LINEAR_ALLOC_HARD_LIMIT),
        args.primaryDexPatterns.or(ImmutableList.<String>of()),
        args.primaryDexClassesFile,
        args.primaryDexScenarioFile,
        args.primaryDexScenarioOverflowAllowed.or(false),
        args.secondaryDexHeadClassesFile,
        args.secondaryDexTailClassesFile);
  }

  private PackageType getPackageType(Arg args) {
    if (!args.packageType.isPresent()) {
      return PackageType.DEBUG;
    }
    return PackageType.valueOf(args.packageType.get().toUpperCase(Locale.US));
  }

  private ResourceCompressionMode getCompressionMode(Arg args) {
    if (!args.resourceCompression.isPresent()) {
      return ResourceCompressionMode.DISABLED;
    }
    return ResourceCompressionMode.valueOf(args.resourceCompression.get().toUpperCase(Locale.US));
  }

  private ImmutableSet<String> addFallbackLocales(ImmutableSet<String> locales) {
    ImmutableSet.Builder<String> allLocales = ImmutableSet.builder();
    for (String locale : locales) {
      allLocales.add(locale);
      Matcher matcher = COUNTRY_LOCALE_PATTERN.matcher(locale);
      if (matcher.matches()) {
        allLocales.add(matcher.group(1));
      }
    }
    return allLocales.build();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    for (Flavor flavor : flavors) {
      if (!FLAVORS.contains(flavor)) {
        return false;
      }
    }
    return true;
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg implements HasTests {
    public SourcePath manifest;
    public BuildTarget keystore;
    public Optional<String> packageType;
    @Hint(isDep = false) public Optional<Set<BuildTarget>> noDx;
    public Optional<Boolean> useSplitDex;
    public Optional<Boolean> useLinearAllocSplitDex;
    public Optional<Boolean> minimizePrimaryDexSize;
    public Optional<Boolean> disablePreDex;
    // TODO(natthu): mark this as deprecated.
    public Optional<Boolean> exopackage;
    public Optional<Set<ExopackageMode>> exopackageModes;
    public Optional<DexStore> dexCompression;
    public Optional<ProGuardObfuscateStep.SdkProguardType> androidSdkProguardConfig;
    public Optional<Boolean> useAndroidProguardConfigWithOptimizations;
    public Optional<Integer> optimizationPasses;
    public Optional<SourcePath> proguardConfig;
    public Optional<String> resourceCompression;
    public Optional<Boolean> skipCrunchPngs;
    public Optional<Boolean> includesVectorDrawables;
    public Optional<List<String>> primaryDexPatterns;
    public Optional<SourcePath> primaryDexClassesFile;
    public Optional<SourcePath> primaryDexScenarioFile;
    public Optional<Boolean> primaryDexScenarioOverflowAllowed;
    public Optional<SourcePath> secondaryDexHeadClassesFile;
    public Optional<SourcePath> secondaryDexTailClassesFile;
    public Optional<Set<BuildTarget>> applicationModuleTargets;
    public Optional<Long> linearAllocHardLimit;
    public Optional<List<String>> resourceFilter;
    public Optional<Set<RType>> bannedDuplicateResourceTypes;
    public Optional<Boolean> trimResourceIds;
    public Optional<String> keepResourcePattern;
    public Optional<String> resourceUnionPackage;
    public Optional<ImmutableSet<String>> locales;
    public Optional<Boolean> buildStringSourceMap;
    public Optional<Set<TargetCpuType>> cpuFilters;
    public Optional<ImmutableSortedSet<BuildTarget>> preprocessJavaClassesDeps;
    public Optional<String> preprocessJavaClassesBash;
    public Optional<Boolean> reorderClassesIntraDex;
    public Optional<SourcePath> dexReorderToolFile;
    public Optional<SourcePath> dexReorderDataDumpFile;
    public Optional<Integer> xzCompressionLevel;
    public Optional<Boolean> packageAssetLibraries;
    public Optional<Boolean> compressAssetLibraries;
    public Optional<Map<String, List<Pattern>>> nativeLibraryMergeMap;
    public Optional<BuildTarget> nativeLibraryMergeGlue;
    public Optional<BuildTarget> nativeLibraryMergeCodeGenerator;
    public Optional<Boolean> enableRelinker;
    public Optional<ManifestEntries> manifestEntries;
    public Optional<BuildConfigFields> buildConfigValues;
    public Optional<SourcePath> buildConfigValuesFile;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    @Hint(isDep = false) public Optional<ImmutableSortedSet<BuildTarget>> tests;

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests.get();
    }

  }
}
