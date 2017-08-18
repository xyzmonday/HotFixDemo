package com.yff.hotfix

import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.transforms.ProGuardTransform
import org.gradle.api.Plugin
import org.gradle.api.Project
import com.yff.hotfix.utils.*
import org.apache.commons.io.FileUtils

/**
 * 注意如果开启了混淆，那么MyTransform->ProguardTransform->DexTransform
 * 那么我们在MyTransform注入了AntilazyLoad类，所以在ProguardTransform会报
 * classNotFound的错误。
 */
public class HotFixPlugin implements Plugin<Project> {

    /**
     * 存对应的构建的混淆配置文件
     */
    public static Map<String, List<File>> proguardConfigFile = new HashMap<String, List<File>>()
    /**
     * 2017年07月29日开发支持Gradle1.5以上的热修复框架。
     * @param project
     */
    @Override
    void apply(Project project) {
        //注册Transform接口
        def android = project.extensions.findByType(AppExtension)
        android.registerTransform(new HotFixTransform(project))
        //扩展参数
        project.extensions.create("hotfix", HotFixExtension, project)
        /**
         * 在所有build.gradle解析完成后，开始执行task之前，此时所有的脚本已经解析完成，
         * task，plugins等所有信息可以获取，task的依赖关系也已经生成，如果此时需要做一些事情，
         * 可以写在afterEvaluate
         */
        project.afterEvaluate {
            project.logger.error "========开始执行afterEvaluate==========="
            project.android.applicationVariants.each { variant ->
                //获取该插件的扩展信息
                def extension = project.extensions.findByName("hotfix") as HotFixExtension
                String preVersion = extension.preVersion;
                String currentVersion = extension.currentVersion;
                //TODO 通过enable字段判断是否需要执行该插件

                //这里获取的就是渠道名
                String variantName = variant.name
                variantName = variantName.replaceFirst(variantName.substring(0, 1), variantName.substring(0, 1).toUpperCase())
                //获取上一次插件生成的mapping.txt,hash.txt,以及补丁目录。
                //这里的目录规则是outputs/hotfix/buildType/preversion
                def currentHotFixDir = new File("${project.buildDir}/outputs/hotfix/${variantName}/${currentVersion}")

                def proguardTask = project.tasks.findByName("transformClassesAndResourcesWithProguardFor${variant.name.capitalize()}")

                Closure copyMappingClosure = {
                    project.logger.error "=====开始执行混淆之后========"
                    if (proguardTask) {
                        //混淆执行完毕之后将mapping里面的mapping.txt文件拷贝到currentHotFixDir目录，以后下一次补丁操作使用
                        def mapFile = new File("${project.buildDir}/outputs/mapping/${variant.dirName}/mapping.txt")
                        def newMapFile = new File("${currentHotFixDir}/mapping.txt")
                        FileUtils.copyFile(mapFile, newMapFile)
                    }
                }

                if (proguardTask) {
                    project.logger.error "proguardTask in afterEvaluate = " + proguardTask.name
                    //在proguardTask任务执行完毕后，将mapping.txt文件拷贝到我们指定的目录
                    proguardTask.doLast(copyMappingClosure)
                    if (currentHotFixDir) {
                        //获取上一次补丁操作的mapping文件
                        def prvMappingFile =new File("${currentHotFixDir}/mapping.txt")
                        project.logger.error "================mappingFile ======= " + prvMappingFile.absolutePath
                        ProGuardTransform transform = proguardTask.getTransform();//哈哈，这里有坑

                        NuwaAndroidUtils.applyMapping(transform, prvMappingFile)

                        def files = transform.getAllConfigurationFiles()

                        /**
                         * 记录这些混淆文件后面再使用,注意这里得到的是
                         * fileName = proguard-android.txt-2.3.3
                         * fileName = proguard-rules.pro
                         * fileName = proguard.txt
                         * fileName = proguard.txt
                         * fileName = aapt_rules.txt
                         */
                        proguardConfigFile.put(variantName, files)

                    } else {
                        String tips = "mapping.txt not found, you can run 'Generate Signed Apk' with release and minify to generate a mapping, or setting generatePath false"
                        throw new IllegalStateException(tips)
                    }
                }
            }
        }
    }
}


