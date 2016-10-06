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

package com.facebook.buck.cxx;

import com.facebook.buck.io.FileContentsScrubber;
import com.facebook.buck.io.FileScrubber;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public class BsdArchiver implements Archiver {

  private static final byte[] EXPECTED_GLOBAL_HEADER = "!<arch>\n".getBytes(Charsets.US_ASCII);
  private static final byte[] LONG_NAME_MARKER = "#1/".getBytes(Charsets.US_ASCII);

  private static final FileContentsScrubber SYMBOL_NAME_TABLE_PADDING_SCRUBBER =
      new FileContentsScrubber() {
        @Override
        public void scrubFile(FileChannel file) throws IOException, ScrubException {
          MappedByteBuffer map = file.map(FileChannel.MapMode.READ_WRITE, 0, file.size());

          // Grab the global header chunk and verify it's accurate.
          byte[] globalHeader = ObjectFileScrubbers.getBytes(map, EXPECTED_GLOBAL_HEADER.length);
          ObjectFileScrubbers.checkArchive(
              Arrays.equals(EXPECTED_GLOBAL_HEADER, globalHeader),
              "invalid global header");

          byte[] marker = ObjectFileScrubbers.getBytes(map, 3);
          if (!Arrays.equals(LONG_NAME_MARKER, marker)) {
            // This file isn't actually made with BSD ar; skip scrubbing it.
            return;
          }

          int nameLength = ObjectFileScrubbers.getDecimalStringAsInt(map, 13);

          /* File modification timestamp */ ObjectFileScrubbers.getDecimalStringAsInt(map, 12);
          /* Owner ID */ ObjectFileScrubbers.getDecimalStringAsInt(map, 6);
          /* Group ID */ ObjectFileScrubbers.getDecimalStringAsInt(map, 6);

          /* File mode */ ObjectFileScrubbers.getOctalStringAsInt(map, 8);
          /* File size */ ObjectFileScrubbers.getDecimalStringAsInt(map, 10);

          // Lastly, grab the file magic entry and verify it's accurate.
          byte[] fileMagic = ObjectFileScrubbers.getBytes(map, 2);
          ObjectFileScrubbers.checkArchive(
              Arrays.equals(ObjectFileScrubbers.END_OF_FILE_HEADER_MARKER, fileMagic),
              "invalid file magic");

          // Skip the file name
          map.position(map.position() + nameLength);

          int descriptorsSize = ObjectFileScrubbers.getLittleEndianInt(map);

          if (descriptorsSize > 0) {

            // We need to find where the last symbol name entry is in the symbol name table, as
            // we need to sanitize the padding that comes immediately after it.
            // There are two types of symbol table formats, one where the descriptors are ordered by
            // archive ordering and one where the descriptors are ordered alphabetically by their
            // name.  In the former case, we could just read the last descriptors offset into the
            // symbol name table.  However, this seems implementation-specific and won't work in the
            // latter case (where the order of the descriptors doesn't correspond to the order of
            // the symbol name table).  So, just search through all the descriptors to find the last
            // symbol name table offset.
            int lastSymbolNameOffset = 0;
            for (int i = 0; i < descriptorsSize / 8; i++) {
              lastSymbolNameOffset =
                  Math.max(
                      lastSymbolNameOffset,
                      ObjectFileScrubbers.getLittleEndianInt(map));
              // Skip the corresponding object offset
              ObjectFileScrubbers.getLittleEndianInt(map);
            }

            int symbolNameTableSize = ObjectFileScrubbers.getLittleEndianInt(map);
            int endOfSymbolNameTableOffset = map.position() + symbolNameTableSize;

            // Skip to the last symbol name
            map.position(map.position() + lastSymbolNameOffset);

            // Skip to the terminating null
            while (map.get() != 0x00) { // NOPMD
            }

            while (map.position() < endOfSymbolNameTableOffset) {
              map.put((byte) 0x00);
            }
          } else {
            int symbolNameTableSize = ObjectFileScrubbers.getLittleEndianInt(map);
            ObjectFileScrubbers.checkArchive(
                symbolNameTableSize == 0,
                "archive has no symbol descriptors but has symbol names");
          }
        }
      };

  private final Tool tool;

  public BsdArchiver(Tool tool) {
    this.tool = tool;
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers() {
    return ImmutableList.<FileScrubber>of(
        ObjectFileScrubbers.createDateUidGidScrubber(ObjectFileScrubbers.PaddingStyle.RIGHT),
        SYMBOL_NAME_TABLE_PADDING_SCRUBBER);
  }

  @Override
  public boolean supportsThinArchives() {
    return false;
  }

  @Override
  public ImmutableList<String> getArchiveOptions(boolean isThinArchive) {
    String options = isThinArchive ? "qcT" : "qc";
    return ImmutableList.<String>of(options);
  }

  @Override
  public ImmutableList<String> outputArgs(String outputPath) {
    return ImmutableList.of(outputPath);
  }

  @Override
  public boolean isRanLibStepRequired() {
    return true;
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
    return tool.getDeps(resolver);
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return tool.getInputs();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return tool.getCommandPrefix(resolver);
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return tool.getEnvironment(resolver);
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink
        .setReflectively("tool", tool)
        .setReflectively("type", getClass().getSimpleName());
  }

}
