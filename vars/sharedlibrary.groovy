@Library('pipeline-library-demo')_
#!/usr/bin/env groovy

@NonCPS
def parseXml(String xmlContent) {
	def parsedXml = new XmlSlurper().parseText(xmlContent)
	// Se sacan las métricas directamente del elemento testsuites
	def testsuitesMetrics = [
		name: parsedXml.'@name'.text(),
		totalTests: parsedXml.'@tests'.text().toInteger(),
		totalFailures: parsedXml.'@failures'.text().toInteger(),
		totalErrors: parsedXml.'@errors'.text().toInteger(),
		totalTime: parsedXml.'@time'.text().toFloat()
	]
	// Se sacan las métricas directamente del elemento testsuite
    def testsuiteMetrics = parsedXml.testsuite.collect {
        [
            name: it.'@name'.text(),
            tests: it.'@tests'.text().toInteger(),
            failures: it.'@failures'.text().toInteger(),
            errors: it.'@errors'.text().toInteger(),
            time: it.'@time'.text().toFloat(),
            skipped: it.'@skipped'.text().toInteger(),
            timestamp: it.'@timestamp'.text()
        ]
    }
	// Recopilación de las métricas de los testcase con relación a su testsuite
    def testcaseMetrics = [:] // Un mapa para agrupar los testcase por testsuite
    parsedXml.testsuite.each { ts ->
        def tsName = ts.'@name'.text()
        testcaseMetrics[tsName] = ts.testcase.collect { tc ->
            def tcMetrics = [
                name: tc.'@name'.text(),
                time: tc.'@time'.text().toFloat(),
                status: tc.'@status'.text(),
        ]
        
            // Control de campos adicionales basados en el estado
            switch (tc.'@status'.text()) {
                case 'PASSED':
                    tcMetrics['result'] = 'OK'
                    break
                case 'ERROR':
                    tcMetrics['errorType'] = tc.error.'@type'.text()
                    tcMetrics['errorMessage'] = tc.error.'@message'.text()
                    break
                case 'FAILED':
                    tcMetrics['failureType'] = tc.failure.'@type'.text()
                    tcMetrics['failureMessage'] = tc.failure.'@message'.text()
                    break
            }
            return tcMetrics
        }
    }
    return [testsuites: testsuitesMetrics, testsuite: testsuiteMetrics, testcase: testcaseMetrics]
}

@NonCPS
def constructPrometheusMetrics(Map metrics, String hash) {
    def metricData = ''

    // Métricas de testsuites
    def testsuitesValue = (metrics.testsuites.totalFailures > 0 || metrics.testsuites.totalErrors > 0) ? 1 : 0
    metricData += "katalon_testsuites_info{name=\"${metrics.testsuites.name}\", tests=\"${metrics.testsuites.totalTests}\", failures=\"${metrics.testsuites.totalFailures}\", errors=\"${metrics.testsuites.totalErrors}\", time=\"${metrics.testsuites.totalTime}\", hash=\"${hash}\", timestamp_execute=\"${env.PIPELINE_TIMESTAMP}\"} ${testsuitesValue}\n"

    // Métricas de testsuite
    metrics.testsuite.each { ts ->
        def testsuiteValue = (ts.failures > 0 || ts.errors > 0) ? 1 : 0
        metricData += "katalon_testsuite_info{name=\"${ts.name}\",tests=\"${ts.tests}\",failures=\"${ts.failures}\",errors=\"${ts.errors}\",time=\"${ts.time}\",skipped=\"${ts.skipped}\",timestamp=\"${ts.timestamp}\", hash=\"${hash}\"} ${testsuiteValue}\n"
    }

    // Métricas de testcase con etiqueta de testsuite
    metrics.testsuite.each { ts ->
        def tsName = ts.name
        metrics.testcase[tsName]?.each { tc -> // testcase del testsuite actual
                def testcaseValue = (tc.status == 'PASSED') ? 0 : 1
                def testcaseMetric = "katalon_testcase_info{testsuite=\"${ts.name}\", name=\"${tc.name}\", time=\"${tc.time}\", status=\"${tc.status}\", hash=\"${hash}\""


                if (testcaseValue == 0) {
                    testcaseMetric += ", result=\"OK\"} ${testcaseValue}\n"
                } else {
                    String detailType = tc.status == 'ERROR' ? 'error' : 'failure'
                    testcaseMetric += ", ${detailType}_type=\"${tc[detailType + 'Type']}\", ${detailType}_message=\"${tc[detailType + 'Message'].replaceAll('"', '\\"')}\"} ${testcaseValue}\n"
                }

                metricData += testcaseMetric
            }
        }
    
    return metricData
}

@NonCPS
def generateCustomHash() {
    def jobName = env.JOB_NAME ?: 'UnknownJob'
    def buildNumber = env.BUILD_NUMBER ?: '0'
    def currentTime = System.currentTimeMillis().toString()

    def stringToHash = "${jobName}-${buildNumber}-${currentTime}"
    def hash = stringToHash.hashCode().toString()

    return hash
}

    node ('minikube') {
                //Se dejan comentados metodos originales katalon 
                //stage ('katalon_GTI- SCM') {
 	            //    checkout([$class: 'GitSCM', branches: [[name: '*/preproduccion']], doGenerateSubmoduleConfigurations: false, userRemoteConfigs: [[credentialsId: 'Admin_GitLab', url: 'https://git.seur.es/testing/gti']]]) 
	            //} 

	            //stage ('katalon_GTI - Build') {
                //    bat 'F:\\Katalon_Studio_Engine_Windows_64-8.1.0\\katalonc.exe -noSplash -runMode=console -projectPath="C:\\Jenkins\\workspace\\katalon_gti_pre\\GTI.prj" -retry=0 -testSuitePath="Test Suites/GTI" -executionProfile="default" -browserType="Chrome" -apiKey="c4e2bb00-7501-4a41-a22b-f5fb239efc6f" --config -proxy.auth.option=NO_PROXY -proxy.system.option=NO_PROXY -proxy.system.applyToDesiredCapabilities=true'
	            //}
       
                stage ('Preparación') {
                    script {
                        env.PIPELINE_TIMESTAMP = new Date().format('yyyy-MM-dd HH:mm:ss')
                        echo "${env.PIPELINE_TIMESTAMP}"
                    }
                }
                
                stage('Generate Hash') {
                    script {
                       env.PIPELINE_HASH = generateCustomHash()
                       echo "${env.PIPELINE_HASH}"
                    }
                }
                
                stage('Parse Katalon XML') {
                    script {

                        //xmlDirectory = 'C:/Jenkins/workspace'
                        foundFiles = findFiles(glob: "**/JUnit_Report*.xml")

                        if (foundFiles.length == 0) {
                            echo "Archivo JUnit_Report.xml no encontrado"
                            return // Salir del script si no se encuentra el archivo
                        }

                        xmlFilePath = foundFiles[0].path
                        echo "Archivo encontrado: ${xmlFilePath}"

                        katalonXmlContent = readFile(xmlFilePath)

                        parsedResults = parseXml(katalonXmlContent)
                        echo "Metrics parsed: ${parsedResults}"

                        prometheusData = """
                            # HELP katalon_testsuites_info Test Suites Information
                            # TYPE katalon_testsuites_info gauge
                            # HELP katalon_testsuite_info Test Suite Information
                            # TYPE katalon_testsuite_info gauge
                            # HELP katalon_testcase_info Test Case Information
                            # TYPE katalon_testcase_info gauge
                            """

                            prometheusData += constructPrometheusMetrics(parsedResults, env.PIPELINE_HASH)

                            writeFile(file: 'katalon.prom', text: prometheusData)
                            echo "Combined Prometheus data:\n${prometheusData}"

                    }
                }

                //Stage para futuro envio a pushgateway de prometheus
                stage('Send to Prometheus') {
                    script {
                        prometheusData = readFile('katalon.prom').trim()
                        if (prometheusData) {
                        //Se deja esta URL a modo de ejemplo local
                        pushgatewayUrl = "http://prometheus-prometheus-pushgateway.prometheus.svc:9091/metrics/job/katalon/hash/${env.PIPELINE_HASH}"
                        response = sh(script: "curl -X POST --data-binary @katalon.prom ${pushgatewayUrl}", returnStdout: true).trim()
                            echo "Response from Pushgateway: ${response}"
                        } else {
                            error("Prometheus data is empty")
                        }
                    }
                }
        
    }