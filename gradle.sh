#!/bin/sh
# Builds with the cached Gradle 8.14.3 and a Gradle home on E: (C: is short on space).
export GRADLE_USER_HOME=E:/MechCannons/.gradle-home
exec "$HOME"/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle "$@"
