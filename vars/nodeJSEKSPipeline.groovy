def call(Map configMap) {
    pipeline {
        agent {
            node {
                label 'roboshop' 
            } 
        }
        environment {
            appVersion = ""
            ACCOUNT_ID = "996669628469"
            region = "us-east-1"
            project = configMap.get("project")
            component = configMap.get("component")
        }
        options {
            // disableConcurrentBuilds()    // Prevents multiple builds of the same job from running at the same time
            timeout(time: 15, unit: 'MINUTES')
        }
        parameters {
            booleanParam(name: 'DEPLOY', defaultValue: false, description: 'Toggle this value')
        }
        stages {
            stage('Read version') {
                steps {
                    script {
                        // Load and parse the JSON file
                        def packageJson = readJSON file: 'package.json'
                        
                        // Access fields directly
                        appVersion = packageJson.version
                        echo "Building version ${appVersion}"
                        // sh 'printenv | sort'
                    }
                }
            }
            stage('Install Dependencies') {
                steps {
                    script{
                        sh """
                            npm install
                        """
                    }
                }
            }
            stage('Unit tests') {
                steps {
                    script{
                        /* sh """
                            npm test
                        """ */
                        def testResult = sh(script: 'npm test', returnStatus: true)
                        if (testResult != 0) {
                            utils.updateCommitStatus('failure', 'Unit tests failed', 'unit-tests')
                            error "Unit tests failed."
                        } else {
                            utils.updateCommitStatus('success', 'Unit tests passed', 'unit-tests')
                        }
                    }
                }
            }
            stage('SonarQube Analysis') {
                steps {
                    script {
                        def scannerHome = tool name: 'sonar-8'      // Agent configuration
                        withSonarQubeEnv('sonar-server') {          // Analyzing and uploading to server
                            sh "${scannerHome}/bin/sonar-scanner"
                        }
                    }
                }
            }
            stage("Quality Gate") {
                steps {
                    timeout(time: 1, unit: 'HOURS') {
                        waitForQualityGate abortPipeline: true
                    }
                }
            }
            stage('Dependabot Alerts Check') {
                steps {
                    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
                        script {
                            def owner = 'javeed-mohd'
                            def repo  = '${component}'

                            def response = sh(
                                script: """
                                    curl -s -w "\\n%{http_code}" \\
                                        -H "Authorization: Bearer ${GITHUB_TOKEN}" \\
                                        -H "Accept: application/vnd.github+json" \\
                                        -H "X-GitHub-Api-Version: 2022-11-28" \\
                                        "https://api.github.com/repos/${owner}/${repo}/dependabot/alerts?severity=high,critical&state=open&per_page=100"
                                """,
                                returnStdout: true
                            ).trim()

                            def parts      = response.tokenize('\n')
                            def httpStatus = parts[-1].trim()
                            def body       = parts[0..-2].join('\n')

                            if (httpStatus != '200') {
                                error "GitHub API call failed with HTTP ${httpStatus}. Check token permissions (security_events scope required).\nResponse: ${body}"
                            }

                            def alerts = readJSON text: body

                            if (alerts.size() == 0) {
                                echo "✅ No HIGH or CRITICAL Dependabot alerts found. Pipeline continues."
                            } else {
                                echo "🚨 Found ${alerts.size()} HIGH/CRITICAL Dependabot alert(s):"
                                alerts.each { alert ->
                                    def pkg      = alert.security_vulnerability?.package?.name ?: 'unknown'
                                    def severity = alert.security_advisory?.severity?.toUpperCase() ?: 'UNKNOWN'
                                    def summary  = alert.security_advisory?.summary ?: 'No summary'
                                    def fixedIn  = alert.security_vulnerability?.first_patched_version?.identifier ?: 'No fix available'
                                    echo "  ❌ [${severity}] ${pkg} — ${summary} (Fixed in: ${fixedIn})"
                                }
                                error "Pipeline failed: ${alerts.size()} HIGH/CRITICAL Dependabot alert(s) detected."
                            }
                        }
                    }
                }
            }
            stage('Build Image') {
                steps {
                    script {
                        withAWS(credentials: 'aws-creds', region: "${region}") {
                            // Commands here will have AWS authentication which we have set
                            /* sh """
                                aws ecr get-login-password --region ${region} | docker login --username AWS --password-stdin ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com
                                docker build -t ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com/roboshop/catalogue:${appVersion} .
                                docker push ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com/roboshop/catalogue:${appVersion}
                            """ */
                            sh """
                                docker build -t ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion} .
                            """
                        }    
                    }
                }
            }
            stage('Trivy OS Scan') {
                steps {
                    script {
                        // Generate table report
                        sh """
                            trivy image \
                                --scanners vuln \
                                --pkg-types os \
                                --severity HIGH,MEDIUM \
                                --format table \
                                --output trivy-os-report.txt \
                                --exit-code 0 \
                                ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion}
                        """

                        // Print table to console
                        sh 'cat trivy-os-report.txt'

                        // Fail pipeline if vulnerabilities found
                        def scanResult = sh(
                            script: """
                                trivy image \
                                    --scanners vuln \
                                    --pkg-types os \
                                    --severity HIGH,MEDIUM \
                                    --format table \
                                    --exit-code 1 \
                                    --quiet \
                                    ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion}
                            """,
                            returnStatus: true
                        )

                        if (scanResult != 0) {
                            error "🚨 Trivy found HIGH/MEDIUM OS vulnerabilities. Pipeline failed."
                        } else {
                            echo "✅ No HIGH or MEDIUM OS vulnerabilities found. Pipeline continues."
                        }
                    }
                }
            }
            stage('Trivy Dockerfile Scan') {
                steps {
                    script {
                        sh """
                            trivy config \
                                --severity HIGH,MEDIUM \
                                --format table \
                                --output trivy-dockerfile-report.txt \
                                Dockerfile
                        """

                        sh 'cat trivy-dockerfile-report.txt'

                        def scanResult = sh(
                            script: """
                                trivy config \
                                    --severity HIGH,MEDIUM \
                                    --exit-code 1 \
                                    --format table \
                                    Dockerfile
                            """,
                            returnStatus: true
                        )

                        if (scanResult != 0) {
                            error "🚨 Trivy found HIGH/MEDIUM misconfigurations in Dockerfile. Pipeline failed."
                        } else {
                            echo "✅ No HIGH or MEDIUM Dockerfile misconfigurations found. Pipeline continues."
                        }
                    }
                }
            }
            stage('Push image to ECR') {
                steps {
                script{
                        withAWS(credentials: 'aws-creds', region: "${region}") {
                            // Commands here will have AWS authentication access
                            sh """
                                aws ecr get-login-password --region ${region} | docker login --username AWS --password-stdin ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com
                                docker push ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion}
                            """
                        }
                    }
                }
            }
        }
        // post build
        post { 
            always { 
                echo 'I will always say Hello again!'
                cleanWs()
            }
            success { 
                echo 'Pipeline Success'
            }
            failure { 
                echo 'Pipeline Failure'
            }
        }
    }
}