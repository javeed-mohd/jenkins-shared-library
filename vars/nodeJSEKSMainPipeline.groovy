def call(Map configMap) {
    pipeline {
        agent { node { label 'roboshop' } }
        parameters {
            choice(name: 'deploy_to', choices: ['dev', 'uat', 'prod'], description: 'Target environment')
            string(name: 'VERSION',   defaultValue: '', description: 'Short commit SHA — set by Jira webhook for UAT/PROD')
            string(name: 'JIRA_KEY',  defaultValue: '', description: 'Jira issue key — set by Jira webhook for UAT/PROD')
            string(name: 'CR_NUMBER', defaultValue: '', description: 'Change Request number — required for PROD deploy')
        }
        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'deploy_to', value: '$.deploy_to'],
                    [key: 'VERSION',   value: '$.VERSION'],
                    [key: 'JIRA_KEY',  value: '$.JIRA_KEY'],
                    [key: 'CR_NUMBER', value: '$.CR_NUMBER']
                ],
                token: "${configMap.get('project')}-main-pipeline",
                causeString: 'Triggered by Jira — $deploy_to deploy',
                printContributedVariables: true,
                printPostContent: true
            )
        }
        environment {
            appVersion   = ""
            shortCommit  = ""
            ACCOUNT_ID   = "996669628469"
            project      = configMap.get("project")
            component    = configMap.get("component")
            jira_project = configMap.get("jiraProject")
            region       = "us-east-1"
            CLUSTER      = "roboshop-dev"
        }

        stages {
            // ── INIT — pick webhook env vars (Generic Trigger) over manual params ─
            stage('Init') {
                steps {
                    script {
                        env.DEPLOY_TO      = env.deploy_to  ?: params.deploy_to  ?: 'dev'
                        env.TARGET_VERSION = env.VERSION    ?: params.VERSION    ?: ''
                        env.JIRA_ISSUE     = env.JIRA_KEY   ?: params.JIRA_KEY   ?: ''
                        env.CR_NUMBER      = env.CR_NUMBER  ?: params.CR_NUMBER  ?: ''
                        echo "DEPLOY_TO=${env.DEPLOY_TO}  TARGET_VERSION=${env.TARGET_VERSION}  JIRA_ISSUE=${env.JIRA_ISSUE}  CR_NUMBER=${env.CR_NUMBER}"
                    }
                }
            }

            // ── DEV ────────────────────────────────────────────────────────────
            stage('Read Version') {
                when { expression { env.DEPLOY_TO == 'dev' } }
                steps {
                    script {
                        def packageJson = readJSON file: 'package.json'
                        appVersion  = packageJson.version
                        shortCommit = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                        echo "appVersion: ${appVersion}   shortCommit: ${shortCommit}"
                    }
                }
            }

            stage('Promote Image') {
                when { expression { env.DEPLOY_TO == 'dev' } }
                steps {
                    script {
                        withAWS(credentials: 'aws-creds', region: "${region}") {
                            sh """
                                aws ecr get-login-password --region ${region} \
                                    | docker login --username AWS --password-stdin ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com
                                docker pull ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion}
                                docker tag  ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion} \
                                            ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${shortCommit}
                                docker push ${ACCOUNT_ID}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${shortCommit}
                            """
                        }
                    }
                }
            }

            stage('Deploy to DEV') {
                when { expression { env.DEPLOY_TO == 'dev' } }
                steps {
                    script {
                        withAWS(region: "${region}", credentials: 'aws-creds') {
                            sh """
                                aws eks update-kubeconfig --region ${region} --name ${CLUSTER}
                                cd helm
                                sed -i "s/IMAGE_VERSION/${shortCommit}/g" values.yaml
                                helm upgrade --install ${component} -f values-dev.yaml -n ${project}-dev --atomic --wait --timeout=5m .
                            """
                        }
                    }
                }
            }

            stage('Functional Tests') {
                when { expression { env.DEPLOY_TO == 'dev' } }
                steps {
                    script {
                        def result = build(job: "${project}/${component}-tests", wait: true, propagate: false)
                        if (result.result != 'SUCCESS') {
                            error("Functional tests failed — Jira ticket not created.")
                        }
                    }
                }
            }
        }
    }
}