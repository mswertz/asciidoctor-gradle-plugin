/*
 * Copyright 2013-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.asciidoctor.gradle.remote

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.asciidoctor.Asciidoctor
import org.asciidoctor.ast.Cursor
import org.asciidoctor.gradle.internal.ExecutorConfiguration
import org.asciidoctor.gradle.internal.ExecutorConfigurationContainer
import org.asciidoctor.gradle.internal.ExecutorLogLevel
import org.asciidoctor.groovydsl.AsciidoctorExtensions
import org.asciidoctor.log.LogHandler
import org.asciidoctor.log.LogRecord

/** Runs Asciidoctor as an externally invoked Java process.
 *
 * @since 2.0.0
 * @author Schalk W. Cronjé
 */
@CompileStatic
class AsciidoctorJavaExec extends ExecutorBase {

    AsciidoctorJavaExec(ExecutorConfigurationContainer ecc) {
        super(ecc)
    }

    void run() {

        Asciidoctor asciidoctor = asciidoctorInstance
        addRequires(asciidoctor)

        runConfigurations.each { runConfiguration ->

            LogHandler lh = getLogHandler(runConfiguration.executorLogLevel)
            asciidoctor.registerLogHandler(lh)
            if (runConfiguration.asciidoctorExtensions?.size()) {
                registerExtensions(asciidoctor, runConfiguration.asciidoctorExtensions)
            }

            runConfiguration.outputDir.mkdirs()
            convertFiles(asciidoctor, runConfiguration)
            asciidoctor.unregisterAllExtensions()
            asciidoctor.unregisterLogHandler(lh)
        }
    }

    @SuppressWarnings(['Println', 'CatchThrowable'])
    private void convertFiles(Asciidoctor asciidoctor, ExecutorConfiguration runConfiguration) {
        runConfiguration.sourceTree.each { File file ->
            try {
                if (runConfiguration.logDocuments) {
                    println("Converting ${file}")
                }
                asciidoctor.convertFile(file, normalisedOptionsFor(file, runConfiguration))
            } catch (Throwable t) {
                throw new AsciidoctorRemoteExecutionException("Error running Asciidoctor whilst attempting to process ${file} using backend ${runConfiguration.backendName}", t)
            }
        }
    }

    private Asciidoctor getAsciidoctorInstance() {
        String combinedGemPath = runConfigurations*.gemPath.join(File.pathSeparator)

        ClassLoader adClassLoader = this.class.classLoader

        (combinedGemPath.empty || combinedGemPath == File.pathSeparator) ?
            Asciidoctor.Factory.create(adClassLoader) :
            Asciidoctor.Factory.create(adClassLoader, combinedGemPath)
    }

    private void addRequires(Asciidoctor asciidoctor) {
        runConfigurations.each { runConfiguration ->
            for (require in runConfiguration.requires) {
                asciidoctor.requireLibrary(require)
            }
        }
    }

    @SuppressWarnings('Println')
    LogHandler getLogHandler(ExecutorLogLevel required) {
        int requiredLevel = required.level
        new LogHandler() {
            @Override
            void log(LogRecord logRecord) {
                int level = LogSeverityMapper.getSeverity(logRecord.severity).level

                if (level >= requiredLevel) {

                    String msg = logRecord.message
                    Cursor cursor = logRecord.cursor
                    if (cursor) {
                        msg = "${msg} :: ${cursor.path ?: ''} :: ${cursor.dir ?: ''}/${cursor.file ?: ''}:${cursor.lineNumber >= 0 ? cursor.lineNumber.toString() : ''}"
                    }
                    if (logRecord.sourceFileName) {
                        msg = "${msg} (${logRecord.sourceFileName}${logRecord.sourceMethodName ? (':' + logRecord.sourceMethodName) : ''})"
                    }

                    println msg
                }
            }
        }
    }

    @CompileDynamic
    private void registerExtensions(Asciidoctor asciidoctor, List<Object> exts) {

        AsciidoctorExtensions extensionRegistry = new AsciidoctorExtensions()

        for (Object ext in rehydrateExtensions(extensionRegistry, exts)) {
            extensionRegistry.addExtension(ext)
        }

        extensionRegistry.registerExtensionsWith((Asciidoctor) asciidoctor)
    }

    static void main(String[] args) {
        if (args.size() != 1) {
            throw new AsciidoctorRemoteExecutionException('No serialised location specified')
        }

        ExecutorConfigurationContainer ecc
        new File(args[0]).withInputStream { input ->
            new ObjectInputStream(input).withCloseable { ois ->
                ecc = (ExecutorConfigurationContainer) ois.readObject()
            }
        }

        new AsciidoctorJavaExec(ecc).run()
    }

}