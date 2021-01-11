// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package rules.jvm.external;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public class MavenBomParser {

    public static void main(String... args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: bom coordinates, output file path, coursier cache path, list of repository paths");
            System.exit(1);
        }

        String bomCoordinates = args[0];
        Path outputPath = Paths.get(args[1]);
        Path cachePath = Paths.get(args[2]);
        List<Path> repositoryPaths = Arrays.stream(args)
                .skip(3)
                .map(Paths::get)
                .map(cachePath::resolve)
                .collect(Collectors.toList());

        MavenBomParser bomParser = new MavenBomParser();

        String[] coords = bomCoordinates.split(":");

        Path groupPath = Paths.get("");
        for (String groupPart : coords[0].split("\\.")) {
            groupPath = groupPath.resolve(groupPart);
        }
        List<Dependency> dependencies = bomParser.parse(coords[0], coords[1], coords[2], repositoryPaths);

//        String output = dependencies.stream()
//                .map(Dependency::toJson)
//                .collect(Collectors.joining("\",\n\t\t\"", "{\n\t\"dependency_management\": [\n\t\t\"", "\"\n\t]\n}"));

        String output = dependencies.stream()
                .collect(Collectors.toMap(MavenBomParser::createKey, MavenBomParser::createValue, (s, s2) -> s2, LinkedHashMap::new))
                .entrySet().stream()
                .map(dependency -> "\t\t\"" + dependency.getKey() + "\": " + dependency.getValue())
                .collect(Collectors.joining(",\n", "{\n\t\"dependency_management\": {\n", "\n\t}\n}"));

        Files.write(outputPath, output.getBytes(StandardCharsets.UTF_8));
    }

    private final SAXParser saxParser;

    private MavenBomParser() throws ParserConfigurationException, SAXException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        this.saxParser = factory.newSAXParser();
    }

    private Map<String, String> processPropertyReferences(Map<String, String> propertiesReference) {
        if (propertiesReference.entrySet().stream()
                .anyMatch(ref -> ref.getValue().startsWith("${") && ref.getValue().endsWith("}"))) {
            return processPropertyReferences(propertiesReference.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                        if (entry.getValue().startsWith("${") && entry.getValue().endsWith("}")) {
                            return propertiesReference.getOrDefault(entry.getValue().substring(2, entry.getValue().length() - 1), entry.getValue());
                        } else {
                            return entry.getValue();
                        }
                    })));
        }

        return propertiesReference;
    }

    public List<Dependency> parse(String groupId, String artifactId, String version, List<Path> repositoryPaths) {

        Path groupPath = Paths.get("");
        for (String groupPart : groupId.split("\\.")) {
            groupPath = groupPath.resolve(groupPart);
        }

        Map<String, String> propertiesReference = new HashMap<>();
        propertiesReference.put("project.version", version);
        List<Dependency> dependencyReference = new LinkedList<>();
        for (Path repositoryPath : repositoryPaths) {
            Path path = repositoryPath.resolve(groupPath).resolve(artifactId).resolve(version).resolve(artifactId + "-" + version + ".pom");
            if (path.toFile().exists()) {
                try {
                    saxParser.parse(path.toFile(), newHandler(propertiesReference, dependencyReference));
                    break;
                } catch (SAXException | IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        Map<String, String> resolvedPropertiesReference = processPropertyReferences(propertiesReference);

        return dependencyReference.stream()
                .map(dependency -> dependency.setVersion(resolvedPropertiesReference.get(dependency.version)))
                .flatMap(dependency -> {
                    if ("import".equals(dependency.scope)) {
                        return parse(dependency.groupId, dependency.artifactId, dependency.version, repositoryPaths).stream();
                    } else {
                        return Stream.of(dependency);
                    }
                })
                .collect(Collectors.toList());
    }

    private static String createKey(Dependency dependency) {
        String key = dependency.groupId + ":" + dependency.artifactId;
        if (dependency.classifier != null && !dependency.classifier.isEmpty()) {
            key += ":" + dependency.classifier;
        }
        return key;
    }

    private static String createValue(Dependency dependency) {
        String version = dependency.version;
        StringBuilder valueBuilder = new StringBuilder();
        valueBuilder.append("{\"version\": \"").append(version).append("\"");
        if (!dependency.exclusions.isEmpty()) {
            String exclusions = "[" + dependency.exclusions.stream().map(exclusion -> "{\"group\": \"" + exclusion.groupId + "\", \"artifact\": \"" + exclusion.artifactId + "\"}").collect(Collectors.joining(", ")) + "]";
            valueBuilder.append(", \"exclusions\": ").append(exclusions);
        }
        valueBuilder.append("}");
        return valueBuilder.toString();
    }

    private DefaultHandler newHandler(Map<String, String> propertiesReference, List<Dependency> dependencyReference) {
        return new DefaultHandler() {

            private boolean inProperties = false;
            private String propertyName;
            private String propertyValue;
            private boolean inDependencyManagement = false;
            private boolean inDependencies = false;
            private boolean inDependency = false;
            private String part;
            private Dependency dependency;
            private boolean inDependencyExcludes = false;
            private Dependency exclusion;

            public void startElement(String uri, String localName,String qName, Attributes attributes) {
                if (qName.equals("properties")) {
                    inProperties = true;
                } else if (qName.equals("dependencyManagement")) {
                    inDependencyManagement = true;
                } else if (inDependencyManagement && qName.equals("dependencies")) {
                    inDependencies = true;
                } else if (inDependencies && qName.equals("dependency")) {
                    inDependency = true;
                    dependency = new Dependency();
                } else if (inDependency) {
                    if (qName.equals("groupId")) {
                        part = "GROUP_ID";
                    } else if (qName.equals("artifactId")) {
                        part = "ARTIFACT_ID";
                    } else if (qName.equals("version")) {
                        part = "VERSION";
                    } else if (qName.equals("type")) {
                        part = "TYPE";
                    } else if (qName.equals("scope")) {
                        part = "SCOPE";
                    } else if (qName.equals("classifier")) {
                        part = "CLASSIFIER";
                    } else if (qName.equals("exclusions")) {
                        inDependencyExcludes = true;
                    } else if (qName.equals("exclusion")) {
                        exclusion = new Dependency();
                    } else {
                        part = null; //TODO handle exclusions
                    }
                } else if (inProperties) {
                    propertyName = qName;
                    propertyValue = "";
                }
            }

            public void endElement(String uri, String localName, String qName) {
                if (qName.equals("properties")) {
                    inProperties = false;
                } else if (qName.equals(propertyName)) {
                    propertiesReference.put(propertyName, propertyValue);
                    propertyName = null;
                    propertyValue = null;
                } else if (qName.equals("dependency")) {
                    if (dependency.version.startsWith("${") && dependency.version.endsWith("}")) {
                        dependency.version = dependency.version.substring(2, dependency.version.length() - 1);
                    }
                    dependencyReference.add(dependency);
                    part = null;
                    inDependency = false;
                } else if (qName.equals("exclusions")) {
                    inDependencyExcludes = false;
                } else if (qName.equals("exclusion")) {
                    dependency.exclusions.add(exclusion);
                    exclusion = null;
                }
            }

            public void characters(char[] ch, int start, int length) {
                if (inProperties) {
                    propertyValue += getString(ch, start, length);
                } else if (exclusion != null) {
                    if ("GROUP_ID".equals(part)) {
                        exclusion.groupId += getString(ch, start, length);
                    } else if ("ARTIFACT_ID".equals(part)) {
                        exclusion.artifactId += getString(ch, start, length);
                    }
                } else if ("GROUP_ID".equals(part)) {
                    dependency.groupId += getString(ch, start, length);
                } else if ("ARTIFACT_ID".equals(part)) {
                    dependency.artifactId += getString(ch, start, length);
                } else if ("VERSION".equals(part)) {
                    dependency.version += getString(ch, start, length);
                } else if ("TYPE".equals(part)) {
                    dependency.type += getString(ch, start, length);
                } else if ("SCOPE".equals(part)) {
                    dependency.scope += getString(ch, start, length);
                } else if ("CLASSIFIER".equals(part)) {
                    dependency.classifier += getString(ch, start, length);
                }
            }

            private String getString(char[] ch, int start, int length) {
                return new String(ch, start, length).trim();
            }
        };
    }

    private static class Dependency {
        private String groupId = "";
        private String artifactId = "";
        private String version = "";
        private String type = "";
        private String classifier = "";
        private String scope = "";
        private List<Dependency> exclusions = new ArrayList<>(); //TODO

        public Dependency setVersion(String version) {
            if (version != null) {
                this.version = version;
            }
            return this;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(groupId).append(":")
                    .append(artifactId).append(":")
                    .append(version).append(":");
            if (!type.isEmpty()) {
                sb.append(type);
            } else {
                sb.append("jar");
            }
            if (!scope.isEmpty()) {
                sb.append(":").append(scope);
            }
            if (!classifier.isEmpty()) {
                sb.append(":").append(classifier);
            }
            return sb.toString();
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("\"group\": \"").append(groupId).append("\",");
            sb.append("\"artifactId\": \"").append(artifactId).append("\",");
            sb.append("\"version\": \"").append(version);
            if (!type.isEmpty()) {
                sb.append("\",").append("\"packaging\": \"").append(type);
            }
            if (!classifier.isEmpty()) {
                sb.append("\",").append("\"classifier\": \"").append(classifier);
            }
            //TODO neverlink
            //TODO testonly
            //TODO exclusions
            return sb.toString();
        }
    }
}