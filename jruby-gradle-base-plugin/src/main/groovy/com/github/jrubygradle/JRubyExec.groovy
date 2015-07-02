package com.github.jrubygradle

import com.github.jrubygradle.internal.JRubyExecTraits
import com.github.jrubygradle.internal.JRubyExecUtils
import org.gradle.api.Project
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskInstantiationException
import org.gradle.internal.FileUtils
import org.gradle.process.JavaExecSpec
import org.gradle.util.CollectionUtils

/** Runs a ruby script using JRuby
 *
 * @author Schalk W. Cronjé
 */
class JRubyExec extends JavaExec implements JRubyExecTraits {

    static final String JRUBYEXEC_CONFIG = 'jrubyExec'
    static final String JAR_DEPENDENCIES_VERSION = '0.1.15'

    static String jarDependenciesGemLibPath(File gemDir) {
        new File(gemDir, "gems/jar-dependencies-${JAR_DEPENDENCIES_VERSION}/lib").absolutePath
    }

    static void updateJRubyDependencies(Project proj) {
        proj.dependencies {
            jrubyExec "org.jruby:jruby-complete:${proj.jruby.execVersion}"
            // jruby-1.7.20 comes with jar-dependencies-0.1.13 which provides
            // Jars.lock files. to be sure old jrubies can load Jars.lock
            // we inject jar-dependencies here.
            if (proj.jruby.execVersion.startsWith("1.7.1")) {
                jrubyExec "rubygems:jar-dependencies:${JAR_DEPENDENCIES_VERSION}"
            }
        }

        proj.tasks.withType(JRubyExec) { t ->
            if (t.jrubyConfigurationName != proj.configurations.jrubyExec) {
                proj.dependencies.add(t.jrubyConfigurationName,
                                        "org.jruby:jruby-complete:${t.jrubyVersion}")
                if (t.jrubyVersion.startsWith("1.7.1")) {
                    String config = configuration ? t.configuration :JRUBYEXEC_CONFIG
                    proj.dependencies.add(config,
                                          "rubygems:jar-dependencies:${JAR_DEPENDENCIES_VERSION}")
                }
            }
        }
    }

    JRubyExec() {
        super()
        super.setMain 'org.jruby.Main'

        try {
            project.configurations.getByName(JRUBYEXEC_CONFIG)
        } catch(UnknownConfigurationException ) {
            throw new TaskInstantiationException('Cannot instantiate a JRubyExec instance before jruby plugin has been loaded')
        }

        jrubyVersion = project.jruby.execVersion
        jrubyConfigurationName = JRUBYEXEC_CONFIG
    }


    /** Allow JRubyExec to inherit a Ruby env from the shell (e.g. RVM)
     *
     * @since 0.1.10
     */
    @Input
    boolean getInheritRubyEnv() {
        this.inheritRubyEnv
    }

    /** Script to execute.
     * @return The path to the script (or nul if not set)
     */
    @Optional
    @Input
    File getScript() {
        _convertScript()
    }

    /** Configuration to copy gems from. If {@code jRubyVersion} has not been set, {@code jRubyExec} will used as
     * configuration. However, if {@code jRubyVersion} has been set, not gems will be used unless an explicit
     * configuration has been provided
     *
     */
    @Optional
    @Input
    String configuration

    /** Sets the configurations
     *
     * @param cfg Name of configuration
     */
    void configuration(final String cfg) {
        configuration = cfg
    }

    /** If it is required that a JRubyExec task needs to be executed with a different version of JRuby that the
     * globally configured one, it can be done by setting it here.
     */
    @Input
    String jrubyVersion

    /** Setting the {@code jruby-complete} version allows for tasks to be run using different versions of JRuby.
     * This is useful for comparing the results of different version or running with a gem that is only
     * compatible with a specific version or when running a script with a different version that what will
     * be packaged.
     *
     * @param version String in the form '1.7.13'
     * @since 0.1.18
     */
    void jrubyVersion(final String ver) {
        setJrubyVersion(ver)
    }

    /** Setting the {@code jruby-complete} version allows for tasks to be run using different versions of JRuby.
     * This is useful for comparing the results of different version or running with a gem that is only
     * compatible with a specific version or when running a script with a different version that what will
     * be packaged.
     *
     * @param version String in the form '1.7.13'
     */
    void setJrubyVersion(final String version) {
        if (version == project.jruby.execVersion) {
            jrubyConfigurationName = JRUBYEXEC_CONFIG
        } else {
            final String cfgName= 'jrubyExec$$' + name
            project.configurations.maybeCreate(cfgName)
            jrubyConfigurationName = cfgName
        }
        jrubyVersion = version
    }

    /** Returns the directory that will be used to unpack GEMs in.
     *
     * @return Target directory
     * @since 0.1.9
     */
    @Optional
    @Input
    File getGemWorkDir() {
        _convertGemWorkDir(project) ?: tmpGemDir()
    }

    /** Returns a list of script arguments
     */
    @Optional
    @Input
    List<Object> getScriptArgs() {
        _convertScriptArgs()
    }

    /** Returns a list of jruby arguments
     */
    @Optional
    @Input
    List<String> getJrubyArgs() {
        _convertJrubyArgs()
    }

    /** Return the computed `PATH` for the task
     *
     */
    String getComputedPATH(String originalPath) {
        JRubyExecUtils.prepareWorkingPath(getGemWorkDir(),originalPath)
    }


    @Override
    void exec() {
        if (configuration == null && jrubyConfigurationName == JRUBYEXEC_CONFIG) {
            configuration = JRUBYEXEC_CONFIG
        }

        GemUtils.OverwriteAction overwrite = project.gradle.startParameter.refreshDependencies ? \
                                                GemUtils.OverwriteAction.OVERWRITE : GemUtils.OverwriteAction.SKIP
        def jrubyCompletePath = project.configurations.getByName(jrubyConfigurationName)
        File gemDir = getGemWorkDir().absoluteFile
        gemDir.mkdirs()
        setEnvironment getPreparedEnvironment(environment)

        if (configuration != null) {
            GemUtils.extractGems(
                    project,
                    jrubyCompletePath,
                    project.configurations.getByName(configuration),
                    gemDir,
                    overwrite
            )
            GemUtils.setupJars(
                    project.configurations.getByName(configuration),
                    gemDir,
                    overwrite
            )
        }

        super.classpath JRubyExecUtils.classpathFromConfiguration(project.configurations.getByName(jrubyConfigurationName))
        super.setArgs(getArgs())
        super.exec()
    }

    /** getArgs gets overridden in order to add JRuby options, script name and script arguments in the correct order.
     *
     * There are three modes of behaviour
     * <ul>
     *   <li> script set. no jrubyArgs, or jrubyArgs does not contain {@code -S} - Normal way to execute script. A check
     *   whether the script exists will be performed.
     *   <li> script set. jrubyArgs contains {@code -S} - If script is not absolute, no check will be performed to see
     *   if the script exists and will be assumed that the script can be found using the default ruby path mechanism.
     *   <li> script not set, but jrubyArgs set - Set up to execute jruby with no script. This should be a rarely used otion.
     * </ul>
     *
     * @throw {@code org.gradle.api.InvalidUserDataException} if mode of behaviour cannot be determined.
     */
    @Override
    List<String> getArgs() {
        // just add the extra load-path even if it does not exists
        List<String> extra = ['-I', jarDependenciesGemLibPath(getGemWorkDir())]
        JRubyExecUtils.buildArgs(extra, jrubyArgs, getScript(), scriptArgs)
    }

    @Override
    JavaExec setMain(final String mainClassName) {
        if (mainClassName == 'org.jruby.Main') {
            super.setMain(mainClassName)
        } else {
            throw notAllowed("Setting main class for jruby to ${mainClassName} is not a valid operation")
        }
    }

    @Override
    JavaExec setArgs(Iterable<?> applicationArgs) {
        throw notAllowed('Use jvmArgs / scriptArgs instead')
    }

    @Override
    JavaExec args(Object... args) {
        throw notAllowed('Use jvmArgs / scriptArgs instead')
    }

    @Override
    JavaExecSpec args(Iterable<?> args) {
        throw notAllowed('Use jvmArgs / scriptArgs instead')
    }

    /** Returns the {@code Configuration} object this task is tied to
     */
    String getJrubyConfigurationName() {
        return this.jrubyConfigurationName
    }

    Map getPreparedEnvironment(Map env) {
        JRubyExecUtils.preparedEnvironment(env,inheritRubyEnv) + [
                'PATH' : getComputedPATH(System.env."${JRubyExecUtils.pathVar()}"),
                'GEM_HOME' : getGemWorkDir().absolutePath,
                'GEM_PATH' : getGemWorkDir().absolutePath,
                'JARS_HOME' : new File(getGemWorkDir().absolutePath, 'jars'),
                'JARS_LOCK' : new File(getGemWorkDir().absolutePath, 'Jars.lock')
        ]
    }

    private static UnsupportedOperationException notAllowed(final String msg) {
        return new UnsupportedOperationException (msg)
    }

    private File tmpGemDir() {
        String ext = FileUtils.toSafeFileName(jrubyConfigurationName)
        if (configuration && configuration != jrubyConfigurationName) {
            ext= ext + "-${FileUtils.toSafeFileName(configuration)}"
        }
        new File( project.buildDir, "tmp/${ext}")
    }

    private String jrubyConfigurationName
}