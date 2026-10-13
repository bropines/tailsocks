// The build's own logic: one plain Java class, ReleaseVersion — the rule between
// VERSION_NAME and VERSION_CODE. app/build.gradle.kts holds every build to it, CI
// runs the file as a program against a tag, and the app's unit tests compile it
// too. No dependencies and no repositories, on purpose: this is compiled before
// every build, F-Droid's included, and must need nothing but the JDK.
plugins {
    java
}
