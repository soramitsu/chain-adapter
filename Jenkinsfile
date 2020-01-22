def dockerTags = ['master': 'latest', 'develop': 'dev']
pipeline {
    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timestamps()
    }
    agent {
        docker {
            label 'd3-build-agent'
            image 'openjdk:8-jdk-alpine'
            args '-v /var/run/docker.sock:/var/run/docker.sock -v /tmp:/tmp'
        }
    }
    stages {
        stage('Build') {
            steps {
                script {
                    sh "./gradlew build --info"
                }
            }
        }
        stage('Test') {
            steps {
                script {
                    sh "./gradlew test --info"
                }
            }
        }
        stage('Sonar') {
            steps {
                script {
                    if (env.BRANCH_NAME in dockerTags) {
                        withCredentials([string(credentialsId: 'SONAR_TOKEN', variable: 'SONAR_TOKEN')]){
                            sh(script: "./gradlew sonarqube -x test --configure-on-demand \
                                -Dsonar.links.ci=${BUILD_URL} \
                                -Dsonar.github.pullRequest=${env.CHANGE_ID} \
                                -Dsonar.github.disableInlineComments=true \
                                -Dsonar.host.url=https://sonar.soramitsu.co.jp \
                                -Dsonar.login=${SONAR_TOKEN} \
                                ")
                        }
                    }
                }
            }
        }
        stage('Push artifacts') {
            when {
                expression { return (env.BRANCH_NAME in dockerTags || env.TAG_NAME ) }
            }
            steps {
                script {
                    if (env.BRANCH_NAME in dockerTags) {
                        withCredentials([usernamePassword(credentialsId: 'nexus-soramitsu-rw', usernameVariable: 'DOCKER_REGISTRY_USERNAME', passwordVariable: 'DOCKER_REGISTRY_PASSWORD')]) {
                            env.DOCKER_REGISTRY_URL = "https://nexus.iroha.tech:19004"
                            env.TAG = dockerTags[env.BRANCH_NAME]
                            sh "./gradlew dockerPush"
                        }
                        withCredentials([usernamePassword(credentialsId: 'nexus-nbc-deploy', usernameVariable: 'DOCKER_REGISTRY_USERNAME', passwordVariable: 'DOCKER_REGISTRY_PASSWORD')]) {
                            env.DOCKER_REGISTRY_URL = "https://nexus.iroha.tech:19000"
                            env.TAG = dockerTags[env.BRANCH_NAME]
                            sh "./gradlew dockerPush"
                        }
                    } else if (env.TAG_NAME ==~ /^bakong-\d{1,4}\.\d.*/) {
                        def tagPattern = (env.TAG_NAME =~ /(?<=bakong-)(\d{1,4}\.\d.*)/)[0][1]
                        println "${tagPattern}"
                        withCredentials([usernamePassword(credentialsId: 'nexus-nbc-deploy', usernameVariable: 'DOCKER_REGISTRY_USERNAME', passwordVariable: 'DOCKER_REGISTRY_PASSWORD')]) {
                            env.DOCKER_REGISTRY_URL = "https://nexus.iroha.tech:19000"
                            env.TAG = "${tagPattern}"
                            sh "./gradlew dockerPush"
                        }
                    } else if (env.TAG_NAME ==~ /^\d.*/) {
                        withCredentials([usernamePassword(credentialsId: 'nexus-soramitsu-rw', usernameVariable: 'DOCKER_REGISTRY_USERNAME', passwordVariable: 'DOCKER_REGISTRY_PASSWORD')]) {
                            env.DOCKER_REGISTRY_URL = "https://nexus.iroha.tech:19004"
                            env.TAG = env.TAG_NAME
                            sh "./gradlew dockerPush"
                        }
                    } else {
                        error "Your git tag doesn't match any pattern!"
                    }
                }
            }
        }
    }
    post {
        always {
            publishHTML (target: [
                allowMissing: false,
                alwaysLinkToLastBuild: false,
                keepAll: true,
                reportDir: 'build/reports',
                reportFiles: 'd3-test-report.html',
                reportName: "D3 test report"
            ])
        }
        cleanup {
            cleanWs()
        }
    }
}
