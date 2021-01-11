java_library(
    name = "byte-streams",
    srcs = ["ByteStreams.java"],
    visibility = [
        "//private/tools/java/rules/jvm/external:__subpackages__",
        "//tests/com/jvm/external:__subpackages__",
    ],
)

java_library(
    name = "hasher",
    srcs = ["Hasher.java"],
    visibility = [
        "//private/tools:__subpackages__",
    ],
)

java_test(
    name = "HasherTest",
    size = "small",
    srcs = ["HasherTest.java"],
    test_class = "rules.jvm.external.HasherTest",
    deps = [
        ":hasher",
        "@maven//:org_hamcrest_hamcrest",
    ],
)

java_library(
    name = "bom_parser",
    srcs = ["MavenBomParser.java"],
    visibility = [
        "//private/tools:__subpackages__",
    ],
)

java_test(
    name = "BomParserTest",
    size = "small",
    srcs = ["BomParserTest.java"],
    test_class = "rules.jvm.external.MavenBomParser",
    deps = [
        ":bom_parser",
        "@maven//:org_hamcrest_hamcrest",
    ],
)