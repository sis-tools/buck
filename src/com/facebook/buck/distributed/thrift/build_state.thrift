# Copyright 2016 Facebook. All Rights Reserved.
#
#!/usr/local/bin/thrift -cpp -py -java
#
# Whenever you change this file please run the following command to refresh the java source code:
# $ thrift --gen java  -out src-gen/ src/com/facebook/buck/distributed/thrift/build_state.thrift

namespace java com.facebook.buck.distributed.thrift

##############################################################################
## Buck client build state
##############################################################################

# Thrift doesn't universally guarantee map ordering. Using list of tuples.
struct OrderedStringMapEntry {
  1: string key;
  2: string value;
}

struct BuildJobStateBuckConfig {
  1: optional map<string, string> userEnvironment;
  2: optional map<string, list<OrderedStringMapEntry>> rawBuckConfig;
  3: optional string architecture;
  4: optional string platform;
}

struct PathWithUnixSeparators {
  1: string path;
}

struct BuildJobStateBuildTarget {
  1: optional string cellName;
  2: string baseName;
  3: string shortName;
  4: set<string> flavors;
}

struct BuildJobStateFileHashEntry {
  1: optional PathWithUnixSeparators path;
  2: optional string archiveMemberPath; // Only present if this is a path to an archive member.
  3: optional string hashCode; // The SHA1 hash of the content.
  4: optional bool isDirectory;
  // The paths to source files are relative, the paths to tools, SDKs, etc.. are absolute.
  5: optional bool pathIsAbsolute;

  // Inlined binary contents of this particular input dep file.
  6: optional binary contents;

  7: optional PathWithUnixSeparators rootSymLink;
  8: optional PathWithUnixSeparators rootSymLinkTarget;
}

struct BuildJobStateFileHashes {
  1: optional i32 cellIndex;
  2: optional list<BuildJobStateFileHashEntry> entries;
}

struct BuildJobStateTargetNode {
  1: optional i32 cellIndex;
  2: optional string rawNode;
  3: optional BuildJobStateBuildTarget buildTarget;
}

struct BuildJobStateCell {
  // This is just so we can generate a user-friendly path, we should not rely on this being unique.
  1: optional string nameHint;
  2: optional BuildJobStateBuckConfig config;
}

struct BuildJobStateTargetGraph {
  1: optional list<BuildJobStateTargetNode> nodes;
}

struct BuildJobState {
  1: optional map<i32, BuildJobStateCell> cells;
  2: optional list<BuildJobStateFileHashes> fileHashes;
  3: optional BuildJobStateTargetGraph targetGraph;
}
