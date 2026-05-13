def call(Map configMap){
    pipeline {
        agent { 
            node { 
                label 'roboshop' 
            } 
        } 
        environment {
            COURSE      = "Jenkins"
            appVersion  = ""
            ACCOUNT_ID  = "996669628469"
            PROJECT     = configMap.get("project")
            COMPONENT   = configMap.get("component")
            region      = "us-east-1"
        }
        stages {
            stage('Deploy') {
                when{
                    expression { deploy_to == "dev" || deploy_to = "qa" || deploy_to = "qa" }
                }
                steps {
                    script{
                        withAWS(region:'us-east-1',credentials:'aws-creds') {
                            sh """
                                set -e
                                aws eks update-kubeconfig --region ${region} --name ${PROJECT}-${deploy_to}
                                kubectl get nodes
                                sed -i "s/IMAGE_VERSION/${appVersion}/g" values.yaml
                                helm upgrade --install ${COMPONENT} -f values-${deploy_to}.yaml -n ${PROJECT} --atomic --wait --timeout=5m .
                                #kubectl apply -f ${COMPONENT}-${deploy_to}.yaml
                            """
                        }
                    }
                }
            }
        }
        // post build
        post {
            success {
                echo "Pipeline succeeded on branch: ${env.BRANCH_NAME}"
            }
            failure {
                echo "Pipeline failed on branch: ${env.BRANCH_NAME}"
            }
        }
    }
}