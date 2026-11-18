// App di prova per l'emulatore (NON va sul telefono): imita WhatsApp/Gmail che passano a 151 Viewer
// un file loro, con un provider che NON permette di cancellarlo. Serve alla prova «Elimina» caso d.
// Build: cd tools/emu-test/sender && ../../../gradlew assembleDebug
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "fintochat"
include(":app")
