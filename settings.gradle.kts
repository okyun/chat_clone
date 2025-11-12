plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "chat-pratice"

include(
    "chat-application",
    "chat-domain",
    "chat-persistence"
    ,"chat-websocket"
    ,"chat-api"
)