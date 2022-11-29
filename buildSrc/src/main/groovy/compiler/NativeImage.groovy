package compiler

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

class NativeImage extends DefaultTask {
    enum Option {
        STATIC('--static')
      , MUSL('--libc=musl')
      , LINK_BUILD('--link-at-build-time')
      String arg
      private Option(String s) { this.arg = s }
    }

    static final List<String> EXECUTABLE = [ 'native-image' ]

    @Input
    List<Option> parameters = [ Option.STATIC, Option.MUSL, Option.LINK_BUILD ]

    @Input
    Integer minHeap = 1
    @Input
    Integer maxHeap = 32
    @Input
    Integer maxNew = 32

    @InputDirectory
    File dir = project.buildDir

    @TaskAction
    void runCommand() {
        def heap = [
          "-R:MinHeapSize=${minHeap}m"
        , "-R:MaxHeapSize=${maxHeap}m"
        , "-R:MaxNewSize=${maxNew}m"
        ]
        def source = [ '-jar', "${project.buildDir}/libs/handbrake-daemon-${project.version}.jar" ]
        def command = EXECUTABLE + parameters*.arg + heap + source
        logger.lifecycle "Executing native-image command: '${command.join(' ')}'"

        def process = command.execute(null, dir)
        process.consumeProcessOutput(System.out, System.err)
        process.waitFor()

        if (process.exitValue() != 0) {
            logger.error "Unable to execute native-image: '${process.exitValue}'"
            throw new GradleException()
        }
    }
}

import org.gradle.api.Plugin
import org.gradle.api.Project

class NativeImagePlugin implements Plugin<Project> {
    void apply(Project project) {
        project.task("nativeImage", type: NativeImage)
    }
}
