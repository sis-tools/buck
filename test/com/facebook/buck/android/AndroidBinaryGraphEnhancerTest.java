/*
 * Copyright 2013-present Facebook, Inc.
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

import static com.facebook.buck.jvm.java.JavaCompilationConstants.ANDROID_JAVAC_OPTIONS;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.android.AndroidBinary.ExopackageMode;
import com.facebook.buck.android.NdkCxxPlatforms.TargetCpuType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.jvm.core.HasJavaClassHashes;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.BuildConfigFields;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.MoreExecutors;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class AndroidBinaryGraphEnhancerTest {

  @Test
  public void testCreateDepsForPreDexing() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    // Create three Java rules, :dep1, :dep2, and :lib. :lib depends on :dep1 and :dep2.
    BuildTarget javaDep1BuildTarget = BuildTargetFactory.newInstance("//java/com/example:dep1");
    BuildRule javaDep1 = JavaLibraryBuilder
        .createBuilder(javaDep1BuildTarget)
        .addSrc(Paths.get("java/com/example/Dep1.java"))
        .build(ruleResolver);

    BuildTarget javaDep2BuildTarget = BuildTargetFactory.newInstance("//java/com/example:dep2");
    BuildRule javaDep2 = JavaLibraryBuilder
        .createBuilder(javaDep2BuildTarget)
        .addSrc(Paths.get("java/com/example/Dep2.java"))
        .build(ruleResolver);

    BuildTarget javaLibBuildTarget = BuildTargetFactory.newInstance("//java/com/example:lib");
    BuildRule javaLib = JavaLibraryBuilder
        .createBuilder(javaLibBuildTarget)
        .addSrc(Paths.get("java/com/example/Lib.java"))
        .addDep(javaDep1.getBuildTarget())
        .addDep(javaDep2.getBuildTarget())
        .build(ruleResolver);

    // Assume we are enhancing an android_binary rule whose only dep
    // is //java/com/example:lib, and that //java/com/example:dep2 is in its no_dx list.
    ImmutableSortedSet<BuildRule> originalDeps = ImmutableSortedSet.of(javaLib);
    ImmutableSet<BuildTarget> buildRulesToExcludeFromDex = ImmutableSet.of(javaDep2BuildTarget);
    BuildTarget apkTarget = BuildTargetFactory.newInstance("//java/com/example:apk");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleParams originalParams = new BuildRuleParams(
        apkTarget,
        Suppliers.ofInstance(originalDeps),
        Suppliers.ofInstance(originalDeps),
        filesystem,
        TestCellBuilder.createCellRoots(filesystem));
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        originalParams,
        ruleResolver,
        ResourcesFilter.ResourceCompressionMode.DISABLED,
        FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
        /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
        Optional.<String>absent(),
        /* locales */ ImmutableSet.<String>of(),
        createStrictMock(PathSourcePath.class),
        AndroidBinary.PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.< TargetCpuType>of(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ true,
        BuildTargets.getScratchPath(
            originalParams.getProjectFilesystem(),
            apkTarget,
            "%s/classes.dex"),
        DexSplitMode.NO_SPLIT,
        buildRulesToExcludeFromDex,
        /* resourcesToExclude */ ImmutableSet.<BuildTarget>of(),
        /* skipCrunchPngs */ false,
        /* includesVectorDrawables */ false,
        ANDROID_JAVAC_OPTIONS,
        EnumSet.noneOf(ExopackageMode.class),
        createStrictMock(Keystore.class),
        /* buildConfigValues */ BuildConfigFields.empty(),
        /* buildConfigValuesFile */ Optional.<SourcePath>absent(),
        /* xzCompressionLevel */ Optional.<Integer>absent(),
        /* trimResourceIds */ false,
        /* keepResourcePattern */ Optional.<String>absent(),
        /* nativePlatforms */ ImmutableMap.<TargetCpuType, NdkCxxPlatform>of(),
        /* nativeLibraryMergeMap */ Optional.<Map<String, List<Pattern>>>absent(),
        /* nativeLibraryMergeGlue */ Optional.<BuildTarget>absent(),
        /* nativeLibraryMergeCodeGenerator */ Optional.<BuildTarget>absent(),
        AndroidBinary.RelinkerMode.DISABLED,
        MoreExecutors.newDirectExecutorService(),
        /* manifestEntries */ ManifestEntries.empty(),
        CxxPlatformUtils.DEFAULT_CONFIG,
        new APKModuleGraph(
            TargetGraph.EMPTY,
            originalParams.getBuildTarget(),
            Optional.<Set<BuildTarget>>absent()));

    BuildTarget aaptPackageResourcesTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#aapt_package");
    BuildRuleParams aaptPackageResourcesParams =
        new FakeBuildRuleParamsBuilder(aaptPackageResourcesTarget).build();
    AaptPackageResources aaptPackageResources = new AaptPackageResources(
        aaptPackageResourcesParams,
        new SourcePathResolver(ruleResolver),
        /* manifest */ new FakeSourcePath("java/src/com/facebook/base/AndroidManifest.xml"),
        createMock(FilteredResourcesProvider.class),
        ImmutableList.<HasAndroidResourceDeps>of(),
        ImmutableSet.<SourcePath>of(),
        /* resourceUnionPackage */ Optional.<String>absent(),
        AndroidBinary.PackageType.DEBUG,
        false,
        /* skipCrunchPngs */ false,
        /* includesVectorDrawables */ false,
        /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
        /* manifestEntries */ ManifestEntries.empty());
    ruleResolver.addToIndex(aaptPackageResources);

    AndroidPackageableCollection collection = new AndroidPackageableCollector(
            /* collectionRoot */ apkTarget,
            ImmutableSet.of(javaDep2BuildTarget),
            /* resourcesToExclude */ ImmutableSet.<BuildTarget>of(),
            new APKModuleGraph(
                TargetGraph.EMPTY,
                apkTarget,
                Optional.<Set<BuildTarget>>absent()))
        .addClasspathEntry(
            ((HasJavaClassHashes) javaDep1), new FakeSourcePath("ignored"))
        .addClasspathEntry(
            ((HasJavaClassHashes) javaDep2), new FakeSourcePath("ignored"))
        .addClasspathEntry(
            ((HasJavaClassHashes) javaLib), new FakeSourcePath("ignored"))
        .build();

    ImmutableMultimap<APKModule, DexProducedFromJavaLibrary> preDexedLibraries =
        graphEnhancer.createPreDexRulesForLibraries(
              /* additionalJavaLibrariesToDex */
              ImmutableList.<BuildRule>of(),
            collection);

    BuildTarget fakeUberRDotJavaCompileTarget = BuildTargetFactory.newInstance(
        "//fake:uber_r_dot_java#compile");
    JavaLibrary fakeUberRDotJavaCompile = (JavaLibrary)
        JavaLibraryBuilder.createBuilder(fakeUberRDotJavaCompileTarget).build(ruleResolver);
    BuildTarget fakeUberRDotJavaDexTarget = BuildTargetFactory.newInstance(
        "//fake:uber_r_dot_java#dex");
    DexProducedFromJavaLibrary fakeUberRDotJavaDex = new DexProducedFromJavaLibrary(
        new FakeBuildRuleParamsBuilder(fakeUberRDotJavaDexTarget).build(),
        new SourcePathResolver(ruleResolver),
        fakeUberRDotJavaCompile);
    ruleResolver.addToIndex(fakeUberRDotJavaDex);

    BuildRule preDexMergeRule = graphEnhancer.createPreDexMergeRule(
        preDexedLibraries,
        fakeUberRDotJavaDex);
    BuildTarget dexMergeTarget =
        BuildTargetFactory.newInstance("//java/com/example:apk#dex_merge");
    BuildRule dexMergeRule = ruleResolver.getRule(dexMergeTarget);

    assertEquals(dexMergeRule, preDexMergeRule);

    BuildTarget javaDep1DexBuildTarget =
        BuildTarget.builder(javaDep1BuildTarget)
            .addFlavors(AndroidBinaryGraphEnhancer.DEX_FLAVOR)
            .build();
    BuildTarget javaDep2DexBuildTarget =
        BuildTarget.builder(javaDep2BuildTarget)
            .addFlavors(AndroidBinaryGraphEnhancer.DEX_FLAVOR)
            .build();
    BuildTarget javaLibDexBuildTarget =
        BuildTarget.builder(javaLibBuildTarget)
            .addFlavors(AndroidBinaryGraphEnhancer.DEX_FLAVOR)
            .build();
    assertThat(
        "There should be a #dex rule for dep1 and lib, but not dep2 because it is in the no_dx " +
            "list.  And we should depend on uber_r_dot_java",
        Iterables.transform(dexMergeRule.getDeps(), HasBuildTarget.TO_TARGET),
        Matchers.allOf(
            Matchers.not(Matchers.hasItem(javaDep1BuildTarget)),
            Matchers.hasItem(javaDep1DexBuildTarget),
            Matchers.not(Matchers.hasItem(javaDep2BuildTarget)),
            Matchers.not(Matchers.hasItem(javaDep2DexBuildTarget)),
            Matchers.hasItem(javaLibDexBuildTarget),
            Matchers.hasItem(fakeUberRDotJavaDex.getBuildTarget())));
  }

  @Test
  public void testAllBuildablesExceptPreDexRule() throws Exception {
    // Create an android_build_config() as a dependency of the android_binary().
    BuildTarget buildConfigBuildTarget = BuildTargetFactory.newInstance("//java/com/example:cfg");
    BuildRuleParams buildConfigParams = new FakeBuildRuleParamsBuilder(buildConfigBuildTarget)
        .build();
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    AndroidBuildConfigJavaLibrary buildConfigJavaLibrary = AndroidBuildConfigDescription
        .createBuildRule(
          buildConfigParams,
          "com.example.buck",
          /* values */ BuildConfigFields.empty(),
          /* valuesFile */ Optional.<SourcePath>absent(),
          /* useConstantExpressions */ false,
          ANDROID_JAVAC_OPTIONS,
          ruleResolver);

    BuildTarget apkTarget = BuildTargetFactory.newInstance("//java/com/example:apk");
    BuildRuleParams originalParams = new FakeBuildRuleParamsBuilder(apkTarget)
        .setDeclaredDeps(ImmutableSortedSet.<BuildRule>of(buildConfigJavaLibrary))
        .build();

    // set it up.
    Keystore keystore = createStrictMock(Keystore.class);
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        originalParams,
        ruleResolver,
        ResourcesFilter.ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS,
        FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
        /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
        Optional.<String>absent(),
        /* locales */ ImmutableSet.<String>of(),
        new FakeSourcePath("AndroidManifest.xml"),
        AndroidBinary.PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.<TargetCpuType>of(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ false,
        BuildTargets.getScratchPath(
            originalParams.getProjectFilesystem(),
            apkTarget,
            "%s/classes.dex"),
        DexSplitMode.NO_SPLIT,
        /* buildRulesToExcludeFromDex */ ImmutableSet.<BuildTarget>of(),
        /* resourcesToExclude */ ImmutableSet.<BuildTarget>of(),
        /* skipCrunchPngs */ false,
        /* includesVectorDrawables */ false,
        ANDROID_JAVAC_OPTIONS,
        EnumSet.of(ExopackageMode.SECONDARY_DEX),
        keystore,
        /* buildConfigValues */ BuildConfigFields.empty(),
        /* buildConfigValuesFiles */ Optional.<SourcePath>absent(),
        /* xzCompressionLevel */ Optional.<Integer>absent(),
        /* trimResourceIds */ false,
        /* keepResourcePattern */ Optional.<String>absent(),
        /* nativePlatforms */ ImmutableMap.<TargetCpuType, NdkCxxPlatform>of(),
        /* nativeLibraryMergeMap */ Optional.<Map<String, List<Pattern>>>absent(),
        /* nativeLibraryMergeGlue */ Optional.<BuildTarget>absent(),
        /* nativeLibraryMergeCodeGenerator */ Optional.<BuildTarget>absent(),
        AndroidBinary.RelinkerMode.DISABLED,
        MoreExecutors.newDirectExecutorService(),
        /* manifestEntries */ ManifestEntries.empty(),
        CxxPlatformUtils.DEFAULT_CONFIG,
        new APKModuleGraph(
            TargetGraph.EMPTY,
            originalParams.getBuildTarget(),
            Optional.<Set<BuildTarget>>absent()));
    replay(keystore);
    AndroidGraphEnhancementResult result = graphEnhancer.createAdditionalBuildables();

    // Verify that android_build_config() was processed correctly.
    Flavor flavor = ImmutableFlavor.of("buildconfig_com_example_buck");
    final SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    BuildTarget enhancedBuildConfigTarget = BuildTarget
        .builder(apkTarget)
        .addFlavors(flavor)
        .build();
    assertEquals(
        "The only classpath entry to dex should be the one from the AndroidBuildConfigJavaLibrary" +
            " created via graph enhancement.",
        ImmutableSet.of(
            BuildTargets
                .getGenPath(
                    originalParams.getProjectFilesystem(),
                    enhancedBuildConfigTarget,
                    "lib__%s__output")
                .resolve(enhancedBuildConfigTarget.getShortNameAndFlavorPostfix() + ".jar")),
        FluentIterable
            .from(result.getClasspathEntriesToDex())
            .transform(pathResolver.deprecatedPathFunction())
        .toSet());
    BuildRule enhancedBuildConfigRule = ruleResolver.getRule(enhancedBuildConfigTarget);
    assertTrue(enhancedBuildConfigRule instanceof AndroidBuildConfigJavaLibrary);
    AndroidBuildConfigJavaLibrary enhancedBuildConfigJavaLibrary =
        (AndroidBuildConfigJavaLibrary) enhancedBuildConfigRule;
    AndroidBuildConfig androidBuildConfig = enhancedBuildConfigJavaLibrary.getAndroidBuildConfig();
    assertEquals("com.example.buck", androidBuildConfig.getJavaPackage());
    assertTrue(androidBuildConfig.isUseConstantExpressions());
    assertEquals(
        "IS_EXOPACKAGE defaults to false, but should now be true. DEBUG should still be true.",
        BuildConfigFields.fromFields(ImmutableList.of(
            BuildConfigFields.Field.of("boolean", "DEBUG", "true"),
            BuildConfigFields.Field.of("boolean", "IS_EXOPACKAGE", "true"),
            BuildConfigFields.Field.of("int", "EXOPACKAGE_FLAGS", "1"))),
        androidBuildConfig.getBuildConfigFields());

    ImmutableSortedSet<BuildRule> finalDeps = result.getFinalDeps();
    BuildRule computeExopackageDepsAbiRule =
        findRuleOfType(ruleResolver, ComputeExopackageDepsAbi.class);
    assertThat(finalDeps, Matchers.hasItem(computeExopackageDepsAbiRule));

    FilteredResourcesProvider resourcesProvider = result.getAaptPackageResources()
        .getFilteredResourcesProvider();
    assertTrue(resourcesProvider instanceof ResourcesFilter);
    BuildRule resourcesFilterRule = findRuleOfType(ruleResolver, ResourcesFilter.class);

    BuildRule aaptPackageResourcesRule =
        findRuleOfType(ruleResolver, AaptPackageResources.class);
    MoreAsserts.assertDepends(
        "AaptPackageResources must depend on ResourcesFilter",
        aaptPackageResourcesRule,
        resourcesFilterRule);

    BuildRule packageStringAssetsRule =
        findRuleOfType(ruleResolver, PackageStringAssets.class);
    MoreAsserts.assertDepends(
        "PackageStringAssets must depend on ResourcesFilter",
        packageStringAssetsRule,
        aaptPackageResourcesRule);


    assertFalse(result.getPreDexMerge().isPresent());

    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on ResourcesFilter",
        computeExopackageDepsAbiRule,
        resourcesFilterRule);
    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on PackageStringAssets",
        computeExopackageDepsAbiRule,
        packageStringAssetsRule);
    MoreAsserts.assertDepends(
        "ComputeExopackageDepsAbi must depend on AaptPackageResources",
        computeExopackageDepsAbiRule,
        aaptPackageResourcesRule);

    assertTrue(result.getPackageStringAssets().isPresent());
    assertTrue(result.getComputeExopackageDepsAbi().isPresent());

    verify(keystore);
  }

  @Test
  public void testResourceRulesBecomeDepsOfAaptPackageResources() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    AndroidResource resource =
        (AndroidResource) AndroidResourceBuilder
            .createBuilder(BuildTargetFactory.newInstance("//:resource"))
            .setRDotJavaPackage("package")
            .setRes(Paths.get("res"))
            .build(ruleResolver);

    // set it up.
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        new FakeBuildRuleParamsBuilder(target)
            .setDeclaredDeps(ImmutableSortedSet.<BuildRule>of(resource))
            .build();
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        originalParams,
        ruleResolver,
        ResourcesFilter.ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS,
        FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
        /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
        Optional.<String>absent(),
        /* locales */ ImmutableSet.<String>of(),
        new FakeSourcePath("AndroidManifest.xml"),
        AndroidBinary.PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.<TargetCpuType>of(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ false,
        BuildTargets.getScratchPath(
            originalParams.getProjectFilesystem(),
            target,
            "%s/classes.dex"),
        DexSplitMode.NO_SPLIT,
        /* buildRulesToExcludeFromDex */ ImmutableSet.<BuildTarget>of(),
        /* resourcesToExclude */ ImmutableSet.<BuildTarget>of(),
        /* skipCrunchPngs */ false,
        /* includesVectorDrawables */ false,
        ANDROID_JAVAC_OPTIONS,
        EnumSet.of(ExopackageMode.SECONDARY_DEX),
        createNiceMock(Keystore.class),
        /* buildConfigValues */ BuildConfigFields.empty(),
        /* buildConfigValuesFiles */ Optional.<SourcePath>absent(),
        /* xzCompressionLevel */ Optional.<Integer>absent(),
        /* trimResourceIds */ false,
        /* keepResourcePattern */ Optional.<String>absent(),
        /* nativePlatforms */ ImmutableMap.<TargetCpuType, NdkCxxPlatform>of(),
        /* nativeLibraryMergeMap */ Optional.<Map<String, List<Pattern>>>absent(),
        /* nativeLibraryMergeGlue */ Optional.<BuildTarget>absent(),
        /* nativeLibraryMergeCodeGenerator */ Optional.<BuildTarget>absent(),
        AndroidBinary.RelinkerMode.DISABLED,
        MoreExecutors.newDirectExecutorService(),
        /* manifestEntries */ ManifestEntries.empty(),
        CxxPlatformUtils.DEFAULT_CONFIG,
        new APKModuleGraph(
            TargetGraph.EMPTY,
            originalParams.getBuildTarget(),
            Optional.<Set<BuildTarget>>absent()));
    graphEnhancer.createAdditionalBuildables();

    BuildRule aaptPackageResourcesRule = findRuleOfType(ruleResolver, AaptPackageResources.class);
    MoreAsserts.assertDepends(
        "AaptPackageResources must depend on resource rules",
        aaptPackageResourcesRule,
        resource);
  }

  @Test
  public void testPackageStringsDependsOnResourcesFilter() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    // set it up.
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        new FakeBuildRuleParamsBuilder(target)
            .build();
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        originalParams,
        ruleResolver,
        ResourcesFilter.ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS,
        FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
        /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
        Optional.<String>absent(),
        /* locales */ ImmutableSet.<String>of(),
        new FakeSourcePath("AndroidManifest.xml"),
        AndroidBinary.PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.<TargetCpuType>of(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ false,
        BuildTargets.getScratchPath(
            originalParams.getProjectFilesystem(),
            target,
            "%s/classes.dex"),
        DexSplitMode.NO_SPLIT,
        /* buildRulesToExcludeFromDex */ ImmutableSet.<BuildTarget>of(),
        /* resourcesToExclude */ ImmutableSet.<BuildTarget>of(),
        /* skipCrunchPngs */ false,
        /* includesVectorDrawables */ false,
        ANDROID_JAVAC_OPTIONS,
        EnumSet.of(ExopackageMode.SECONDARY_DEX),
        createNiceMock(Keystore.class),
        /* buildConfigValues */ BuildConfigFields.empty(),
        /* buildConfigValuesFiles */ Optional.<SourcePath>absent(),
        /* xzCompressionLevel */ Optional.<Integer>absent(),
        /* trimResourceIds */ false,
        /* keepResourcePattern */ Optional.<String>absent(),
        /* nativePlatforms */ ImmutableMap.<TargetCpuType, NdkCxxPlatform>of(),
        /* nativeLibraryMergeMap */ Optional.<Map<String, List<Pattern>>>absent(),
        /* nativeLibraryMergeGlue */ Optional.<BuildTarget>absent(),
        /* nativeLibraryMergeCodeGenerator */ Optional.<BuildTarget>absent(),
        AndroidBinary.RelinkerMode.DISABLED,
        MoreExecutors.newDirectExecutorService(),
        /* manifestEntries */ ManifestEntries.empty(),
        CxxPlatformUtils.DEFAULT_CONFIG,
        new APKModuleGraph(
            TargetGraph.EMPTY,
            originalParams.getBuildTarget(),
            Optional.<Set<BuildTarget>>absent()));
    graphEnhancer.createAdditionalBuildables();

    ResourcesFilter resourcesFilter = findRuleOfType(ruleResolver, ResourcesFilter.class);
    PackageStringAssets packageStringAssetsRule =
        findRuleOfType(ruleResolver, PackageStringAssets.class);
    MoreAsserts.assertDepends(
        "PackageStringAssets must depend on AaptPackageResources",
        packageStringAssetsRule,
        resourcesFilter);
  }

  @Test
  public void testResourceRulesDependOnRulesBehindResourceSourcePaths() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);

    FakeBuildRule resourcesDep =
        ruleResolver.addToIndex(
            new FakeBuildRule(
                BuildTargetFactory.newInstance("//:resource_dep"),
                pathResolver));

    AndroidResource resource =
        ruleResolver.addToIndex(
            new AndroidResource(
                new FakeBuildRuleParamsBuilder("//:resources").build()
                    .appendExtraDeps(ImmutableSortedSet.of(resourcesDep)),
                pathResolver,
                ImmutableSortedSet.<BuildRule>of(),
                new BuildTargetSourcePath(resourcesDep.getBuildTarget()),
                ImmutableSortedSet.<SourcePath>of(),
                Optional.<SourcePath>absent(),
                null,
                null,
                ImmutableSortedSet.<SourcePath>of(),
                Optional.<SourcePath>absent(),
                new FakeSourcePath("manifest"),
                false));

    // set it up.
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams originalParams =
        new FakeBuildRuleParamsBuilder(target)
            .setDeclaredDeps(ImmutableSortedSet.<BuildRule>of(resource))
            .build();
    AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
        originalParams,
        ruleResolver,
        ResourcesFilter.ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS,
        FilterResourcesStep.ResourceFilter.EMPTY_FILTER,
        /* bannedDuplicateResourceTypes */ EnumSet.noneOf(RType.class),
        Optional.<String>absent(),
        /* locales */ ImmutableSet.<String>of(),
        new FakeSourcePath("AndroidManifest.xml"),
        AndroidBinary.PackageType.DEBUG,
        /* cpuFilters */ ImmutableSet.<TargetCpuType>of(),
        /* shouldBuildStringSourceMap */ false,
        /* shouldPreDex */ false,
        BuildTargets.getScratchPath(
            originalParams.getProjectFilesystem(),
            target,
            "%s/classes.dex"),
        DexSplitMode.NO_SPLIT,
        /* buildRulesToExcludeFromDex */ ImmutableSet.<BuildTarget>of(),
        /* resourcesToExclude */ ImmutableSet.<BuildTarget>of(),
        /* skipCrunchPngs */ false,
        /* includesVectorDrawables */ false,
        ANDROID_JAVAC_OPTIONS,
        EnumSet.of(ExopackageMode.SECONDARY_DEX),
        createNiceMock(Keystore.class),
        /* buildConfigValues */ BuildConfigFields.empty(),
        /* buildConfigValuesFiles */ Optional.<SourcePath>absent(),
        /* xzCompressionLevel */ Optional.<Integer>absent(),
        /* trimResourceIds */ false,
        /* keepResourcePattern */ Optional.<String>absent(),
        /* nativePlatforms */ ImmutableMap.<TargetCpuType, NdkCxxPlatform>of(),
        /* nativeLibraryMergeMap */ Optional.<Map<String, List<Pattern>>>absent(),
        /* nativeLibraryMergeGlue */ Optional.<BuildTarget>absent(),
        /* nativeLibraryMergeCodeGenerator */ Optional.<BuildTarget>absent(),
        AndroidBinary.RelinkerMode.DISABLED,
        MoreExecutors.newDirectExecutorService(),
        /* manifestEntries */ ManifestEntries.empty(),
        CxxPlatformUtils.DEFAULT_CONFIG,
        new APKModuleGraph(
            TargetGraph.EMPTY,
            originalParams.getBuildTarget(),
            Optional.<Set<BuildTarget>>absent()));
    graphEnhancer.createAdditionalBuildables();


    ResourcesFilter resourcesFilter = findRuleOfType(ruleResolver, ResourcesFilter.class);
    MoreAsserts.assertDepends(
        "ResourcesFilter must depend on rules behind resources source paths",
        resourcesFilter,
        resourcesDep);
  }

  private <T extends BuildRule> T findRuleOfType(
      BuildRuleResolver ruleResolver,
      Class<T> ruleClass) {
    for (BuildRule rule : ruleResolver.getBuildRules()) {
      if (ruleClass.isAssignableFrom(rule.getClass())) {
        return ruleClass.cast(rule);
      }
    }
    fail("Could not find build rule of type " + ruleClass.getCanonicalName());
    return null;
  }

}
