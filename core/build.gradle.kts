plugins { `java-library` }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
tasks.register<JavaExec>("checkCore") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.vaultdown.core.CoreTests")
}
tasks.check { dependsOn("checkCore") }
