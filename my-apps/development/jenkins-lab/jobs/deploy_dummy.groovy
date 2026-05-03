pipelineJob('deploy-dummy-nginx') {
    description('Deploy a dummy nginx container to the cluster using skaffold')
    definition {
        cps {
            script('''
pipeline {
    agent {
        kubernetes {
            inheritFrom 'skaffold'
        }
    }
    stages {
        stage('Checkout') {
            steps {
                git branch: 'main', url: 'https://github.com/pboyd-oss/talos-argocd-proxmox.git'
            }
        }
        stage('Deploy') {
            steps {
                container('skaffold') {
                    sh 'skaffold apply my-apps/development/jenkins-lab/jobs/manifests/dummy-nginx.yaml'
                }
            }
        }
    }
}
''')
            sandbox(true)
        }
    }
}
