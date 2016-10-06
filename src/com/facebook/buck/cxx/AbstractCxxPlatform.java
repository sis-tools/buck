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
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;

import org.immutables.value.Value;

import java.util.List;

/**
 * Interface describing a C/C++ toolchain and platform to build for.
 */
@Value.Immutable
@BuckStyleImmutable
interface AbstractCxxPlatform extends FlavorConvertible {

  @Override
  Flavor getFlavor();

  CompilerProvider getAs();
  List<String> getAsflags();

  PreprocessorProvider getAspp();
  List<String> getAsppflags();

  CompilerProvider getCc();
  List<String> getCflags();

  CompilerProvider getCxx();
  List<String> getCxxflags();

  PreprocessorProvider getCpp();
  List<String> getCppflags();

  PreprocessorProvider getCxxpp();
  List<String> getCxxppflags();

  Optional<PreprocessorProvider> getCudapp();
  List<String> getCudappflags();

  Optional<CompilerProvider> getCuda();
  List<String> getCudaflags();

  Optional<PreprocessorProvider> getAsmpp();
  List<String> getAsmppflags();

  Optional<CompilerProvider> getAsm();
  List<String> getAsmflags();

  LinkerProvider getLd();
  List<String> getLdflags();
  Multimap<Linker.LinkableDepType, String> getRuntimeLdflags();

  Tool getStrip();
  List<String> getStripFlags();

  Archiver getAr();
  List<String> getArflags();

  Tool getRanlib();
  List<String> getRanlibflags();

  SymbolNameTool getSymbolNameTool();

  String getSharedLibraryExtension();
  String getSharedLibraryVersionedExtensionFormat();

  String getStaticLibraryExtension();
  String getObjectFileExtension();

  DebugPathSanitizer getDebugPathSanitizer();

  /**
   * @return a map for macro names to their respective expansions, to be used to expand macro
   *     references in user-provided flags.
   */
  ImmutableMap<String, String> getFlagMacros();

}
