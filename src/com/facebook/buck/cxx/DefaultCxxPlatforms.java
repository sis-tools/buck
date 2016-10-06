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

package com.facebook.buck.cxx;

import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.ConstantToolProvider;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class to create a C/C++ platform described in the "cxx"
 * section of .buckconfig, with reasonable system defaults.
 */
public class DefaultCxxPlatforms {

  // Utility class, do not instantiate.
  private DefaultCxxPlatforms() { }

  public static final Flavor FLAVOR = ImmutableFlavor.of("default");

  private static final Path DEFAULT_C_FRONTEND = Paths.get("/usr/bin/gcc");
  private static final Path DEFAULT_CXX_FRONTEND = Paths.get("/usr/bin/g++");
  private static final Path DEFAULT_AR = Paths.get("/usr/bin/ar");
  private static final Path DEFAULT_STRIP = Paths.get("/usr/bin/strip");
  private static final Path DEFAULT_RANLIB = Paths.get("/usr/bin/ranlib");
  private static final Path DEFAULT_NM = Paths.get("/usr/bin/nm");

  private static final Path DEFAULT_OSX_C_FRONTEND = Paths.get("/usr/bin/clang");
  private static final Path DEFAULT_OSX_CXX_FRONTEND = Paths.get("/usr/bin/clang++");

  public static CxxPlatform build(CxxBuckConfig config) {
    return build(Platform.detect(), config);
  }

  public static CxxPlatform build(
      Platform platform,
      CxxBuckConfig config) {
    String sharedLibraryExtension;
    String sharedLibraryVersionedExtensionFormat;
    String staticLibraryExtension;
    String objectFileExtension;
    Path defaultCFrontend;
    Path defaultCxxFrontend;
    LinkerProvider.Type linkerType;
    Archiver archiver;
    switch (platform) {
      case LINUX:
        sharedLibraryExtension = "so";
        sharedLibraryVersionedExtensionFormat = "so.%s";
        staticLibraryExtension = "a";
        objectFileExtension = "o";
        defaultCFrontend = DEFAULT_C_FRONTEND;
        defaultCxxFrontend = DEFAULT_CXX_FRONTEND;
        linkerType = LinkerProvider.Type.GNU;
        archiver = new GnuArchiver(new HashedFileTool(DEFAULT_AR));
        break;
      case MACOS:
        sharedLibraryExtension = "dylib";
        sharedLibraryVersionedExtensionFormat = ".%s.dylib";
        staticLibraryExtension = "a";
        objectFileExtension = "o";
        defaultCFrontend = DEFAULT_OSX_C_FRONTEND;
        defaultCxxFrontend = DEFAULT_OSX_CXX_FRONTEND;
        linkerType = LinkerProvider.Type.DARWIN;
        archiver = new BsdArchiver(new HashedFileTool(DEFAULT_AR));
        break;
      case WINDOWS:
        sharedLibraryExtension = "dll";
        sharedLibraryVersionedExtensionFormat = "dll";
        staticLibraryExtension = "lib";
        objectFileExtension = "obj";
        defaultCFrontend = DEFAULT_C_FRONTEND;
        defaultCxxFrontend = DEFAULT_CXX_FRONTEND;
        linkerType = LinkerProvider.Type.GNU;
        archiver = new WindowsArchiver(new HashedFileTool(DEFAULT_AR));
        break;
      case FREEBSD:
        sharedLibraryExtension = "so";
        sharedLibraryVersionedExtensionFormat = "so.%s";
        staticLibraryExtension = "a";
        objectFileExtension = "o";
        defaultCFrontend = DEFAULT_C_FRONTEND;
        defaultCxxFrontend = DEFAULT_CXX_FRONTEND;
        linkerType = LinkerProvider.Type.GNU;
        archiver = new BsdArchiver(new HashedFileTool(DEFAULT_AR));
        break;
      //$CASES-OMITTED$
      default:
        throw new RuntimeException(String.format("Unsupported platform: %s", platform));
    }

    PreprocessorProvider aspp =
        new PreprocessorProvider(
            defaultCFrontend,
            Optional.<CxxToolProvider.Type>absent());
    CompilerProvider as =
        new CompilerProvider(
            defaultCFrontend,
            Optional.<CxxToolProvider.Type>absent());

    PreprocessorProvider cpp =
        new PreprocessorProvider(
            defaultCFrontend,
            Optional.<CxxToolProvider.Type>absent());
    CompilerProvider cc =
        new CompilerProvider(
            defaultCFrontend,
            Optional.<CxxToolProvider.Type>absent());
    PreprocessorProvider cxxpp =
        new PreprocessorProvider(
            defaultCxxFrontend,
            Optional.<CxxToolProvider.Type>absent());
    CompilerProvider cxx =
        new CompilerProvider(
            defaultCxxFrontend,
            Optional.<CxxToolProvider.Type>absent());

    return CxxPlatforms.build(
        FLAVOR,
        config,
        as,
        aspp,
        cc,
        cxx,
        cpp,
        cxxpp,
        new DefaultLinkerProvider(
            linkerType,
            new ConstantToolProvider(new HashedFileTool(defaultCxxFrontend))),
        ImmutableList.<String>of(),
        new HashedFileTool(DEFAULT_STRIP),
        archiver,
        new HashedFileTool(DEFAULT_RANLIB),
        new PosixNmSymbolNameTool(new HashedFileTool(DEFAULT_NM)),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        sharedLibraryExtension,
        sharedLibraryVersionedExtensionFormat,
        staticLibraryExtension,
        objectFileExtension,
        Optional.<DebugPathSanitizer>absent(),
        ImmutableMap.<String, String>of());
  }

}
