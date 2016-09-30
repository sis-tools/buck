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

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Path;

public class HaskellLibraryDescriptionTest {

  @Test
  public void compilerFlags() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    String flag = "-compiler-flag";
    HaskellLibraryBuilder builder =
        new HaskellLibraryBuilder(target)
            .setCompilerFlags(ImmutableList.of(flag));
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    HaskellLibrary library = (HaskellLibrary) builder.build(resolver);
    library.getCompileInput(
        CxxPlatformUtils.DEFAULT_PLATFORM,
        Linker.LinkableDepType.STATIC);
    BuildTarget compileTarget =
        HaskellDescriptionUtils.getCompileBuildTarget(
            target,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC);
    HaskellCompileRule rule = resolver.getRuleWithType(compileTarget, HaskellCompileRule.class);
    assertThat(rule.getFlags(), Matchers.hasItem(flag));
  }

  @Test
  public void targetsAndOutputsAreDifferentBetweenLinkStyles() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(),
            new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget baseTarget = BuildTargetFactory.newInstance("//:rule");

    BuildRule staticLib =
        new HaskellLibraryBuilder(
            baseTarget.withFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                HaskellLibraryDescription.Type.STATIC.getFlavor()))
            .build(resolver);
    BuildRule staticPicLib =
        new HaskellLibraryBuilder(
            baseTarget.withFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                HaskellLibraryDescription.Type.STATIC_PIC.getFlavor()))
            .build(resolver);
    BuildRule sharedLib =
        new HaskellLibraryBuilder(
            baseTarget.withFlavors(
                CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor(),
                HaskellLibraryDescription.Type.SHARED.getFlavor()))
            .build(resolver);

    ImmutableList<Path> outputs =
        ImmutableList.of(
            Preconditions.checkNotNull(staticLib.getPathToOutput()),
            Preconditions.checkNotNull(staticPicLib.getPathToOutput()),
            Preconditions.checkNotNull(sharedLib.getPathToOutput()));
    assertThat(outputs.size(), Matchers.equalTo(ImmutableSet.copyOf(outputs).size()));

    ImmutableList<BuildTarget> targets =
        ImmutableList.of(
            staticLib.getBuildTarget(),
            staticPicLib.getBuildTarget(),
            sharedLib.getBuildTarget());
    assertThat(targets.size(), Matchers.equalTo(ImmutableSet.copyOf(targets).size()));
  }

  @Test
  public void linkWhole() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    HaskellLibraryBuilder builder =
        new HaskellLibraryBuilder(target)
            .setLinkWhole(true);
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    HaskellLibrary library = (HaskellLibrary) builder.build(resolver);

    // Lookup the link whole flags.
    Linker linker = CxxPlatformUtils.DEFAULT_PLATFORM.getLd().resolve(resolver);
    ImmutableList<String> linkWholeFlags =
        FluentIterable.from(linker.linkWhole(new StringArg("sentinel")))
            .transformAndConcat(Arg.stringListFunction())
            .filter(Predicates.not(Predicates.equalTo("sentinel")))
            .toList();

    // Test static dep type.
    NativeLinkableInput staticInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC);
    assertThat(
        Arg.stringify(staticInput.getArgs()),
        hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()])));

    // Test static-pic dep type.
    NativeLinkableInput staticPicInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC_PIC);
    assertThat(
        Arg.stringify(staticPicInput.getArgs()),
        hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()])));

    // Test shared dep type.
    NativeLinkableInput sharedInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.SHARED);
    assertThat(
        Arg.stringify(sharedInput.getArgs()),
        not(hasItems(linkWholeFlags.toArray(new String[linkWholeFlags.size()]))));
  }

  @Test
  public void preferredLinkage() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(),
            new DefaultTargetNodeToBuildRuleTransformer());

    // Test default value.
    HaskellLibrary defaultLib =
        (HaskellLibrary) new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:default"))
            .build(resolver);
    assertThat(
        defaultLib.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.is(NativeLinkable.Linkage.ANY));

    // Test `ANY` value.
    HaskellLibrary anyLib =
        (HaskellLibrary) new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:any"))
            .setPreferredLinkage(NativeLinkable.Linkage.ANY)
            .build(resolver);
    assertThat(
        anyLib.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.is(NativeLinkable.Linkage.ANY));

    // Test `STATIC` value.
    HaskellLibrary staticLib =
        (HaskellLibrary) new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:static"))
            .setPreferredLinkage(NativeLinkable.Linkage.STATIC)
            .build(resolver);
    assertThat(
        staticLib.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.is(NativeLinkable.Linkage.STATIC));

    // Test `SHARED` value.
    HaskellLibrary sharedLib =
        (HaskellLibrary) new HaskellLibraryBuilder(BuildTargetFactory.newInstance("//:shared"))
            .setPreferredLinkage(NativeLinkable.Linkage.SHARED)
            .build(resolver);
    assertThat(
        sharedLib.getPreferredLinkage(CxxPlatformUtils.DEFAULT_PLATFORM),
        Matchers.is(NativeLinkable.Linkage.SHARED));
  }

  @Test
  public void thinArchivesPropagatesDepFromObjects() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    CxxBuckConfig cxxBuckConfig =
        new CxxBuckConfig(
            FakeBuckConfig.builder().setSections("[cxx]", "archive_contents=thin").build());
    HaskellLibraryBuilder builder =
        new HaskellLibraryBuilder(
            target,
            FakeHaskellConfig.DEFAULT,
            cxxBuckConfig,
            CxxPlatformUtils.DEFAULT_PLATFORMS)
            .setSrcs(
                SourceList.ofUnnamedSources(
                    ImmutableSortedSet.<SourcePath>of(new FakeSourcePath("Test.hs"))))
            .setLinkWhole(true);
    BuildRuleResolver resolver =
        new BuildRuleResolver(
            TargetGraphFactory.newInstance(builder.build()),
            new DefaultTargetNodeToBuildRuleTransformer());
    HaskellLibrary library = (HaskellLibrary) builder.build(resolver);

    // Test static dep type.
    NativeLinkableInput staticInput =
        library.getNativeLinkableInput(
            CxxPlatformUtils.DEFAULT_PLATFORM,
            Linker.LinkableDepType.STATIC);
    assertThat(
        FluentIterable.from(staticInput.getArgs())
            .transformAndConcat(Arg.getDepsFunction(new SourcePathResolver(resolver)))
            .transform(HasBuildTarget.TO_TARGET)
            .toList(),
        Matchers.hasItem(
            HaskellDescriptionUtils.getCompileBuildTarget(
                library.getBuildTarget(),
                CxxPlatformUtils.DEFAULT_PLATFORM,
                Linker.LinkableDepType.STATIC)));
  }

}
