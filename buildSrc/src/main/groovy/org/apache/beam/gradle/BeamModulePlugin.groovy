/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * License); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.beam.gradle

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.concurrent.atomic.AtomicInteger
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.FileTree
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoReport

/**
 * This plugin adds methods to configure a module with Beam's defaults, called "natures".
 *
 * <p>The natures available:
 *
 * <ul>
 *   <li>Java   - Configures plugins commonly found in Java projects
 *   <li>Go     - Configures plugins commonly found in Go projects
 *   <li>Docker - Configures plugins commonly used to build Docker containers
 *   <li>Grpc   - Configures plugins commonly used to generate source from protos
 *   <li>Avro   - Configures plugins commonly used to generate source from Avro specifications
 * </ul>
 *
 * <p>For example, see applyJavaNature.
 */
class BeamModulePlugin implements Plugin<Project> {

  /** Licence header enforced by spotless */
  static final String javaLicenseHeader = """/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
"""
  static AtomicInteger startingExpansionPortNumber = new AtomicInteger(18091)

  /** A class defining the set of configurable properties accepted by applyJavaNature. */
  class JavaNatureConfiguration {
    /** Controls whether the spotbugs plugin is enabled and configured. */
    boolean enableSpotbugs = true

    /** Controls whether the dependency analysis plugin is enabled. */
    boolean enableStrictDependencies = false

    /** Override the default "beam-" + `dash separated path` archivesBaseName. */
    String archivesBaseName = null;

    /**
     * List of additional lint warnings to disable.
     * In addition, defaultLintSuppressions defined below
     * will be applied to all projects.
     */
    List<String> disableLintWarnings = []

    /** Controls whether tests are run with shadowJar. */
    boolean testShadowJar = false

    /**
     * Controls whether the shadow jar is validated to not contain any classes outside the org.apache.beam namespace.
     * This protects artifact jars from leaking dependencies classes causing conflicts for users.
     *
     * Note that this can be disabled for subprojects that produce application artifacts that are not intended to
     * be depended on by users.
     */
    boolean validateShadowJar = true

    /**
     * The set of excludes that should be used during validation of the shadow jar. Projects should override
     * the default with the most specific set of excludes that is valid for the contents of its shaded jar.
     *
     * By default we exclude any class underneath the org.apache.beam namespace.
     */
    List<String> shadowJarValidationExcludes = ["org/apache/beam/**"]

    /**
     * If unset, no shading is performed. The jar and test jar archives are used during publishing.
     * Otherwise the shadowJar and shadowTestJar artifacts are used during publishing.
     *
     * The shadowJar / shadowTestJar tasks execute the specified closure to configure themselves.
     */
    Closure shadowClosure;

    /** Controls whether this project is published to Maven. */
    boolean publish = true

    /** Controls whether javadoc is exported for this project. */
    boolean exportJavadoc = true

    /**
     * Automatic-Module-Name Header value to be set in MANFIEST.MF file.
     * This is a required parameter unless publishing to Maven is disabled for this project.
     *
     * @see: https://github.com/GoogleCloudPlatform/cloud-opensource-java/blob/master/library-best-practices/JLBP-20.md
     */
    String automaticModuleName = null
  }

  def isRelease(Project project) {
    return project.hasProperty('isRelease')
  }

  def defaultArchivesBaseName(Project p) {
    return 'beam' + p.path.replace(':', '-')
  }

  void apply(Project project) {

    /** ***********************************************************************************************/
    // Apply common properties/repositories and tasks to all projects.

    project.ext.mavenGroupId = 'org.apache.beam'

    // Automatically use the official release version if we are performing a release
    // otherwise append '-SNAPSHOT'
    project.version = '2.18.0'
    if (!isRelease(project)) {
      project.version += '-SNAPSHOT'
    }

    // Default to dash-separated directories for artifact base name,
    // which will also be the default artifactId for maven publications
    project.apply plugin: 'base'
    project.archivesBaseName = defaultArchivesBaseName(project)

    // Register all Beam repositories and configuration tweaks
    Repositories.register(project)

    // Apply a plugin which enables configuring projects imported into Intellij.
    project.apply plugin: "idea"

    // Provide code coverage
    // TODO: Should this only apply to Java projects?
    project.apply plugin: "jacoco"
    project.gradle.taskGraph.whenReady { graph ->
      // Disable jacoco unless report requested such that task outputs can be properly cached.
      // https://discuss.gradle.org/t/do-not-cache-if-condition-matched-jacoco-agent-configured-with-append-true-satisfied/23504
      def enabled = graph.allTasks.any { it instanceof JacocoReport || it.name.contains("javaPreCommit") }
      project.tasks.withType(Test) { jacoco.enabled = enabled }
    }

    // Apply a plugin which provides tasks for dependency / property / task reports.
    // See https://docs.gradle.org/current/userguide/project_reports_plugin.html
    // for further details. This is typically very useful to look at the "htmlDependencyReport"
    // when attempting to resolve dependency issues.
    project.apply plugin: "project-report"

    // Apply a plugin which fails the build if there is a dependency on a transitive
    // non-declared dependency, since these can break users (as in BEAM-6558)
    //
    // Though this is Java-specific, it is required to be applied to the root
    // project due to implementation-details of the plugin. It can be enabled/disabled
    // via JavaNatureConfiguration per project. It is disabled by default until we can
    // make all of our deps good.
    project.apply plugin: "ca.cutterslade.analyze"

    // Adds a taskTree task that prints task dependency tree report to the console.
    // Useful for investigating build issues.
    // See: https://github.com/dorongold/gradle-task-tree
    project.apply plugin: "com.dorongold.task-tree"
    project.taskTree { noRepeat = true }

    /** ***********************************************************************************************/
    // Define and export a map dependencies shared across multiple sub-projects.
    //
    // Example usage:
    // configuration {
    //   compile library.java.avro
    //   testCompile library.java.junit
    // }

    // A map of maps containing common libraries used per language. To use:
    // dependencies {
    //   compile library.java.slf4j_api
    // }
    project.ext.library = [
      java : [
        google_cloud_bigtable_client_core           : "com.google.cloud.bigtable:bigtable-client-core:1.8.0",
      ],
    ]

    project.ext.repositories = {
      maven {
        name "testPublicationLocal"
        url "file://${project.rootProject.projectDir}/testPublication/"
      }
      maven {
        url(project.properties['distMgmtSnapshotsUrl'] ?: isRelease(project)
                ? 'https://repository.apache.org/service/local/staging/deploy/maven2'
                : 'https://repository.apache.org/content/repositories/snapshots')
        // We attempt to find and load credentials from ~/.m2/settings.xml file that a user
        // has configured with the Apache release and snapshot staging credentials.
        // <settings>
        //   <servers>
        //     <server>
        //       <id>apache.releases.https</id>
        //       <username>USER_TOKEN</username>
        //       <password>PASS_TOKEN</password>
        //     </server>
        //     <server>
        //       <id>apache.snapshots.https</id>
        //       <username>USER_TOKEN</username>
        //       <password>PASS_TOKEN</password>
        //     </server>
        //   </servers>
        // </settings>
        def settingsXml = new File(System.getProperty('user.home'), '.m2/settings.xml')
        if (settingsXml.exists()) {
          def serverId = (project.properties['distMgmtServerId'] ?: isRelease(project)
                  ? 'apache.releases.https' : 'apache.snapshots.https')
          def m2SettingCreds = new XmlSlurper().parse(settingsXml).servers.server.find { server -> serverId.equals(server.id.text()) }
          if (m2SettingCreds) {
            credentials {
              username m2SettingCreds.username.text()
              password m2SettingCreds.password.text()
            }
          }
        }
      }
    }

    // Configures a project with a default set of plugins that should apply to all Java projects.
    //
    // Users should invoke this method using Groovy map syntax. For example:
    // applyJavaNature(enableSpotbugs: true)
    //
    // See JavaNatureConfiguration for the set of accepted properties.
    //
    // The following plugins are enabled:
    //  * java
    //  * maven
    //  * net.ltgt.apt (plugin to configure annotation processing tool)
    //  * propdeps (provide optional and provided dependency configurations)
    //  * propdeps-maven
    //  * propdeps-idea
    //  * checkstyle
    //  * spotbugs
    //  * shadow (conditional on shadowClosure being specified)
    //  * com.diffplug.gradle.spotless (code style plugin)
    //
    // Dependency Management for Java Projects
    // ---------------------------------------
    //
    // By default, the shadow plugin is not enabled. It is only enabled by specifying a shadowClosure
    // as an argument. If no shadowClosure has been specified, dependencies should fall into the
    // configurations as described within the Gradle documentation (https://docs.gradle.org/current/userguide/java_plugin.html#sec:java_plugin_and_dependency_management)
    //
    // When the shadowClosure argument is specified, the shadow plugin is enabled to perform shading
    // of commonly found dependencies. Because of this it is important that dependencies are added
    // to the correct configuration. Dependencies should fall into one of these four configurations:
    //  * compile     - Required during compilation or runtime of the main source set.
    //                  This configuration represents all dependencies that much also be shaded away
    //                  otherwise the generated Maven pom will be missing this dependency.
    //  * shadow      - Required during compilation or runtime of the main source set.
    //                  Will become a runtime dependency of the generated Maven pom.
    //  * testCompile - Required during compilation or runtime of the test source set.
    //                  This must be shaded away in the shaded test jar.
    //  * shadowTest  - Required during compilation or runtime of the test source set.
    //                  TODO: Figure out whether this should be a test scope dependency
    //                  of the generated Maven pom.
    //
    // When creating a cross-project dependency between two Java projects, one should only rely on
    // the shaded configurations if the project has a shadowClosure being specified. This allows
    // for compilation/test execution to occur against the final artifact that will be provided to
    // users. This is by done by referencing the "shadow" or "shadowTest" configuration as so:
    //   dependencies {
    //     shadow project(path: "other:java:project1", configuration: "shadow")
    //     shadowTest project(path: "other:java:project2", configuration: "shadowTest")
    //   }
    // This will ensure the correct set of transitive dependencies from those projects are correctly
    // added to the main and test source set runtimes.

    project.ext.applyJavaNature = {
      // Use the implicit it parameter of the closure to handle zero argument or one argument map calls.
      JavaNatureConfiguration configuration = it ? it as JavaNatureConfiguration : new JavaNatureConfiguration()

      if (configuration.archivesBaseName) {
        project.archivesBaseName = configuration.archivesBaseName
      }

      project.apply plugin: "java"

      // Configure the Java compiler source language and target compatibility levels. Also ensure that
      // we configure the Java compiler to use UTF-8.
      project.sourceCompatibility = project.javaVersion
      project.targetCompatibility = project.javaVersion

      def defaultLintSuppressions = [
        'options',
        'cast',
        // https://bugs.openjdk.java.net/browse/JDK-8190452
        'classfile',
        'deprecation',
        'fallthrough',
        'processing',
        'rawtypes',
        'serial',
        'try',
        'unchecked',
        'varargs',
      ]

      project.tasks.withType(JavaCompile) {
        options.encoding = "UTF-8"
        // As we want to add '-Xlint:-deprecation' we intentionally remove '-Xlint:deprecation' from compilerArgs here,
        // as intellij is adding this, see https://youtrack.jetbrains.com/issue/IDEA-196615
        options.compilerArgs -= ["-Xlint:deprecation"]
        options.compilerArgs += ([
          '-parameters',
          '-Xlint:all',
          '-Werror',
          '-XepDisableWarningsInGeneratedCode',
          '-XepExcludedPaths:(.*/)?(build/generated-src|build/generated.*avro-java|build/generated)/.*',
          '-Xep:MutableConstantField:OFF' // Guava's immutable collections cannot appear on API surface.
        ]
        + (defaultLintSuppressions + configuration.disableLintWarnings).collect { "-Xlint:-${it}" })
      }

      // Configure the default test tasks set of tests executed
      // to match the equivalent set that is executed by the maven-surefire-plugin.
      // See http://maven.apache.org/components/surefire/maven-surefire-plugin/test-mojo.html
      project.test {
        include "**/Test*.class"
        include "**/*Test.class"
        include "**/*Tests.class"
        include "**/*TestCase.class"
        // fixes issues with test filtering on multi-module project
        // see https://discuss.gradle.org/t/multi-module-build-fails-with-tests-filter/25835
        filter { setFailOnNoMatchingTests(false) }
      }

      project.tasks.withType(Test) {
        // Configure all test tasks to use JUnit
        useJUnit {}
        // default maxHeapSize on gradle 5 is 512m, lets increase to handle more demanding tests
        maxHeapSize = '2g'
      }

      if (configuration.shadowClosure) {
        // Ensure that tests are packaged and part of the artifact set.
        project.task('packageTests', type: Jar) {
          classifier = 'tests-unshaded'
          from project.sourceSets.test.output
        }
        project.artifacts.archives project.packageTests
      }

      // Configures annotation processing for commonly used annotation processors
      // across all Java projects.
      project.apply plugin: "net.ltgt.apt"
      // let idea apt plugin handle the ide integration
      project.apply plugin: "net.ltgt.apt-idea"

      // Note that these plugins specifically use the compileOnly and testCompileOnly
      // configurations because they are never required to be shaded or become a
      // dependency of the output.
      def compileOnlyAnnotationDeps = [
        "com.google.auto.value:auto-value-annotations:1.6.3",
        "com.google.auto.service:auto-service-annotations:1.0-rc6",
        "com.google.j2objc:j2objc-annotations:1.3",
        // These dependencies are needed to avoid error-prone warnings on package-info.java files,
        // also to include the annotations to suppress warnings.
        //
        // spotbugs-annotations artifact is licensed under LGPL and cannot be included in the
        // Apache Beam distribution, but may be relied on during build.
        // See: https://www.apache.org/legal/resolved.html#prohibited
        "com.github.spotbugs:spotbugs-annotations:3.1.12",
        "net.jcip:jcip-annotations:1.0",
      ]

      project.dependencies {
        compileOnlyAnnotationDeps.each { dep ->
          compileOnly dep
          testCompileOnly dep
          annotationProcessor dep
          testAnnotationProcessor dep
        }

        // Add common annotation processors to all Java projects
        def annotationProcessorDeps = [
          "com.google.auto.value:auto-value:1.6.3",
          "com.google.auto.service:auto-service:1.0-rc6",
        ]

        annotationProcessorDeps.each { dep ->
          annotationProcessor dep
          testAnnotationProcessor dep
        }
      }

      // Add the optional and provided configurations for dependencies
      // TODO: Either remove these plugins and find another way to generate the Maven poms
      // with the correct dependency scopes configured.
      project.apply plugin: 'propdeps'
      project.apply plugin: 'propdeps-maven'
      project.apply plugin: 'propdeps-idea'

      // Defines Targets for sonarqube analysis reporting.
      project.apply plugin: "org.sonarqube"

      // Configures a checkstyle plugin enforcing a set of rules and also allows for a set of
      // suppressions.
      project.apply plugin: 'checkstyle'
      project.tasks.withType(Checkstyle) {
        configFile = project.project(":").file("sdks/java/build-tools/src/main/resources/beam/checkstyle.xml")
        configProperties = ["checkstyle.suppressions.file": project.project(":").file("sdks/java/build-tools/src/main/resources/beam/suppressions.xml")]
        showViolations = true
        maxErrors = 0
      }
      project.checkstyle { toolVersion = "8.23" }

      // Configures javadoc plugin and ensure check runs javadoc.
      project.tasks.withType(Javadoc) {
        options.encoding = 'UTF-8'
        options.addBooleanOption('Xdoclint:-missing', true)
      }
      project.check.dependsOn project.javadoc

      // Apply the eclipse and apt-eclipse plugins.  This adds the "eclipse" task and
      // connects the apt-eclipse plugin to update the eclipse project files
      // with the instructions needed to run apt within eclipse to handle the AutoValue
      // and additional annotations
      project.apply plugin: 'eclipse'
      project.apply plugin: "net.ltgt.apt-eclipse"

      // Enables a plugin which can apply code formatting to source.
      project.apply plugin: "com.diffplug.gradle.spotless"
      // scan CVE
      project.apply plugin: "net.ossindex.audit"
      project.audit { rateLimitAsError = false }
      // Spotless can be removed from the 'check' task by passing -PdisableSpotlessCheck=true on the Gradle
      // command-line. This is useful for pre-commit which runs spotless separately.
      def disableSpotlessCheck = project.hasProperty('disableSpotlessCheck') &&
              project.disableSpotlessCheck == 'true'
      project.spotless {
        enforceCheck !disableSpotlessCheck
        java {
          licenseHeader javaLicenseHeader
          googleJavaFormat('1.7')
          target project.fileTree(project.projectDir) { include 'src/*/java/**/*.java' }
        }
      }

      // Enables a plugin which performs code analysis for common bugs.
      // This plugin is configured to only analyze the "main" source set.
      if (configuration.enableSpotbugs) {
        project.apply plugin: 'com.github.spotbugs'
        project.dependencies {
          spotbugs "com.github.spotbugs:spotbugs:3.1.12"
          spotbugs "com.google.auto.value:auto-value:1.6.3"
          compileOnlyAnnotationDeps.each { dep -> spotbugs dep }
        }
        project.spotbugs {
          excludeFilter = project.rootProject.file('sdks/java/build-tools/src/main/resources/beam/spotbugs-filter.xml')
          sourceSets = [sourceSets.main]
        }
      }

      // Disregard unused but declared (test) compile only dependencies used
      // for common annotation classes used during compilation such as annotation
      // processing or post validation such as spotbugs.
      project.dependencies {
        compileOnlyAnnotationDeps.each { dep ->
          permitUnusedDeclared dep
          permitTestUnusedDeclared dep
        }
      }
      if (configuration.enableStrictDependencies) {
        project.tasks.analyzeClassesDependencies.enabled = true
        project.tasks.analyzeDependencies.enabled = true
        project.tasks.analyzeTestClassesDependencies.enabled = false
      } else {
        project.tasks.analyzeClassesDependencies.enabled = false
        project.tasks.analyzeTestClassesDependencies.enabled = false
        project.tasks.analyzeDependencies.enabled = false
      }

      // Enable errorprone static analysis
      project.apply plugin: 'net.ltgt.errorprone'

      project.configurations.errorprone { resolutionStrategy.force 'com.google.errorprone:error_prone_core:2.3.1' }

      if (configuration.shadowClosure) {
        // Enables a plugin which can perform shading of classes. See the general comments
        // above about dependency management for Java projects and how the shadow plugin
        // is expected to be used for the different Gradle configurations.
        //
        // TODO: Enforce all relocations are always performed to:
        // getJavaRelocatedPath(package_suffix) where package_suffix is something like "com.google.commmon"
        project.apply plugin: 'com.github.johnrengelman.shadow'

        // Create a new configuration 'shadowTest' like 'shadow' for the test scope
        project.configurations {
          shadow { description = "Dependencies for shaded source set 'main'" }
          compile.extendsFrom shadow
          shadowTest {
            description = "Dependencies for shaded source set 'test'"
            extendsFrom shadow
          }
          testCompile.extendsFrom shadowTest
        }
      }

      project.ext.includeInJavaBom = configuration.publish
      project.ext.exportJavadoc = configuration.exportJavadoc

      if ((isRelease(project) || project.hasProperty('publishing')) &&
      configuration.publish) {
        project.apply plugin: "maven-publish"

        // Create a task which emulates the maven-archiver plugin in generating a
        // pom.properties file.
        def pomPropertiesFile = "${project.buildDir}/publications/mavenJava/pom.properties"
        project.task('generatePomPropertiesFileForMavenJavaPublication') {
          outputs.file "${pomPropertiesFile}"
          doLast {
            new File("${pomPropertiesFile}").text =
                    """version=${project.version}
                       groupId=${project.mavenGroupId}
                       artifactId=${project.archivesBaseName}"""
          }
        }

        // Have the main artifact jar include both the generate pom.xml and its properties file
        // emulating the behavior of the maven-archiver plugin.
        project.(configuration.shadowClosure ? 'shadowJar' : 'jar') {
          def pomFile = "${project.buildDir}/publications/mavenJava/pom-default.xml"

          // Validate that the artifacts exist before copying them into the jar.
          doFirst {
            if (!project.file("${pomFile}").exists()) {
              throw new GradleException("Expected ${pomFile} to have been generated by the 'generatePomFileForMavenJavaPublication' task.")
            }
            if (!project.file("${pomPropertiesFile}").exists()) {
              throw new GradleException("Expected ${pomPropertiesFile} to have been generated by the 'generatePomPropertiesFileForMavenJavaPublication' task.")
            }
          }

          dependsOn 'generatePomFileForMavenJavaPublication'
          into("META-INF/maven/${project.mavenGroupId}/${project.archivesBaseName}") {
            from "${pomFile}"
            rename('.*', 'pom.xml')
          }

          dependsOn project.generatePomPropertiesFileForMavenJavaPublication
          into("META-INF/maven/${project.mavenGroupId}/${project.archivesBaseName}") { from "${pomPropertiesFile}" }
        }

        // Only build artifacts for archives if we are publishing
        if (configuration.shadowClosure) {
          project.artifacts.archives project.shadowJar
          project.artifacts.archives project.shadowTestJar
        } else {
          project.artifacts.archives project.jar
          project.artifacts.archives project.testJar
        }

        project.task('sourcesJar', type: Jar) {
          from project.sourceSets.main.allSource
          classifier = 'sources'
        }
        project.artifacts.archives project.sourcesJar

        project.task('testSourcesJar', type: Jar) {
          from project.sourceSets.test.allSource
          classifier = 'test-sources'
        }
        project.artifacts.archives project.testSourcesJar

        project.task('javadocJar', type: Jar, dependsOn: project.javadoc) {
          classifier = 'javadoc'
          from project.javadoc.destinationDir
        }
        project.artifacts.archives project.javadocJar

        project.publishing {
          repositories project.ext.repositories

          publications {
            mavenJava(MavenPublication) {
              if (configuration.shadowClosure) {
                artifact project.shadowJar
                artifact project.shadowTestJar
              } else {
                artifact project.jar
                artifact project.testJar
              }
              artifact project.sourcesJar
              artifact project.testSourcesJar
              artifact project.javadocJar

              artifactId = project.archivesBaseName
              groupId = project.mavenGroupId

              pom {
                name = project.description
                if (project.hasProperty("summary")) {
                  description = project.summary
                }
                url = "http://beam.apache.org"
                inceptionYear = "2016"
                licenses {
                  license {
                    name = "Apache License, Version 2.0"
                    url = "http://www.apache.org/licenses/LICENSE-2.0.txt"
                    distribution = "repo"
                  }
                }
                scm {
                  connection = "scm:git:https://gitbox.apache.org/repos/asf/beam.git"
                  developerConnection = "scm:git:https://gitbox.apache.org/repos/asf/beam.git"
                  url = "https://gitbox.apache.org/repos/asf?p=beam.git;a=summary"
                }
                issueManagement {
                  system = "jira"
                  url = "https://issues.apache.org/jira/browse/BEAM"
                }
                mailingLists {
                  mailingList {
                    name = "Beam Dev"
                    subscribe = "dev-subscribe@beam.apache.org"
                    unsubscribe = "dev-unsubscribe@beam.apache.org"
                    post = "dev@beam.apache.org"
                    archive = "http://www.mail-archive.com/dev%beam.apache.org"
                  }
                  mailingList {
                    name = "Beam User"
                    subscribe = "user-subscribe@beam.apache.org"
                    unsubscribe = "user-unsubscribe@beam.apache.org"
                    post = "user@beam.apache.org"
                    archive = "http://www.mail-archive.com/user%beam.apache.org"
                  }
                  mailingList {
                    name = "Beam Commits"
                    subscribe = "commits-subscribe@beam.apache.org"
                    unsubscribe = "commits-unsubscribe@beam.apache.org"
                    post = "commits@beam.apache.org"
                    archive = "http://www.mail-archive.com/commits%beam.apache.org"
                  }
                }
                developers {
                  developer {
                    name = "The Apache Beam Team"
                    email = "dev@beam.apache.org"
                    url = "http://beam.apache.org"
                    organization = "Apache Software Foundation"
                    organizationUrl = "http://www.apache.org"
                  }
                }
              }

              pom.withXml {
                def root = asNode()
                def dependenciesNode = root.appendNode('dependencies')
                def generateDependenciesFromConfiguration = { param ->
                  project.configurations."${param.configuration}".allDependencies.each {
                    def dependencyNode = dependenciesNode.appendNode('dependency')
                    def appendClassifier = { dep ->
                      dep.artifacts.each { art ->
                        if (art.hasProperty('classifier')) {
                          dependencyNode.appendNode('classifier', art.classifier)
                        }
                      }
                    }

                    if (it instanceof ProjectDependency) {
                      dependencyNode.appendNode('groupId', it.getDependencyProject().mavenGroupId)
                      dependencyNode.appendNode('artifactId', it.getDependencyProject().archivesBaseName)
                      dependencyNode.appendNode('version', it.version)
                      dependencyNode.appendNode('scope', param.scope)
                      appendClassifier(it)
                    } else {
                      dependencyNode.appendNode('groupId', it.group)
                      dependencyNode.appendNode('artifactId', it.name)
                      dependencyNode.appendNode('version', it.version)
                      dependencyNode.appendNode('scope', param.scope)
                      appendClassifier(it)
                    }

                    // Start with any exclusions that were added via configuration exclude rules.
                    // Then add all the exclusions that are specific to the dependency (if any
                    // were declared). Finally build the node that represents all exclusions.
                    def exclusions = []
                    exclusions += project.configurations."${param.configuration}".excludeRules
                    if (it.hasProperty('excludeRules')) {
                      exclusions += it.excludeRules
                    }
                    if (!exclusions.empty) {
                      def exclusionsNode = dependencyNode.appendNode('exclusions')
                      exclusions.each { exclude ->
                        def exclusionNode = exclusionsNode.appendNode('exclusion')
                        exclusionNode.appendNode('groupId', exclude.group)
                        exclusionNode.appendNode('artifactId', exclude.module)
                      }
                    }
                  }
                }

                // TODO: Should we use the runtime scope instead of the compile scope
                // which forces all our consumers to declare what they consume?
                generateDependenciesFromConfiguration(
                        configuration: (configuration.shadowClosure ? 'shadow' : 'compile'), scope: 'compile')
                generateDependenciesFromConfiguration(configuration: 'provided', scope: 'provided')

                // NB: This must come after asNode() logic, as it seems asNode()
                // removes XML comments.
                // TODO: Load this from file?
                def elem = asElement()
                def hdr = elem.getOwnerDocument().createComment(
                        '''
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at
      http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
''')
                elem.insertBefore(hdr, elem.getFirstChild())
              }
            }
          }
        }
        // Only sign artifacts if we are performing a release
        if (isRelease(project) && !project.hasProperty('noSigning')) {
          project.apply plugin: "signing"
          project.signing {
            useGpgCmd()
            sign project.publishing.publications
          }
        }
      }

      // Force usage of the libraries defined within our common set found in the root
      // build.gradle instead of using Gradles default dependency resolution mechanism
      // which chooses the latest version available.
      //
      // TODO: Figure out whether we should force all dependency conflict resolution
      // to occur in the "shadow" and "shadowTest" configurations.
      project.configurations.all { config ->
        // The "errorprone" configuration controls the classpath used by errorprone static analysis, which
        // has different dependencies than our project.
        if (config.getName() != "errorprone") {
          config.resolutionStrategy {
            force project.library.java.values()
          }
        }
      }
    }
  }
}
