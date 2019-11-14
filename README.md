This copy of Apache Beam is to troubleshoot [BEAM-8654 beam_Dependency_Check's not getting correct
report from Gradle dependencyUpdates](https://issues.apache.org/jira/projects/BEAM/issues/BEAM-8654
). `./gradlew dependencyUpdates` returns the following output:

```
suztomo@suxtomo24:~/beam-version-problem$ ./gradlew dependencyUpdates
Configuration on demand is an incubating feature.

> Task :dependencyUpdates

------------------------------------------------------------
: Project Dependency Updates (report to plain text file)
------------------------------------------------------------

The following dependencies are using the latest milestone version:
 - com.google.cloud.bigtable:bigtable-client-core:1.8.0

The following dependencies have later milestone versions:
 - com.github.ben-manes.versions:com.github.ben-manes.versions.gradle.plugin [0.20.0 -> 0.27.0]
 - org.sonarqube:org.sonarqube.gradle.plugin [2.7 -> 2.8]

Gradle updates:
 - Gradle: [5.2.1 -> 6.0]

Generated report file build/dependencyUpdates/report.txt

Deprecated Gradle features were used in this build, making it incompatible with Gradle 6.0.
Use '--warning-mode all' to show the individual deprecation warnings.
See https://docs.gradle.org/5.2.1/userguide/command_line_interface.html#sec:command_line_warnings
```

As of November 14th, 2019, the latest `bigtable-client-core` is
[`com.google.cloud.bigtable:bigtable-client-core:1.12.1`](
https://search.maven.org/artifact/com.google.cloud.bigtable/bigtable-client-core/1.12.1/jar).

The problem is `BeamModulePlugin.groovy` defines resolution this strategy

```
  config.resolutionStrategy {
    force project.library.java.values()
  }
```
