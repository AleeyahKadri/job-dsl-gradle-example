plugins {
    groovy
}

sourceSets {
    create("jobs") {
        withConvention(GroovySourceSet::class) {
            groovy {
                srcDirs("src/jobs")
                compileClasspath += sourceSets["main"].compileClasspath
            }
        }
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://repo.jenkins-ci.org/public/")
    }
}

val testPlugins by configurations.creating

configurations {
    // see JENKINS-45512
    testImplementation {
        exclude(group = "xalan")
        exclude(group = "xerces")
    }
}

// Exclude buggy Xalan dependency this way the JRE default TransformerFactory is used
// The xalan pulled in by htmlunit does not properly deal with spaces folder / job names
configurations.all {
    exclude(group = "xalan")
}

val jobDslVersion: String by project
val jenkinsVersion: String by project

dependencies {
    implementation("org.codehaus.groovy:groovy-all:2.4.11")
    implementation("org.jenkins-ci.plugins:job-dsl-core:$jobDslVersion")
    implementation("org.kohsuke:github-api:1.93")

    testImplementation("org.spockframework:spock-core:1.3-groovy-2.4")
    testImplementation("cglib:cglib-nodep:2.2.2") // used by Spock

    // Jenkins test harness dependencies
    testImplementation("org.jenkins-ci.main:jenkins-test-harness:2.49") {
        exclude(group = "org.netbeans.modules", module = "org-netbeans-insane") // https://github.com/sheehan/job-dsl-gradle-example/issues/90
    }
    testImplementation("org.jenkins-ci.main:jenkins-war:$jenkinsVersion") {
        exclude(group = "org.jenkins-ci.ui", module = "bootstrap") // https://github.com/sheehan/job-dsl-gradle-example/issues/87
    }

    // Job DSL plugin including plugin dependencies
    testImplementation("org.jenkins-ci.plugins:job-dsl:$jobDslVersion")
    testImplementation("org.jenkins-ci.plugins:job-dsl:$jobDslVersion@jar")
    testImplementation("org.jenkins-ci.plugins:structs:1.20@jar")

    // Plugins to install in test instance
    testPlugins("org.jenkins-ci.plugins:cloudbees-folder:5.14")
    testPlugins("org.jenkins-ci.plugins:credentials:2.1.10")
    testPlugins("org.jenkins-ci.plugins:cvs:2.13")
    testPlugins("org.jenkins-ci.plugins:ghprb:1.40.0")
    testPlugins("org.jenkins-ci.plugins:token-macro:2.5")
    testPlugins("org.jenkins-ci.plugins.workflow:workflow-cps-global-lib:2.7")

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

tasks.register<Copy>("resolveTestPlugins") {
    from(testPlugins)
    into(file("${sourceSets["test"].output.resourcesDir}/test-dependencies"))
    include("*.hpi")
    include("*.jpi")
    val mapping = mutableMapOf<String, String>()

    doFirst {
        testPlugins.resolvedConfiguration.resolvedArtifacts.forEach {
            mapping[it.file.name] = "${it.name}.${it.extension}"
        }
    }
    rename { mapping[it] }

    doLast {
        val baseNames = source.files.map { mapping[it.name] }.map { it!!.substring(0, it.lastIndexOf('.')) }
        file(destinationDir).resolve("index").writeText(baseNames.joinToString("\n"), Charsets.UTF_8)
    }
}

tasks.test {
    dependsOn(tasks.named("resolveTestPlugins"))
    val jobsSourceSet = sourceSets["jobs"]
    val groovySourceDirSet = jobsSourceSet.extensions.getByName("groovy") as SourceDirectorySet
    inputs.files(groovySourceDirSet.srcDirs)
}
