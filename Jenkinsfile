@Library('jenkins-shared-library') _

node (label: 'build_nodes') {

    def environmentVariables = [
            "SERVICE_NAME=REACH Azure Blob Storage",
            "RUN_SONAR=true",
            "PROJECT_REPO_URL=https://giteux.azure.defra.cloud/chemicals/reach-azure-blob-storage.git"
    ]

    withEnv(environmentVariables) {
        reachLibraryPipeline()
    }
}
