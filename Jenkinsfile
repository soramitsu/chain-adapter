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
        stage('Build artifacts') {
            steps {
                script {
                    if (env.BRANCH_NAME ==~ /(master|develop)/ || env.TAG_NAME) {
                        DOCKER_TAGS = ['master': 'latest', 'develop': 'develop']
                        withCredentials([usernamePassword(credentialsId: 'nexus-soramitsu-rw', usernameVariable: 'login', passwordVariable: 'password')]) {
                          env.DOCKER_REGISTRY_URL = "https://nexus.iroha.tech:19004"
                          env.DOCKER_REGISTRY_USERNAME = "${login}"
                          env.DOCKER_REGISTRY_PASSWORD = "${password}"
                          env.TAG = env.TAG_NAME ? env.TAG_NAME : DOCKER_TAGS[env.BRANCH_NAME]
                          sh "./gradlew dockerPush"
                        }
                    }
                }
            }
        }
        stage('Sonar') {
                  steps {
                    script {
                      if (env.BRANCH_NAME == 'develop') {
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