@NonCPS
def parseXml(String xmlContent) {
def parsedXml = new XmlSlurper().parseText(xmlContent)
// Se sacan las mÃ©tricas directamente del elemento testsuites
def testsuitesMetrics = [
name: parsedXml.'@name'.text(),
totalTests: parsedXml.'@tests'.text().toInteger(),
totalFailures: parsedXml.'@failures'.text().toInteger(),
totalErrors: parsedXml.'@errors'.text().toInteger(),
totalTime: parsedXml.'@time'.text().toFloat()
]
// Se sacan las mÃ©tricas directamente del elemento testsuite
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
// RecopilaciÃ³n de las mÃ©tricas de los testcase con relaciÃ³n a su testsuite
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
