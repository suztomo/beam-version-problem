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

  /** A class defining the set of configurable properties accepted by applyPortabilityNature. */
  class PortabilityNatureConfiguration {
    /**
     * The set of excludes that should be used during validation of the shadow jar. Projects should override
     * the default with the most specific set of excludes that is valid for the contents of its shaded jar.
     *
     * By default we exclude any class underneath the org.apache.beam namespace.
     */
    List<String> shadowJarValidationExcludes = ["org/apache/beam/**"]

    /** Override the default "beam-" + `dash separated path` archivesBaseName. */
    String archivesBaseName = null;

    /** Controls whether this project is published to Maven. */
    boolean publish = true

    /**
     * Automatic-Module-Name Header value to be set in MANFIEST.MF file.
     * This is a required parameter unless publishing to Maven is disabled for this project.
     *
     * @see: https://github.com/GoogleCloudPlatform/cloud-opensource-java/blob/master/library-best-practices/JLBP-20.md
     */
    String automaticModuleName
  }

  // A class defining the set of configurable properties for createJavaExamplesArchetypeValidationTask
  class JavaExamplesArchetypeValidationConfiguration {
    // Type [Quickstart, MobileGaming] for the postrelease validation is required.
    // Used both for the test name run${type}Java${runner}
    // and also for the script name, ${type}-java-${runner}.toLowerCase().
    String type

    // runner [Direct, Dataflow, Spark, Flink, FlinkLocal, Apex]
    String runner

    // gcpProject sets the gcpProject argument when executing examples.
    String gcpProject

    // gcsBucket sets the gcsProject argument when executing examples.
    String gcsBucket

    // bqDataset sets the BigQuery Dataset when executing mobile-gaming examples
    String bqDataset

    // pubsubTopic sets topics when executing streaming pipelines
    String pubsubTopic
  }

  // Reads and contains all necessary performance test parameters
  class JavaPerformanceTestConfiguration {
    // Optional. Runner which will be used for running the tests. Possible values: dataflow/direct.
    // PerfKitBenchmarker will have trouble reading 'null' value. It expects empty string if no config file is expected.
    String runner = System.getProperty('integrationTestRunner', '')

    // Optional. Filesystem which will be used for running the tests. Possible values: hdfs.
    // if not specified runner's local filesystem will be used.
    String filesystem = System.getProperty('filesystem')

    // Required. Pipeline options to be used by the tested pipeline.
    String integrationTestPipelineOptions = System.getProperty('integrationTestPipelineOptions')
  }

  // Reads and contains all necessary performance test parameters
  class PythonPerformanceTestConfiguration {
    // Fully qualified name of the test to run.
    String tests = System.getProperty('tests')

    // Attribute tag that can filter the test set.
    String attribute = System.getProperty('attr')

    // Extra test options pass to nose.
    String[] extraTestOptions = ["--nocapture"]

    // Name of Cloud KMS encryption key to use in some tests.
    String kmsKeyName = System.getProperty('kmsKeyName')

    // Pipeline options to be used for pipeline invocation.
    String pipelineOptions = System.getProperty('pipelineOptions', '')
  }

  // A class defining the set of configurable properties accepted by containerImageName.
  class ContainerImageNameConfiguration {
    String root = null // Sets the docker repository root (optional).
    String name = null // Sets the short container image name, such as "go" (required).
    String tag = null // Sets the image tag (optional).
  }

  // A class defining the configuration for PortableValidatesRunner.
  class PortableValidatesRunnerConfiguration {
    // Task name for validate runner case.
    String name = 'validatesPortableRunner'
    // Fully qualified JobServerClass name to use.
    String jobServerDriver
    // A string representing the jobServer Configuration.
    String jobServerConfig
    // Number of parallel test runs.
    Integer numParallelTests = 1
    // Extra options to pass to TestPipeline
    String[] pipelineOpts = []
    // Spin up the Harness inside a DOCKER container
    Environment environment = Environment.DOCKER
    // Categories for tests to run.
    Closure testCategories = {
      includeCategories 'org.apache.beam.sdk.testing.ValidatesRunner'
      // Use the following to include / exclude categories:
      // includeCategories 'org.apache.beam.sdk.testing.ValidatesRunner'
      // excludeCategories 'org.apache.beam.sdk.testing.FlattenWithHeterogeneousCoders'
    }
    // Configuration for the classpath when running the test.
    Configuration testClasspathConfiguration
    // Additional system properties.
    Properties systemProperties = []

    enum Environment {
      DOCKER,   // Docker-based Harness execution
      PROCESS,  // Process-based Harness execution
      EMBEDDED, // Execute directly inside the execution engine (testing only)
    }
  }

  // A class defining the configuration for CrossLanguageValidatesRunner.
  class CrossLanguageValidatesRunnerConfiguration {
    // Task name for cross-language validate runner case.
    String name = 'validatesCrossLanguageRunner'
    // Fully qualified JobServerClass name to use.
    String jobServerDriver
    // A string representing the jobServer Configuration.
    String jobServerConfig
    // Number of parallel test runs.
    Integer numParallelTests = 1
    // Extra options to pass to TestPipeline
    String[] pipelineOpts = []
    // Categories for tests to run.
    Closure testCategories = {
      includeCategories 'org.apache.beam.sdk.testing.UsesCrossLanguageTransforms'
      // Use the following to include / exclude categories:
      // includeCategories 'org.apache.beam.sdk.testing.ValidatesRunner'
      // excludeCategories 'org.apache.beam.sdk.testing.FlattenWithHeterogeneousCoders'
    }
    // Configuration for the classpath when running the test.
    Configuration testClasspathConfiguration
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
      groovy: [
        groovy_all: "org.codehaus.groovy:groovy-all:2.4.13",
      ],
      // For generating pom.xml from archetypes.
      maven: [
        maven_compiler_plugin: "maven-plugins:maven-compiler-plugin:3.7.0",
        maven_exec_plugin    : "maven-plugins:maven-exec-plugin:1.6.0",
        maven_jar_plugin     : "maven-plugins:maven-jar-plugin:3.0.2",
        maven_shade_plugin   : "maven-plugins:maven-shade-plugin:3.1.0",
        maven_surefire_plugin: "maven-plugins:maven-surefire-plugin:2.21.0",
      ],
    ]

    /** ***********************************************************************************************/

    // Returns a string representing the relocated path to be used with the shadow plugin when
    // given a suffix such as "com.google.common".
    project.ext.getJavaRelocatedPath = { String suffix ->
      return ("org.apache.beam.repackaged."
              + project.name.replace("-", "_")
              + "."
              + suffix)
    }

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

      // Ban these dependencies from all configurations
      project.configurations.all {
        // guava-jdk5 brings in classes which conflict with guava
        exclude group: "com.google.guava", module: "guava-jdk5"
        // Ban the usage of the JDK tools as a library as this is system dependent
        exclude group: "jdk.tools", module: "jdk.tools"
        // protobuf-lite duplicates classes which conflict with protobuf-java
        exclude group: "com.google.protobuf", module: "protobuf-lite"
        // Exclude these test dependencies because they bundle other common
        // test libraries classes causing version conflicts. Users should rely
        // on using the yyy-core package instead of the yyy-all package.
        exclude group: "org.hamcrest", module: "hamcrest-all"
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

    // When applied in a module's build.gradle file, this closure provides task for running
    // IO integration tests.
    project.ext.enableJavaPerformanceTesting = {

      // Use the implicit it parameter of the closure to handle zero argument or one argument map calls.
      // See: http://groovy-lang.org/closures.html#implicit-it
      JavaPerformanceTestConfiguration configuration = it ? it as JavaPerformanceTestConfiguration : new JavaPerformanceTestConfiguration()

      // Task for running integration tests
      project.task('integrationTest', type: Test) {

        // Disable Gradle cache (it should not be used because the IT's won't run).
        outputs.upToDateWhen { false }

        include "**/*IT.class"

        def pipelineOptionsString = configuration.integrationTestPipelineOptions
        if(pipelineOptionsString && configuration.runner?.equalsIgnoreCase('dataflow')) {
          project.evaluationDependsOn(":runners:google-cloud-dataflow-java:worker:legacy-worker")
          def allOptionsList = (new JsonSlurper()).parseText(pipelineOptionsString)
          def dataflowWorkerJar = project.findProperty('dataflowWorkerJar') ?:
                  project.project(":runners:google-cloud-dataflow-java:worker:legacy-worker").shadowJar.archivePath

          allOptionsList.addAll([
            '--workerHarnessContainerImage=',
            '--dataflowWorkerJar=${dataflowWorkerJar}',
          ])

          pipelineOptionsString = JsonOutput.toJson(allOptionsList)
        }

        systemProperties.beamTestPipelineOptions = pipelineOptionsString
      }
    }

    // When applied in a module's build.gradle file, this closure adds task providing
    // additional dependencies that might be needed while running integration tests.
    project.ext.provideIntegrationTestingDependencies = {

      // Use the implicit it parameter of the closure to handle zero argument or one argument map calls.
      // See: http://groovy-lang.org/closures.html#implicit-it
      JavaPerformanceTestConfiguration configuration = it ? it as JavaPerformanceTestConfiguration : new JavaPerformanceTestConfiguration()

      project.dependencies {
        def runner = configuration.runner
        def filesystem = configuration.filesystem

        /* include dependencies required by runners */
        //if (runner?.contains('dataflow')) {
        if (runner?.equalsIgnoreCase('dataflow')) {
          testRuntime it.project(path: ":runners:google-cloud-dataflow-java", configuration: 'testRuntime')
          testRuntime it.project(path: ":runners:google-cloud-dataflow-java:worker:legacy-worker", configuration: 'shadow')
        }

        if (runner?.equalsIgnoreCase('direct')) {
          testRuntime it.project(path: ":runners:direct-java", configuration: 'shadowTest')
        }

        if (runner?.equalsIgnoreCase('flink')) {
          testRuntime it.project(path: ":runners:flink:1.9", configuration: 'testRuntime')
        }

        if (runner?.equalsIgnoreCase('spark')) {
          testRuntime it.project(path: ":runners:spark", configuration: 'testRuntime')
          testRuntime project.library.java.spark_core
          testRuntime project.library.java.spark_streaming

          // Testing the Spark runner causes a StackOverflowError if slf4j-jdk14 is on the classpath
          project.configurations.testRuntimeClasspath {
            exclude group: "org.slf4j", module: "slf4j-jdk14"
          }
        }

        /* include dependencies required by filesystems */
        if (filesystem?.equalsIgnoreCase('hdfs')) {
          testRuntime it.project(path: ":sdks:java:io:hadoop-file-system", configuration: 'testRuntime')
          testRuntime project.library.java.hadoop_client
        }

        /* include dependencies required by AWS S3 */
        if (filesystem?.equalsIgnoreCase('s3')) {
          testRuntime it.project(path: ":sdks:java:io:amazon-web-services", configuration: 'testRuntime')
        }
      }
      project.task('packageIntegrationTests', type: Jar)
    }

    /** ***********************************************************************************************/

    project.ext.applyGoNature = {
      // Define common lifecycle tasks and artifact types
      project.apply plugin: 'base'

      project.apply plugin: "com.github.blindpirate.gogradle"
      project.golang { goVersion = '1.12' }

      project.repositories {
        golang {
          // Gogradle doesn't like thrift: https://github.com/gogradle/gogradle/issues/183
          root 'git.apache.org/thrift.git'
          emptyDir()
        }
        golang {
          root 'github.com/apache/thrift'
          emptyDir()
        }
        project.clean.dependsOn project.goClean
        project.check.dependsOn project.goCheck
        project.assemble.dependsOn project.goBuild
      }

      project.idea {
        module {
          // The gogradle plugin downloads all dependencies into the source tree here,
          // which is a path baked into golang
          excludeDirs += project.file("${project.path}/vendor")

          // gogradle's private working directory
          excludeDirs += project.file("${project.path}/.gogradle")
        }
      }
    }

    /** ***********************************************************************************************/

    project.ext.applyDockerNature = {
      project.apply plugin: "com.palantir.docker"
      project.docker { noCache true }
    }

    /** ***********************************************************************************************/

    project.ext.applyGroovyNature = {
      project.apply plugin: "groovy"

      project.apply plugin: "com.diffplug.gradle.spotless"
      project.spotless {
        def grEclipseConfig = project.project(":").file("buildSrc/greclipse.properties")
        groovy {
          licenseHeader javaLicenseHeader
          paddedCell() // Recommended to avoid cyclic ambiguity issues
          greclipse().configFile(grEclipseConfig)
        }
        groovyGradle { greclipse().configFile(grEclipseConfig) }
      }
    }

    // containerImageName returns a configurable container image name, by default a
    // development image at docker.io (see sdks/CONTAINERS.md):
    //
    //     format: apachebeam/$NAME_sdk:latest
    //     ie: apachebeam/python2.7_sdk:latest apachebeam/java_sdk:latest apachebeam/go_sdk:latest
    //
    // Both the root and tag can be defined using properties or explicitly provided.
    project.ext.containerImageName = {
      // Use the implicit it parameter of the closure to handle zero argument or one argument map calls.
      ContainerImageNameConfiguration configuration = it ? it as ContainerImageNameConfiguration : new ContainerImageNameConfiguration()

      if (configuration.root == null) {
        if (project.rootProject.hasProperty(["docker-repository-root"])) {
          configuration.root = project.rootProject["docker-repository-root"]
        } else {
          configuration.root = "${System.properties["user.name"]}-docker-apache.bintray.io/beam"
        }
      }
      if (configuration.tag == null) {
        if (project.rootProject.hasProperty(["docker-tag"])) {
          configuration.tag = project.rootProject["docker-tag"]
        } else {
          configuration.tag = 'latest'
        }
      }
      return "${configuration.root}/${configuration.name}:${configuration.tag}"
    }

    /** ***********************************************************************************************/

    project.ext.applyGrpcNature = {
      project.apply plugin: "com.google.protobuf"
      project.protobuf {
        protoc {
          // The artifact spec for the Protobuf Compiler
          artifact = "com.google.protobuf:protoc:3.6.0" }

        // Configure the codegen plugins
        plugins {
          // An artifact spec for a protoc plugin, with "grpc" as
          // the identifier, which can be referred to in the "plugins"
          // container of the "generateProtoTasks" closure.
          grpc { artifact = "io.grpc:protoc-gen-grpc-java:1.13.1" }
        }

        generateProtoTasks {
          ofSourceSet("main")*.plugins {
            // Apply the "grpc" plugin whose spec is defined above, without
            // options.  Note the braces cannot be omitted, otherwise the
            // plugin will not be added. This is because of the implicit way
            // NamedDomainObjectContainer binds the methods.
            grpc {}
          }
        }
      }

      def generatedProtoMainJavaDir = "${project.buildDir}/generated/source/proto/main/java"
      def generatedProtoTestJavaDir = "${project.buildDir}/generated/source/proto/test/java"
      def generatedGrpcMainJavaDir = "${project.buildDir}/generated/source/proto/main/grpc"
      def generatedGrpcTestJavaDir = "${project.buildDir}/generated/source/proto/test/grpc"
      project.idea {
        module {
          sourceDirs += project.file(generatedProtoMainJavaDir)
          generatedSourceDirs += project.file(generatedProtoMainJavaDir)

          testSourceDirs += project.file(generatedProtoTestJavaDir)
          generatedSourceDirs += project.file(generatedProtoTestJavaDir)

          sourceDirs += project.file(generatedGrpcMainJavaDir)
          generatedSourceDirs += project.file(generatedGrpcMainJavaDir)

          testSourceDirs += project.file(generatedGrpcTestJavaDir)
          generatedSourceDirs += project.file(generatedGrpcTestJavaDir)
        }
      }
    }

    /** ***********************************************************************************************/

    project.ext.applyPortabilityNature = {
      PortabilityNatureConfiguration configuration = it ? it as PortabilityNatureConfiguration : new PortabilityNatureConfiguration()

      if (configuration.archivesBaseName) {
        project.archivesBaseName = configuration.archivesBaseName
      }

      project.ext.applyJavaNature(
              exportJavadoc: false,
              enableSpotbugs: false,
              publish: configuration.publish,
              archivesBaseName: configuration.archivesBaseName,
              automaticModuleName: configuration.automaticModuleName,
              shadowJarValidationExcludes: it.shadowJarValidationExcludes,
              shadowClosure: GrpcVendoring.shadowClosure() << {
                // We perform all the code relocations but don't include
                // any of the actual dependencies since they will be supplied
                // by org.apache.beam:beam-vendor-grpc-v1p21p0:0.1
                dependencies {
                  include(dependency { return false })
                }
              })

      // Don't force modules here because we don't want to take the shared declarations in build_rules.gradle
      // because we would like to have the freedom to choose which versions of dependencies we
      // are using for the portability APIs separate from what is being used inside other modules such as GCP.
      project.configurations.all { config ->
        config.resolutionStrategy { forcedModules = []}
      }

      project.apply plugin: "com.google.protobuf"
      project.protobuf {
        protoc {
          // The artifact spec for the Protobuf Compiler
          artifact = "com.google.protobuf:protoc:3.7.1" }

        // Configure the codegen plugins
        plugins {
          // An artifact spec for a protoc plugin, with "grpc" as
          // the identifier, which can be referred to in the "plugins"
          // container of the "generateProtoTasks" closure.
          grpc { artifact = "io.grpc:protoc-gen-grpc-java:1.21.0" }
        }

        generateProtoTasks {
          ofSourceSet("main")*.plugins {
            // Apply the "grpc" plugin whose spec is defined above, without
            // options.  Note the braces cannot be omitted, otherwise the
            // plugin will not be added. This is because of the implicit way
            // NamedDomainObjectContainer binds the methods.
            grpc { }
          }
        }
      }

      project.dependencies GrpcVendoring.dependenciesClosure() << { shadow project.ext.library.java.vendored_grpc_1_21_0 }
    }

    /** ***********************************************************************************************/

    // TODO: Decide whether this should be inlined into the one project that relies on it
    // or be left here.
    project.ext.applyAvroNature = { project.apply plugin: "com.commercehub.gradle.plugin.avro" }

    project.ext.applyAntlrNature = {
      project.apply plugin: 'antlr'
      project.idea {
        module {
          // mark antlrs output folders as generated
          generatedSourceDirs += project.generateGrammarSource.outputDirectory
          generatedSourceDirs += project.generateTestGrammarSource.outputDirectory
        }
      }
    }

    // Creates a task to run the quickstart for a runner.
    // Releases version and URL, can be overriden for a RC release with
    // ./gradlew :release:runJavaExamplesValidationTask -Pver=2.3.0 -Prepourl=https://repository.apache.org/content/repositories/orgapachebeam-1027
    project.ext.createJavaExamplesArchetypeValidationTask = {
      JavaExamplesArchetypeValidationConfiguration config = it as JavaExamplesArchetypeValidationConfiguration
      def taskName = "run${config.type}Java${config.runner}"
      def releaseVersion = project.findProperty('ver') ?: project.version
      def releaseRepo = project.findProperty('repourl') ?: 'https://repository.apache.org/content/repositories/snapshots'
      def argsNeeded = [
        "--ver=${releaseVersion}",
        "--repourl=${releaseRepo}"
      ]
      if (config.gcpProject) {
        argsNeeded.add("--gcpProject=${config.gcpProject}")
      }
      if (config.gcsBucket) {
        argsNeeded.add("--gcsBucket=${config.gcsBucket}")
      }
      if (config.bqDataset) {
        argsNeeded.add("--bqDataset=${config.bqDataset}")
      }
      if (config.pubsubTopic) {
        argsNeeded.add("--pubsubTopic=${config.pubsubTopic}")
      }
      project.evaluationDependsOn(':release')
      project.task(taskName, dependsOn: ':release:classes', type: JavaExec) {
        group = "Verification"
        description = "Run the Beam ${config.type} with the ${config.runner} runner"
        main = "${config.type}-java-${config.runner}".toLowerCase()
        classpath = project.project(':release').sourceSets.main.runtimeClasspath
        args argsNeeded
      }
    }


    /** ***********************************************************************************************/

    // Method to create the PortableValidatesRunnerTask.
    // The method takes PortableValidatesRunnerConfiguration as parameter.
    project.ext.createPortableValidatesRunnerTask = {
      /*
       * We need to rely on manually specifying these evaluationDependsOn to ensure that
       * the following projects are evaluated before we evaluate this project. This is because
       * we are attempting to reference the "sourceSets.test.output" directly.
       */
      project.evaluationDependsOn(":sdks:java:core")
      project.evaluationDependsOn(":runners:core-java")
      def config = it ? it as PortableValidatesRunnerConfiguration : new PortableValidatesRunnerConfiguration()
      def name = config.name
      def beamTestPipelineOptions = [
        "--runner=org.apache.beam.runners.portability.testing.TestPortableRunner",
        "--jobServerDriver=${config.jobServerDriver}",
        "--environmentCacheMillis=10000"
      ]
      beamTestPipelineOptions.addAll(config.pipelineOpts)
      if (config.environment == PortableValidatesRunnerConfiguration.Environment.EMBEDDED) {
        beamTestPipelineOptions += "--defaultEnvironmentType=EMBEDDED"
      }
      if (config.jobServerConfig) {
        beamTestPipelineOptions.add("--jobServerConfig=${config.jobServerConfig}")
      }
      config.systemProperties.put("beamTestPipelineOptions", JsonOutput.toJson(beamTestPipelineOptions))
      project.tasks.create(name: name, type: Test) {
        group = "Verification"
        description = "Validates the PortableRunner with JobServer ${config.jobServerDriver}"
        systemProperties config.systemProperties
        classpath = config.testClasspathConfiguration
        testClassesDirs = project.files(project.project(":sdks:java:core").sourceSets.test.output.classesDirs, project.project(":runners:core-java").sourceSets.test.output.classesDirs)
        maxParallelForks config.numParallelTests
        useJUnit(config.testCategories)
        // increase maxHeapSize as this is directly correlated to direct memory,
        // see https://issues.apache.org/jira/browse/BEAM-6698
        maxHeapSize = '4g'
        if (config.environment == PortableValidatesRunnerConfiguration.Environment.DOCKER) {
          dependsOn ':sdks:java:container:docker'
        }
      }
    }

    /** ***********************************************************************************************/

    // Method to create the crossLanguageValidatesRunnerTask.
    // The method takes crossLanguageValidatesRunnerConfiguration as parameter.
    project.ext.createCrossLanguageValidatesRunnerTask = {
      def config = it ? it as CrossLanguageValidatesRunnerConfiguration : new CrossLanguageValidatesRunnerConfiguration()

      project.evaluationDependsOn(":sdks:python")
      project.evaluationDependsOn(":sdks:java:testing:expansion-service")
      project.evaluationDependsOn(":runners:core-construction-java")

      // Task for launching expansion services
      def envDir = project.project(":sdks:python").envdir
      def pythonDir = project.project(":sdks:python").projectDir
      def javaPort = startingExpansionPortNumber.getAndDecrement()
      def pythonPort = startingExpansionPortNumber.getAndDecrement()
      def expansionJar = project.project(':sdks:java:testing:expansion-service').buildTestExpansionServiceJar.archivePath
      def expansionServiceOpts = [
        "group_id": project.name,
        "java_expansion_service_jar": expansionJar,
        "java_port": javaPort,
        "python_virtualenv_dir": envDir,
        "python_expansion_service_module": "apache_beam.runners.portability.expansion_service_test",
        "python_port": pythonPort
      ]
      def serviceArgs = project.project(':sdks:python').mapToArgString(expansionServiceOpts)
      def setupTask = project.tasks.create(name: config.name+"Setup", type: Exec) {
        dependsOn ':sdks:java:container:docker'
        dependsOn ':sdks:python:container:py2:docker'
        dependsOn ':sdks:java:testing:expansion-service:buildTestExpansionServiceJar'
        dependsOn ":sdks:python:installGcpTest"
        // setup test env
        executable 'sh'
        args '-c', "$pythonDir/scripts/run_expansion_services.sh stop --group_id ${project.name} && $pythonDir/scripts/run_expansion_services.sh start $serviceArgs"
      }

      def mainTask = project.tasks.create(name: config.name) {
        group = "Verification"
        description = "Validates cross-language capability of runner"
      }

      def cleanupTask = project.tasks.create(name: config.name+'Cleanup', type: Exec) {
        // teardown test env
        executable 'sh'
        args '-c', "$pythonDir/scripts/run_expansion_services.sh stop --group_id ${project.name}"
      }
      setupTask.finalizedBy cleanupTask

      // Task for running testcases in Java SDK
      def beamJavaTestPipelineOptions = [
        "--runner=org.apache.beam.runners.portability.testing.TestPortableRunner",
        "--jobServerDriver=${config.jobServerDriver}",
        "--environmentCacheMillis=10000"
      ]
      beamJavaTestPipelineOptions.addAll(config.pipelineOpts)
      if (config.jobServerConfig) {
        beamJavaTestPipelineOptions.add("--jobServerConfig=${config.jobServerConfig}")
      }
      ['Java': javaPort, 'Python': pythonPort].each { sdk, port ->
        def javaTask = project.tasks.create(name: config.name+"JavaUsing"+sdk, type: Test) {
          group = "Verification"
          description = "Validates runner for cross-language capability of using ${sdk} transforms from Java SDK"
          systemProperty "beamTestPipelineOptions", JsonOutput.toJson(beamJavaTestPipelineOptions)
          systemProperty "expansionPort", port
          classpath = config.testClasspathConfiguration
          testClassesDirs = project.files(project.project(":runners:core-construction-java").sourceSets.test.output.classesDirs)
          maxParallelForks config.numParallelTests
          useJUnit(config.testCategories)
          // increase maxHeapSize as this is directly correlated to direct memory,
          // see https://issues.apache.org/jira/browse/BEAM-6698
          maxHeapSize = '4g'
          dependsOn setupTask
        }
        mainTask.dependsOn javaTask
        cleanupTask.mustRunAfter javaTask

        // Task for running testcases in Python SDK
        def testOpts = [
          "--attr=UsesCrossLanguageTransforms"
        ]
        def pipelineOpts = [
          "--runner=PortableRunner",
          "--environment_cache_millis=10000"
        ]
        def beamPythonTestPipelineOptions = [
          "pipeline_opts": pipelineOpts,
          "test_opts": testOpts,
          "suite": "xlangValidateRunner"
        ]
        def cmdArgs = project.project(':sdks:python').mapToArgString(beamPythonTestPipelineOptions)
        def pythonTask = project.tasks.create(name: config.name+"PythonUsing"+sdk, type: Exec) {
          group = "Verification"
          description = "Validates runner for cross-language capability of using ${sdk} transforms from Python SDK"
          environment "EXPANSION_JAR", expansionJar
          environment "EXPANSION_PORT", port
          executable 'sh'
          args '-c', ". $envDir/bin/activate && cd $pythonDir && ./scripts/run_integration_test.sh $cmdArgs"
          dependsOn setupTask
          // We need flink-job-server-container dependency since Python PortableRunner automatically
          // brings the flink-job-server-container up when --job_endpoint is not specified.
          dependsOn ':runners:flink:1.9:job-server-container:docker'
        }
        mainTask.dependsOn pythonTask
        cleanupTask.mustRunAfter pythonTask
      }
    }

    /** ***********************************************************************************************/

    project.ext.applyPythonNature = {

      // Define common lifecycle tasks and artifact types
      project.apply plugin: "base"

      // For some reason base doesn't define a test task  so we define it below and make
      // check depend on it. This makes the Python project similar to the task layout like
      // Java projects, see https://docs.gradle.org/4.2.1/userguide/img/javaPluginTasks.png
      project.task('test') {}
      project.check.dependsOn project.test

      project.evaluationDependsOn(":runners:google-cloud-dataflow-java:worker")

      // Due to Beam-4256, we need to limit the length of virtualenv path to make the
      // virtualenv activated properly. So instead of include project name in the path,
      // we use the hash value.
      project.ext.envdir = "${project.rootProject.buildDir}/gradleenv/${project.path.hashCode()}"
      def pythonRootDir = "${project.rootDir}/sdks/python"

      // Python interpreter version for virtualenv setup and test run. This value can be
      // set from commandline with -PpythonVersion, or in build script of certain project.
      // If none of them applied, version set here will be used as default value.
      project.ext.pythonVersion = project.hasProperty('pythonVersion') ?
              project.pythonVersion : '2.7'

      project.task('setupVirtualenv')  {
        doLast {
          def virtualenvCmd = [
            'virtualenv',
            "${project.ext.envdir}",
            "--python=python${project.ext.pythonVersion}",
          ]
          project.exec { commandLine virtualenvCmd }
          project.exec {
            executable 'sh'
            args '-c', ". ${project.ext.envdir}/bin/activate && pip install --retries 10 --upgrade tox==3.11.1 grpcio-tools==1.3.5"
          }
        }
        // Gradle will delete outputs whenever it thinks they are stale. Putting a
        // specific binary here could make gradle delete it while pip will believe
        // the package is fully installed.
        outputs.dirs(project.ext.envdir)
      }

      project.ext.pythonSdkDeps = project.files(
              project.fileTree(
              dir: "${project.rootDir}",
              include: ['model/**', 'sdks/python/**'],
              // Exclude temporary directories used in build and test.
              exclude: [
                '**/build/**',
                '**/dist/**',
                '**/target/**',
                'sdks/python/test-suites/**',
              ])
              )
      def copiedSrcRoot = "${project.buildDir}/srcs"

      // Create new configuration distTarBall which represents Python source
      // distribution tarball generated by :sdks:python:sdist.
      project.configurations { distTarBall }

      project.task('installGcpTest', dependsOn: 'setupVirtualenv') {
        doLast {
          project.exec {
            executable 'sh'
            args '-c', ". ${project.ext.envdir}/bin/activate && pip install --retries 10 -e ${pythonRootDir}/[gcp,test]"
          }
        }
      }
      project.installGcpTest.mustRunAfter project.configurations.distTarBall

      project.task('cleanPython') {
        doLast {
          def activate = "${project.ext.envdir}/bin/activate"
          project.exec {
            executable 'sh'
            args '-c', "if [ -e ${activate} ]; then " +
                    ". ${activate} && cd ${pythonRootDir} && python setup.py clean; " +
                    "fi"
          }
          project.delete project.buildDir     // Gradle build directory
          project.delete project.ext.envdir   // virtualenv directory
          project.delete "$project.projectDir/target"   // tox work directory
        }
      }
      project.clean.dependsOn project.cleanPython

      // Return a joined String from a Map that contains all commandline args of
      // IT test.
      project.ext.mapToArgString = { argMap ->
        def argList = []
        argMap.each { k, v ->
          if (v in List) {
            v = "\"${v.join(' ')}\""
          } else if (v in String && v.contains(' ')) {
            // We should use double quote around the arg value if it contains series
            // of flags joined with space. Otherwise, commandline parsing of the
            // shell script will be broken.
            v = "\"${v.replace('"', '')}\""
          }
          argList.add("--$k $v")
        }
        return argList.join(' ')
      }

      project.ext.toxTask = { name, tox_env ->
        project.tasks.create(name) {
          dependsOn 'setupVirtualenv'
          dependsOn ':sdks:python:sdist'

          doLast {
            // Python source directory is also tox execution workspace, We want
            // to isolate them per tox suite to avoid conflict when running
            // multiple tox suites in parallel.
            project.copy { from project.pythonSdkDeps; into copiedSrcRoot }

            def copiedPyRoot = "${copiedSrcRoot}/sdks/python"
            def distTarBall = "${pythonRootDir}/build/apache-beam.tar.gz"
            project.exec {
              executable 'sh'
              args '-c', ". ${project.ext.envdir}/bin/activate && cd ${copiedPyRoot} && scripts/run_tox.sh $tox_env $distTarBall"
            }
          }
          inputs.files project.pythonSdkDeps
          outputs.files project.fileTree(dir: "${pythonRootDir}/target/.tox/${tox_env}/log/")
        }
      }

      // Run single or a set of integration tests with provided test options and pipeline options.
      project.ext.enablePythonPerformanceTest = {

        // Use the implicit it parameter of the closure to handle zero argument or one argument map calls.
        // See: http://groovy-lang.org/closures.html#implicit-it
        def config = it ? it as PythonPerformanceTestConfiguration : new PythonPerformanceTestConfiguration()

        project.task('integrationTest') {
          dependsOn 'installGcpTest'
          dependsOn ':sdks:python:sdist'

          doLast {
            def argMap = [:]

            // Build test options that configures test environment and framework
            def testOptions = []
            if (config.tests)
              testOptions += "--tests=$config.tests"
            if (config.attribute)
              testOptions += "--attr=$config.attribute"
            testOptions.addAll(config.extraTestOptions)
            argMap["test_opts"] = testOptions

            // Build pipeline options that configures pipeline job
            if (config.pipelineOptions)
              argMap["pipeline_opts"] = config.pipelineOptions
            if (config.kmsKeyName)
              argMap["kms_key_name"] = config.kmsKeyName
            argMap["suite"] = "integrationTest-perf"

            def cmdArgs = project.mapToArgString(argMap)
            def runScriptsDir = "${pythonRootDir}/scripts"
            project.exec {
              executable 'sh'
              args '-c', ". ${project.ext.envdir}/bin/activate && ${runScriptsDir}/run_integration_test.sh ${cmdArgs}"
            }
          }
        }
      }

      def addPortableWordCountTask = { boolean isStreaming, String runner ->
        project.task('portableWordCount' + (runner.equals("PortableRunner") ? "" : runner) + (isStreaming ? 'Streaming' : 'Batch')) {
          dependsOn = ['installGcpTest']
          mustRunAfter = [
            ':runners:flink:1.9:job-server-container:docker',
            ':runners:flink:1.9:job-server:shadowJar',
            ':runners:spark:job-server:shadowJar',
            ':sdks:python:container:py2:docker',
            ':sdks:python:container:py35:docker',
            ':sdks:python:container:py36:docker',
            ':sdks:python:container:py37:docker'
          ]
          doLast {
            // TODO: Figure out GCS credentials and use real GCS input and output.
            def options = [
              "--input=/etc/profile",
              "--output=/tmp/py-wordcount-direct",
              "--runner=${runner}",
              "--experiments=worker_threads=100",
              "--parallelism=2",
              "--shutdown_sources_on_final_watermark",
              "--sdk_worker_parallelism=1",
            ]
            if (isStreaming)
              options += [
                "--streaming"
              ]
            else
              // workaround for local file output in docker container
              options += [
                "--environment_cache_millis=60000"
              ]
            if (project.hasProperty("jobEndpoint"))
              options += [
                "--job_endpoint=${project.property('jobEndpoint')}"
              ]
            if (project.hasProperty("environmentType")) {
              options += [
                "--environment_type=${project.property('environmentType')}"
              ]
            }
            if (project.hasProperty("environmentConfig")) {
              options += [
                "--environment_config=${project.property('environmentConfig')}"
              ]
            }
            project.exec {
              executable 'sh'
              args '-c', ". ${project.ext.envdir}/bin/activate && python -m apache_beam.examples.wordcount ${options.join(' ')}"
              // TODO: Check that the output file is generated and runs.
            }
          }
        }
      }
      project.ext.addPortableWordCountTasks = {
        ->
        addPortableWordCountTask(false, "PortableRunner")
        addPortableWordCountTask(true, "PortableRunner")
        addPortableWordCountTask(false, "FlinkRunner")
        addPortableWordCountTask(true, "FlinkRunner")
        addPortableWordCountTask(false, "SparkRunner")
      }
    }
  }
}
