group = "io.github.bryceaspringfield"

patches {
    about {
        name = "YouTube Extras"
        description = "Channel-scoped filters, video length and category filters, auto feedback, and fixes for YouTube. Select it together with Morphe Patches."
        source = "https://github.com/bryceaspringfield/morphe/tree/main/youtube"
        author = "bryceaspringfield"
        contact = "https://github.com/bryceaspringfield/morphe/issues"
        website = "https://github.com/bryceaspringfield/morphe/tree/main/youtube"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
