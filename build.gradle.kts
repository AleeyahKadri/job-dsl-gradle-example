import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.GroovySourceSet

plugins {
    groovy
}

val sourceSets = the<SourceSetContainer>()

sourceSets {
    create("jobs") {
        withConvention(GroovySourceSet::class) {
            groovy.srcDirs("src/jobs")
            compileClasspath += sourceSets["main"].compileClasspath
        }
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.jenkins-ci.org/releases/")
    }
    jcenter()
}

configurations {
    create("testPlugins")

    // see JENKINS-45512
    getByName("testCompile") {
        exclude(mapOf("group" to "xalan"))
        exclude(mapOf("group" to "xerces"))
    }
}

// Exclude buggy Xalan dependency this way the JRE default TransformerFactory is used
// The xalan pulled in by htmlunit does not properly deal with spaces folder / job names
configurations.all {
    exclude(mapOf("group" to "xalan"))
}

dependencies {
    add("compile", "org.codehaus.groovy:groovy-all:2.4.11")
    add("compile", "org.jenkins-ci.plugins:job-dsl-core:$jobDslVersion")
    add("compile", "org.kohsuke:github-api:1.93")

    add("testCompile", "org.spockframework:spock-core:1.3-groovy-2.4")
    add("testCompile", "cglib:cglib-nodep:2.2.2") // used by Spock

    // Jenkins test harness dependencies
    add("testCompile", "org.jenkins-ci.main:jenkins-test-harness:2.49") {
        exclude(mapOf("group" to "org.netbeans.modules", "module" to "org-netbeans-insane")) // https://github.com/sheehan/job-dsl-gradle-example/issues/90
    }
    add("testCompile", "org.jenkins-ci.main:jenkins-war:$jenkinsVersion") {
        exclude(mapOf("group" to "org.jenkins-ci.ui", "module" to "bootstrap")) // https://github.com/sheehan/job-dsl-gradle-example/issues/87
    }

    // Job DSL plugin including plugin dependencies
    add("testCompile", "org.jenkins-ci.plugins:job-dsl:$jobDslVersion")
    add("testCompile", "org.jenkins-ci.plugins:job-dsl:$jobDslVersion@jar")
    add("testCompile", "org.jenkins-ci.plugins:structs:1.20@jar")

    // Plugins to install in test instance
    add("testPlugins", "org.jenkins-ci.plugins:cloudbees-folder:5.14")
    add("testPlugins", "org.jenkins-ci.plugins:credentials:2.1.10")
    add("testPlugins", "org.jenkins-ci.plugins:cvs:2.13")
    add("testPlugins", "org.jenkins-ci.plugins:ghprb:1.40.0")
    add("testPlugins", "org.jenkins-ci.plugins:token-macro:2.5")
    add("testPlugins", "org.jenkins-ci.plugins.workflow:workflow-cps-global-lib:2.7")

    // Run the following script in the Script Console of your Jenkins instance to generate
    // the above testPlugins list. (adapted from https://git.io/fjpUs)
    /*
        Jenkins.instance.pluginManager.plugins
            .findAll { !(it.shortName in ['job-dsl', 'structs']) }
            .collect { "testPlugins '${it.manifest.mainAttributes.getValue("Group-Id")}:${it.shortName}:${it.version}'" }
            .sort()
            .each { println it }
     */
}

val resolveTestPlugins = tasks.create("resolveTestPlugins", Copy::class.java) {
    from(configurations["testPlugins"])
    into(File(sourceSets["test"].output.resourcesDir, "test-dependencies"))
    include("*.hpi")
    include("*.jpi")

    val mapping = mutableMapOf<String, String>()

    doFirst {
        configurations["testPlugins"].resolvedConfiguration.resolvedArtifacts.forEach {
            mapping[it.file.name] = "${it.name}.${it.extension}"
        }
    }

    rename { fileName ->
        mapping[fileName] ?: fileName
    }

    doLast {
        val baseNames = mapping.values.map { it.substring(0, it.lastIndexOf('.')) }
        File(destinationDir, "index").writeText(baseNames.joinToString("\n"), Charsets.UTF_8)
    }
}

tasks.named<Test>("test") {
    dependsOn(resolveTestPlugins)
    inputs.files(sourceSets["jobs"].withConvention(GroovySourceSet::class) { groovy.srcDirs })
}
