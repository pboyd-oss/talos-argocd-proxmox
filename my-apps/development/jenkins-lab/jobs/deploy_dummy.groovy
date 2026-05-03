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
        stage('Write manifests') {
            steps {
                container('skaffold') {
                    writeFile file: 'skaffold.yaml', text: """
apiVersion: skaffold/v4beta11
kind: Config
manifests:
  rawYaml:
    - k8s.yaml
"""
                    writeFile file: 'k8s.yaml', text: """
apiVersion: apps/v1
kind: Deployment
metadata:
  name: dummy-nginx
  namespace: default
spec:
  replicas: 1
  selector:
    matchLabels:
      app: dummy-nginx
  template:
    metadata:
      labels:
        app: dummy-nginx
    spec:
      containers:
        - name: nginx
          image: nginx:alpine
          ports:
            - containerPort: 80
"""
                }
            }
        }
        stage('Deploy') {
            steps {
                container('skaffold') {
                    sh 'skaffold apply k8s.yaml --kubeconfig=$KUBECONFIG'
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
