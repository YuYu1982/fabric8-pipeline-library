#!/usr/bin/groovy
import io.fabric8.Fabric8Commands
import io.fabric8.Utils

def call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [version: '', noCache: false]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    container('clients') {
        def newVersion = config.version
        if (newVersion == '') {
            newVersion = getNewVersion {}
        }

        def noCache = config.noCache
        if (noCache == '') {
            noCache = false
        }

        env.setProperty('VERSION', newVersion)

        def flow = new Fabric8Commands()
        if (flow.isOpenShift()) {
            s2iBuild(newVersion, noCache)
        } else {
            dockerBuild(newVersion)
        }

        return newVersion
    }
}

def dockerBuild(version){
    def utils = new Utils()
    def flow = new Fabric8Commands()
    def namespace = utils.getNamespace()
    def newImageName = "${env.FABRIC8_DOCKER_REGISTRY_SERVICE_HOST}:${env.FABRIC8_DOCKER_REGISTRY_SERVICE_PORT}/${namespace}/${env.JOB_NAME}:${version}"

    sh "docker build -t ${newImageName} ."
    if (flow.isSingleNode()) {
        sh "echo 'Running on a single node, skipping docker push as not needed'"
    } else {
        sh "docker push ${newImageName}"
    }
}

def s2iBuild(version, noCache){

    def utils = new Utils()
    def ns = utils.namespace
    def is = getImageStream(ns)
    def bc = getBuildConfig(version, ns, noCache)

    kubernetesApply(file: is, environment: ns)
    kubernetesApply(file: bc, environment: ns)
    sh "echo JOB_NAME ${env.JOB_NAME}"
    sh "echo ns ${ns}"
    sh "oc start-build ${env.JOB_NAME}-s2i --from-dir ../${env.JOB_NAME} --follow -n ${ns}"
    //sh "oc tag ${ns}/${env.JOB_NAME}:${version} demo-staging/${env.JOB_NAME}:${version}"

}

def getImageStream(ns){
    return """
apiVersion: v1
kind: ImageStream
metadata:
  name: ${env.JOB_NAME}
  namespace: ${ns}
"""
}

def getBuildConfig(version, ns, noCache){
    return """
apiVersion: v1
kind: BuildConfig
metadata:
  name: ${env.JOB_NAME}-s2i
  namespace: ${ns}
spec:
  output:
    to:
      kind: ImageStreamTag
      name: ${env.JOB_NAME}:${version}
  runPolicy: Serial
  source:
    type: Binary
  strategy:
    type: Docker
    dockerStrategy:
      - name: "HTTP_PROXY"
        value: "http://101.132.158.165:40000"
      - name: "HTTPS_PROXY"
        value: "http://101.132.158.165:40000"  
      noCache: ${noCache}
"""
}
