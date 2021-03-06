def k8slabel = "jenkins-pipeline-${UUID.randomUUID().toString()}"
def configData = ''
def slavePodTemplate = """
      metadata:
        labels:
          k8s-label: ${k8slabel}
        annotations:
          jenkinsjoblabel: ${env.JOB_NAME}-${env.BUILD_NUMBER}
      spec:
        affinity:
          podAntiAffinity:
            requiredDuringSchedulingIgnoredDuringExecution:
            - labelSelector:
                matchExpressions:
                - key: component
                  operator: In
                  values:
                  - jenkins-jenkins-master
              topologyKey: "kubernetes.io/hostname"
        containers:
        - name: fuchicorptools
          image: fuchicorp/buildtools
          imagePullPolicy: Always
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /var/run/docker.sock
              name: docker-sock
            - mountPath: /etc/secrets/service-account/
              name: google-service-account
        serviceAccountName: common-service-account
        securityContext:
          runAsUser: 0
          fsGroup: 0
        volumes:
          - name: google-service-account
            secret:
              secretName: google-service-account
          - name: docker-sock
            hostPath:
              path: /var/run/docker.sock
    """

properties([
    parameters([

        booleanParam(defaultValue: false, description: 'Please select debug mode true to be able to turn on ', name: 'DebugMode'),
        choice(choices: ['dev', 'qa', 'prod', 'test'], description: 'Please select the environment to deploy', name: 'environment')
        
        ])
    ])


podTemplate(name: k8slabel, label: k8slabel, yaml: slavePodTemplate, showRawYaml: params.DebugMode) {
    node(k8slabel) {
      container("fuchicorptools") {
        dir("${WORKSPACE}/") {
            stage("Pull Source Code") {
                git branch: 'dev-feature/fsadykov', url: 'https://github.com/fuchicorp/source-kube.git'
            }

            stage("Deployment Info") {
                println("""
                MODE: ${params.DebugMode}
                Environment: ${params.environment}
                """  
                )
            }

            if (params.environment == "cluster-1") {
              sh = '''#!/bin/bash
              cp -rf /cluster-1  ~/.kube/config'''
            } else if (params.environment == "cluster-2") {
              sh = '''#!/bin/bash
              cp -rf /cluster-2  ~/.kube/config'''
            }

            stage("Generate Config") {
                sh """
                    cat  /etc/secrets/service-account/credentials.json > ${WORKSPACE}/deployments/terraform/fuchicorp-service-account.json
                    ## This script should move to docker container to set up ~/.kube/config
                    sh /scripts/Dockerfile/set-config.sh
                    """

                dir("${WORKSPACE}/deployments/terraform") { 
                    configData += """
                    deployment_environment = \"${params.environment}\"
                    deployment_name        = \"source-kube\"   
                    credentials            = \"./fuchicorp-service-account.json\"
                    """.stripIndent()

                    writeFile(
                    [file: "${WORKSPACE}/deployments/terraform/deployment_configuration.tfvars", text: "${configData}"]
                    )
                }
                
            }

            stage("Apply/Plan") {
              dir("${WORKSPACE}/deployments/terraform") {
                    sh '''#!/bin/bash -e
                    source set-env.sh deployment_configuration.tfvars
                    terraform apply --auto-approve -var-file=deployment_configuration.tfvars
                    '''
                }
            }   
          }
        }
      }
    }
