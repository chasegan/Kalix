plugins {
    application
    java
}

group = "com.kalix"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("org.ini4j:ini4j:0.5.4")
    
    // FlatLaf modern look and feel
    implementation("com.formdev:flatlaf:3.2.5")
    implementation("com.formdev:flatlaf-extras:3.2.5")
    
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

application {
    mainClass.set("com.kalix.gui.KalixGUI")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(23))
    }
}

tasks.test {
    useJUnitPlatform()
}