#
# Maven specification
#

def _maven_repository(url, user = None, password = None):
    """Generates the data map for a Maven repository specifier given the available information.

    If both a user and password are given as arguments, it will include the
    access credentials in the repository spec. If one or both are missing, it
    will just generate the repository url.

    Args:
        url: A string containing the repository url (ex: "https://maven.google.com/").
        user: A username for this Maven repository, if it requires authentication (ex: "johndoe").
        password: A password for this Maven repository, if it requires authentication (ex: "example-password").
    """

    # Output Schema:
    #     {
    #         "repo_url": String
    #         "credentials: Optional Map
    #             {
    #                 "username": String
    #                 "password": String
    #             }
    #     }
    if user == None and password == None:
        return {"repo_url": url}
    elif user == None or password == None:
        fail("Invalid repository info: Either user and password must both be provided, or neither.")
    else:
        credentials = {"user": user, "password": password}
        return {"repo_url": url, "credentials": credentials}

def _maven_artifact(group, artifact, version = None, packaging = None, classifier = None, override_license_types = None, exclusions = None, neverlink = None, testonly = None):
    """Generates the data map for a Maven artifact given the available information about its coordinates.

    Args:
        group: The Maven artifact coordinate group name (ex: "com.google.guava").
        artifact: The Maven artifact coordinate artifact name (ex: "guava").
        version: The Maven artifact coordinate version name (ex: "27.0-jre").
        packaging: The Maven packaging specifier (ex: "jar").
        classifier: The Maven artifact classifier (ex: "javadoc").
        override_license_types: An array of Bazel license type strings to use for this artifact's rules (overrides autodetection) (ex: ["notify"]).
        exclusions: An array of exclusion objects to create exclusion specifiers for this artifact (ex: maven.exclusion("junit", "junit")).
        neverlink: Determines if this artifact should be part of the runtime classpath.
        testonly: Determines whether this artifact is available for targets not marked as `testonly = True`.
    """

    # Output Schema:
    #     {
    #         "group": String
    #         "artifact": String
    #         "version": String
    #         "packaging": Optional String
    #         "classifier": Optional String
    #         "override_license_types": Optional Array of String
    #         "exclusions": Optional Array of exclusions (see below)
    #         "neverlink": Optional Boolean
    #         "testonly": Optional Boolean
    #     }
    maven_artifact = {}
    maven_artifact["group"] = group
    maven_artifact["artifact"] = artifact

    if version != None:
        maven_artifact["version"] = version
    if packaging != None:
        maven_artifact["packaging"] = packaging
    if classifier != None:
        maven_artifact["classifier"] = classifier
    if override_license_types != None:
        maven_artifact["override_license_types"] = override_license_types
    if exclusions != None:
        maven_artifact["exclusions"] = exclusions
    if neverlink != None:
        maven_artifact["neverlink"] = neverlink
    if testonly != None:
        maven_artifact["testonly"] = testonly

    return maven_artifact

def _maven_exclusion(group, artifact):
    """Generates the data map for a Maven artifact exclusion.

    Args:
        group: The Maven group name of the dependency to exclude, e.g. "com.google.guava".
        artifact: The Maven artifact name of the dependency to exclude, e.g. "guava".
    """

    # Output Schema:
    # {
    #     "group": String
    #     "artifact": String
    # }
    return {"group": group, "artifact": artifact}

maven = struct(
    repository = _maven_repository,
    artifact = _maven_artifact,
    exclusion = _maven_exclusion,
)

#
# Parsing tools
#

def _parse_exclusion_spec_list(exclusion_specs):
    """
    Given a string (g:a), returns an exclusion map
    """
    exclusions = []
    for exclusion_spec in exclusion_specs:
        if type(exclusion_spec) == "string":
            pieces = exclusion_spec.split(":")
            if len(pieces) == 2:
                exclusion_spec = {"group": pieces[0], "artifact": pieces[1]}
            else:
                fail(("Invalid exclusion: %s. Exclusions are specified as " +
                      "group-id:artifact-id, without the version, packaging or " +
                      "classifier.") % exclusion_spec)
        exclusions.append(exclusion_spec)
    return exclusions

def _parse_maven_coordinate_string(mvn_coord):
    """
    Given a string containing a standard Maven coordinate (g:a:[p:[c:]]v), returns a maven artifact map (see above).
    """
    pieces = mvn_coord.split(":")
    group = pieces[0]
    artifact = pieces[1]

    if (len(pieces) == 2):
        return {"group": group, "artifact": artifact}
    if len(pieces) == 3:
        version = pieces[2]
        return {"group": group, "artifact": artifact, "version": version}
    elif len(pieces) == 4:
        packaging = pieces[2]
        version = pieces[3]
        return {"group": group, "artifact": artifact, "packaging": packaging, "version": version}
    elif len(pieces) == 5:
        packaging = pieces[2]
        classifier = pieces[3]
        version = pieces[4]
        return {"group": group, "artifact": artifact, "packaging": packaging, "classifier": classifier, "version": version}
    else:
        fail("Could not parse maven coordinate", attr = mvn_coord)

def _parse_repository_spec_list(repository_specs):
    """
    Given a list containing either strings or repository maps (see above), returns a list containing repository maps.
    """
    repos = []
    for repo in repository_specs:
        if type(repo) == "string":
            repos.append({"repo_url": repo})
        else:
            repos.append(repo)
    return repos

def _parse_artifact_spec_list(artifact_specs):
    """
    Given a list containing either strings or artifact maps (see above), returns a list containing artifact maps.
    """
    artifacts = []
    for artifact in artifact_specs:
        if type(artifact) == "string":
            artifacts.append(_parse_maven_coordinate_string(artifact))
        else:
            artifacts.append(artifact)
    return artifacts

parse = struct(
    parse_maven_coordinate = _parse_maven_coordinate_string,
    parse_repository_spec_list = _parse_repository_spec_list,
    parse_artifact_spec_list = _parse_artifact_spec_list,
    parse_exclusion_spec_list = _parse_exclusion_spec_list,
)

#
# JSON serialization
#

def _repository_credentials_spec_to_json(credentials_spec):
    """
    Given a repository credential spec or None, returns the json serialization of the object, or None if the object wasn't given.

    """
    if credentials_spec != None:
        return "{ \"user\": \"" + credentials_spec["user"] + "\", \"password\": \"" + credentials_spec["password"] + "\" }"
    else:
        return None

def _repository_spec_to_json(repository_spec):
    """
    Given a repository spec, returns the json serialization of the object.
    """
    maybe_credentials_json = _repository_credentials_spec_to_json(repository_spec.get("credentials"))
    if maybe_credentials_json != None:
        return "{ \"repo_url\": \"" + repository_spec["repo_url"] + "\", \"credentials\": " + maybe_credentials_json + " }"
    else:
        return "{ \"repo_url\": \"" + repository_spec["repo_url"] + "\" }"

def _exclusion_spec_to_json(exclusion_spec):
    """
    Given an artifact exclusion spec, returns the json serialization of the object.
    """
    return "{ \"group\": \"" + exclusion_spec["group"] + "\", \"artifact\": \"" + exclusion_spec["artifact"] + "\" }"

def _override_license_types_spec_to_json(override_license_types_spec):
    """
    Given an override license types spec, returns the json serialization of the object.
    """
    license_type_strings = []
    for license_type in override_license_types_spec:
        license_type_strings.append("\"" + license_type + "\"")
    return ("[" + ", ".join(license_type_strings) + "]")

def _artifact_spec_to_json(artifact_spec):
    """
    Given an artifact spec, returns the json serialization of the object.
    """
    maybe_exclusion_specs_jsons = []
    for spec in _parse_exclusion_spec_list(artifact_spec.get("exclusions") or []):
        maybe_exclusion_specs_jsons.append(_exclusion_spec_to_json(spec))
    exclusion_specs_json = (("[" + ", ".join(maybe_exclusion_specs_jsons) + "]") if len(maybe_exclusion_specs_jsons) > 0 else None)

    required = "{ \"group\": \"" + artifact_spec["group"] + \
               "\", \"artifact\": \"" + artifact_spec["artifact"] + "\""

    with_version = required + ((", \"version\": \"" + artifact_spec["version"] + "\"") if artifact_spec.get("version") != None else "")
    with_packaging = with_version + ((", \"packaging\": \"" + artifact_spec["packaging"] + "\"") if artifact_spec.get("packaging") != None else "")
    with_classifier = with_packaging + ((", \"classifier\": \"" + artifact_spec["classifier"] + "\"") if artifact_spec.get("classifier") != None else "")
    with_override_license_types = with_classifier + ((", " + _override_license_types_spec_to_json(artifact_spec["override_license_types"])) if artifact_spec.get("override_license_types") != None else "")
    with_exclusions = with_override_license_types + ((", \"exclusions\": " + exclusion_specs_json) if artifact_spec.get("exclusions") != None else "")
    with_neverlink = with_exclusions + ((", \"neverlink\": " + str(artifact_spec.get("neverlink")).lower()) if artifact_spec.get("neverlink") != None else "")
    with_testonly = with_neverlink + ((", \"testonly\": " + str(artifact_spec.get("testonly")).lower()) if artifact_spec.get("testonly") != None else "")

    return with_testonly + " }"

json = struct(
    write_repository_credentials_spec = _repository_credentials_spec_to_json,
    write_repository_spec = _repository_spec_to_json,
    write_exclusion_spec = _exclusion_spec_to_json,
    write_override_license_types_spec = _override_license_types_spec_to_json,
    write_artifact_spec = _artifact_spec_to_json,
)

#
# Accessors
#

#
# Couriser expects artifacts to be defined in the form `group:artifact:version`, but it also supports two attributes: classifier and url.
# In contrast with group, artifact and version, the attributes are a key=value comma-separated string appended at the end,
# For example: `coursier fetch group:artifact:version,classifier=xxx,url=yyy`
#
def _artifact_to_coord(artifact):
    classifier = (",classifier=" + artifact["classifier"]) if artifact.get("classifier") != None else ""
    return artifact["group"] + ":" + artifact["artifact"] + ":" + artifact["version"] + classifier

#
# Returns a string "{hostname} {user}:{password}" for a repository_spec
#
def _repository_credentials(repository_spec):
    if "credentials" not in repository_spec:
        fail("Asked to generate credentials for a repository that does not need it")

    (_, remainder) = repository_spec["repo_url"].split("//")
    hostname = remainder.split("/")[0]

    credentials = repository_spec["credentials"]

    # See https://get-coursier.io/docs/other-credentials.html#inline for docs on this format
    return hostname + " " + credentials["user"] + ":" + credentials["password"]

utils = struct(
    artifact_coordinate = _artifact_to_coord,
    repo_credentials = _repository_credentials,
)
