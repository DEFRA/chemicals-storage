#!/bin/bash -eux
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
curl -f --retry 5 https://artifactoryv2.azure.defra.cloud/artifactory/CHM-Maven/settings.xml -o settings.xml
mvn -s settings.xml verify --quiet -Ddependency-check.skip=true -DskipITs=true
