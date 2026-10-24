
[![GitHub Release](https://img.shields.io/github/v/release/codemonstur/maven-check-pom.svg)](https://github.com/codemonstur/maven-check-pom/releases)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.codemonstur/maven-check-pom.svg)](https://central.sonatype.com/artifact/com.github.codemonstur/maven-check-pom)
[![MIT Licence](https://img.shields.io/github/license/codemonstur/maven-check-pom.svg)](LICENSE)

## Maven-check-pom

A maven plugin that checks whether the properties defined in a pom are still being used.

By default the plugin will fail the build for any property that is defined in a pom but never
referenced anywhere in the build.
The purpose of the plugin is to prevent a pom from filling up with properties that nothing reads
anymore, after the plugin or the profile that needed them was removed or replaced.

The plugin is an aggregator: it runs once per build, not once per module, and it scans the whole
reactor. A property defined in the parent pom and used in a child pom counts as used.
The plugin will run during the `validate` phase.

### What counts as a use

A property counts as used when its name appears in any of these forms:

    ${property.name}     the standard maven reference
    @{property.name}     the late binding reference surefire and jacoco use, like @{argLine}
    @property.name@      the alternate delimiter of maven-resources filtering

The scan looks in the pom files of the whole reactor (including poms reachable through
`<module>` entries), in the files that sit directly next to each pom (README, bobfile, Makefile),
and in everything below each `src` directory, which covers filtered resources and test data.

Some properties are read by maven plugins straight from the project and are therefore never
referenced in any file: `maven.compiler.source`, `maven.compiler.target`, `maven.compiler.release`,
`maven.compiler.encoding`, `maven.compiler.testSource`, `maven.compiler.testTarget`,
`maven.compiler.testRelease`, `maven.compiler.parameters`, `maven.main.skip`, `maven.test.skip`,
`argLine`, `project.build.sourceEncoding` and `project.reporting.outputEncoding`.
These are ignored by definition.

A property that is only used outside of the scanned files, for example in a parent pom that is not
part of the build or in a CI script, can be listed in `ignored`, in the `ignoredFile`, or in the
`checkpom.ignored` system property.

### Example pom configuration

1. Add this code to the pom:
```
<plugin>
    <groupId>com.github.codemonstur</groupId>
    <artifactId>maven-check-pom</artifactId>
    <version>1.0.0</version>
    <executions>
        <execution><goals><goal>check</goal></goals></execution>
    </executions>
    <configuration>
        <enabled>true</enabled> <!-- default: true -->
        <printUnused>true</printUnused> <!-- default: true -->
        <failBuildOnUnused>true</failBuildOnUnused> <!-- default: true -->

        <ignored> <!-- default: empty list -->
            <ignored>revision</ignored>
            <ignored>org.example.some-property</ignored>
        </ignored>

        <!-- optional, file with one property name per line, '#' starts a comment -->
        <ignoredFile>${project.basedir}/config/unused-properties.txt</ignoredFile>
    </configuration>
</plugin>
```
2. Run `mvn validate`

### Configuration settings

The following settings can be used for the plugin:

| config name        | default value | description                                                                                             |
|--------------------|---------------|---------------------------------------------------------------------------------------------------------|
| enabled            | true          | Turns the plugin on or off                                                                              |
| printUnused        | true          | If true will print a warning in the log for each unused property                                        |
| failBuildOnUnused  | true          | If true will cause the build to fail if any property is unused                                          |
| ignored            | []            | The list of property names that are allowed to stay unused. Use the tag `<ignored>` for each name       |
| ignoredFile        | none          | Optional file with one property name per line, '#' starts a comment                                     |

In addition the system property `checkpom.ignored` accepts a comma separated list of property
names, for example `mvn validate -Dcheckpom.ignored=revision,sha1`.
