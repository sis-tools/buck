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

package com.facebook.buck.haskell;

import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.io.File;
import java.util.Map;

public class HaskellLibraryDescription implements
    Description<HaskellLibraryDescription.Arg>,
    ImplicitDepsInferringDescription<HaskellLibraryDescription.Arg>,
    Flavored {

  private static final BuildRuleType TYPE = BuildRuleType.of("haskell_library");
  private static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("Haskell Library Type", Type.class);

  private final HaskellConfig haskellConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public HaskellLibraryDescription(
      HaskellConfig haskellConfig,
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.haskellConfig = haskellConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  private BuildTarget getBaseBuildTarget(BuildTarget target) {
    return target.withoutFlavors(Sets.union(Type.FLAVOR_VALUES, cxxPlatforms.getFlavors()));
  }

  /**
   * @return the package identifier to use for the library with the given target.
   */
  private HaskellPackageInfo getPackageInfo(BuildTarget target) {
    String name =
        String.format("%s-%s", target.getBaseName(), target.getShortName());
    name = name.replace(File.separatorChar, '-');
    name = name.replace('_', '-');
    name = name.replaceFirst("^-*", "");
    return HaskellPackageInfo.of(name, "1.0.0", name);
  }

  private HaskellCompileRule requireCompileRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Arg args,
      Linker.LinkableDepType depType)
      throws NoSuchBuildTargetException {
    return HaskellDescriptionUtils.requireCompileRule(
        params,
        resolver,
        pathResolver,
        cxxPlatform,
        haskellConfig,
        depType,
        Optional.<String>absent(),
        Optional.of(getPackageInfo(params.getBuildTarget())),
        args.compilerFlags.or(ImmutableList.<String>of()),
        HaskellSources.from(
            params.getBuildTarget(),
            resolver,
            pathResolver,
            cxxPlatform,
            "srcs",
            args.srcs.or(SourceList.EMPTY)));
  }

  private Archive createStaticLibrary(
      BuildTarget target,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Arg args,
      Linker.LinkableDepType depType)
      throws NoSuchBuildTargetException {
    HaskellCompileRule compileRule =
        requireCompileRule(baseParams, resolver, pathResolver, cxxPlatform, args, depType);
    return Archive.from(
        target,
        baseParams,
        pathResolver,
        cxxPlatform,
        cxxBuckConfig.getArchiveContents(),
        CxxDescriptionEnhancer.getStaticLibraryPath(
            baseParams.getProjectFilesystem(),
            target,
            cxxPlatform.getFlavor(),
            depType == Linker.LinkableDepType.STATIC ?
                CxxSourceRuleFactory.PicType.PDC :
                CxxSourceRuleFactory.PicType.PIC,
            cxxPlatform.getStaticLibraryExtension()),
        compileRule.getObjects());
  }

  private Archive requireStaticLibrary(
      BuildTarget baseTarget,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Arg args,
      Linker.LinkableDepType depType)
      throws NoSuchBuildTargetException {
    Preconditions.checkArgument(
        Sets.intersection(
            baseTarget.getFlavors(),
            Sets.union(Type.FLAVOR_VALUES, cxxPlatforms.getFlavors()))
            .isEmpty());
    BuildTarget target =
        baseTarget.withFlavors(
            depType == Linker.LinkableDepType.STATIC ?
                Type.STATIC.getFlavor() :
                Type.STATIC_PIC.getFlavor(),
            cxxPlatform.getFlavor());
    Optional<Archive> archive = resolver.getRuleOptionalWithType(target, Archive.class);
    if (archive.isPresent()) {
      return archive.get();
    }
    return resolver.addToIndex(
        createStaticLibrary(
            target,
            baseParams,
            resolver,
            pathResolver,
            cxxPlatform,
            args,
            depType));
  }

  private HaskellPackageRule createPackage(
      BuildTarget target,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Arg args,
      Linker.LinkableDepType depType)
      throws NoSuchBuildTargetException {

    BuildRule library;
    switch (depType) {
      case SHARED:
        library =
            requireSharedLibrary(
                getBaseBuildTarget(target),
                baseParams,
                resolver,
                pathResolver,
                cxxPlatform,
                args);
        break;
      case STATIC:
      case STATIC_PIC:
        library =
            requireStaticLibrary(
                getBaseBuildTarget(target),
                baseParams,
                resolver,
                pathResolver,
                cxxPlatform,
                args,
                depType);
        break;
      default:
        throw new IllegalStateException();
    }

    ImmutableSortedMap.Builder<String, HaskellPackage> depPackagesBuilder =
        ImmutableSortedMap.naturalOrder();
    for (BuildRule rule : baseParams.getDeclaredDeps().get()) {
      if (rule instanceof HaskellCompileDep) {
        ImmutableList<HaskellPackage> packages =
            ((HaskellCompileDep) rule).getCompileInput(cxxPlatform, depType).getPackages();
        for (HaskellPackage pkg : packages) {
          depPackagesBuilder.put(pkg.getInfo().getIdentifier(), pkg);
        }
      }
    }
    ImmutableSortedMap<String, HaskellPackage> depPackages = depPackagesBuilder.build();

    HaskellCompileRule compileRule =
        requireCompileRule(baseParams, resolver, pathResolver, cxxPlatform, args, depType);

    return HaskellPackageRule.from(
        target,
        baseParams,
        pathResolver,
        haskellConfig.getPackager().resolve(resolver),
        getPackageInfo(target),
        depPackages,
        compileRule.getModules(),
        ImmutableSortedSet.<SourcePath>of(new BuildTargetSourcePath(library.getBuildTarget())),
        ImmutableSortedSet.of(compileRule.getInterfaces()));
  }

  private HaskellPackageRule requirePackage(
      BuildTarget baseTarget,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Arg args,
      Linker.LinkableDepType depType)
      throws NoSuchBuildTargetException {
    Preconditions.checkArgument(
        Sets.intersection(
            baseTarget.getFlavors(),
            Sets.union(Type.FLAVOR_VALUES, cxxPlatforms.getFlavors()))
            .isEmpty());
    BuildTarget target = baseTarget.withFlavors(cxxPlatform.getFlavor());
    switch (depType) {
      case SHARED:
        target = target.withFlavors(Type.PACKAGE_SHARED.getFlavor());
        break;
      case STATIC:
        target = target.withFlavors(Type.PACKAGE_STATIC.getFlavor());
        break;
      case STATIC_PIC:
        target = target.withFlavors(Type.PACKAGE_STATIC_PIC.getFlavor());
        break;
      default:
        throw new IllegalStateException();
    }
    Optional<HaskellPackageRule> packageRule =
        resolver.getRuleOptionalWithType(target, HaskellPackageRule.class);
    if (packageRule.isPresent()) {
      return packageRule.get();
    }
    return resolver.addToIndex(
        createPackage(target, baseParams, resolver, pathResolver, cxxPlatform, args, depType));
  }

  private HaskellLinkRule createSharedLibrary(
      BuildTarget target,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Arg args)
      throws NoSuchBuildTargetException {
    HaskellCompileRule compileRule =
        requireCompileRule(
            baseParams,
            resolver,
            pathResolver,
            cxxPlatform,
            args,
            Linker.LinkableDepType.SHARED);
    return HaskellDescriptionUtils.createLinkRule(
        target,
        baseParams,
        resolver,
        pathResolver,
        cxxPlatform,
        haskellConfig,
        Linker.LinkType.SHARED,
        ImmutableList.<String>of(),
        ImmutableList.copyOf(SourcePathArg.from(pathResolver, compileRule.getObjects())),
        Iterables.filter(baseParams.getDeclaredDeps().get(), NativeLinkable.class),
        Linker.LinkableDepType.SHARED);
  }

  private HaskellLinkRule requireSharedLibrary(
      BuildTarget baseTarget,
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Arg args)
      throws NoSuchBuildTargetException {
    Preconditions.checkArgument(
        Sets.intersection(
            baseTarget.getFlavors(),
            Sets.union(Type.FLAVOR_VALUES, cxxPlatforms.getFlavors()))
            .isEmpty());
    BuildTarget target = baseTarget.withFlavors(Type.SHARED.getFlavor(), cxxPlatform.getFlavor());
    Optional<HaskellLinkRule> linkRule =
        resolver.getRuleOptionalWithType(target, HaskellLinkRule.class);
    if (linkRule.isPresent()) {
      return linkRule.get();
    }
    return resolver.addToIndex(
        createSharedLibrary(target, baseParams, resolver, pathResolver, cxxPlatform, args));
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args)
      throws NoSuchBuildTargetException {

    final BuildTarget buildTarget = params.getBuildTarget();
    final SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(buildTarget);
    Optional<CxxPlatform> cxxPlatform = cxxPlatforms.getValue(buildTarget);
    if (type.isPresent()) {
      Preconditions.checkState(cxxPlatform.isPresent());

      // Get the base build, without any flavors referring to the library type or platform.
      BuildTarget baseTarget =
          params.getBuildTarget().withoutFlavors(
              Sets.union(Type.FLAVOR_VALUES, cxxPlatforms.getFlavors()));

      switch (type.get().getValue()) {
        case PACKAGE_SHARED:
        case PACKAGE_STATIC:
        case PACKAGE_STATIC_PIC:
          Linker.LinkableDepType depType;
          if (type.get().getValue().equals(Type.PACKAGE_SHARED)) {
            depType = Linker.LinkableDepType.SHARED;
          } else if (type.get().getValue().equals(Type.PACKAGE_STATIC)) {
            depType = Linker.LinkableDepType.STATIC;
          } else {
            depType = Linker.LinkableDepType.STATIC_PIC;
          }
          return requirePackage(
              baseTarget,
              params,
              resolver,
              pathResolver,
              cxxPlatform.get(),
              args,
              depType);
        case SHARED:
          return requireSharedLibrary(
              baseTarget,
              params,
              resolver,
              pathResolver,
              cxxPlatform.get(),
              args);
        case STATIC_PIC:
        case STATIC:
          return requireStaticLibrary(
              baseTarget,
              params,
              resolver,
              pathResolver,
              cxxPlatform.get(),
              args,
              type.get().getValue() == Type.STATIC ?
                  Linker.LinkableDepType.STATIC :
                  Linker.LinkableDepType.STATIC_PIC);
      }

      throw new IllegalStateException(
          String.format(
              "%s: unexpected type `%s`",
              params.getBuildTarget(),
              type.get().getValue()));
    }

    return new HaskellLibrary(params, pathResolver) {

      @Override
      public HaskellCompileInput getCompileInput(
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType depType)
          throws NoSuchBuildTargetException {
        HaskellPackageRule rule =
            requirePackage(
                getBaseBuildTarget(getBuildTarget()),
                params,
                resolver,
                pathResolver,
                cxxPlatform,
                args,
                depType);
        return HaskellCompileInput.builder()
            .addPackages(rule.getPackage())
            .build();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableDeps(CxxPlatform cxxPlatform) {
        return ImmutableList.of();
      }

      @Override
      public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps(
          CxxPlatform cxxPlatform) {
        return FluentIterable.from(getDeps())
            .filter(NativeLinkable.class);
      }

      @Override
      public NativeLinkableInput getNativeLinkableInput(
          CxxPlatform cxxPlatform,
          Linker.LinkableDepType type)
          throws NoSuchBuildTargetException {
        Iterable<com.facebook.buck.rules.args.Arg> linkArgs;
        switch (type) {
          case STATIC:
          case STATIC_PIC:
            Archive archive =
                requireStaticLibrary(
                    getBaseBuildTarget(getBuildTarget()),
                    params,
                    resolver,
                    pathResolver,
                    cxxPlatform,
                    args,
                    type);
            linkArgs =
                args.linkWhole.or(false) ?
                    cxxPlatform.getLd().resolve(resolver).linkWhole(archive.toArg()) :
                    ImmutableList.of(archive.toArg());
            break;
          case SHARED:
            BuildRule rule =
                requireSharedLibrary(
                    getBaseBuildTarget(getBuildTarget()),
                    params,
                    resolver,
                    pathResolver,
                    cxxPlatform,
                    args);
            linkArgs =
                ImmutableList.<com.facebook.buck.rules.args.Arg>of(
                    new SourcePathArg(getResolver(),
                        new BuildTargetSourcePath(rule.getBuildTarget())));
            break;
          default:
            throw new IllegalStateException();
        }
        return NativeLinkableInput.builder()
            .addAllArgs(linkArgs)
            .build();
      }

      @Override
      public Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
        return args.preferredLinkage.or(Linkage.ANY);
      }

      @Override
      public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform)
          throws NoSuchBuildTargetException {
        ImmutableMap.Builder<String, SourcePath> libs = ImmutableMap.builder();
        String sharedLibrarySoname =
            CxxDescriptionEnhancer.getSharedLibrarySoname(
                Optional.<String>absent(),
                getBuildTarget(),
                cxxPlatform);
        BuildRule sharedLibraryBuildRule =
            requireSharedLibrary(
                getBaseBuildTarget(getBuildTarget()),
                params,
                resolver,
                pathResolver,
                cxxPlatform,
                args);
        libs.put(
            sharedLibrarySoname,
            new BuildTargetSourcePath(sharedLibraryBuildRule.getBuildTarget()));
        return libs.build();
      }
    };

  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    if (cxxPlatforms.containsAnyOf(flavors)) {
      return true;
    }

    for (Type type : Type.values()) {
      if (flavors.contains(type.getFlavor())) {
        return true;
      }
    }

    return false;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    return HaskellDescriptionUtils.getParseTimeDeps(haskellConfig, cxxPlatforms.getValues());
  }

  protected enum Type implements FlavorConvertible {

    PACKAGE_SHARED(ImmutableFlavor.of("package-shared")),
    PACKAGE_STATIC(ImmutableFlavor.of("package-static")),
    PACKAGE_STATIC_PIC(ImmutableFlavor.of("package-static-pic")),

    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR),
    STATIC_PIC(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR),

    ;

    public static final ImmutableSet<Flavor> FLAVOR_VALUES =
        FluentIterable.from(ImmutableList.copyOf(Type.values()))
            .transform(
                new Function<Type, Flavor>() {
                  @Override
                  public Flavor apply(Type type) {
                    return type.getFlavor();
                  }
                })
            .toSet();

    private final Flavor flavor;

    Type(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }

  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<SourceList> srcs;
    public Optional<ImmutableList<String>> compilerFlags;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<Boolean> linkWhole;
    public Optional<NativeLinkable.Linkage> preferredLinkage;
  }

}
