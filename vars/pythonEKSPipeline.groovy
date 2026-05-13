def call(Map configMap){
    pipeline {
        agent {
            node {
                label 'roboshop'
            }
        }
        environment {
            appVersion  = ""
            ACCOUNT_ID  = "996669628469"
            region      = "us-east-1"
            project     = configMap.get("project")
            component   = configMap.get("component")
        }
        options {
            timeout(time: 15, unit: 'MINUTES')
        }
        parameters {
            booleanParam(name: 'DEPLOY', defaultValue: false, description: 'Toggle this value')
        }
        stages {
            stage('Read version') {
                steps {
                    script {
                        appVersion = sh(
                            script: "cat version.txt",
                            returnStdout: true
                        ).trim()
                        echo "Building version ${appVersion}"
                    }
                }
            }
            stage('Install Dependencies') {
                steps {
                    script {
                        sh "pip3 install -r requirements.txt --quiet"
                    }
                }
            }
            stage('Unit tests') {
                steps {
                    script {
                        def testResult = sh(script: 'pytest --tb=short -q', returnStatus: true)
                        if (testResult != 0) {
                            utils.updateCommitStatus('failure', 'Unit tests failed', 'unit-tests')
                            error "Unit tests failed."
                        } else {
                            utils.updateCommitStatus('success', 'Unit tests passed', 'unit-tests')
                        }
                    }
                }
            }
            /* stage ('SonarQube Analysis'){
                steps {
                    script {
                        def scannerHome = tool name: 'sonar-8'
                        withSonarQubeEnv('sonar-server') {
                            sh "${scannerHome}/bin/sonar-scanner"
                        }
                    }
                }
            }
            stage("Quality Gate") {
                steps {
                    script {
                        timeout(time: 1, unit: 'HOURS') {
                            def qg = waitForQualityGate()
                            if (qg.status != 'OK') {
                                utils.updateCommitStatus('failure', "SonarQube quality gate failed: ${qg.status}", 'sonar-scan')
                                error "Quality gate failed: ${qg.status}"
                            } else {
                                utils.updateCommitStatus('success', 'SonarQube quality gate passed', 'sonar-scan')
                            }
                        }
                    }
                }
            }
            stage('Dependabot Alerts Check') {
                steps {
                    script {
                        withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN_SCAN')]) {
                            def repoUrl = sh(script: 'git remote get-url origin', returnStdout: true).trim()
                            def repoPath = repoUrl.replaceAll(/.*github\.com[\/:]/, '').replaceAll(/\.git$/, '')

                            def alertCount = sh(
                                script: """
                                    curl -sf \
                                        -H "Authorization: Bearer \$GITHUB_TOKEN_SCAN" \
                                        -H "Accept: application/vnd.github+json" \
                                        -H "X-GitHub-Api-Version: 2022-11-28" \
                                        "https://api.github.com/repos/${repoPath}/dependabot/alerts?state=open&per_page=100" \
                                    | jq '[.[] | select(.security_vulnerability.severity == "high" or .security_vulnerability.severity == "critical")] | length'
                                """,
                                returnStdout: true
                            ).trim()

                            if (alertCount.toInteger() > 0) {
                                utils.updateCommitStatus('failure', "${alertCount} HIGH/CRITICAL Dependabot alert(s) detected", 'library-scan')
                                error("Build aborted: ${alertCount} HIGH/CRITICAL Dependabot alert(s) detected. Resolve them before proceeding.")
                            }
                            utils.updateCommitStatus('success', 'Dependabot check passed — no HIGH/CRITICAL alerts', 'library-scan')
                        }
                    }
                }
            } */
            stage('Build Image') {
                steps {
                    script {
                        withAWS(credentials: 'aws-creds', region: "${region}") {
                            sh """
                                aws ecr get-login-password --region ${region} | docker login --username AWS --password-stdin ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com
                                docker build -t ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion} .
                                docker push ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion}
                            """
                        }
                    }
                }
            }
            /* stage('Trivy OS Scan') { ... }
            stage('Trivy Dockerfile Scan') { ... } */
            stage('Push image to ECR') {
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: "${region}") {
                                sh """
                                    aws ecr get-login-password --region ${region} | docker login --username AWS --password-stdin ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com
                                    docker push ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion}
                                """
                            }
                            utils.updateCommitStatus('success', "Image ${appVersion} pushed to ECR", 'push-image')
                        } catch (err) {
                            utils.updateCommitStatus('failure', 'Failed to push image to ECR', 'push-image')
                            throw err
                        }
                    }
                }
            }
        }

        post {
            always {
                echo 'I will always say Hello again!'
                cleanWs()
            }
            success {
                echo "Pipeline Success"
            }
            failure {
                echo "Pipeline Failure"
            }
        }
    }
}