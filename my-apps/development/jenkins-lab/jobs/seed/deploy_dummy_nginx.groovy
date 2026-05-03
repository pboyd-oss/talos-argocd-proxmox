pipelineJob('deploy-dummy-nginx') {
    description('Deploy a dummy nginx container to the cluster using skaffold')
    definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url('https://github.com/pboyd-oss/talos-argocd-proxmox.git')
                    }
                    branch('main')
                }
            }
            scriptPath('my-apps/development/jenkins-lab/jobs/deploy-dummy-nginx/Jenkinsfile')
        }
    }
}
