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
package org.asciidoctor.gradle.jvm

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.asciidoctor.gradle.internal.AsciidoctorUtils
import org.asciidoctor.gradle.internal.ExecutorConfiguration
import org.asciidoctor.gradle.internal.ExecutorConfigurationContainer
import org.asciidoctor.gradle.internal.JavaExecUtils
import org.asciidoctor.gradle.remote.AsciidoctorJExecuter
import org.asciidoctor.gradle.remote.AsciidoctorJavaExec
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.InvalidUserDataException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyResolveDetails
import org.gradle.api.file.CopySpec
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.internal.file.copy.CopySpecInternal
@java.lang.SuppressWarnings('NoWildcardImports')
import org.gradle.api.tasks.*
import org.gradle.api.tasks.util.PatternSet
import org.gradle.process.JavaExecSpec
import org.gradle.process.JavaForkOptions
import org.gradle.util.GradleVersion
import org.gradle.workers.WorkerConfiguration
import org.gradle.workers.WorkerExecutor
import org.ysb33r.grolifant.api.FileUtils
import org.ysb33r.grolifant.api.StringUtils

import java.nio.file.Path

import static org.asciidoctor.gradle.internal.AsciidoctorUtils.executeDelegatingClosure
import static org.asciidoctor.gradle.internal.AsciidoctorUtils.getClassLocation
import static org.gradle.workers.IsolationMode.CLASSLOADER
import static org.gradle.workers.IsolationMode.PROCESS

/** Base class for all AsciidoctorJ tasks.
 *
 * @since 2.0.0
 * @author Schalk W. Cronjé
 * @author Manuel Prinz
 */
@SuppressWarnings('MethodCount')
@CompileStatic
class AbstractAsciidoctorTask extends DefaultTask {

    final static ProcessMode IN_PROCESS = ProcessMode.IN_PROCESS
    final static ProcessMode OUT_OF_PROCESS = ProcessMode.OUT_OF_PROCESS
    final static ProcessMode JAVA_EXEC = ProcessMode.JAVA_EXEC

    @Internal
    protected final static GradleVersion LAST_GRADLE_WITH_CLASSPATH_LEAKAGE = GradleVersion.version(('4.99'))

    @Nested
    protected final OutputOptions configuredOutputOptions = new OutputOptions()

    private final AsciidoctorJExtension asciidoctorj
    private final WorkerExecutor worker
    private final List<Object> asciidocConfigurations = []
    private
    final org.ysb33r.grolifant.api.JavaForkOptions javaForkOptions = new org.ysb33r.grolifant.api.JavaForkOptions()

    private Object baseDir
    private Object srcDir
    private Object outDir
    private PatternSet sourceDocumentPattern
    private PatternSet secondarySourceDocumentPattern
    private CopySpec resourceCopy

    private List<String> copyResourcesForBackends = []
    private boolean withIntermediateWorkDir = false
    private PatternSet intermediateArtifactPattern

    /** Logs documents as they are converted
     *
     */
    @Internal
    boolean logDocuments = false

    /** Run Asciidoctor conversions in or out of process
     *
     * Valid options are {@link #IN_PROCESS}, {@link #OUT_OF_PROCESS} and {@link #JAVA_EXEC}.
     *
     */
    @Internal
    ProcessMode inProcess = JAVA_EXEC

    /** Set the mode for running conversions sequential or in parallel.
     * For instance a task that has multiple backends can have the
     * conversion in parallel.
     *
     * When running sequential, the worker classloader, Asciidoctor instances
     * and Asciidoctor extensions will be shared across all of the conversions.
     * When running parallel each conversion will be in a separate classloader,
     * with a new Asciidoctor instance being initialised for every conversion.
     *
     * Sequential work might execute slightly faster, but if you have backend-specific
     * extensions you might want to consider parallel mode (or use another Asciidoctor
     * task instance).
     *
     * Default is parallel.
     *
     * WHen {@link #inProcess} {@code ==} {@link #JAVA_EXEC} this option is ignored.
     */
    @Internal
    boolean parallelMode = true

    /** Sets the new Asciidoctor parent source directory.
     *
     * @param f Any object convertible with {@code project.file}.
     */
    void setSourceDir(Object f) {
        this.srcDir = f
    }

    /** Returns the parent directory for Asciidoctor source.
     */
    @Internal
    File getSourceDir() {
        project.file(srcDir)
    }

    /** Configures sources.
     *
     * @param cfg Configuration closure. Is passed a {@link PatternSet}.
     */
    void sources(final Closure cfg) {
        if (sourceDocumentPattern == null) {
            sourceDocumentPattern = new PatternSet()
        }
        Closure configuration = (Closure) cfg.clone()
        configuration.delegate = sourceDocumentPattern
        configuration()
    }

    /** Configures sources.
     *
     * @param cfg Configuration {@link Action}. Is passed a {@link PatternSet}.
     */
    void sources(final Action<? super PatternSet> cfg) {
        if (sourceDocumentPattern == null) {
            sourceDocumentPattern = new PatternSet()
        }
        cfg.execute(sourceDocumentPattern)
    }

    /** Set fork options for {@link #JAVA_EXEC} and {@link #OUT_OF_PROCESS} modes.
     *
     * These options are ignored if {@link #inProcess} {@code ==} {@link #IN_PROCESS}.
     *
     * @param configurator Closure that configures a {@link org.ysb33r.grolifant.api.JavaForkOptions} instance.
     */
    void forkOptions(@DelegatesTo(org.ysb33r.grolifant.api.JavaForkOptions) Closure configurator) {
        executeDelegatingClosure(this.javaForkOptions, configurator)
    }

    /** Set fork options for {@link #JAVA_EXEC} and {@link #OUT_OF_PROCESS} modes.
     *
     * These options are ignored if {@link #inProcess} {@code ==} {@link #IN_PROCESS}.
     *
     * @param configurator Action that configures a {@link org.ysb33r.grolifant.api.JavaForkOptions} instance.
     */
    void forkOptions(Action<org.ysb33r.grolifant.api.JavaForkOptions> configurator) {
        configurator.execute(this.javaForkOptions)
    }

    /** Returns a FileTree containing all of the source documents
     *
     * @return If {@code sources} was never called then all asciidoc source files below {@code sourceDir} will
     * be included.
     *
     * @since 1.5.1
     */
    @InputFiles
    @SkipWhenEmpty
    FileTree getSourceFileTree() {
        getSourceFileTreeFrom(sourceDir)
    }

    /** Clears any of the existing secondary soruces patterns.
     *
     * This should be used if none of the default patterns should be monitored.
     */
    void clearSecondarySources() {
        secondarySourceDocumentPattern = new PatternSet()
    }

    /** Configures secondary sources.
     *
     * @param cfg Configuration closure. Is passed a {@link PatternSet}.
     */
    @CompileDynamic
    void secondarySources(final Closure cfg) {
        if (this.secondarySourceDocumentPattern == null) {
            this.secondarySourceDocumentPattern = defaultSecondarySourceDocumentPattern
        }
        executeDelegatingClosure(this.secondarySourceDocumentPattern, cfg)
    }

    /** Configures sources.
     *
     * @param cfg Configuration {@link Action}. Is passed a {@link PatternSet}.
     */
    void secondarySources(final Action<? super PatternSet> cfg) {
        if (secondarySourceDocumentPattern == null) {
            secondarySourceDocumentPattern = defaultSecondarySourceDocumentPattern
        }
        cfg.execute(secondarySourceDocumentPattern)
    }

    /** Returns a FileTree containing all of the secondary source documents.
     *
     * @return Collection of secondary files
     *
     */
    @InputFiles
    FileTree getSecondarySourceFileTree() {
        getSecondarySourceFileTreeFrom(sourceDir)
    }

    /** Returns the current toplevel output directory
     *
     */
    @OutputDirectory
    File getOutputDir() {
        this.outDir != null ? project.file(this.outDir) : null
    }

    /** Sets the new Asciidoctor parent output directory.
     *
     * @param f An object convertible via {@code project.file}
     */
    void setOutputDir(Object f) {
        this.outDir = f
    }

    /** Returns a list of all output directories by backend
     *
     * @since 1.5.1
     */
    @OutputDirectories
    Set<File> getBackendOutputDirectories() {
        configuredOutputOptions.backends.collect { getOutputDirFor(it) } as Set
    }

    /** Base directory (current working directory) for a conversion.
     *
     * @return Base directory.
     */
    // IMPORTANT: Do not change this to @InputDirectory as it can lead to file locking issues on
    // Windows. In reality we do not need to track contents of the directory
    // simply the value change, we tarck this via a normal property
    @Internal
    File getBaseDir() {
        this.baseDir != null ? project.file(this.baseDir) : project.projectDir
    }

    /** Sets the base directory for a conversion.
     *
     * The base directory is used by AsciidoctorJ to set a curretn working directory for
     * a conversion.
     *
     * If never set, then {@code project.projectDir ( )} will be assumed to be the base directory.
     *
     * WHen {@link}
     *
     * @param f Base directory
     */
    void setBaseDir(Object f) {
        this.baseDir = f
    }

    /** Returns all of the Asciidoctor options.
     *
     * This is equivalent of using {@code asciidoctorj.getOptions}
     *
     */
    @Input
    Map getOptions() {
        asciidoctorj.options
    }

    /** Apply a new set of Asciidoctor options, clearing any options previously set.
     *
     * If set here all global Asciidoctor options are ignored within this task.
     *
     * This is equivalent of using {@code asciidoctorj.setOptions}.
     *
     * @param m Map with new options
     */
    void setOptions(Map m) {
        asciidoctorj.options = m
    }

    /** Add additional asciidoctor options
     *
     * If set here these options will be used in addition to any global Asciidoctor options.
     *
     * This is equivalent of using {@code asciidoctorj.options}.
     *
     * @param m Map with new options
     */
    @SuppressWarnings('ConfusingMethodName')
    void options(Map m) {
        asciidoctorj.options(m)
    }

    /** Returns all of the Asciidoctor options.
     *
     * This is equivalent of using {@code asciidoctorj.getAttributes}
     *
     */
    @Input
    Map getAttributes() {
        asciidoctorj.attributes
    }

    /** Apply a new set of Asciidoctor options, clearing any options previously set.
     *
     * If set here all global Asciidoctor options are ignored within this task.
     *
     * This is equivalent of using {@code asciidoctorj.setAttributes}.
     *
     * @param m Map with new options
     */
    void setAttributes(Map m) {
        asciidoctorj.attributes = m
    }

    /** Add additional asciidoctor options
     *
     * If set here these options will be used in addition to any global Asciidoctor options.
     *
     * This is equivalent of using {@code asciidoctorj.attributes}.
     *
     * @param m Map with new options
     */
    void attributes(Map m) {
        asciidoctorj.attributes(m)
    }

    /** Add to the CopySpec for extra files. The destination of these files will always have a parent directory
     * of {@code outputDir} or {@code outputDir + backend}
     *
     * @param cfg {@link CopySpec} runConfiguration closure
     * @since 1.5.1
     */
    void resources(Closure cfg) {
        if (this.resourceCopy == null) {
            this.resourceCopy = project.copySpec(cfg)
        } else {
            Closure configuration = (Closure) cfg.clone()
            configuration.delegate = this.resourceCopy
            configuration()
        }
    }

    /** Add to the CopySpec for extra files. The destination of these files will always have a parent directory
     * of {@code outputDir} or {@code outputDir + backend}
     *
     * @param cfg {@link CopySpec} runConfiguration {@link Action}
     */
    void resources(Action<? super CopySpec> cfg) {
        if (this.resourceCopy == null) {
            this.resourceCopy = project.copySpec(cfg)
        } else {
            cfg.execute(this.resourceCopy)
        }
    }

    /** Copies all resources to the output directory.
     *
     * Some backends (such as {@code html5}) require all resources to be copied to the output directory.
     * This is the default behaviour for this task.
     */
    void copyAllResources() {
        this.copyResourcesForBackends = []
    }

    /** Do not copy any resources to the output directory.
     *
     * Some backends (such as {@code pdf}) process all resources in place.
     *
     */
    void copyNoResources() {
        this.copyResourcesForBackends = null
    }

    /** Copy resources to the output directory only if the backend names matches any of the specified
     * names.
     *
     * @param backendNames List of names for which resources should be copied.
     *
     */
    void copyResourcesOnlyIf(String... backendNames) {
        this.copyResourcesForBackends = []
        this.copyResourcesForBackends.addAll(backendNames)
    }

    /** Returns all of the specified configurations as a collections of files.
     *
     * If any extensions are dependencies then they w2ill be included here too.
     *
     * @return FileCollection
     */
    @InputFiles
    @SuppressWarnings('Instanceof')
    FileCollection getConfigurations() {
        FileCollection fc = asciidoctorj.configuration
        FileCollection precompiledExtensions = findDependenciesInExtensions()
        this.asciidocConfigurations.each {
            if (it instanceof Configuration) {
                fc = fc + (FileCollection) it
            } else {
                fc = fc + (FileCollection) (project.configurations.getByName(StringUtils.stringize(it)))
            }
        }
        precompiledExtensions ? fc + precompiledExtensions : fc
    }

    /** Override any existing configurations except the ones available via the {@code asciidoctorj} task extension.
     *
     * @param configs
     */
    void setConfigurations(Iterable<Object> configs) {
        this.asciidocConfigurations.clear()
        configurations(configs)
    }

    /** Add additional configurations.
     *
     * @param configs Instances of {@link Configuration} or anything convertible to a string than can be used
     *   as a name of a runConfiguration.
     */
    void configurations(Iterable<Object> configs) {
        this.asciidocConfigurations.addAll(configs)
    }

    /** Add additional configurations.
     *
     * @param configs Instances of {@link Configuration} or anything convertible to a string than can be used
     *   as a name of a runConfiguration.
     */
    void configurations(Object... configs) {
        this.asciidocConfigurations.addAll(configs)
    }

    /** Some extensionRegistry such as {@code ditaa} creates images in the source directory.
     *
     * Use this setting to copy all sources and resources to an intermediate work directory
     * before processing starts. This will keep the source directory pristine
     */
    void useIntermediateWorkDir() {
        withIntermediateWorkDir = true
    }

    /** The document conversion might generate additional artifacts that could
     * require copying to the final destination.
     *
     * An example is use of {@code ditaa} diagram blocks. These artifacts can be specified
     * in this block. Use of the option implies {@link #useIntermediateWorkDir}.
     * If {@link #copyNoResources} is set or {@link #copyResourcesOnlyIf(String ...)} does not
     * match the backend, no copy will occur.
     *
     * @param cfg Configures a {@link PatternSet} with a base directory of the intermediate working
     * directory.
     */
    void withIntermediateArtifacts(@DelegatesTo(PatternSet) Closure cfg) {
        useIntermediateWorkDir()
        if (this.intermediateArtifactPattern == null) {
            this.intermediateArtifactPattern = new PatternSet()
        }
        executeDelegatingClosure(this.intermediateArtifactPattern, cfg)
    }

    /** Additional artifacts created by Asciidoctor that might require copying.
     *
     * @param cfg Action that configures a {@link PatternSet}.
     *
     * @see {@link #withIntermediateArtifacts(Closure cfg)}
     */
    void withIntermediateArtifacts(final Action<PatternSet> cfg) {
        useIntermediateWorkDir()
        if (this.intermediateArtifactPattern == null) {
            this.intermediateArtifactPattern = new PatternSet()
        }
        cfg.execute(this.intermediateArtifactPattern)
    }

    @SuppressWarnings('UnnecessaryGetter')
    @TaskAction
    void processAsciidocSources() {

        checkForInvalidSourceDocuments()
        checkForIncompatiblePathRoots()

        File workingSourceDir
        FileTree sourceTree

        if (this.withIntermediateWorkDir) {
            File tmpDir = getIntermediateWorkDir()
            prepareTempWorkspace(tmpDir)
            workingSourceDir = tmpDir
            sourceTree = getSourceFileTreeFrom(tmpDir)
        } else {
            workingSourceDir = getSourceDir()
            sourceTree = getSourceFileTree()
        }

        Set<File> sourceFiles = sourceTree.files

        Map<String, ExecutorConfiguration> executorConfigurations

        if (finalProcessMode != JAVA_EXEC) {
            executorConfigurations = runWithWorkers(workingSourceDir, sourceFiles)
        } else {
            executorConfigurations = runWithJavaExec(workingSourceDir, sourceFiles)
        }

        copyResourcesByBackend(executorConfigurations.values())
    }

    /** Initialises the core an Asciidoctor task
     *
     * @param we {@link WorkerExecutor}. This is usually injected into the
     *   constructor of the subclass.
     */
    protected AbstractAsciidoctorTask(WorkerExecutor we) {
        this.worker = we
        this.asciidoctorj = extensions.create(AsciidoctorJExtension.NAME, AsciidoctorJExtension, this)

        addInputProperty 'required-ruby-modules', { asciidoctorj.requires }
        addInputProperty 'gemPath', { asciidoctorj.asGemPath() }
        addInputProperty 'trackBaseDir', { getBaseDir().absolutePath }

        inputs.files { asciidoctorj.gemPaths }
        inputs.files { (resourceCopySpec as CopySpecInternal).buildRootResolver().allSource }
    }

    /** Returns all of the executor configurations for this task
     *
     * @return Executor configurations
     */
    protected Map<String, ExecutorConfiguration> getExecutorConfigurations(
        final File workingSourceDir,
        final Set<File> sourceFiles
    ) {
        configuredOutputOptions.backends.collectEntries { String activeBackend ->
            ["backend=${activeBackend}".toString(), getExecutorConfigurationFor(activeBackend, workingSourceDir, sourceFiles)]
        }
    }

    /** Provides configuration information for the worker.
     *
     * @param backendName Name of backend that will be run.
     * @param workingSourceDir Source directory that will used for work. This can be
     *   the original source directory or an intermediate.
     * @param sourceFiles THe actual top-level source files that will be used as entry points
     *   for generating documentation.
     * @return Executor configuration
     */
    @SuppressWarnings('Instanceof')
    protected ExecutorConfiguration getExecutorConfigurationFor(
        final String backendName,
        final File workingSourceDir,
        final Set<File> sourceFiles
    ) {
        File outputTo = getOutputDirFor(backendName)
        Map<String, Object> attrs = getTaskSpecificDefaultAttributes(workingSourceDir)
        attrs.putAll(attributes)

        new ExecutorConfiguration(
            sourceDir: workingSourceDir,
            sourceTree: sourceFiles,
            outputDir: outputTo,
            baseDir: getBaseDir(),
            projectDir: project.projectDir,
            rootDir: project.rootProject.projectDir,
            options: options,
            attributes: attrs,
            backendName: backendName,
            logDocuments: logDocuments,
            gemPath: gemPath,
            fatalMessagePatterns: asciidoctorj.fatalWarnings,
            asciidoctorExtensions: (asciidoctorJExtensions.findAll { !(it instanceof Dependency) }),
            requires: requires,
            copyResources: this.copyResourcesForBackends != null && (this.copyResourcesForBackends.empty || backendName in this.copyResourcesForBackends),
            executorLogLevel: AsciidoctorUtils.getExecutorLogLevel(asciidoctorj.logLevel),
            safeModeLevel: asciidoctorj.safeMode.level
        )
    }

    /** A task may add some default attributes.
     *
     * If the user specifies any of these attributes, then those attributes will not be utilised.
     *
     * The default implementation will add {@code includedir}
     *
     * @param workingSourceDit DIrectory where source files are located.
     *
     * @return A collection of default attributes.
     */
    protected Map<String, Object> getTaskSpecificDefaultAttributes(File workingSourceDir) {
        [includedir: (Object) workingSourceDir.absolutePath]
    }

    /** The default PatternSet that will be used if {@code sources} was never called
     *
     * By default all *.adoc,*.ad,*.asc,*.asciidoc is included. Files beginning with underscore are excluded
     *
     * @since 1.5.1
     */
    @Internal
    protected PatternSet getDefaultSourceDocumentPattern() {
        PatternSet ps = new PatternSet()
        ps.include '**/*.adoc'
        ps.include '**/*.ad'
        ps.include '**/*.asc'
        ps.include '**/*.asciidoc'
        ps.exclude '**/_*'
    }

    /** The default pattern set for secondary sources.
     *
     * @return {@link #getDefaultSourceDocumentPattern} + `*docinfo*`.
     */
    @Internal
    protected PatternSet getDefaultSecondarySourceDocumentPattern() {
        defaultSourceDocumentPattern
    }

    /** The default CopySpec that will be used if {@code resources} was never called
     *
     * By default anything below {@code $sourceDir/images} will be included.
     *
     *
     * @return A {@link CopySpec}. Never {@code null}.
     */
    @CompileDynamic
    protected CopySpec getDefaultResourceCopySpec() {
        project.copySpec {
            from(sourceDir) {
                include 'images/**'
            }
        }
    }

    /** Gets the CopySpec for additional resources
     * If {@code resources} was never called, it will return a default CopySpec otherwise it will return the
     * one built up via successive calls to {@code resources}
     *
     * @return A {@link CopySpec}. Never {@code null}.
     */
    @Internal
    protected CopySpec getResourceCopySpec() {
        this.resourceCopy ?: defaultResourceCopySpec
    }

    /** Returns all of the associated extensionRegistry.
     *
     * @return AsciidoctorJ extensionRegistry
     */
    @Internal
    protected List<Object> getAsciidoctorJExtensions() {
        asciidoctorj.extensions
    }

    /** Obtains a source tree based on patterns.
     *
     * @param dir Toplevel source directory.
     * @return Source tree based upon configured pattern.
     */
    protected FileTree getSourceFileTreeFrom(File dir) {
        project.fileTree(dir).
            matching(this.sourceDocumentPattern ?: defaultSourceDocumentPattern)
    }

    /** Obtains a secondary source tree based on patterns.
     *
     * @param dir Toplevel source directory.
     * @return Source tree based upon configured pattern.
     */
    protected FileTree getSecondarySourceFileTreeFrom(File dir) {
        project.fileTree(dir).
            matching(this.secondarySourceDocumentPattern ?: defaultSecondarySourceDocumentPattern)
    }

    /** Get the output directory for a specific backend.
     *
     * @param backendName Name of backend
     * @return Output directory.
     */
    protected File getOutputDirFor(final String backendName) {
        if (outputDir == null) {
            throw new GradleException("outputDir has not been defined for task '${name}'")
        }
        configuredOutputOptions.separateOutputDirs ? new File(outputDir, backendName) : outputDir
    }

    /** Configure Java fork options prior to execution
     *
     * The default method will copy anything configured via {@link #forkOptions(Closure c)} or
     * {@link #forkOptions(Action c)} to the rpovided {@link JavaForkOptions}.
     *
     * @param pfo Fork options to be configured.
     */
    @SuppressWarnings('UnusedMethodParameter')
    protected void configureForkOptions(JavaForkOptions pfo) {
        this.javaForkOptions.copyTo(pfo)
    }

    /** Adds an input property.
     *
     * Serves as a proxy method in order to deal with the API differences between Gradle 4.0-4.2 and 4.3
     *
     * @param propName Name of property
     * @param value Value of the input property
     */
    @CompileDynamic
    protected void addInputProperty(String propName, Object value) {
        inputs.property propName, value
    }

    /** Allow a task to enhance additional '{@code requires}'
     *
     * The default implementation will add a special script to deal with verbose mode.
     *
     * @return The final set of '{@code requires}'
     */
    @Internal
    protected Set<String> getRequires() {
        asciidoctorj.requires
    }

    /** Selects a final process mode.
     *
     * Some incompatibilities can cause certain process mode to fail given a combination of factors.
     *
     * Task implementations can override this method to select a safte process mode, than the one provided by the
     * build script author. The default implementation will simply return whatever what was configured, except in the
     * case for Gradle 4.3 or older in which case it will always return {@link #JAVA_EXEC}.
     *
     * @return Process mode to use for execution.
     */
    @Internal
    protected ProcessMode getFinalProcessMode() {
        if (inProcess != JAVA_EXEC && GradleVersion.current() < GradleVersion.version(('4.3'))) {
            logger.warn 'Gradle API classpath leakage will cause issues with Gradle < 4.3. Switching to JAVA_EXEC instead.'
            JAVA_EXEC
        } else {
            this.inProcess
        }
    }

    private void checkForInvalidSourceDocuments() {
        if (!sourceFileTree.filter { File f ->
            f.name.startsWith('_')
        }.empty) {
            throw new InvalidUserDataException('Source documents may not start with an underscore')
        }
    }

    @CompileDynamic
    private void prepareTempWorkspace(final File tmpDir) {
        if (tmpDir.exists()) {
            tmpDir.deleteDir()
        }
        tmpDir.mkdirs()
        project.copy {
            into tmpDir
            from sourceFileTree
            with resourceCopySpec
        }
    }

    private File getIntermediateWorkDir() {
        project.file("${project.buildDir}/tmp/${FileUtils.toSafeFileName(this.name)}.intermediate")
    }

    private void checkForIncompatiblePathRoots() {
        if (outputDir == null) {
            throw new GradleException("outputDir has not been defined for task '${name}'")
        }

        Path sourceRoot = sourceDir.toPath().root
        Path baseRoot = getBaseDir().toPath().root
        Path outputRoot = outputDir.toPath().root

        if (sourceRoot != baseRoot || outputRoot != baseRoot) {
            throw new AsciidoctorExecutionException('sourceDir, outputDir and baseDir needs to have the same root filesystem for AsciidoctorJ to function correctly. This is typically caused on Winwdows where everything is not on the same drive letter.')
        }
    }

    private String getGemPath() {
        asciidoctorj.asGemPath()
    }

    private Map<String, ExecutorConfiguration> runWithWorkers(
        final File workingSourceDir, final Set<File> sourceFiles) {
        FileCollection asciidoctorClasspath = configurations
        logger.info "Running AsciidoctorJ with workers. Classpath = ${asciidoctorClasspath.files}"
        Map<String, ExecutorConfiguration> executorConfigurations = getExecutorConfigurations(workingSourceDir, sourceFiles)
        if (parallelMode) {
            executorConfigurations.each { String configName, ExecutorConfiguration executorConfiguration ->
                worker.submit(AsciidoctorJExecuter) { WorkerConfiguration config ->
                    configureWorker(
                        "Asciidoctor (task=${name}) conversion for ${configName}",
                        config,
                        asciidoctorClasspath,
                        new ExecutorConfigurationContainer(executorConfiguration)
                    )
                }
            }
        } else {
            worker.submit(AsciidoctorJExecuter) { WorkerConfiguration config ->
                configureWorker(
                    "Asciidoctor (task=${name}) conversions for ${executorConfigurations.keySet().join(', ')}",
                    config,
                    asciidoctorClasspath,
                    new ExecutorConfigurationContainer(executorConfigurations.values())
                )
            }
        }
        executorConfigurations
    }

    private void configureWorker(
        final String displayName,
        final WorkerConfiguration config,
        final FileCollection asciidoctorClasspath,
        final ExecutorConfigurationContainer ecContainer
    ) {
        config.isolationMode = inProcess == IN_PROCESS ? CLASSLOADER : PROCESS
        config.classpath = asciidoctorClasspath
        config.displayName = displayName
        config.params(
            ecContainer
        )
        configureForkOptions(config.forkOptions)
    }

    private Map<String, ExecutorConfiguration> runWithJavaExec(
        final File workingSourceDir,
        final Set<File> sourceFiles
    ) {
        FileCollection javaExecClasspath = JavaExecUtils.getJavaExecClasspath(project, configurations)
        Map<String, ExecutorConfiguration> executorConfigurations = getExecutorConfigurations(workingSourceDir, sourceFiles)
        File execConfigurationData = JavaExecUtils.writeExecConfigurationData(this, executorConfigurations.values())

        logger.debug("Serialised AsciidoctorJ configuration to ${execConfigurationData}")
        logger.info "Running AsciidoctorJ instance with classpath ${javaExecClasspath.files}"

        project.javaexec { JavaExecSpec jes ->
            configureForkOptions(jes)
            logger.debug "Running AsciidoctorJ instance with environment: ${jes.environment}"
            jes.with {
                main = AsciidoctorJavaExec.canonicalName
                classpath = javaExecClasspath
                args execConfigurationData.absolutePath
            }
        }

        executorConfigurations
    }

    private void copyResourcesByBackend(Iterable<ExecutorConfiguration> executorConfigurations) {
        CopySpec rcs = resourceCopySpec
        for (ExecutorConfiguration ec : executorConfigurations) {
            if (ec.copyResources) {
                logger.info "Copy resources for '${ec.backendName}' to ${ec.outputDir}"

                FileTree ps = this.intermediateArtifactPattern ? project.fileTree(ec.sourceDir).matching(this.intermediateArtifactPattern) : null
                project.copy(new Action<CopySpec>() {
                    @Override
                    void execute(CopySpec copySpec) {
                        copySpec.with {
                            into ec.outputDir
                            with rcs

                            if (ps != null) {
                                from ps
                            }
                        }
                    }
                })
            }
        }
    }

    @SuppressWarnings('Instanceof')
    private FileCollection findDependenciesInExtensions() {
        List<Dependency> deps = asciidoctorj.extensions.findAll {
            it instanceof Dependency
        } as List<Dependency>

        Set<File> closurePaths = asciidoctorj.extensions.findAll {
            it instanceof Closure
        }.collect {
            getClassLocation(it.class)
        }.toSet()

        if (!closurePaths.empty) {
            // Jumping through hoops to make extensions based upon closures to work.
            closurePaths.add(getClassLocation(org.gradle.internal.scripts.ScriptOrigin))
        }

        if (deps.empty && closurePaths.empty) {
            null
        } else if (closurePaths.empty) {
            jrubyLessConfiguration(deps)
        } else if (deps.empty) {
            project.files(closurePaths)
        } else {
            jrubyLessConfiguration(deps) + project.files(closurePaths)
        }
    }

    @CompileDynamic
    private Configuration jrubyLessConfiguration(List<Dependency> deps) {
        Configuration cfg = project.configurations.detachedConfiguration(deps.toArray() as Dependency[])
        cfg.resolutionStrategy.eachDependency { DependencyResolveDetails dsr ->
            dsr.with {
                if (target.name == 'jruby' && target.group == 'org.jruby') {
                    useTarget "org.jruby:jruby:${target.version}"
                }
            }
        }
        cfg
    }
}
